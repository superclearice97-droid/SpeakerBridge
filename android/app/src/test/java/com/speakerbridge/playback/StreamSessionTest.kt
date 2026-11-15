package com.speakerbridge.playback

import com.speakerbridge.model.Device
import com.speakerbridge.model.ProtocolHit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/** Whole sessions against fake speakers: picky TVs, mixed brands, drift, the microphone. */
class StreamSessionTest {
    @get:Rule val noHanging: Timeout = Timeout.seconds(90)

    private val lines = CopyOnWriteArrayList<String>()
    private val open = mutableListOf<AutoCloseable>()

    private fun waitFor(seconds: Double = 15.0, what: () -> Boolean) {
        val end = now() + seconds
        while (!what()) { check(now() < end) { "waited too long; so far: $lines" }; Thread.sleep(50) }
    }

    @Before fun quicker() {
        StreamSession.CONFIRM_TIMEOUT_S = 5.0
        StreamSession.IDLE_GRACE_S = 0.8
    }

    @After fun restore() {
        open.forEach { runCatching { it.close() } }
        StreamSession.CONFIRM_TIMEOUT_S = 15.0
        StreamSession.IDLE_GRACE_S = 5.0
        StreamSession.DLNA_POLL_MS = 500L..1500L
        CastPlayer.POLL_MS = 2000
        CastPlayer.PLAY_TIMEOUT_MS = 15_000
        DriftMonitor.WARMUP_S = 8.0
        DriftMonitor.MAX_PPM = 1000.0
        DriftMonitor.PRECISE_MAX_SPREAD_S = 0.03
        MicCalibration.LISTEN_S = 0.0
        MicTiming.MAX_LATENCY_S = 5.0
        Clicks.PATTERN = doubleArrayOf(0.56, 0.86, 0.66, 1.06, 0.76, 0.96, 0.60, 0.90)
    }

    private fun renderer(player: FakePlayer = FakePlayer(), sinks: String = "", wholeSeconds: Boolean = true,
                         reportsPosition: Boolean = true, rejects: (String) -> Boolean = { false },
                         plays: (String, Boolean) -> Boolean = { _, _ -> true }) =
        FakeRenderer(player, sinks, plays, rejects, wholeSeconds, reportsPosition).also { open += it }

    private fun cast(player: FakePlayer = FakePlayer(), refusesLaunch: Boolean = false) =
        FakeCast(player, refusesLaunch = refusesLaunch).also { it.start() }

    /** Streams sync clicks to [devices] for [seconds]; returns the session's report. */
    private fun clicks(devices: List<Device>, prefs: SpeakerPrefs = MapPrefs(), seconds: Double = 3.0,
                       during: (ClickRun) -> Unit = {}): String {
        val run = ClickRun(devices, prefs, Clicks.EVERY_SECOND, { _, line -> lines += line })
        run.start()
        val end = now() + seconds
        during(run)
        while (now() < end && !run.ended) Thread.sleep(50)
        run.close()
        return run.session.report()
    }

    private fun said(text: String) = lines.any { text in it }

    // ------------------------------------------------------------ picky speakers

    @Test fun aTvThatOnlyPlaysRawL16GetsItFirstTime() {
        // What LG webOS TVs are reported to need: uncompressed audio only as audio/L16.
        val tv = renderer { mime, _ -> mime.startsWith("audio/L16") }
        val prefs = MapPrefs()
        clicks(listOf(tv.device("Lounge TV")), prefs)
        assertEquals(listOf("audio/L16;rate=48000;channels=2|live"), tv.offered.toList())
        assertTrue(tv.player.contentType.startsWith("audio/L16"))
        assertTrue(tv.player.heard.size >= 2)                      // and it really played the clicks
        assertTrue(lines.toString(), said("Lounge TV: Streaming over DLNA."))
        assertEquals("dlna:L16", prefs.routes["dlna:Lounge TV"])
        assertEquals("Stop", tv.actions.last())
    }

    @Test fun aSpeakerThatOnlyPlaysWavIsFoundByTryingAndRememberedNextTime() {
        val prefs = MapPrefs()
        val first = renderer { mime, _ -> mime == "audio/wav" }
        clicks(listOf(first.device("Den")), prefs, seconds = 6.0)
        assertEquals(listOf("audio/L16;rate=48000;channels=2|live", "audio/wav|live"), first.offered.toList())
        assertTrue(lines.toString(), said("Den: Streaming over DLNA."))
        assertTrue(first.player.heard.isNotEmpty())
        assertEquals("dlna:WAV", prefs.routes["dlna:Den"])

        val again = renderer { mime, _ -> mime == "audio/wav" }
        clicks(listOf(again.device("Den")), prefs)
        assertEquals(listOf("audio/wav|live"), again.offered.toList())   // straight to what worked
    }

    @Test fun aSpeakerThatNeedsALengthGetsOne() {
        val picky = renderer { _, sized -> sized }
        clicks(listOf(picky.device()), seconds = 8.0)
        assertEquals("audio/L16;rate=48000;channels=2|sized", picky.offered.last())
        assertEquals(LiveStreamServer.SIZED_LENGTH.toString(), picky.player.contentLength)
        assertTrue(said("Streaming over DLNA."))
    }

    @Test fun aTypeTheSpeakerRefusesOutrightMovesStraightToTheNext() {
        val t0 = now()
        val strict = renderer(rejects = { it.startsWith("audio/L16") })
        clicks(listOf(strict.device()), seconds = 3.0)
        assertEquals("audio/wav|live", strict.offered.last())
        assertTrue(said("Streaming over DLNA."))
        assertTrue(strict.player.heard.isNotEmpty())
        assertTrue(now() - t0 < 6)   // no waiting around for a type it said no to
    }

    @Test fun whatTheSpeakerSaysItPlaysDecidesTheOrder() {
        val wavOnly = "http-get:*:audio/mpeg:*,http-get:*:audio/x-wav:*,http-get:*:video/mp4:*"
        assertEquals(Variant.WAV, DlnaRenderer.order(wavOnly.split(',')).first())
        assertEquals(Variant.L16, DlnaRenderer.order("http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM".split(',')).first())
        assertEquals(Variant.L16, DlnaRenderer.order(emptyList()).first())
        val tv = renderer(sinks = wavOnly)
        val report = clicks(listOf(tv.device()))
        assertEquals("audio/wav|live", tv.offered.first())
        assertTrue(report, "says it plays: audio/mpeg, audio/x-wav" in report)
    }

    @Test fun aSpeakerThatPlaysNothingIsReportedHonestly() {
        val dud = renderer { _, _ -> false }
        clicks(listOf(dud.device("Old Receiver")), seconds = 9.0)
        assertEquals(4, dud.offered.size)
        assertFalse(lines.toString(), said("Streaming"))
        val last = lines.last { "Old Receiver" in it }
        assertTrue(last, "couldn't stream: it took the stream but didn't play it" in last)
        assertTrue(last, "tried DLNA, L16; DLNA, WAV; DLNA, L16 with a length; DLNA, WAV with a length" in last)
        assertEquals("Stop", dud.actions.last())   // and its player isn't left sitting open
    }

    @Test fun whenCastFailsTheSameSpeakerIsReachedOverDlna() {
        val dlna = renderer()
        val c = cast(refusesLaunch = true)
        val both = dlna.device("Soundbar").also { it.add(ProtocolHit("googlecast", "test", port = c.server.localPort)) }
        val report = clicks(listOf(both))
        assertTrue("LAUNCH" in c.events)
        assertTrue(lines.toString(), said("Soundbar: Streaming over DLNA."))
        assertTrue(dlna.player.heard.isNotEmpty())
        assertTrue(report, "Google Cast didn't play" in report)
    }

    @Test fun aRoutePickedByHandIsTheOnlyOneTried() {
        val tv = renderer()
        val prefs = MapPrefs(forced = mutableMapOf("dlna:Fake TV" to "dlna:WAV_SIZED"))
        clicks(listOf(tv.device()), prefs)
        assertEquals(listOf("audio/wav|sized"), tv.offered.toList())
        assertEquals("audio/wav", tv.player.contentType)
    }

    @Test fun aSpeakerStoppedFromItsOwnRemoteIsNoticed() {
        val tv = renderer()
        clicks(listOf(tv.device("Lounge TV")), seconds = 12.0) { run ->
            waitFor { said("Streaming over DLNA.") }
            Thread.sleep(1000)
            tv.player.stop()
            tv.state = "STOPPED"
            waitFor { run.ended }
        }
        assertTrue(lines.toString(), said("Lounge TV: couldn't stream: the speaker stopped the stream"))
    }

    // ------------------------------------------------------------ together, in step

    @Test fun mixedSpeakersWithTheirDelaysPlayTogether() {
        val nest = FakePlayer(bufferS = 0.3)
        val tv = FakePlayer(bufferS = 1.0)
        val c = cast(nest)
        val r = renderer(tv)
        val prefs = MapPrefs(delays = mutableMapOf("cast:Fake Nest" to 700))
        val report = clicks(listOf(c.device(), r.device()), prefs, seconds = 5.0)
        assertTrue(lines.toString(), said("Fake Nest: Streaming.") && said("Fake TV: Streaming over DLNA."))
        val pairs = nest.heard.flatMap { a -> tv.heard.filter { kotlin.math.abs(a - it) < 0.5 }.map { a to it } }
        assertTrue("$pairs ${nest.heard} ${tv.heard}", pairs.size >= 2)
        for ((a, b) in pairs) assertTrue("${a - b}", kotlin.math.abs(a - b) < 0.03)   // undelayed they'd be 700 ms apart
        assertTrue("STOP" in c.events)
        assertTrue(report, "1. Fake Nest" in report && "delay 700 ms" in report && "2. Fake TV" in report)
        assertTrue(report, "Fake TV asked GET L16" in report && "Fake Nest asked GET WAV" in report)
        assertTrue(report, "Fake TV: says it is PLAYING" in report)
    }

    @Test fun aDelayChangedWhilePlayingMovesThatSpeaker() {
        val p = FakePlayer()
        val c = cast(p)
        clicks(listOf(c.device()), seconds = 6.0) { run ->
            Thread.sleep(2600)
            run.session.setDelay("cast:Fake Nest", 250)
        }
        val gaps = p.heard.zipWithNext { a, b -> b - a }
        assertTrue(gaps.toString(), gaps.any { kotlin.math.abs(it - 1.25) < 0.02 })   // one click arrives 250 ms late
        assertTrue(gaps.toString(), gaps.filter { kotlin.math.abs(it - 1.0) < 0.02 }.size >= gaps.size - 1)
    }

    private fun fasterDrift() {
        // A 2% clock error instead of 50 ppm, so the test takes seconds, not hours.
        CastPlayer.POLL_MS = 100
        StreamSession.DLNA_POLL_MS = 30L..80L
        DriftMonitor.WARMUP_S = 1.0
        DriftMonitor.MAX_PPM = 50000.0
        DriftMonitor.PRECISE_MAX_SPREAD_S = 0.1
    }

    private fun interval(p: FakePlayer): Double {
        val heard = p.heard.filter { it - p.tStart > 3 }   // after the starting point is taken
        return (heard.last() - heard.first()) / (heard.size - 1)
    }

    @Test fun driftIsCorrectedOnALiveCastSpeaker() {
        fasterDrift()
        val fast = FakePlayer(ppm = 20000.0)   // plays 2% fast: 20 ms a second
        val c = cast(fast)
        clicks(listOf(c.device()), seconds = 9.0)
        assertTrue("GET_STATUS" in c.events)
        assertEquals(1.0, interval(fast), 0.006)   // uncorrected it would be 0.980
        assertTrue(lines.toString(), lines.any { "Nudged +" in it && "fast" in it })
    }

    @Test fun driftIsCorrectedOnALiveDlnaSpeaker() {
        fasterDrift()
        val slow = FakePlayer(ppm = -20000.0)
        val r = renderer(slow, wholeSeconds = false)
        clicks(listOf(r.device()), seconds = 9.0)
        assertEquals(1.0, interval(slow), 0.006)   // uncorrected it would be 1.020
        assertTrue(lines.toString(), lines.any { "Nudged -" in it && "slow" in it })
    }

    @Test fun aDlnaSpeakerThatWontSayWhereItIsIsLeftAlone() {
        StreamSession.DLNA_POLL_MS = 50L..100L
        DriftMonitor.WARMUP_S = 0.5
        val r = renderer(reportsPosition = false)
        clicks(listOf(r.device()), seconds = 4.0)
        assertTrue(lines.toString(), said("it doesn't report how far it has played"))
        assertTrue(said("Streaming over DLNA."))
        assertTrue(r.actions.count { it == "GetPositionInfo" } < 30)   // it stops asking
    }

    // ------------------------------------------------------------ the microphone

    @Test fun microphoneCalibrationLinesMixedSpeakersUp() {
        Clicks.PATTERN = doubleArrayOf(0.30, 0.50, 0.40, 0.62, 0.36, 0.54)
        MicTiming.MAX_LATENCY_S = 1.5
        MicCalibration.LISTEN_S = 4.5
        val nest = FakePlayer(bufferS = 0.3)
        val tv = FakePlayer(bufferS = 1.0)
        val c = cast(nest)
        val r = renderer(tv)
        val room = FakeRoom(listOf(nest to 0.8, tv to 0.1))   // one loud and near, one quiet and far
        val prefs = MapPrefs(delays = mutableMapOf("cast:Fake Nest" to 450))   // an old delay must not skew the measurement
        val (delays, problems) = MicCalibration.calibrate(listOf(c.device(), r.device()), prefs, { room }, { false }, { lines += it })
        assertEquals(lines.toString(), emptyList<String>(), problems)
        assertEquals(0, delays["dlna:Fake TV"])                       // the slowest stays at 0
        assertEquals(700.0, delays.getValue("cast:Fake Nest").toDouble(), 25.0)   // 1.0 s vs 0.3 s of buffering
        assertEquals(2, room.cleared)                                 // the recording is cleared after each speaker
        assertTrue(room.closed)
        assertTrue(said("Fake Nest: heard") && said("Fake TV: heard"))
    }

    @Test fun aSpeakerTheMicrophoneCannotHearIsReportedAndTheRestStillMeasured() {
        Clicks.PATTERN = doubleArrayOf(0.30, 0.50, 0.40, 0.62, 0.36, 0.54)
        MicTiming.MAX_LATENCY_S = 1.5
        MicCalibration.LISTEN_S = 4.5
        val nest = FakePlayer()
        val tv = FakePlayer()
        val c = cast(nest)
        val r = renderer(tv)
        val room = FakeRoom(listOf(nest to 0.5, tv to 0.0))   // the TV is muted
        val (delays, problems) = MicCalibration.calibrate(listOf(c.device(), r.device()), MapPrefs(), { room }, { false }, {})
        assertEquals(setOf("cast:Fake Nest"), delays.keys)
        assertTrue(problems.toString(), problems.single().startsWith("Fake TV: the microphone couldn't pick out its clicks"))
    }

    @Test fun cancellingStopsListeningStraightAway() {
        val c = cast()
        val room = FakeRoom(emptyList())
        val t0 = now()
        var cancelled = false
        Thread { Thread.sleep(1500); cancelled = true }.start()
        val e = runCatching { MicCalibration.calibrate(listOf(c.device()), MapPrefs(), { room }, { cancelled }, {}) }.exceptionOrNull()
        assertTrue(e is MicError && e.message == "stopped")
        assertTrue(now() - t0 < 5)
        assertTrue(room.closed)
    }
}
