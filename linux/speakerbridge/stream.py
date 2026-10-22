"""Streams this computer's audio to one or more speakers, of any brand (v0.2/0.3).

How it works:
  1. A virtual PipeWire output called "SpeakerBridge" is created and made the default,
     so whatever the computer plays goes there instead of the local speakers.
  2. That output is recorded (parec) and served as a live WAV stream on your LAN only.
  3. Every chosen speaker is told to play that stream, over Google Cast or DLNA,
     whichever it speaks. Stopping puts everything back.
"""
from __future__ import annotations

import queue
import shutil
import socket
import struct
import subprocess
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Iterable, Iterator

from .cast import NS_MEDIA, NS_RECEIVER, APP_ID, CastError, CastSession
from .dlna import DlnaError, DlnaRenderer

RATE, CHANNELS = 48000, 2
SINK = "speakerbridge"
CHUNK = RATE * CHANNELS * 2 // 50   # 20 ms of 16-bit audio


class StreamError(Exception):
    pass


@dataclass
class Target:
    """A speaker to stream to and how to reach it."""
    name: str
    protocol: str        # "googlecast" or "dlna_renderer"
    host: str
    port: int = 0        # Cast
    location: str = ""   # DLNA description URL


def target_for(dev) -> Target | None:
    """How to stream to a discovered Device: Cast when it has it (Google keeps groups and
    stereo pairs in sync), else DLNA. None when it offers neither."""
    cast = dev.protocols.get("googlecast")
    if cast:
        return Target(dev.name, "googlecast", dev.ip, port=cast.port or 8009)
    dlna = dev.protocols.get("dlna_renderer")
    if dlna and dlna.details.get("location"):
        return Target(dev.name, "dlna_renderer", dev.ip, location=dlna.details["location"])
    return None


def wav_header(rate: int = RATE, channels: int = CHANNELS) -> bytes:
    """A WAV header for a stream of unknown length (sizes set to the maximum)."""
    return (b"RIFF" + struct.pack("<I", 0xFFFFFFFF) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, channels, rate, rate * channels * 2, channels * 2, 16) +
            b"data" + struct.pack("<I", 0xFFFFFFFF))


def local_ip_for(target_ip: str) -> str:
    """This computer's address on the network that reaches target_ip (no packet is sent)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((target_ip, 9))
        return s.getsockname()[0]


# ------------------------------------------------------------ PipeWire capture
def _pactl(*args: str) -> str:
    return subprocess.run(["pactl", *args], check=True, capture_output=True, text=True).stdout


class PipeWireCapture:
    """Creates the SpeakerBridge output, routes system audio to it and yields raw PCM."""

    def __init__(self, fmt: str = "wav"):
        for tool in ("pactl", "parec") + (("ffmpeg",) if fmt == "mp3" else ()):
            if not shutil.which(tool):
                raise StreamError(f"'{tool}' is missing. On CachyOS: sudo pacman -S "
                                  f"{'ffmpeg' if tool == 'ffmpeg' else 'libpulse pipewire-pulse'}")
        self.fmt = fmt
        self.procs: list[subprocess.Popen] = []
        self.previous_sink = ""
        self.module = ""

    def __enter__(self) -> "PipeWireCapture":
        self._unload_leftovers()
        self.previous_sink = _pactl("get-default-sink").strip()
        self.module = _pactl("load-module", "module-null-sink", f"sink_name={SINK}",
                             "sink_properties=device.description=SpeakerBridge").strip()
        _pactl("set-default-sink", SINK)
        self._move_inputs(SINK)
        rec = subprocess.Popen(["parec", "-d", f"{SINK}.monitor", "--raw", "--format=s16le",
                                f"--rate={RATE}", f"--channels={CHANNELS}", "--latency-msec=20"],
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.procs.append(rec)
        if self.fmt == "mp3":
            enc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE),
                                    "-ac", str(CHANNELS), "-i", "pipe:0", "-b:a", "320k", "-f", "mp3",
                                    "pipe:1"], stdin=rec.stdout, stdout=subprocess.PIPE)
            rec.stdout.close()
            self.procs.append(enc)
        return self

    def chunks(self) -> Iterator[bytes]:
        out = self.procs[-1].stdout
        while True:
            b = out.read(CHUNK)
            if not b:
                return
            yield b

    def __exit__(self, *exc) -> None:
        for p in reversed(self.procs):
            p.terminate()
        for p in self.procs:
            try:
                p.wait(timeout=2)
            except subprocess.TimeoutExpired:
                p.kill()
            if p.stdout:
                p.stdout.close()
        try:
            if self.previous_sink and self.previous_sink != SINK:
                _pactl("set-default-sink", self.previous_sink)
                self._move_inputs(self.previous_sink, only_from=SINK)
        finally:
            if self.module:
                subprocess.run(["pactl", "unload-module", self.module], capture_output=True)

    def _move_inputs(self, sink: str, only_from: str = "") -> None:
        sinks = {line.split("\t")[0]: line.split("\t")[1]
                 for line in _pactl("list", "short", "sinks").splitlines() if "\t" in line}
        for line in _pactl("list", "short", "sink-inputs").splitlines():
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            if only_from and sinks.get(parts[1]) != only_from:
                continue
            subprocess.run(["pactl", "move-sink-input", parts[0], sink], capture_output=True)

    def _unload_leftovers(self) -> None:
        """Removes a SpeakerBridge output left behind by a run that crashed."""
        for line in _pactl("list", "short", "modules").splitlines():
            if "module-null-sink" in line and f"sink_name={SINK}" in line:
                subprocess.run(["pactl", "unload-module", line.split("\t")[0]], capture_output=True)


# ------------------------------------------------------------ live HTTP stream
class LiveServer:
    """Serves one live audio stream over HTTP on a LAN address; every listener gets 'now'."""

    def __init__(self, source: Iterable[bytes], bind_ip: str, fmt: str = "wav"):
        self.fmt = fmt
        self.content_type = "audio/wav" if fmt == "wav" else "audio/mpeg"
        self.path = f"/speakerbridge.{fmt}"
        self._listeners: list[queue.Queue] = []
        self._lock = threading.Lock()
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"   # stream until the connection closes

            def do_HEAD(self):
                self._headers()

            def do_GET(self):
                if not self._headers():
                    return
                q: queue.Queue = queue.Queue(maxsize=250)   # ~5 s of audio
                with server._lock:
                    server._listeners.append(q)
                try:
                    if server.fmt == "wav":
                        self.wfile.write(wav_header())
                    while True:
                        b = q.get()
                        if b is None:
                            return
                        self.wfile.write(b)
                except OSError:
                    pass   # the speaker disconnected
                finally:
                    with server._lock:
                        if q in server._listeners:
                            server._listeners.remove(q)

            def _headers(self) -> bool:
                if self.path.split("?")[0] != server.path:
                    self.send_error(404)
                    return False
                self.send_response(200)
                self.send_header("Content-Type", server.content_type)
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return True

            def log_message(self, *a):
                pass

        self.httpd = ThreadingHTTPServer((bind_ip, 0), Handler)
        self.httpd.daemon_threads = True
        self.url = f"http://{bind_ip}:{self.httpd.server_address[1]}{self.path}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        threading.Thread(target=self._pump, args=(source,), daemon=True).start()

    def _pump(self, source: Iterable[bytes]) -> None:
        for b in source:
            with self._lock:
                for q in list(self._listeners):
                    try:
                        q.put_nowait(b)
                    except queue.Full:      # a listener that fell far behind gets cut off
                        try:
                            q.get_nowait()
                        except queue.Empty:
                            pass
                        q.put_nowait(None)
                        self._listeners.remove(q)
        with self._lock:
            for q in self._listeners:
                q.put(None)

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


# ------------------------------------------------------------ putting it together
TITLE = "This computer (SpeakerBridge)"


def run(targets: list[Target], stop: threading.Event, source: Iterable[bytes] | None = None,
        fmt: str = "wav", on_status=print) -> list[str]:
    """Streams to every target at once until `stop` is set or every speaker has stopped.
    Blocking. A speaker that fails doesn't stop the others; returns their error messages
    (raises StreamError if none could play). `source` replaces the PipeWire capture (tests)."""
    if not targets:
        raise StreamError("no speakers to stream to")
    bind_ip = local_ip_for(targets[0].host)
    say = (lambda t, msg: on_status(f"{t.name}: {msg}")) if len(targets) > 1 else (lambda t, msg: on_status(msg))
    failures: list[str] = []

    capture = PipeWireCapture(fmt) if source is None else None
    with (capture if capture else _nullctx()):
        server = LiveServer(capture.chunks() if capture else source, bind_ip, fmt)

        def play(t: Target) -> None:
            try:
                if local_ip_for(t.host) != bind_ip:
                    raise StreamError(f"it is on a different network from {targets[0].name}")
                (_play_cast if t.protocol == "googlecast" else _play_dlna)(t, server, stop, lambda m: say(t, m))
            except (StreamError, CastError, DlnaError, OSError) as exc:
                if len(targets) == 1:
                    failures.append(str(exc))
                else:
                    failures.append(f"{t.name}: {exc}")
                    say(t, f"Couldn't stream: {exc}. The other speakers carry on.")

        if len({t.protocol for t in targets}) > 1:
            on_status("Note: Cast and DLNA speakers buffer differently, so they can be a second "
                      "or two apart. Fine for different rooms; in one room it will echo.")
        threads = [threading.Thread(target=play, args=(t,), daemon=True) for t in targets]
        try:
            for th in threads:
                th.start()
            for th in threads:
                th.join()
        finally:
            stop.set()   # on Ctrl+C too, so every speaker is told to stop before audio switches back
            for th in threads:
                th.join(timeout=10)
            server.close()
    if len(failures) == len(targets):
        raise StreamError("; ".join(failures))
    return failures


def _play_cast(t: Target, server: LiveServer, stop: threading.Event, say) -> None:
    say("Connecting to the speaker…")
    session = CastSession(t.host, t.port or 8009)
    try:
        session.launch()
        session.load(server.url, server.content_type, TITLE, stream_type="LIVE", autoplay=True)
        say("Streaming. Anything this computer plays now comes out of the speaker. "
            "Stop to switch audio back.")

        def ended(ns, p):
            if ns == NS_RECEIVER and p.get("type") == "RECEIVER_STATUS":
                apps = (p.get("status") or {}).get("applications") or []
                return not any(a.get("appId") == APP_ID for a in apps)
            if ns == NS_MEDIA:
                for st in p.get("status") or []:
                    if st.get("playerState") == "IDLE":
                        return True
            return False

        while not stop.is_set():
            if session.wait(0.5, ended):
                say("The speaker stopped the stream (it was stopped from elsewhere or "
                    "lost the connection).")
                break
    finally:
        session.stop()
        session.close()


def _play_dlna(t: Target, server: LiveServer, stop: threading.Event, say) -> None:
    say("Connecting to the speaker over DLNA…")
    renderer = DlnaRenderer(t.location)
    try:
        renderer.play(server.url, server.content_type, TITLE)
        say("Streaming over DLNA. Anything this computer plays now comes out of the speaker. "
            "Stop to switch audio back.")
        stop.wait()
    finally:
        renderer.stop()


class _nullctx:
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False
