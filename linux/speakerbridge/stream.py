"""Streams this computer's audio to one or more speakers, of any brand (v0.2/0.3).

How it works:
  1. A virtual PipeWire output called "SpeakerBridge" is created and made the default,
     so whatever the computer plays goes there instead of the local speakers.
  2. That output is recorded (parec) and served as a live WAV stream on your LAN only.
  3. Every chosen speaker is told to play that stream, over Google Cast or DLNA,
     whichever it speaks. Stopping puts everything back.
"""
from __future__ import annotations

import math
import queue
import random
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from array import array
from bisect import bisect_right
from collections import deque
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Iterable, Iterator

from . import drift
from .cast import NS_MEDIA, NS_RECEIVER, APP_ID, CastError, CastSession
from .dlna import DlnaError, DlnaRenderer

RATE, CHANNELS = 48000, 2
SINK = "speakerbridge"
CHUNK = RATE * CHANNELS * 2 // 50   # 20 ms of 16-bit audio
BYTES_PER_MS = RATE * CHANNELS * 2 // 1000
MAX_DELAY_MS = 3000
FADE_FRAMES = RATE * 2 // 1000      # 2 ms fade either side of an insert or skip, so it doesn't click


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
    key: str = ""        # Device.key, for saving this speaker's delay
    delay_ms: int = 0    # sync delay; may be changed while streaming


def target_for(dev) -> Target | None:
    """How to stream to a discovered Device: Cast when it has it (Google keeps groups and
    stereo pairs in sync), else DLNA. None when it offers neither."""
    cast = dev.protocols.get("googlecast")
    if cast:
        return Target(dev.name, "googlecast", dev.ip, port=cast.port or 8009, key=dev.key)
    dlna = dev.protocols.get("dlna_renderer")
    if dlna and dlna.details.get("location"):
        return Target(dev.name, "dlna_renderer", dev.ip, location=dlna.details["location"], key=dev.key)
    return None


def wav_header(rate: int = RATE, channels: int = CHANNELS) -> bytes:
    """A WAV header for a stream of unknown length (sizes set to the maximum)."""
    return (b"RIFF" + struct.pack("<I", 0xFFFFFFFF) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, channels, rate, rate * channels * 2, channels * 2, 16) +
            b"data" + struct.pack("<I", 0xFFFFFFFF))


CLICK_HZ = 1500


def click_chunk() -> bytes:
    """20 ms of audio starting with a 10 ms, 1.5 kHz click that fades out."""
    click = bytearray()
    for i in range(RATE // 100):
        v = int(20000 * math.sin(2 * math.pi * CLICK_HZ * i / RATE) * (1 - i / (RATE // 100)))
        click += struct.pack("<hh", v, v)
    return bytes(click) + bytes(CHUNK - len(click))


def clicks() -> Iterator[bytes]:
    """A short click once a second, paced in real time, for lining speakers up by ear."""
    period = [click_chunk()] + [bytes(CHUNK)] * 49
    start, n = time.monotonic(), 0
    while True:
        yield period[n % 50]
        n += 1
        time.sleep(max(0.0, start + n * 0.02 - time.monotonic()))


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
def fade(b: bytes, out: bool) -> bytes:
    """Fades the last (out) or first (in) FADE_FRAMES of 16-bit PCM."""
    a = array("h", b)
    if sys.byteorder == "big":
        a.byteswap()
    frames = len(a) // CHANNELS
    n = min(FADE_FRAMES, frames)
    for i in range(n):
        g = (i + 1) / (n + 1)
        f = frames - 1 - i if out else i
        for c in range(CHANNELS):
            a[f * CHANNELS + c] = int(a[f * CHANNELS + c] * g)
    if sys.byteorder == "big":
        a.byteswap()
    return a.tobytes()


class Timeline:
    """What one speaker has been sent, so the play position it reports can be turned back
    into the moment that audio was captured (see drift.py)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._chunks: deque = deque(maxlen=1500)   # ~30 s of (media offset, capture time or None)
        self._changes: deque = deque(maxlen=500)   # (media offset, total ms inserted - skipped so far)
        self._nudge = 0
        self.sent = 0                              # media bytes sent (after the WAV header)

    def add(self, nbytes: int, captured: float | None) -> None:
        with self._lock:
            self._chunks.append((self.sent, captured))
            self.sent += nbytes

    def changed(self, ms: int) -> None:
        """Records that `ms` of silence was inserted (+) or audio skipped (-) here."""
        with self._lock:
            total = self._changes[-1][1] if self._changes else 0
            self._changes.append((self.sent, total + ms))

    def nudge(self, ms: int) -> None:
        """Asks the stream to insert (+) or skip (-) `ms` without changing the planned delay."""
        with self._lock:
            self._nudge += ms

    def take_nudge(self) -> int:
        with self._lock:
            n, self._nudge = self._nudge, 0
            return n

    def capture_time(self, pos: int) -> float | None:
        """When the audio at media byte `pos` was captured; None for silence or unknown."""
        with self._lock:
            chunks, sent = list(self._chunks), self.sent
        i = bisect_right([c[0] for c in chunks], pos) - 1
        if i < 0 or pos >= sent:
            return None
        off, ts = chunks[i]
        return None if ts is None else ts + (pos - off) / (BYTES_PER_MS * 1000)

    def changes_before(self, pos: int) -> int:
        """Total ms inserted minus skipped before media byte `pos`."""
        with self._lock:
            changes = list(self._changes)
        i = bisect_right([c[0] for c in changes], pos) - 1
        return changes[i][1] if i >= 0 else 0


class LiveServer:
    """Serves one live audio stream over HTTP on a LAN address; every listener gets 'now'.

    Speaker N fetches /N/speakerbridge.wav and hears it delay_ms(N) later, so a speaker
    that plays early can wait for slower ones. A speaker starts playing once it has buffered
    a fixed amount and then keeps its own clock, so only holding audio back *before* it starts
    adds delay. A change mid-stream is made by inserting silence or skipping audio instead
    (WAV only; for MP3 a new delay applies on the next stream). Each speaker's Timeline also
    takes small drift corrections.
    """

    def __init__(self, source: Iterable[bytes], bind_ip: str, fmt: str = "wav",
                 delay_ms: Callable[[int], int] = lambda i: 0):
        self.fmt = fmt
        self.content_type = "audio/wav" if fmt == "wav" else "audio/mpeg"
        self.path = f"/speakerbridge.{fmt}"
        self._route = re.compile(rf"^(?:/(\d+))?{re.escape(self.path)}$")
        self._listeners: list[queue.Queue] = []
        self._lock = threading.Lock()
        self.timelines: dict[int, Timeline] = {}   # latest connection of each speaker
        self._closed = False
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"   # stream until the connection closes

            def do_HEAD(self):
                self._headers()

            def do_GET(self):
                if not self._headers():
                    return
                n = server._route.match(self.path.split("?")[0]).group(1)

                def delay() -> int:
                    try:   # any LAN device can ask for any number; unknown ones get no delay
                        return 0 if n is None else max(0, min(MAX_DELAY_MS, int(delay_ms(int(n)))))
                    except (IndexError, KeyError, TypeError, ValueError):
                        return 0
                # ~5 s of audio beyond the longest delay
                q: queue.Queue = queue.Queue(maxsize=250 + MAX_DELAY_MS // 20)
                tl = Timeline()
                with server._lock:
                    server._listeners.append(q)
                    if n is not None:
                        server.timelines[int(n)] = tl
                wav = server.fmt == "wav"
                try:
                    if wav:
                        self.wfile.write(wav_header())
                    held, skip, fade_in = delay(), 0, False
                    while True:
                        item = q.get()
                        if item is None:
                            return
                        captured, b = item
                        if skip:
                            cut = min(skip, len(b))
                            b, skip, captured = b[cut:], skip - cut, captured + cut / (BYTES_PER_MS * 1000)
                        wait = captured + held / 1000 - time.monotonic()
                        if wait > 0:
                            time.sleep(wait)
                        # A new delay changes `held` too, so the speaker's buffer stays as full as it
                        # was; a drift nudge changes only what is sent. Both apply after this chunk,
                        # so the silence goes out before the next chunk is held back.
                        want = delay() if wav else held
                        change = want - held + (tl.take_nudge() if wav else 0)
                        if b:
                            if fade_in:
                                b, fade_in = fade(b, out=False), False
                            if change:
                                b = fade(b, out=True)
                            tl.add(len(b), captured)
                            self.wfile.write(b)
                        if change > 0:
                            tl.add(change * BYTES_PER_MS, None)
                            self.wfile.write(bytes(change * BYTES_PER_MS))
                        elif change < 0:
                            skip += -change * BYTES_PER_MS
                        if change:
                            tl.changed(change)
                            fade_in = True
                        held = want
                except OSError:
                    pass   # the speaker disconnected
                finally:
                    with server._lock:
                        if q in server._listeners:
                            server._listeners.remove(q)

            def _headers(self) -> bool:
                if not server._route.match(self.path.split("?")[0]):
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

    def timeline(self, n: int) -> Timeline | None:
        with self._lock:
            return self.timelines.get(n)

    def url_for(self, n: int) -> str:
        """The stream URL for speaker n, which gets delay_ms(n)."""
        return self.url.replace(self.path, f"/{n}{self.path}", 1)

    def _pump(self, source: Iterable[bytes]) -> None:
        for b in source:
            if self._closed:   # an endless source (clicks) would otherwise run on forever
                break
            item = (time.monotonic(), b)
            with self._lock:
                for q in list(self._listeners):
                    try:
                        q.put_nowait(item)
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
        """Stops serving, and ends every open stream (a speaker that ignored Stop included)."""
        self._closed = True
        self.httpd.shutdown()
        self.httpd.server_close()
        with self._lock:
            for q in self._listeners:
                try:
                    q.get_nowait()   # make room if it's full
                except queue.Empty:
                    pass
                q.put_nowait(None)


# ------------------------------------------------------------ putting it together
TITLE = "This computer (SpeakerBridge)"


@dataclass
class _Link:
    """Everything one speaker's player thread needs."""
    target: Target
    url: str
    content_type: str
    stop: threading.Event
    say: Callable[[str], None]
    heard: str                              # extra words for the "Streaming." message
    monitor: drift.DriftMonitor | None      # None for MP3, which can't take corrections
    playing: Callable[[], None]             # call once the speaker has started playing


def run(targets: list[Target], stop: threading.Event, source: Iterable[bytes] | None = None,
        fmt: str = "wav", on_status=print, on_playing: Callable[[Target], None] = lambda t: None) -> list[str]:
    """Streams to every target at once until `stop` is set or every speaker has stopped.
    Blocking. A speaker that fails doesn't stop the others; returns their error messages
    (raises StreamError if none could play). `source` replaces the PipeWire capture (sync
    clicks, microphone calibration, tests). on_playing(target) is called as each one starts."""
    if not targets:
        raise StreamError("no speakers to stream to")
    bind_ip = local_ip_for(targets[0].host)
    say = (lambda t, msg: on_status(f"{t.name}: {msg}")) if len(targets) > 1 else (lambda t, msg: on_status(msg))
    failures: list[str] = []

    capture = PipeWireCapture(fmt) if source is None else None
    # Only true when it's this computer's audio (not the sync clicks or a test source).
    heard = (" Anything this computer plays now comes out of the speaker. Stop to switch audio back."
             if capture else "")
    with (capture if capture else _nullctx()):
        server = LiveServer(capture.chunks() if capture else source, bind_ip, fmt,
                            delay_ms=lambda n: targets[n].delay_ms)

        def play(n: int, t: Target) -> None:
            try:
                if local_ip_for(t.host) != bind_ip:
                    raise StreamError(f"it is on a different network from {targets[0].name}")
                say_t = lambda m: say(t, m)   # noqa: E731
                monitor = (drift.DriftMonitor(lambda: server.timeline(n), say_t, BYTES_PER_MS * 1000)
                           if fmt == "wav" else None)
                link = _Link(t, server.url_for(n), server.content_type, stop, say_t, heard, monitor,
                             lambda: on_playing(t))
                (_play_cast if t.protocol == "googlecast" else _play_dlna)(link)
            except (StreamError, CastError, DlnaError, OSError) as exc:
                if len(targets) == 1:
                    failures.append(str(exc))
                else:
                    failures.append(f"{t.name}: {exc}")
                    say(t, f"Couldn't stream: {exc}. The other speakers carry on.")

        if capture and len({t.protocol for t in targets}) > 1 and not any(t.delay_ms for t in targets):
            on_status("Note: Cast and DLNA speakers buffer differently, so they can be a second "
                      "or two apart. Fine for different rooms; for one room, line them up with "
                      "sync clicks first.")
        threads = [threading.Thread(target=play, args=(n, t), daemon=True) for n, t in enumerate(targets)]
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


def _play_cast(link: _Link) -> None:
    t, say, mon = link.target, link.say, link.monitor
    say("Connecting to the speaker…")
    session = CastSession(t.host, t.port or 8009)
    try:
        session.launch()
        session.load(link.url, link.content_type, TITLE, stream_type="LIVE", autoplay=True)
        say("Streaming." + link.heard)
        link.playing()

        def ended(ns, p):
            if ns == NS_RECEIVER and p.get("type") == "RECEIVER_STATUS":
                apps = (p.get("status") or {}).get("applications") or []
                return not any(a.get("appId") == APP_ID for a in apps)
            if ns == NS_MEDIA:
                for st in p.get("status") or []:
                    if st.get("playerState") == "IDLE":
                        return True
                    if mon and st.get("playerState") == "PLAYING" and "currentTime" in st:
                        mon.sample(time.monotonic(), float(st["currentTime"]))
            return False

        next_poll = 0.0
        while not link.stop.is_set():
            if mon and mon.enabled and time.monotonic() >= next_poll:
                session.get_status()
                next_poll = time.monotonic() + drift.CAST_POLL_S
            if session.wait(0.5, ended):
                say("The speaker stopped the stream (it was stopped from elsewhere or "
                    "lost the connection).")
                break
    finally:
        session.stop()
        session.close()


def _play_dlna(link: _Link) -> None:
    say, mon = link.say, link.monitor
    say("Connecting to the speaker over DLNA…")
    renderer = DlnaRenderer(link.target.location)
    try:
        renderer.play(link.url, link.content_type, TITLE)
        say("Streaming over DLNA." + link.heard)
        link.playing()
        started, answered = time.monotonic(), False
        while not link.stop.wait(random.uniform(*drift.DLNA_POLL_S) if mon and mon.enabled else None):
            t0 = time.monotonic()
            pos = renderer.position()
            t1 = time.monotonic()
            if pos is not None:
                answered = True
                mon.sample((t0 + t1) / 2, pos[0], whole_seconds=pos[1], half_rtt=(t1 - t0) / 2)
            elif not answered and t1 - started > drift.WARMUP_S:
                mon.enabled = False
                say("Can't keep this speaker in sync automatically: it doesn't report how far "
                    "it has played.")
    finally:
        renderer.stop()


class _nullctx:
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False
