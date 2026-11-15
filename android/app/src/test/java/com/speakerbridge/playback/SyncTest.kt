package com.speakerbridge.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Simulated time: what LiveStreamServer does for one listener (send each 20 ms chunk when
 * it's due, apply nudges) plus a speaker whose clock is [ppm] off. Fast enough to run an hour
 * of playback in a second or two.
 */
private class SimSpeaker(ppm: Double, private val bufferS: Double = 1.5, private val wholeSeconds: Boolean = false, monitor: Boolean = true) {
    val tl = Timeline()
    private val rate = 1 + ppm * 1e-6
    val said = mutableListOf<String>()
    val mon = if (monitor) DriftMonitor({ tl }, { said += it }) else null

    /** Returns (time, true capture-to-ear latency) once a second. */
    fun run(seconds: Int): List<Pair<Double, Double>> {
        val rng = Random(7)
        var cNext = 0.0
        var skipS = 0.0
        var tStart = Double.NaN
        var nextPoll = 1.0
        val latencies = mutableListOf<Pair<Double, Double>>()
        for (step in 1..seconds * 50) {
            val t = step * 0.02
            while (cNext + 0.02 <= t + 1e-9) {          // what LiveStreamServer sends (no planned delay here)
                cNext += skipS
                skipS = 0.0
                tl.add(Pcm.CHUNK, cNext)
                cNext += 0.02
                if (tStart.isNaN()) tStart = t + bufferS
                val n = tl.takeNudge()
                if (n > 0) tl.add(n * Pcm.BYTES_PER_MS, null) else if (n < 0) skipS = -n / 1000.0
                if (n != 0) tl.changed(n)
            }
            if (tStart.isNaN() || t < tStart) continue
            val played = (t - tStart) * rate
            if (mon != null && t >= nextPoll) {
                val rtt = rng.nextDouble(0.002, 0.02)
                mon.sample(t + rtt / 2, if (wholeSeconds) Math.floor(played) else played, wholeSeconds, rtt / 2)
                nextPoll = t + if (wholeSeconds) rng.nextDouble(0.5, 1.5) else 2.0
            }
            if (step % 50 == 0) {
                tl.captureTime((played * Pcm.BYTES_PER_SECOND).toLong())?.let { latencies += t to (t - it) }
            }
        }
        return latencies
    }
}

class SyncTest {
    @After fun restore() { DriftMonitor.WARMUP_S = 8.0; DriftMonitor.MAX_PPM = 1000.0; DriftMonitor.PRECISE_MAX_SPREAD_S = 0.03 }

    private fun spreadMs(lat: List<Pair<Double, Double>>): Double {
        val late = lat.filter { it.first > 60 }.map { it.second }
        return (late.max() - late.min()) * 1000
    }

    private fun filled(seconds: Int = 30) = Timeline().apply { repeat(seconds * 50) { add(Pcm.CHUNK, it * 0.02) } }

    @Test fun timelineTurnsAPositionBackIntoWhenItWasCaptured() {
        val tl = Timeline()
        tl.add(Pcm.CHUNK, 10.0)
        tl.add(Pcm.CHUNK, 10.02)
        tl.add(100 * Pcm.BYTES_PER_MS, null)    // inserted silence
        tl.changed(100)
        tl.add(Pcm.CHUNK, 10.04)
        assertEquals(10.01, tl.captureTime(Pcm.CHUNK / 2L)!!, 1e-9)
        assertNull(tl.captureTime(2L * Pcm.CHUNK + 10))                       // inside the silence
        assertEquals(10.04, tl.captureTime(2L * Pcm.CHUNK + 100 * Pcm.BYTES_PER_MS)!!, 1e-9)
        assertEquals(0, tl.changesBefore(Pcm.CHUNK.toLong()))
        assertEquals(100, tl.changesBefore(2L * Pcm.CHUNK + 100 * Pcm.BYTES_PER_MS))
        assertNull(tl.captureTime(tl.sent))                                    // not sent yet
    }

    @Test fun fadesRampToSilenceAtTheRightEnd() {
        val loud = ByteArray(Pcm.CHUNK) { if (it % 2 == 0) 0x00 else 0x40 }   // every sample 16384
        fun sample(b: ByteArray, frame: Int) = (b[frame * 4].toInt() and 0xff) or (b[frame * 4 + 1].toInt() shl 8)
        val out = Pcm.fade(loud, out = true)
        val inn = Pcm.fade(loud, out = false)
        val last = Pcm.CHUNK / 4 - 1
        assertTrue(sample(out, last) < 400 && sample(out, 0) == 16384)
        assertTrue(sample(inn, 0) < 400 && sample(inn, last) == 16384)
        assertEquals(16384, sample(loud, last))   // the original is left alone
    }

    @Test fun withoutMonitoringAnHourDriftsApart() {
        assertTrue(spreadMs(SimSpeaker(50.0, monitor = false).run(3600)) > 170)   // 50 ppm = 180 ms an hour
    }

    @Test fun castStyleReportsKeepAFastOrSlowSpeakerInLineForAnHour() {
        for (ppm in listOf(50.0, -50.0, 200.0, -200.0)) {
            val sim = SimSpeaker(ppm)
            assertTrue("$ppm ppm", spreadMs(sim.run(3600)) < 2 * DriftMonitor.PRECISE_THRESHOLD_MS + 5)
            assertTrue(sim.mon!!.enabled)
            val word = if (ppm > 0) "fast" else "slow"
            assertTrue("$ppm ${sim.said.take(3)}", sim.said.isNotEmpty() && sim.said.all { word in it })
        }
    }

    @Test fun wholeSecondDlnaReportsStillKeepItInLine() {
        for (ppm in listOf(50.0, -200.0)) {
            val sim = SimSpeaker(ppm, wholeSeconds = true)
            // whole seconds only pin the latency down to a few tens of ms; uncorrected this
            // would spread 180 ms (50 ppm) or 720 ms (200 ppm) in the hour
            assertTrue("$ppm ppm", spreadMs(sim.run(3600)) < 80)
            assertTrue(sim.mon!!.enabled)
        }
    }

    @Test fun aPerfectClockIsLeftAlone() {
        val sim = SimSpeaker(0.0, wholeSeconds = true)
        sim.run(1800)
        assertEquals(emptyList<String>(), sim.said)
    }

    @Test fun aSpeakerThatDoesNotAdvanceIsNotCorrected() {
        val tl = filled(20)
        val said = mutableListOf<String>()
        val mon = DriftMonitor({ tl }, { said += it })
        for (i in 0 until 20) mon.sample(1.0 + i, 0.0)
        assertFalse(mon.enabled)
        assertEquals(0, tl.takeNudge())
        assertTrue("doesn't report" in said.last())
    }

    @Test fun jumpyPreciseReportsAreNeverActedOn() {
        val rng = Random(3)
        val tl = filled()
        val said = mutableListOf<String>()
        val mon = DriftMonitor({ tl }, { said += it })
        var t = 2.0
        repeat(60) { mon.sample(t, t - 1.0 - rng.nextDouble(0.0, 0.8)); t += 0.3 }   // a receiver whose position only updates now and then
        assertEquals(0, tl.takeNudge())
        assertFalse(mon.enabled)
        assertTrue("too jumpy" in said.last())
    }

    @Test fun anImplausibleJumpStopsMonitoring() {
        val tl = filled()
        val said = mutableListOf<String>()
        val mon = DriftMonitor({ tl }, { said += it })
        var t = 2.0
        repeat(12) { mon.sample(t, t - 1.0); t += 1 }   // steady 1.0 s latency: becomes the baseline
        repeat(5) { mon.sample(t, t - 6.0); t += 1 }    // then it claims to be 5 s behind
        assertFalse(mon.enabled)
        assertEquals(0, tl.takeNudge())
        assertTrue("jumped" in said.last())
    }
}

class MicTimingTest {
    private fun sent(n: Int = 16): List<Double> {
        var t = 10.0
        return List(n) { k -> t.also { t += Clicks.PATTERN[k % Clicks.PATTERN.size] } }
    }

    @Test fun hearsLoudAndQuietClicksToAMillisecondDespiteEchoes() {
        val truth = listOf(0.5, 1.37, 2.03, 3.11, 4.4)
        val rec = FakeRoom.recording(5.0, truth.mapIndexed { i, t -> t to if (i % 2 == 1) 1.0 else 0.05 })
        val found = MicTiming.onsets(rec, MicTiming.RATE, 100.0)
        assertEquals(found.toString(), truth.size, found.size)
        for ((f, t) in found.zip(truth)) assertEquals(t, f - 100.0, 0.0015)
    }

    @Test fun hissAloneIsNotAClick() {
        assertEquals(emptyList<Double>(), MicTiming.onsets(FakeRoom.recording(3.0, emptyList()), MicTiming.RATE, 0.0))
    }

    @Test fun patternMatchSurvivesMissedAndStrayClicks() {
        val rng = Random(2)
        val s = sent()
        val heard = s.filterIndexed { i, _ -> i % 3 != 0 }.map { it + 1.234 + rng.nextDouble(-0.002, 0.002) } +   // a third missed
            List(6) { s[0] + rng.nextDouble(0.0, 12.0) }                                                            // door slams
        assertEquals(1.234, MicTiming.match(s, heard.sorted())!!, 0.003)
    }

    @Test fun noMatchWithoutThePattern() {
        val s = sent()
        assertNull(MicTiming.match(s, List(12) { s[0] + 0.5 + it * 0.7 }))   // evenly spaced: not our pattern
    }

    @Test fun delaysMakeEverySpeakerAsLateAsTheSlowest() {
        assertEquals(mapOf("a" to 700, "b" to 0, "c" to 3000),
            MicTiming.delaysFor(mapOf("a" to 4.3, "b" to 5.0, "c" to 0.5)))   // capped at the longest delay there is
    }

    @Test fun theClickTrackClicksOnceASecondAndReportsWhen() {
        val chunks = mutableListOf<Boolean>()
        val clicks = mutableListOf<Double>()
        Clicks.run({ pcm, _ -> chunks += Pcm.isLoud(pcm) }, { chunks.size >= 60 }, Clicks.EVERY_SECOND) { clicks += it }
        assertEquals(listOf(0, 50), chunks.indices.filter { chunks[it] })
        assertEquals(1.0, clicks[1] - clicks[0], 0.02)   // paced in real time
        assertTrue(chunks.size == 60)
    }
}
