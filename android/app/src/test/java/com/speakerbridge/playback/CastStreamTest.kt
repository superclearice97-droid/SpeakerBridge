package com.speakerbridge.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** A fake Cast receiver, like a TV, that loads a stream PAUSED and plays only when told to. */
private class FakeReceiver(private val playsOnPlay: Boolean) : Thread() {
    val events = CopyOnWriteArrayList<String>()
    val server: SSLServerSocket
    var playSessionId = -1

    init {
        isDaemon = true
        val dir = File.createTempFile("cast", "").apply { delete(); mkdirs() }
        val ks = File(dir, "k.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        ProcessBuilder(keytool, "-genkeypair", "-alias", "tv", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=fake-tv", "-validity", "1", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start().waitFor()
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    }

    override fun run() {
        runCatching {
            server.accept().use { c ->
                val input = DataInputStream(c.getInputStream())
                val out = DataOutputStream(c.getOutputStream())
                fun send(src: String, ns: String, payload: JSONObject) {
                    val b = CastPlayer.encode(src, "sender-0", ns, payload.toString())
                    out.writeInt(b.size); out.write(b); out.flush()
                }
                fun media(state: String) = JSONObject().put("type", "MEDIA_STATUS")
                    .put("status", JSONArray().put(JSONObject().put("playerState", state).put("mediaSessionId", 7)))
                while (true) {
                    val m = CastPlayer.decode(ByteArray(input.readInt()).also { input.readFully(it) })
                    val p = JSONObject(m.payload)
                    val type = p.optString("type")
                    events += type
                    when (type) {
                        "LAUNCH" -> send("receiver-0", CastPlayer.NS_RECEIVER, JSONObject().put("type", "RECEIVER_STATUS")
                            .put("status", JSONObject().put("applications", JSONArray().put(
                                JSONObject().put("appId", CastPlayer.APP_ID).put("transportId", "web-1").put("sessionId", "S1")))))
                        "LOAD" -> send("web-1", CastPlayer.NS_MEDIA, media("PAUSED"))   // ignores autoplay
                        "PLAY" -> {
                            playSessionId = p.optInt("mediaSessionId", -1)
                            if (playsOnPlay) send("web-1", CastPlayer.NS_MEDIA, media("PLAYING"))
                        }
                    }
                }
            }
        }
    }
}

class CastStreamTest {
    private val oldTimeout = CastPlayer.PLAY_TIMEOUT_MS

    @After fun restore() { CastPlayer.PLAY_TIMEOUT_MS = oldTimeout }

    private fun stream(tv: FakeReceiver, steps: MutableList<String>, stopAfterStreaming: Boolean = true): Throwable? {
        var error: Throwable? = null
        val t = Thread {
            error = runCatching {
                CastPlayer("127.0.0.1", tv.server.localPort).stream("http://127.0.0.1:1/x.wav", "audio/wav", "t",
                    { steps += it }) { stopAfterStreaming && "Streaming." in steps }
            }.exceptionOrNull()
        }
        t.start()
        t.join(20_000)
        assertFalse("stream() should have returned", t.isAlive)
        return error
    }

    @Test fun aTvThatLoadsPausedIsToldToPlayAndOnlyThenCountsAsStreaming() {
        val tv = FakeReceiver(playsOnPlay = true).also { it.start() }
        val steps = CopyOnWriteArrayList<String>()
        assertNull(stream(tv, steps))
        assertTrue("PLAY" in tv.events)
        assertEquals(7, tv.playSessionId)
        assertTrue(steps.indexOf("Waiting for the speaker to start playing…") < steps.indexOf("Streaming."))
        assertTrue("STOP" in tv.events)   // and it's closed properly at the end
    }

    @Test fun aTvThatNeverPlaysIsReportedNotCalledStreaming() {
        CastPlayer.PLAY_TIMEOUT_MS = 1500
        val tv = FakeReceiver(playsOnPlay = false).also { it.start() }
        val steps = CopyOnWriteArrayList<String>()
        val e = stream(tv, steps, stopAfterStreaming = false) ?: run { fail("expected an error"); return }
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
