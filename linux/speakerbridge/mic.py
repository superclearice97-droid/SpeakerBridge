"""Lines speakers up automatically, by listening to them with this computer's microphone.

Only runs when you ask for it, and asks first every time. Each speaker in turn plays a
pattern of clicks with uneven gaps while the microphone listens. Matching the clicks it
hears to the ones it sent gives that speaker's latency from this computer to the room (the
uneven gaps make the match unambiguous even if some clicks are missed or the room adds
noises). The delays then make every speaker as late as the slowest one.

The recording is kept in memory only for the measurement and cleared straight after; it is
never saved or sent anywhere. The microphone's own recording lag is the same for every
speaker, so it cancels out. Speakers end up in line where the microphone is, so the
computer should be roughly where you listen (sound takes ~3 ms per metre).
"""
from __future__ import annotations

import math
import shutil
import statistics
import subprocess
import sys
import threading
import time
from array import array
from dataclasses import replace

from . import stream

CONSENT = ("SpeakerBridge will use this computer's microphone to time the speakers: each one plays "
           "clicks for about 15 seconds while it listens. The recording stays in this computer's "
           "memory, is cleared as soon as each speaker is measured, and is never saved or sent "
           "anywhere.\n\nBefore starting: put the computer roughly where you listen, set the "
           "speakers to a normal volume and keep the room quiet.")

MIC_RATE = 48000
GAPS_S = (0.56, 0.86, 0.66, 1.06, 0.76, 0.96, 0.60, 0.90)   # uneven, so only one offset matches
MAX_LATENCY_S = 5.0          # a speaker later than this isn't measured (the pattern repeats every 6.3 s)
LISTEN_S = MAX_LATENCY_S + sum(GAPS_S)   # after it starts: at least one full pattern heard
START_TIMEOUT_S = 25.0
MATCH_TOLERANCE_S = 0.012
MIN_MATCHES = 5


class MicError(Exception):
    pass


# ------------------------------------------------------------ the click pattern
class Pattern:
    """Paced 20 ms chunks with a click at each gap in GAPS_S, repeating. `sent` collects when
    each click went out, on the same clock the stream uses."""

    def __init__(self):
        self.sent: list[float] = []

    def __iter__(self):
        click, quiet = stream.click_chunk(), bytes(stream.CHUNK)
        steps = [round(g / 0.02) for g in GAPS_S]
        start, n, k, until_click = time.monotonic(), 0, 0, 0
        while True:
            if until_click == 0:
                self.sent.append(time.monotonic())
                yield click
                until_click, k = steps[k % len(steps)], k + 1
            else:
                yield quiet
            until_click -= 1
            n += 1
            time.sleep(max(0.0, start + n * 0.02 - time.monotonic()))


# ------------------------------------------------------------ recording
class MicRecorder:
    """Records the default microphone (mono, 48 kHz) in memory while in use."""

    def __init__(self):
        for tool in ("pactl", "parec"):
            if not shutil.which(tool):
                raise MicError(f"'{tool}' is missing. On CachyOS: sudo pacman -S libpulse pipewire-pulse")
        source = subprocess.run(["pactl", "get-default-source"], capture_output=True, text=True).stdout.strip()
        if not source or source.endswith(".monitor"):
            raise MicError("no microphone is set as this computer's input (check your sound settings)")
        self._samples = array("h")
        self._lock = threading.Lock()
        self.t0 = math.inf          # monotonic time of sample 0 (the least-delayed estimate)

    def __enter__(self) -> "MicRecorder":
        self._proc = subprocess.Popen(
            ["parec", "--record", "-d", "@DEFAULT_SOURCE@", "--raw", "--format=s16le",
             f"--rate={MIC_RATE}", "--channels=1", "--latency-msec=10"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        threading.Thread(target=self._read, daemon=True).start()
        return self

    def _read(self) -> None:
        out = self._proc.stdout
        while True:
            b = out.read(MIC_RATE // 50 * 2)   # 20 ms
            if not b:
                return
            now = time.monotonic()
            a = array("h", b[: len(b) // 2 * 2])
            if sys.byteorder == "big":
                a.byteswap()
            with self._lock:
                self._samples.extend(a)
                self.t0 = min(self.t0, now - len(self._samples) / MIC_RATE)

    def between(self, t_from: float, t_to: float) -> tuple[array, float]:
        """The samples recorded between two monotonic times, and the time of the first one."""
        with self._lock:
            if not self._samples:
                raise MicError("the microphone isn't recording (check it's connected and allowed)")
            i = max(0, int((t_from - self.t0) * MIC_RATE))
            j = max(i, int((t_to - self.t0) * MIC_RATE))
            return self._samples[i:j], self.t0 + i / MIC_RATE

    def clear(self) -> None:
        with self._lock:
            del self._samples[:]
            self.t0 = math.inf   # re-estimated from the next reads

    def __exit__(self, *exc) -> None:
        self._proc.terminate()
        try:
            self._proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self._proc.kill()
        with self._lock:
            del self._samples[:]


# ------------------------------------------------------------ hearing the clicks
def onsets(samples, rate: int, t0: float, hz: float = stream.CLICK_HZ) -> list[float]:
    """When each click starts, to ~1 ms: the click frequency's energy in 1 ms frames, a
    click being a jump well above the room's noise; its start is where it first reaches a
    quarter of its own peak (so loud and quiet speakers are timed the same way)."""
    n = rate // 1000
    coeff = 2 * math.cos(2 * math.pi * hz / rate)
    energy = []
    for f in range(len(samples) // n):
        s1 = s2 = 0.0
        for x in samples[f * n:(f + 1) * n]:
            s1, s2 = x + coeff * s1 - s2, s1
        energy.append(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    if not energy:
        return []
    noise = statistics.median(energy)
    threshold = max(noise * 30, (100 * n) ** 2)   # and never mistake a near-silent hiss for a click
    found, f = [], 0
    while f < len(energy):
        if energy[f] < threshold:
            f += 1
            continue
        peak = max(energy[f:f + 20])
        start = f
        while start > max(0, f - 10) and energy[start - 1] >= peak / 4:
            start -= 1
        while energy[start] < peak / 4:
            start += 1
        found.append(t0 + start * n / rate)
        f += 300   # a click and its echoes in the room
    return found


def match(sent: list[float], heard: list[float]) -> float | None:
    """The one delay that lines most heard clicks up with sent ones, or None."""
    best, best_hits = None, []
    for h in heard:
        for s in sent:
            lag = h - s
            if not 0 <= lag <= MAX_LATENCY_S:
                continue
            hits = [x - y for x in heard for y in sent if abs(x - y - lag) <= MATCH_TOLERANCE_S]
            if len(hits) > len(best_hits):
                best, best_hits = lag, hits
    if len(best_hits) < MIN_MATCHES:
        return None
    return statistics.median(best_hits)


# ------------------------------------------------------------ calibrating
def measure(target: stream.Target, rec: MicRecorder, on_status=print,
            cancel: threading.Event | None = None) -> float:
    """Seconds from this computer to the room for one speaker (with no sync delay)."""
    cancel = cancel or threading.Event()
    pattern, stop, playing, problem = Pattern(), threading.Event(), threading.Event(), []

    def go():
        try:
            stream.run([replace(target, delay_ms=0)], stop, source=iter(pattern),
                       on_status=lambda m: on_status(f"{target.name}: {m}"),
                       on_playing=lambda t: playing.set())
        except stream.StreamError as exc:
            problem.append(str(exc))
        finally:
            playing.set()

    th = threading.Thread(target=go, daemon=True)
    th.start()
    def wait(until, seconds):   # until() is true, or the time is up, or it's cancelled
        deadline = time.monotonic() + seconds
        while not until() and time.monotonic() < deadline:
            if cancel.wait(0.1):
                raise MicError("stopped")

    try:
        wait(playing.is_set, START_TIMEOUT_S)
        if not playing.is_set():
            raise MicError("it didn't start playing")
        if problem:
            raise MicError(problem[0])
        t_from = time.monotonic()
        wait(lambda: not th.is_alive(), LISTEN_S)
        if problem:
            raise MicError(problem[0])
        t_to = time.monotonic()
    finally:
        stop.set()
        th.join(10)
    samples, t0 = rec.between(t_from, t_to)
    rec.clear()
    try:
        lag = match(pattern.sent, onsets(samples, MIC_RATE, t0))
    finally:
        del samples[:]
    if lag is None:
        raise MicError("the microphone couldn't pick out its clicks. Turn it up, bring the computer "
                       "closer or quieten the room, then try again")
    return lag


def calibrate(targets: list[stream.Target], on_status=print, recorder=None,
              cancel: threading.Event | None = None) -> tuple[dict[str, int], list[str]]:
    """Measures every speaker and works out the delays that line them up. Returns
    ({device key: delay ms}, [what went wrong with speakers that couldn't be measured]).
    Setting `cancel` stops it (and the microphone) within a fraction of a second."""
    cancel = cancel or threading.Event()
    latency, problems = {}, []
    with (recorder or MicRecorder()) as rec:
        for t in targets:
            if cancel.is_set():
                raise MicError("stopped")
            on_status(f"{t.name}: playing clicks and listening…")
            try:
                latency[t.key] = measure(t, rec, on_status, cancel)
                on_status(f"{t.name}: heard {latency[t.key] * 1000:.0f} ms after it was sent.")
            except MicError as exc:
                if cancel.is_set():
                    raise
                problems.append(f"{t.name}: {exc}")
                on_status(f"{t.name}: {exc}.")
            cancel.wait(1.0)   # let its last clicks die away before the next speaker
    if not latency:
        return {}, problems
    slowest = max(latency.values())
    return {k: min(stream.MAX_DELAY_MS, round((slowest - v) * 1000)) for k, v in latency.items()}, problems
