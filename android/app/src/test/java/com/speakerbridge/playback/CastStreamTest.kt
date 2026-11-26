package com.speakerbridge.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CastStreamTest {
    private val oldTimeout = CastPlayer.PLAY_TIMEOUT_MS

    @After fun restore() { CastPlayer.PLAY_TIMEOUT_MS = oldTimeout }

    /** A TV that loads a stream paused and plays only when told to, or never. */
    private fun pausedTv(playsOnPlay: Boolean) = FakeCast(loadState = "PAUSED", playsOnPlay = playsOnPlay).also { it.start() }

    private fun stream(tv: FakeCast, steps: MutableList<String>, stopAfterStreaming: Boolean = true): Throwable? {
        var error: Throwable? = null
        val t = Thread {
            error = runCatching {
                CastPlayer("127.0.0.1", tv.server.localPort).stream("http://127.0.0.1:1/x.wav", "audio/wav", "t",
                    { steps += it }, stopped = { stopAfterStreaming && "Streaming." in steps })
            }.exceptionOrNull()
        }
        t.start()
        t.join(20_000)
        assertFalse("stream() should have returned", t.isAlive)
        return error
    }

    @Test fun aTvThatLoadsPausedIsToldToPlayAndOnlyThenCountsAsStreaming() {
        val tv = pausedTv(playsOnPlay = true)
        val steps = CopyOnWriteArrayList<String>()
        assertNull(stream(tv, steps))
        assertTrue("PLAY" in tv.events)
        assertEquals(7, tv.playSessionId)
        assertTrue(steps.indexOf("Waiting for the speaker to start playing…") < steps.indexOf("Streaming."))
        assertTrue("STOP" in tv.events)   // and it's closed properly at the end
    }

    @Test fun aTvThatNeverPlaysIsReportedNotCalledStreaming() {
        CastPlayer.PLAY_TIMEOUT_MS = 1500
        val tv = pausedTv(playsOnPlay = false)
        val steps = CopyOnWriteArrayList<String>()
        val e = stream(tv, steps, stopAfterStreaming = false) ?: run { fail("expected an error"); return }
        assertTrue(e is NeverPlayed)   // so another route can be tried
        assertTrue(e.message, "won't play it" in e.message.orEmpty())
        assertTrue("it stayed paused" in e.message.orEmpty())
        assertFalse("Streaming." in steps)
        assertTrue("STOP" in tv.events)   // its media player is closed, so the TV doesn't sit on a paused screen
    }
}

class SilenceWatchTest {
    private val second = ByteArray(48000 * 2 * 2)   // 1 s of 48 kHz stereo 16-bit silence

    private fun loud() = ByteArray(3840).also { b ->
        for (i in b.indices step 2) { b[i] = 0x00; b[i + 1] = 0x10 }   // 4096
    }

    @Test fun threeSecondsOfSilenceIsReportedAndSoundClearsIt() {
        val w = SilenceWatch()
        assertNull(w.feed(second, second.size))   // not long enough to say yet
        assertNull(w.feed(second, second.size))
        assertEquals(false, w.feed(second, second.size))
        assertNull(w.feed(second, second.size))   // no change
        assertEquals(true, w.feed(loud(), 3840))
    }

    @Test fun aShortGapBetweenSongsDoesNotCount() {
        val w = SilenceWatch()
        assertEquals(true, w.feed(loud(), 3840))
        assertNull(w.feed(second, second.size))
        assertNull(w.feed(second, second.size))
        assertNull(w.feed(loud(), 3840))
    }

    @Test fun quietNegativeSamplesAreNotMistakenForSound() {
        val w = SilenceWatch()
        val hiss = ByteArray(second.size).also { b -> for (i in b.indices step 2) { b[i] = 0xF0.toByte(); b[i + 1] = 0xFF.toByte() } }   // -16
        assertEquals(listOf(null, null, false), List(3) { w.feed(hiss, hiss.size) })
    }
}
