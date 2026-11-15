package com.speakerbridge.playback

import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The sync clicks: for lining speakers up by ear, and for timing them with the microphone. */
object Clicks {
    const val HZ = 1500.0
    /** Uneven gaps (seconds), so only one offset lines heard clicks up with sent ones. */
    @JvmField var PATTERN = doubleArrayOf(0.56, 0.86, 0.66, 1.06, 0.76, 0.96, 0.60, 0.90)
    val EVERY_SECOND = doubleArrayOf(1.0)

    /** 20 ms of audio starting with a 10 ms, 1.5 kHz click that fades out. */
    fun chunk(): ByteArray {
        val b = ByteArray(Pcm.CHUNK)
        val n = Pcm.RATE / 100
        for (i in 0 until n) {
            val v = (20000 * sin(2 * PI * HZ * i / Pcm.RATE) * (1 - i.toDouble() / n)).toInt()
            for (c in 0 until Pcm.CHANNELS) {
                val o = (i * Pcm.CHANNELS + c) * 2
                b[o] = v.toByte()
                b[o + 1] = (v shr 8).toByte()
            }
        }
        return b
    }

    /**
     * Blocking: feeds [push] 20 ms chunks in real time, with a click at each of [gapsS] in
     * turn (repeating), until [stopped]. [onClick] gets the moment each click went out, in
     * seconds on the System.nanoTime clock the stream uses.
     */
    fun run(push: (ByteArray, Long) -> Unit, stopped: () -> Boolean, gapsS: DoubleArray = EVERY_SECOND,
            onClick: (Double) -> Unit = {}) {
        val click = chunk()
        val quiet = ByteArray(Pcm.CHUNK)
        val steps = gapsS.map { Math.round(it / 0.02).toInt() }
        val start = System.nanoTime()
        var n = 0L
        var k = 0
        var untilClick = 0
        while (!stopped()) {
            val now = System.nanoTime()
            if (untilClick == 0) {
                onClick(now / 1e9)
                push(click, now)
                untilClick = steps[k++ % steps.size]
            } else {
                push(quiet, now)
            }
            untilClick--
            n++
            val wait = start + n * 20_000_000L - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }
}

/** Something that records the room: the phone's microphone, or a fake one in tests. */
interface RoomRecorder : Closeable {
    /** Mono 16-bit samples recorded between two times (s, System.nanoTime clock), and the time of the first. */
    fun between(fromS: Double, toS: Double): Pair<ShortArray, Double>
    /** Forgets everything recorded so far. */
    fun clear()
}

class MicError(message: String) : Exception(message)

/**
 * Timing speakers with the microphone. Each speaker plays the uneven click pattern; matching
 * the clicks heard to the ones sent gives how late that speaker is. Only ever used after the
 * user agrees, and the recording is cleared as soon as each speaker is measured.
 */
object MicTiming {
    const val RATE = 48000
    @JvmField var MAX_LATENCY_S = 5.0          // a speaker later than this isn't measured (the pattern repeats every 6.4 s)
    private const val MATCH_TOLERANCE_S = 0.012
    private const val MIN_MATCHES = 5

    /**
     * When each click starts, to ~1 ms: the click frequency's energy in 1 ms frames, a click
     * being a jump well above the room's noise; its start is where it first reaches a quarter
     * of its own peak, so loud and quiet speakers are timed the same way.
     */
    fun onsets(samples: ShortArray, rate: Int, t0: Double): List<Double> {
        val n = rate / 1000
        val coeff = 2 * cos(2 * PI * Clicks.HZ / rate)
        val energy = DoubleArray(samples.size / n) { f ->
            var s1 = 0.0
            var s2 = 0.0
            for (i in f * n until (f + 1) * n) {
                val s = samples[i] + coeff * s1 - s2
                s2 = s1
                s1 = s
            }
            s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
        if (energy.isEmpty()) return emptyList()
        val noise = energy.sorted()[energy.size / 2]
        val threshold = maxOf(noise * 30, (100.0 * n) * (100.0 * n))   // never mistake a near-silent hiss for a click
        val found = mutableListOf<Double>()
        var f = 0
        while (f < energy.size) {
            if (energy[f] < threshold) { f++; continue }
            var peak = 0.0
            for (i in f until minOf(f + 20, energy.size)) peak = maxOf(peak, energy[i])
            var start = f
            while (start > maxOf(0, f - 10) && energy[start - 1] >= peak / 4) start--
            while (energy[start] < peak / 4) start++
            found += t0 + start.toDouble() * n / rate
            f += 300   // a click and its echoes in the room
        }
        return found
    }

    /** The one delay that lines most heard clicks up with sent ones, or null. */
    fun match(sent: List<Double>, heard: List<Double>): Double? {
        var best = emptyList<Double>()
        for (h in heard) for (s in sent) {
            val lag = h - s
            if (lag < 0 || lag > MAX_LATENCY_S) continue
            val hits = ArrayList<Double>()
            for (x in heard) for (y in sent) if (abs(x - y - lag) <= MATCH_TOLERANCE_S) hits += x - y
            if (hits.size > best.size) best = hits
        }
        if (best.size < MIN_MATCHES) return null
        return best.sorted()[best.size / 2]
    }

    /** Delays (ms) that make every measured speaker as late as the slowest one. */
    fun delaysFor(latencyS: Map<String, Double>): Map<String, Int> {
        val slowest = latencyS.values.maxOrNull() ?: return emptyMap()
        return latencyS.mapValues { minOf(Pcm.MAX_DELAY_MS, Math.round((slowest - it.value) * 1000).toInt()) }
    }
}
