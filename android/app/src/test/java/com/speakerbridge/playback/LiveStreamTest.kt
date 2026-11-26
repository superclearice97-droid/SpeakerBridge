package com.speakerbridge.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class LiveStreamTest {
    private val loopback = InetAddress.getLoopbackAddress()

    /** Opens [url] like a speaker would; returns the socket, its data stream and the response head. */
    private fun open(url: String, method: String = "GET"): Triple<Socket, DataInputStream, String> {
        val u = URL(url)
        val s = Socket(u.host, u.port)
        s.soTimeout = 5000
        s.getOutputStream().write("$method ${u.path} HTTP/1.1\r\nHost: x\r\nRange: bytes=0-\r\nUser-Agent: TestTV/1.0\r\n\r\n".toByteArray())
        val input = DataInputStream(s.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
        }
        return Triple(s, input, head.toString())
    }

    /** Pushes chunk k filled with the 32-bit number k (from 1) every 20 ms until [alive] goes false. */
    private fun numbered(server: LiveStreamServer, alive: () -> Boolean) = Thread {
        var k = 1
        while (alive()) {
            server.push(ByteBuffer.allocate(Pcm.CHUNK).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(Pcm.CHUNK / 4) { putInt(k) } }.array())
            k++
            Thread.sleep(20)
        }
    }.apply { isDaemon = true; start() }

    private fun frames(input: DataInputStream, bytes: Int): List<Int> {
        val b = ByteArray(bytes).also { input.readFully(it) }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes / 4) { bb.getInt() }
    }

    /** The chunk numbers that arrived (whole or mostly), ignoring the few faded frames. */
    private fun chunkIds(values: List<Int>) = values.groupingBy { it }.eachCount().filter { it.key != 0 && it.value >= 100 }.keys.sorted()

    private fun longestSilence(values: List<Int>): Int {
        var best = 0
        var run = 0
        for (v in values) { run = if (v == 0) run + 1 else 0; best = maxOf(best, run) }
        return best
    }

    @Test fun wavHeaderDescribesEndless48kStereo() {
        val h = ByteBuffer.wrap(LiveStreamServer.wavHeader()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals(-1, h.getInt(4))              // RIFF size: unknown
        assertEquals(2, h.getShort(22).toInt())    // channels
        assertEquals(48000, h.getInt(24))          // sample rate
        assertEquals(48000 * 4, h.getInt(28))      // byte rate
        assertEquals(-1, h.getInt(40))             // data size: unknown
    }

    @Test fun wavSpeakerGetsHeaderThenLiveAudio() {
        LiveStreamServer(loopback).use { server ->
            var alive = true
            val (s, input, head) = open(server.url(0, Variant.WAV))
            s.use {
                assertTrue(head, head.startsWith("HTTP/1.0 200"))
                assertTrue("Content-Type: audio/wav\r\n" in head)
                assertFalse("Content-Length" in head)
                assertTrue("transferMode.dlna.org: Streaming" in head)
                assertArrayEquals(LiveStreamServer.wavHeader(), ByteArray(44).also { input.readFully(it) })
                numbered(server) { alive }
                val got = frames(input, Pcm.CHUNK * 3)
                assertEquals(3, chunkIds(got).size)   // audio pushed after it connected, in order
                assertEquals(chunkIds(got), chunkIds(got).sorted())
            }
            alive = false
        }
    }

    @Test fun l16SpeakerGetsRawBigEndianAudioWithTheDlnaProfile() {
        LiveStreamServer(loopback).use { server ->
            val (s, input, head) = open(server.url(0, Variant.L16))
            s.use {
                assertTrue("Content-Type: audio/L16;rate=48000;channels=2\r\n" in head)
                assertTrue("DLNA.ORG_PN=LPCM" in head)
                val chunk = ByteArray(Pcm.CHUNK) { (it % 251).toByte() }
                val t = Thread { repeat(50) { server.push(chunk); Thread.sleep(20) } }.apply { isDaemon = true; start() }
                val got = ByteArray(Pcm.CHUNK).also { input.readFully(it) }
                assertArrayEquals(LiveStreamServer.swapped(chunk), got)   // no file header, bytes swapped
                assertEquals(chunk[1], got[0])
                t.interrupt()
            }
        }
    }

    @Test fun sizedFormsDeclareALength() {
        LiveStreamServer(loopback).use { server ->
            for (v in listOf(Variant.WAV_SIZED, Variant.L16_SIZED)) {
                val (s, _, head) = open(server.url(0, v), "HEAD")
                s.close()
                assertTrue(head, "Content-Length: ${LiveStreamServer.SIZED_LENGTH}\r\n" in head)
            }
        }
    }

    @Test fun onlyTheExactSecretAddressIsServed() {
        LiveStreamServer(loopback).use { server ->
            val good = server.url(3, Variant.WAV)
            val token = URL(good).path.split('/')[1]
            assertEquals(16, token.length)
            for (bad in listOf(good.replace(token, "0".repeat(16)), good.replace("/live/", "/other/"), good.replace(token, ""),
                good.substringBefore(token) + "speakerbridge-phone.wav")) {
                val (s, _, head) = open(bad)
                s.close()
                assertTrue(bad, head.startsWith("HTTP/1.0 404"))
            }
            assertFalse(token in server.log.text())   // and the secret never reaches the report
            assertTrue(LiveStreamServer(loopback).use { URL(it.url(3, Variant.WAV)).path.split('/')[1] } != token)
        }
    }

    @Test fun requestsAreLoggedForTheReport() {
        LiveStreamServer(loopback, name = { "Lounge TV" }).use { server ->
            val (s, _, _) = open(server.url(0, Variant.L16), "HEAD")
            s.close()
            Thread.sleep(100)
            val log = server.log.text()
            assertTrue(log, "Lounge TV asked HEAD L16 HTTP/1.1" in log)
            assertTrue(log, "Range: bytes=0-" in log && "User-Agent: TestTV/1.0" in log)
        }
    }

    @Test fun statsShowWhetherASpeakerIsTakingAudio() {
        LiveStreamServer(loopback).use { server ->
            var alive = true
            assertFalse(server.stats(1).flowing())
            val (s, input, _) = open(server.url(1, Variant.L16))
            numbered(server) { alive }
            frames(input, Pcm.CHUNK * 5)
            assertTrue(server.stats(1).flowing())
            assertEquals(1, server.stats(1).connections)
            s.close()
            Thread.sleep(300)
            assertFalse(server.stats(1).flowing())   // it hung up
            assertFalse(server.stats(0).flowing())   // and another speaker's stats are its own
            alive = false
        }
    }

    @Test fun delayHoldsAudioBackFromTheStart() {
        val delays = mapOf(0 to 0, 1 to 300)
        LiveStreamServer(loopback, delayMs = { delays[it] ?: 0 }).use { server ->
            var alive = true
            numbered(server) { alive }
            for ((n, low, high) in listOf(Triple(0, 0.0, 0.2), Triple(1, 0.3, 0.6))) {
                val (s, input, _) = open(server.url(n, Variant.L16))
                val t0 = System.nanoTime()
                input.readFully(ByteArray(Pcm.CHUNK))
                val took = (System.nanoTime() - t0) / 1e9
                s.close()
                assertTrue("speaker $n waited $took s", took >= low && took < high)
            }
            alive = false
        }
    }

    @Test fun raisingTheDelayMidStreamInsertsSilence() {
        val delays = ConcurrentHashMap(mapOf(0 to 0))
        LiveStreamServer(loopback, delayMs = { delays[it] ?: 0 }).use { server ->
            var alive = true
            numbered(server) { alive }
            val (s, input, _) = open(server.url(0, Variant.WAV))
            input.readFully(ByteArray(44))
            val before = frames(input, Pcm.CHUNK * 5)
            delays[0] = 100
            val after = frames(input, Pcm.CHUNK * 12)
            s.close()
            alive = false
            assertTrue(longestSilence(before) <= 2 * 96)
            // exactly 100 ms, give or take the 2 ms fade either side rounding to zero
            assertTrue("silence ${longestSilence(after)}", kotlin.math.abs(longestSilence(after) - 4800) <= 2 * 96)
            val ids = chunkIds(before + after)
            assertEquals((ids.first()..ids.last()).toList(), ids)   // nothing skipped
        }
    }

    @Test fun loweringTheDelayMidStreamSkipsAudio() {
        val delays = ConcurrentHashMap(mapOf(0 to 200))
        LiveStreamServer(loopback, delayMs = { delays[it] ?: 0 }).use { server ->
            var alive = true
            numbered(server) { alive }
            val (s, input, _) = open(server.url(0, Variant.WAV))
            input.readFully(ByteArray(44))
            val before = frames(input, Pcm.CHUNK * 3)
            delays[0] = 100
            val after = frames(input, Pcm.CHUNK * 10)
            s.close()
            alive = false
            assertTrue(longestSilence(before + after) <= 2 * 96)   // no silence added
            val seen = chunkIds(before + after)
            assertEquals(5, (seen.first()..seen.last()).count { it !in seen })   // 100 ms = 5 chunks skipped
        }
    }

    @Test fun aNudgeInsertsSilenceWithoutHoldingAudioBack() {
        LiveStreamServer(loopback).use { server ->
            var alive = true
            numbered(server) { alive }
            val (s, input, _) = open(server.url(0, Variant.WAV))
            input.readFully(ByteArray(44))
            frames(input, Pcm.CHUNK * 3)
            val tl = server.timeline(0)!!
            tl.nudge(60)
            val after = frames(input, Pcm.CHUNK * 10)
            s.close()
            alive = false
            assertTrue(kotlin.math.abs(longestSilence(after) - 60 * 48) <= 2 * 96)
            assertEquals(60, tl.changesBefore(tl.sent))
        }
    }

    @Test fun closingEndsOpenStreams() {
        val server = LiveStreamServer(loopback)
        var alive = true
        numbered(server) { alive }
        val (s, input, _) = open(server.url(0, Variant.L16))
        frames(input, Pcm.CHUNK)
        server.close()
        val t0 = System.nanoTime()
        while (input.read(ByteArray(4096)) >= 0) assertTrue((System.nanoTime() - t0) / 1e9 < 3)   // cut off, even if it ignored Stop
        s.close()
        alive = false
    }
}
