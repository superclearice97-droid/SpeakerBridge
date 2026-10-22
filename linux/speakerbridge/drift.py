"""Keeps each speaker where it started while it streams (drift monitoring).

A speaker plays at its own clock, which runs a little fast or slow: tens of parts per
million, a few ms a minute, enough to pull speakers in one room apart over an hour.
Every so often SpeakerBridge asks the speaker how far into the stream it is, looks up when
that audio was captured (stream.Timeline) and so knows how late the speaker is playing.
When that has slid more than a threshold from where it started, the stream inserts or skips
that much audio for that speaker.

Reports come in two kinds. Google Cast gives its position to the millisecond, so the median
of the last few readings is used and a speaker stays within ~15 ms. Most DLNA speakers give
whole seconds only: each reading then just says "the latency is between these two values".
Readings taken at random moments over ten minutes are combined: every straight line (a
latency plus a steady drift) that passes through all their ranges is allowed, and the spread
of what those lines say about "now" is both the estimate and its honest uncertainty, typically
20-30 ms, so such a speaker stays within ~30 ms. A correction is only made when the whole
range is past the threshold. If a speaker's reports don't make sense, monitoring stops for
that speaker (and says so) rather than "correcting" it with bad data.
"""
from __future__ import annotations

import statistics
from collections import deque

WARMUP_S = 8.0               # let a speaker settle before taking its starting point
PRECISE_THRESHOLD_MS = 15
PRECISE_MAX_SPREAD_S = 0.03  # the last few precise readings must agree this well to act on
JUMPY_LIMIT = 30             # this many disagreeing readings in a row (~1 minute) stops monitoring
COARSE_THRESHOLD_MS = 10     # applies to the whole uncertainty range, so noise can't trigger it
COARSE_WINDOW = 600          # whole-second readings combined (~10 minutes; drift is steady over that)
COARSE_MIN_READINGS = 30
COARSE_EVERY = 20            # refit after this many new readings (it's the costly part)
MAX_OFF_S = 1.0              # a bigger jump than this isn't believable
MAX_EXTRA_S = 0.5            # total corrections allowed beyond what MAX_PPM of drift explains
MAX_PPM = 1000               # corrections faster than this, long-run, mean the reports are wrong
CAST_POLL_S = 2.0
DLNA_POLL_S = (0.5, 1.5)     # random gaps, so whole-second readings land at every phase


class DriftMonitor:
    def __init__(self, timeline, say, bytes_per_second: int):
        self.timeline = timeline      # () -> stream.Timeline | None (the speaker's current connection)
        self.say = say
        self.bps = bytes_per_second
        self.enabled = True
        self._reset(None)

    def _reset(self, tl) -> None:
        self.tl, self.first, self.base, self.nudged, self.readings, self.jumpy = tl, None, None, 0, 0, 0
        self.precise: list[float] = []
        self.coarse: deque = deque(maxlen=COARSE_WINDOW)   # (time, lowest, highest latency)
        self.lowest = self.highest = None

    def _latency(self, t: float, media_s: float) -> float | None:
        """How late the speaker plays audio, leaving out silence inserted / audio skipped on purpose."""
        pos = int(media_s * self.bps)
        captured = self.tl.capture_time(pos)
        return None if captured is None else t - captured - self.tl.changes_before(pos) / 1000

    def _fit(self, at: float) -> tuple[float, float] | None:
        """The range the latency at time `at` must be in, given every whole-second reading."""
        while len(self.coarse) >= COARSE_MIN_READINGS:
            fit = _fit_range([(t - at, a, b) for t, a, b in self.coarse])
            if fit:
                return fit
            self.coarse.popleft()   # no line fits: drop the oldest (a stall, or a stray reading)
        return None

    def _stop(self, reason: str) -> None:
        self.enabled = False
        self.say(f"Can't keep this speaker in sync automatically: {reason}.")

    def sample(self, t: float, media_s: float, whole_seconds: bool = False, half_rtt: float = 0.0) -> None:
        """One reading: at monotonic time `t` the speaker said it had played `media_s` seconds."""
        if not self.enabled:
            return
        tl = self.timeline()
        if tl is None:
            return
        if tl is not self.tl:
            self._reset(tl)   # the speaker reconnected: start over
        if self.first is None:
            self.first = t
        self.lowest = media_s if self.lowest is None else min(self.lowest, media_s)
        self.highest = media_s if self.highest is None else max(self.highest, media_s)
        elapsed = t - self.first
        if elapsed >= WARMUP_S and self.highest - self.lowest < elapsed / 2 - 1:
            self._stop("it doesn't report how far it has played")
            return

        # est and base are ranges (lo, hi): the latency now, and where it started
        start = self.first + WARMUP_S
        if whole_seconds:   # really somewhere in [media_s, media_s + 1)
            early, late = self._latency(t, media_s + 1), self._latency(t, media_s)
            if early is None or late is None:
                return
            self.coarse.append((t, early - half_rtt, late + half_rtt))
            self.readings += 1
            if t < start or self.readings % COARSE_EVERY:
                return
            est = self._fit(t)
            if est and self.coarse[0][0] <= start:   # the readings still reach back to the start:
                self.base = self._fit(start) or self.base   # narrow down where it started
            threshold = COARSE_THRESHOLD_MS / 1000
        else:
            lat = self._latency(t, media_s)
            if lat is None:
                return
            self.precise = (self.precise + [lat])[-5:]
            if t < start or len(self.precise) < 5:
                return
            if max(self.precise) - min(self.precise) > PRECISE_MAX_SPREAD_S:
                self.jumpy += 1
                if self.jumpy >= JUMPY_LIMIT:
                    self._stop("its position reports are too jumpy to rely on")
                return
            self.jumpy = 0
            mid = statistics.median(self.precise)
            est = (mid, mid)
            self.base = self.base or est
            threshold = PRECISE_THRESHOLD_MS / 1000
        if est is None or self.base is None:
            return

        # How much later (>0) than at the start it now plays, as a range. Act only when the
        # whole range is past the threshold (so noise can't trigger it), then correct to its middle.
        lo = est[0] - self.base[1] + self.nudged / 1000
        hi = est[1] - self.base[0] + self.nudged / 1000
        off = (lo + hi) / 2
        if abs(off) > MAX_OFF_S:
            self._stop(f"its reported position jumped by {off:+.1f} s")
            return
        if lo <= threshold and hi >= -threshold:
            return
        ms = -round(off * 1000)
        if abs(self.nudged + ms) / 1000 > MAX_EXTRA_S + MAX_PPM * 1e-6 * (t - start):
            self._stop("the corrections it keeps needing are too large to be clock drift")
            return
        self.nudged += ms
        tl.nudge(ms)
        self.say(f"Nudged {ms:+d} ms to stay in sync (its clock runs {'fast' if ms > 0 else 'slow'}).")


def _fit_range(readings: list[tuple[float, float, float]]) -> tuple[float, float] | None:
    """readings are (dt, lo, hi): at time dt the latency was within [lo, hi]. Returns every
    latency at dt = 0 that some line `latency = a + rate * dt` through all the ranges allows,
    or None if no line fits. For a given rate the allowed `a` is [lo(rate), hi(rate)], with
    lo convex and hi concave in the rate, so golden-section searches find the extremes."""
    def lo(rate):
        return max(l - rate * dt for dt, l, _ in readings)

    def hi(rate):
        return min(h - rate * dt for dt, _, h in readings)

    def best(f, a, b, sign):   # argmax of sign * f on [a, b] (f concave for sign=+1, convex for -1)
        g = (5 ** 0.5 - 1) / 2
        for _ in range(24):          # the rate to within ~0.03 ppm
            c, d = b - g * (b - a), a + g * (b - a)
            if sign * f(c) > sign * f(d):
                b = d
            else:
                a = c
        return (a + b) / 2

    def edge(inside, outside):   # where the fitting rates end, between a rate that fits and one that doesn't
        for _ in range(24):
            mid = (inside + outside) / 2
            inside, outside = (mid, outside) if hi(mid) >= lo(mid) else (inside, mid)
        return inside

    limit = MAX_PPM * 1e-6
    widest = best(lambda r: hi(r) - lo(r), -limit, limit, +1)
    if hi(widest) < lo(widest):
        return None
    low = -limit if hi(-limit) >= lo(-limit) else edge(widest, -limit)
    high = limit if hi(limit) >= lo(limit) else edge(widest, limit)
    return lo(best(lo, low, high, -1)), hi(best(hi, low, high, +1))
