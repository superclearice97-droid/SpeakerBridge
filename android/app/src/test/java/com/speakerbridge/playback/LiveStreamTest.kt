package com.speakerbridge.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveStreamTest {

    @Test fun wavHeaderDescribesEndless48kStereo() {
        val h = ByteBuffer.wrap(LiveStreamServer.wavHeader()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals(-1, h.getInt(4))              // RIFF size: unknown
        assertEquals(2, h.getShort(22).toInt())    // channels
        assertEquals(48000, h.getInt(24))          // sample rate
        assertEquals(48000 * 4, h.getInt(28))      // byte rate
        assertEquals(-1, h.getInt(40))             // data size: unknown
    }

    @Test fun speakerGetsHeaderThenLiveAudio() {
        LiveStreamServer(InetAddress.getLoopbackAddress()).use { server ->
            val url = URL(server.url)
            Socket(url.host, url.port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write("GET ${url.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
                assertTrue(head.startsWith("HTTP/1.0 200"))
                assertTrue("Content-Type: audio/wav" in head)
                val wav = ByteArray(44).also { input.readFully(it) }
                assertArrayEquals(LiveStreamServer.wavHeader(), wav)

                // Audio pushed after the speaker connected reaches it, in order.
                val chunk = ByteArray(3840) { (it % 251).toByte() }
                val deadline = System.currentTimeMillis() + 5000
                Thread {
                    while (System.currentTimeMillis() < deadline && !s.isClosed) { server.push(chunk); Thread.sleep(20) }
                }.apply { isDaemon = true }.start()
                val got = ByteArray(chunk.size).also { input.readFully(it) }
                assertArrayEquals(chunk, got)
            }
        }
    }

    @Test fun unknownPathIs404() {
        LiveStreamServer(InetAddress.getLoopbackAddress()).use { server ->
            val url = URL(server.url)
            Socket(url.host, url.port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write("GET /other HTTP/1.1\r\n\r\n".toByteArray())
                val line = s.getInputStream().bufferedReader().readLine()
                assertTrue(line.startsWith("HTTP/1.0 404"))
            }
        }
    }
}
