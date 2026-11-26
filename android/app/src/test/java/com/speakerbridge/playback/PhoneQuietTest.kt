package com.speakerbridge.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates 20 ms capture chunks and a phone whose muting does, or doesn't, silence the capture. */
class PhoneQuietTest {
    private val calls = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private var muted = false

    private fun quiet(result: PhoneQuiet.Mute = PhoneQuiet.Mute.DONE) = PhoneQuiet(
        mute = { calls += "mute"; if (result == PhoneQuiet.Mute.DONE) muted = true; result },
        unmute = { calls += "unmute"; muted = false },
        say = { said += it },
    )

    /** Feeds `ms` of music. If muting silences capture on this phone, chunks captured more
     *  than `lagMs` after the mute come through silent (the pipeline still holds older audio). */
    private fun play(q: PhoneQuiet, fromMs: Long, ms: Long, muteSilencesCapture: Boolean, lagMs: Long = 300) {
        var mutedAt = Long.MAX_VALUE
        var t = fromMs
        while (t < fromMs + ms) {
            if (muted && mutedAt == Long.MAX_VALUE) mutedAt = t
            val silenced = muteSilencesCapture && muted && t - mutedAt > lagMs
            q.feed(loud = !silenced, nowMs = t)
            t += 20
        }
    }

    @Test fun mutesOnceMusicFlowsAndUnmutesWhenStreamingStops() {
        val q = quiet()
        play(q, 0, 3000, muteSilencesCapture = false)
        assertEquals(listOf("mute"), calls)
        assertTrue(muted)
        assertTrue(said.single().startsWith("Your phone is muted while it streams"))
        q.release()
        assertEquals(listOf("mute", "unmute"), calls)
        assertTrue(!muted)
    }

    @Test fun unmutesStraightAwayOnAPhoneWhereMutingSilencesWhatIsShared() {
        val q = quiet()
        play(q, 0, 3000, muteSilencesCapture = true)
        assertEquals(listOf("mute", "unmute"), calls)
        assertTrue(!muted)
        assertTrue(said.single().startsWith("This phone also silences what it shares"))
        q.release()
        assertEquals(listOf("mute", "unmute"), calls)   // nothing more to undo
    }

    @Test fun audioFromJustBeforeTheMuteIsNotTakenAsProof() {
        val q = quiet()
        play(q, 0, 3000, muteSilencesCapture = true, lagMs = 600)   // a slow capture pipeline
        assertEquals(listOf("mute", "unmute"), calls)
    }

    @Test fun waitsForMusicBeforeDoingAnything() {
        val q = quiet()
        var t = 0L
        while (t < 5000) { q.feed(loud = false, nowMs = t); t += 20 }
        assertEquals(emptyList<String>(), calls)
        q.release()
        assertEquals(emptyList<String>(), calls)   // never muted, so never unmuted
    }

    @Test fun aPhoneTheUserAlreadyMutedIsLeftAlone() {
        val q = quiet(PhoneQuiet.Mute.ALREADY)
        play(q, 0, 3000, muteSilencesCapture = false)
        q.release()
        assertEquals(listOf("mute"), calls)   // asked once, and no unmute of the user's own mute
        assertEquals(emptyList<String>(), said)
    }

    @Test fun aPhoneThatDoesNotAllowVolumeChangesIsExplained() {
        val q = quiet(PhoneQuiet.Mute.NOT_ALLOWED)
        play(q, 0, 3000, muteSilencesCapture = false)
        q.release()
        assertEquals(listOf("mute"), calls)
        assertTrue(said.single().contains("doesn't let apps change its volume"))
    }
}
