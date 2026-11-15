package com.speakerbridge.playback

import java.util.ArrayDeque

/*
 * Keeping speakers in step. A port of linux/speakerbridge/stream.py (Timeline, fade) and
 * drift.py, so the phone and the Linux app behave the same way.
 */

/** Stream format shared by everything here: 48 kHz, stereo, 16-bit little-endian PCM. */
object Pcm {
    const val RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_MS = RATE * CHANNELS * 2 / 1000
    const val BYTES_PER_SECOND = RATE * CHANNELS * 2
    const val CHUNK = BYTES_PER_SECOND / 50          // 20 ms
    const val MAX_DELAY_MS = 3000
    private const val FADE_FRAMES = RATE * 2 / 1000  // 2 ms either side of an insert or skip, so it doesn't click

    /** Fades the last ([out]) or first FADE_FRAMES of 16-bit little-endian PCM, in a copy. */
    fun fade(b: ByteArray, out: Boolean): ByteArray {
        val a = b.copyOf()
        val frames = a.size / (CHANNELS * 2)
        val n = minOf(FADE_FRAMES, frames)
        for (i in 0 until n) {
            val g = (i + 1).toDouble() / (n + 1)
            val f = if (out) frames - 1 - i else i
            for (c in 0 until CHANNELS) {
                val o = (f * CHANNELS + c) * 2
                val v = (((a[o].toInt() and 0xff) or (a[o + 1].toInt() shl 8)) * g).toInt()
                a[o] = v.toByte()
                a[o + 1] = (v shr 8).toByte()
            }
        }
        return a
    }

    /** Whether the PCM has any real sound in it (not just silence or dither). */
    fun isLoud(pcm: ByteArray, n: Int = pcm.size): Boolean {
        var i = 0
        while (i + 1 < n) {
            val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            if (v > 64 || v < -64) return true   // of 32767: well below anything audible, above dither
            i += 2
        }
        return false
    }
}

/**
 * What one speaker has been sent, so the play position it reports can be turned back into
 * the moment that audio was captured. Positions are media bytes (after any file header).
 */
class Timeline {
    private val chunks = ArrayDeque<Pair<Long, Double?>>()    // ~30 s of (media offset, capture time s or null = silence)
    private val changes = ArrayDeque<Pair<Long, Int>>()       // (media offset, total ms inserted - skipped so far)
    private var nudge = 0
    var sent = 0L                                             // media bytes sent
        private set

    @Synchronized fun add(bytes: Int, capturedS: Double?) {
        chunks.addLast(sent to capturedS)
        if (chunks.size > 1500) chunks.removeFirst()
        sent += bytes
    }

    /** Records that [ms] of silence was inserted (+) or audio skipped (-) here. */
    @Synchronized fun changed(ms: Int) {
        changes.addLast(sent to ((changes.peekLast()?.second ?: 0) + ms))
        if (changes.size > 500) changes.removeFirst()
    }

    /** Asks the stream to insert (+) or skip (-) [ms] without changing the planned delay. */
    @Synchronized fun nudge(ms: Int) { nudge += ms }

    @Synchronized fun takeNudge(): Int = nudge.also { nudge = 0 }

    /** When the audio at media byte [pos] was captured (seconds); null for silence or unknown. */
    @Synchronized fun captureTime(pos: Long): Double? {
        if (pos >= sent) return null
        val hit = chunks.lastOrNull { it.first <= pos } ?: return null
        return hit.second?.let { it + (pos - hit.first).toDouble() / Pcm.BYTES_PER_SECOND }
    }

    /** Total ms inserted minus skipped before media byte [pos]. */
    @Synchronized fun changesBefore(pos: Long): Int = changes.lastOrNull { it.first <= pos }?.second ?: 0
}

/**
 * Keeps one speaker where it started while it streams. A speaker's clock runs a little fast
 * or slow (tens of parts per million: a few ms a minute). Each reading says how far into the
 * stream the speaker is; the [Timeline] turns that into how late it is playing. When that has
 * slid past a threshold, the stream inserts or skips that much audio for that speaker.
 *
 * Google Cast reports to the millisecond: the median of the last few readings is used.
 * Most DLNA speakers report whole seconds: each reading then only says "the latency is
 * between these two values", and every straight line (a latency plus a steady drift) through
 * ten minutes of those ranges is allowed; the spread of what the lines say about "now" is the
 * honest uncertainty, and a correction is made only when that whole range is past the
 * threshold. If a speaker's reports don't make sense, monitoring stops for it (and says so)
 * rather than "correcting" it from bad data.
 */
class DriftMonitor(private val timeline: () -> Timeline?, private val say: (String) -> Unit) {
    var enabled = true
        private set
    private var tl: Timeline? = null
    private var first = Double.NaN
    private var base: Pair<Double, Double>? = null
    private var nudged = 0
    private var readings = 0
    private var jumpy = 0
    private var precise = listOf<Double>()
    private val coarse = ArrayDeque<Triple<Double, Double, Double>>()   // (time, lowest, highest latency)
    private var lowest = Double.NaN
    private var highest = Double.NaN

    private fun reset(t: Timeline?) {
        tl = t; first = Double.NaN; base = null; nudged = 0; readings = 0; jumpy = 0
        precise = emptyList(); coarse.clear(); lowest = Double.NaN; highest = Double.NaN
    }

    /** How late the speaker plays audio, leaving out silence inserted / audio skipped on purpose. */
    private fun latency(t: Double, mediaS: Double): Double? {
        val pos = (mediaS * Pcm.BYTES_PER_SECOND).toLong()
        val captured = tl!!.captureTime(pos) ?: return null
        return t - captured - tl!!.changesBefore(pos) / 1000.0
    }

    private fun fit(at: Double): Pair<Double, Double>? {
        while (coarse.size >= COARSE_MIN_READINGS) {
            fitRange(coarse.map { Triple(it.first - at, it.second, it.third) })?.let { return it }
            coarse.removeFirst()   // no line fits: drop the oldest (a stall, or a stray reading)
        }
        return null
    }

    private fun stop(reason: String) {
        enabled = false
        say("Can't keep this speaker in sync automatically: $reason.")
    }

    /** Stops monitoring, e.g. because the speaker never answers when asked where it is. */
    @Synchronized fun giveUp(reason: String) { if (enabled) stop(reason) }

    /** One reading: at time [t] (seconds, any steady clock) the speaker had played [mediaS] seconds. */
    @Synchronized fun sample(t: Double, mediaS: Double, wholeSeconds: Boolean = false, halfRtt: Double = 0.0) {
        if (!enabled) return
        val now = timeline() ?: return
        if (now !== tl) reset(now)   // the speaker reconnected: start over
        if (first.isNaN()) first = t
        lowest = if (lowest.isNaN()) mediaS else minOf(lowest, mediaS)
        highest = if (highest.isNaN()) mediaS else maxOf(highest, mediaS)
        val elapsed = t - first
        if (elapsed >= WARMUP_S && highest - lowest < elapsed / 2 - 1) return stop("it doesn't report how far it has played")

        // est and base are ranges (lo, hi): the latency now, and where it started
        val start = first + WARMUP_S
        val est: Pair<Double, Double>?
        val threshold: Double
        if (wholeSeconds) {   // really somewhere in [mediaS, mediaS + 1)
            val early = latency(t, mediaS + 1) ?: return
            val late = latency(t, mediaS) ?: return
            coarse.addLast(Triple(t, early - halfRtt, late + halfRtt))
            if (coarse.size > COARSE_WINDOW) coarse.removeFirst()
            readings++
            if (t < start || readings % COARSE_EVERY != 0) return
            est = fit(t)
            if (est != null && coarse.first.first <= start) base = fit(start) ?: base   // narrow down where it started
            threshold = COARSE_THRESHOLD_MS / 1000.0
        } else {
            val lat = latency(t, mediaS) ?: return
            precise = (precise + lat).takeLast(5)
            if (t < start || precise.size < 5) return
            if (precise.max() - precise.min() > PRECISE_MAX_SPREAD_S) {
                if (++jumpy >= JUMPY_LIMIT) stop("its position reports are too jumpy to rely on")
                return
            }
            jumpy = 0
            val mid = precise.sorted()[2]
            est = mid to mid
            if (base == null) base = est
            threshold = PRECISE_THRESHOLD_MS / 1000.0
        }
        val b = base
        if (est == null || b == null) return

        // How much later (>0) than at the start it now plays, as a range. Act only when the whole
        // range is past the threshold (so noise can't trigger it), then correct to its middle.
        val lo = est.first - b.second + nudged / 1000.0
        val hi = est.second - b.first + nudged / 1000.0
        val off = (lo + hi) / 2
        if (kotlin.math.abs(off) > MAX_OFF_S) return stop("its reported position jumped by ${"%+.1f".format(off)} s")
        if (lo <= threshold && hi >= -threshold) return
        val ms = -Math.round(off * 1000).toInt()
        if (kotlin.math.abs(nudged + ms) / 1000.0 > MAX_EXTRA_S + MAX_PPM * 1e-6 * (t - start)) {
            return stop("the corrections it keeps needing are too large to be clock drift")
        }
        nudged += ms
        now.nudge(ms)
        say("Nudged ${"%+d".format(ms)} ms to stay in sync (its clock runs ${if (ms > 0) "fast" else "slow"}).")
    }

    companion object {
        @JvmField var WARMUP_S = 8.0               // let a speaker settle before taking its starting point
        const val PRECISE_THRESHOLD_MS = 15
        @JvmField var PRECISE_MAX_SPREAD_S = 0.03  // the last few precise readings must agree this well
        const val JUMPY_LIMIT = 30                 // this many disagreeing readings in a row stops monitoring
        const val COARSE_THRESHOLD_MS = 10         // applies to the whole uncertainty range
        const val COARSE_WINDOW = 600              // whole-second readings combined (~10 minutes)
        const val COARSE_MIN_READINGS = 30
        const val COARSE_EVERY = 20                // refit after this many new readings (the costly part)
        const val MAX_OFF_S = 1.0                  // a bigger jump than this isn't believable
        const val MAX_EXTRA_S = 0.5                // corrections allowed beyond what MAX_PPM of drift explains
        @JvmField var MAX_PPM = 1000.0

        /**
         * readings are (dt, lo, hi): at time dt the latency was within [lo, hi]. Returns every
         * latency at dt = 0 that some line `latency = a + rate * dt` through all the ranges
         * allows, or null if no line fits. For a given rate the allowed `a` is
         * [lo(rate), hi(rate)], lo convex and hi concave, so golden-section searches find them.
         */
        fun fitRange(readings: List<Triple<Double, Double, Double>>): Pair<Double, Double>? {
            fun lo(rate: Double) = readings.maxOf { it.second - rate * it.first }
            fun hi(rate: Double) = readings.minOf { it.third - rate * it.first }
            fun best(f: (Double) -> Double, from: Double, to: Double, sign: Int): Double {
                val g = (Math.sqrt(5.0) - 1) / 2
                var a = from
                var b = to
                repeat(24) {
                    val c = b - g * (b - a)
                    val d = a + g * (b - a)
                    if (sign * f(c) > sign * f(d)) b = d else a = c
                }
                return (a + b) / 2
            }
            fun edge(inside0: Double, outside0: Double): Double {
                var inside = inside0
                var outside = outside0
                repeat(24) {
                    val mid = (inside + outside) / 2
                    if (hi(mid) >= lo(mid)) inside = mid else outside = mid
                }
                return inside
            }
            val limit = MAX_PPM * 1e-6
            val widest = best({ hi(it) - lo(it) }, -limit, limit, +1)
            if (hi(widest) < lo(widest)) return null
            val low = if (hi(-limit) >= lo(-limit)) -limit else edge(widest, -limit)
            val high = if (hi(limit) >= lo(limit)) limit else edge(widest, limit)
            return lo(best(::lo, low, high, -1)) to hi(best(::hi, low, high, +1))
        }
    }
}
