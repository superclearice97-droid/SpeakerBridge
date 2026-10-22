"""Minimal Google Cast sender (standard library only).

Opens a speaker's Cast channel, starts Google's Default Media Receiver on it and
asks it to play one URL. A Google Home speaker group or stereo pair answers on its
own port, and Google keeps its members in sync. Mirrors the Android CastPlayer.
"""
from __future__ import annotations

import json
import queue
import select
import socket
import ssl
import struct
import threading
import time

APP_ID = "CC1AD845"  # Google's Default Media Receiver
SENDER, RECEIVER = "sender-0", "receiver-0"
NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
NS_MEDIA = "urn:x-cast:com.google.cast.media"


class CastError(Exception):
    pass


# ------------------------------------------------------------ wire format
def _varint(n: int) -> bytes:
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def encode(source: str, dest: str, namespace: str, payload: str) -> bytes:
    """CastMessage protobuf: version=0, source, dest, namespace, payload_type=STRING, payload."""
    def s(field: int, text: str) -> bytes:
        b = text.encode()
        return bytes([(field << 3) | 2]) + _varint(len(b)) + b
    return b"\x08\x00" + s(2, source) + s(3, dest) + s(4, namespace) + b"\x28\x00" + s(6, payload)


def decode(b: bytes) -> dict:
    """Returns {"source", "dest", "namespace", "payload"}."""
    i, fields = 0, {}

    def varint() -> int:
        nonlocal i
        shift = r = 0
        while True:
            c = b[i]
            i += 1
            r |= (c & 0x7F) << shift
            if not c & 0x80:
                return r
            shift += 7

    while i < len(b):
        tag = varint()
        if tag & 7 == 0:
            varint()
        elif tag & 7 == 2:
            n = varint()
            fields[tag >> 3] = b[i:i + n].decode("utf-8", "replace")
            i += n
        else:
            raise CastError("unexpected Cast message encoding")
    return {"source": fields.get(2, ""), "dest": fields.get(3, ""),
            "namespace": fields.get(4, ""), "payload": fields.get(6, "")}


# ---------------------------------------------------------------- session
class CastSession:
    """One connection to a Cast speaker. Not thread-safe except for close()."""

    def __init__(self, host: str, port: int = 8009, timeout: float = 4.0):
        # Cast devices present certificates signed by Google's own device CA, not a public
        # one, so the channel can't be validated against the system trust store. This only
        # ever connects to a speaker's LAN address found by discovery.
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        raw = socket.create_connection((host, port), timeout=timeout)
        self.sock = ctx.wrap_socket(raw)
        # OpenSSL forbids reading and writing one TLS connection from two threads at once
        # (it corrupts the stream: "bad record mac"). Every recv and send holds _io_lock, and
        # the reader only takes it once select() says data is waiting.
        self.sock.settimeout(0.05)
        self.inbox: queue.Queue = queue.Queue()
        self.request_id = 1
        self.last_ping = 0.0
        self.session_id = ""
        self.transport_id = ""
        self.media_session_id = 0
        self._io_lock = threading.Lock()
        threading.Thread(target=self._reader, daemon=True).start()
        self.send(RECEIVER, NS_CONNECTION, {"type": "CONNECT"})

    def _reader(self) -> None:
        try:
            while True:
                n = struct.unpack(">I", self._read_exact(4))[0]
                if n > 1 << 20:
                    raise CastError("unexpected Cast frame size")
                self.inbox.put(decode(self._read_exact(n)))
        except Exception as exc:
            self.inbox.put(exc)

    def _read_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            if not self.sock.pending() and not select.select([self.sock], [], [], 0.5)[0]:
                continue   # wait for data without holding the lock, so sends never queue behind it
            with self._io_lock:
                try:
                    chunk = self.sock.recv(n - len(buf))
                except TimeoutError:
                    continue
            if not chunk:
                raise CastError("the speaker closed the connection")
            buf += chunk
        return buf

    def send(self, dest: str, namespace: str, payload: dict) -> None:
        if "requestId" in payload and payload["requestId"] is None:
            payload["requestId"] = self.request_id
            self.request_id += 1
        b = encode(SENDER, dest, namespace, json.dumps(payload))
        with self._io_lock:
            self.sock.settimeout(5)
            try:
                self.sock.sendall(struct.pack(">I", len(b)) + b)
            finally:
                self.sock.settimeout(0.05)

    def wait(self, timeout: float, match) -> dict | None:
        """Reads messages (answering heartbeats) until match(namespace, payload) is truthy."""
        deadline = time.monotonic() + timeout
        while True:
            now = time.monotonic()
            if now >= deadline:
                return None
            if now - self.last_ping > 5:
                self.send(RECEIVER, NS_HEARTBEAT, {"type": "PING"})
                self.last_ping = now
            try:
                m = self.inbox.get(timeout=min(1.0, deadline - now))
            except queue.Empty:
                continue
            if isinstance(m, Exception):
                self.inbox.put(m)
                raise CastError(str(m)) from m
            try:
                p = json.loads(m["payload"] or "{}")
            except ValueError:
                continue
            if m["namespace"] == NS_HEARTBEAT:
                if p.get("type") == "PING":
                    self.send(m["source"], NS_HEARTBEAT, {"type": "PONG"})
                continue
            if match(m["namespace"], p):
                return p

    def launch(self) -> None:
        self.send(RECEIVER, NS_RECEIVER, {"type": "LAUNCH", "appId": APP_ID, "requestId": None})

        def launched(ns, p):
            if p.get("type") == "LAUNCH_ERROR":
                raise CastError(f"the speaker refused to start its media player ({p.get('reason')})")
            for a in (p.get("status") or {}).get("applications") or []:
                if a.get("appId") == APP_ID and a.get("transportId"):
                    self.transport_id, self.session_id = a["transportId"], a.get("sessionId", "")
                    return True
            return False

        if not self.wait(15, launched):
            raise CastError("the speaker didn't start its media player in time")
        self.send(self.transport_id, NS_CONNECTION, {"type": "CONNECT"})

    def load(self, url: str, content_type: str, title: str, stream_type: str = "BUFFERED",
             autoplay: bool = True) -> str:
        """Loads the URL and waits until it is PAUSED (autoplay off) or PLAYING. Returns the state."""
        media = {"contentId": url, "contentType": content_type, "streamType": stream_type,
                 "metadata": {"metadataType": 0, "title": title}}
        self.send(self.transport_id, NS_MEDIA,
                  {"type": "LOAD", "requestId": None, "media": media, "autoplay": autoplay})
        state = {}

        def ready(ns, p):
            if ns != NS_MEDIA:
                return False
            if p.get("type") in ("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST"):
                raise CastError(f"the speaker couldn't load the audio ({p.get('type')})")
            for st in p.get("status") or []:
                self.media_session_id = st.get("mediaSessionId", self.media_session_id)
                if st.get("playerState") == "IDLE" and st.get("idleReason") == "ERROR":
                    raise CastError("the speaker couldn't play the audio")
                if st.get("playerState") in ("PAUSED", "PLAYING"):
                    state["s"] = st["playerState"]
                    return True
            return False

        if not self.wait(20, ready):
            raise CastError("the speaker didn't finish loading the audio")
        return state["s"]

    def play(self) -> None:
        self.send(self.transport_id, NS_MEDIA,
                  {"type": "PLAY", "requestId": None, "mediaSessionId": self.media_session_id})

    def stop(self) -> None:
        """Closes the media player on the speaker (best effort)."""
        if self.session_id:
            try:
                self.send(RECEIVER, NS_RECEIVER,
                          {"type": "STOP", "sessionId": self.session_id, "requestId": None})
            except OSError:
                pass

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass
