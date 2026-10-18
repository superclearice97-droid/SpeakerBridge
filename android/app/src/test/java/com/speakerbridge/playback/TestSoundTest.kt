package com.speakerbridge.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** JVM unit tests for the Cast wire format, the chime and the local file server. Run: ./gradlew test */
class TestSoundTest {

    @Test
    fun castMessageMatchesProtobufLayout() {
        val b = CastPlayer.encode("sender-0", "receiver-0", "ns", "{}")
        val expected = byteArrayOf(0x08, 0x00, 0x12, 8) + "sender-0".toByteArray() +
            byteArrayOf(0x1a, 10) + "receiver-0".toByteArray() +
            byteArrayOf(0x22, 2) + "ns".toByteArray() +
            byteArrayOf(0x28, 0x00, 0x32, 2) + "{}".toByteArray()
        assertArrayEquals(expected, b)
    }

    @Test
    fun castMessageRoundTripsLongPayload() {
        val payload = "{\"type\":\"LOAD\",\"x\":\"" + "a".repeat(300) + "\"}"
        val m = CastPlayer.decode(CastPlayer.encode("sender-0", "web-5", CastPlayer.NS_MEDIA, payload))
        assertEquals(CastPlayer.Msg("sender-0", "web-5", CastPlayer.NS_MEDIA, payload), m)
    }

    @Test
    fun chimeIsAValidWav() {
        val w = TestTone.wav()
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        val samples = TestTone.DURATION_MS * TestTone.SAMPLE_RATE / 1000
        assertEquals(44 + samples * 2, w.size.toLong())
        assertTrue(w.drop(44).any { it != 0.toByte() })
    }

    @Test
    fun serverServesFileAndRanges() {
        val body = ByteArray(1000) { it.toByte() }
        ToneServer(InetAddress.getLoopbackAddress(), body, "audio/wav").use { s ->
            val full = URL(s.url).openConnection() as HttpURLConnection
            assertEquals(200, full.responseCode)
            assertEquals("audio/wav", full.contentType)
            assertArrayEquals(body, full.inputStream.readBytes())

            val part = URL(s.url).openConnection() as HttpURLConnection
            part.setRequestProperty("Range", "bytes=10-19")
            assertEquals(206, part.responseCode)
            assertArrayEquals(body.copyOfRange(10, 20), part.inputStream.readBytes())

            val missing = URL(s.url + "x").openConnection() as HttpURLConnection
            assertEquals(404, missing.responseCode)
        }
    }
}
