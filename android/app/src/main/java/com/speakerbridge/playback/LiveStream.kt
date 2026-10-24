package com.speakerbridge.playback

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serves live audio as one endless WAV stream on the phone's Wi-Fi address, to every speaker
 * that connects. LAN only; it stops as soon as it is closed.
 */
class LiveStreamServer(bindAddr: InetAddress) : Closeable {
    private val server = ServerSocket(0, 8, bindAddr)
    private val listeners = CopyOnWriteArrayList<Listener>()
    val url = "http://${bindAddr.hostAddress}:${server.localPort}/$PATH"

    /** One connected speaker; holds about 5 s of audio before it counts as fallen behind. */
    private class Listener(val socket: Socket) {
        val queue = ArrayBlockingQueue<ByteArray>(250)
    }

    init {
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: IOException) { break }
                Thread { runCatching { c.use { serve(it) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Sends one chunk of PCM ([RATE] Hz, [CHANNELS] channels, 16-bit) to every speaker. */
    fun push(pcm: ByteArray) {
        for (l in listeners) {
            if (!l.queue.offer(pcm)) drop(l)   // a speaker that fell far behind gets cut off
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 5000
        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        while (!reader.readLine().isNullOrEmpty()) { /* skip headers */ }
        val parts = request.split(' ')
        val out = c.getOutputStream()
        if (parts.size < 2 || parts[1].substringBefore('?') != "/$PATH") {
            out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        // HTTP/1.0 with no length: the stream runs until the connection closes.
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: $CONTENT_TYPE\r\nCache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
        if (parts[0] == "HEAD") return
        out.write(wavHeader())
        out.flush()
        val l = Listener(c)
        listeners.add(l)
        try {
            while (true) {
                val b = l.queue.take()
                if (b === END) return
                out.write(b)
            }
        } finally {
            listeners.remove(l)
        }
    }

    private fun drop(l: Listener) {
        listeners.remove(l)
        l.queue.clear()
        l.queue.offer(END)
        runCatching { l.socket.close() }   // unblocks a write to a speaker that stopped reading
    }

    override fun close() {
        server.close()
        listeners.forEach(::drop)
    }

    companion object {
        const val RATE = 48000
        const val CHANNELS = 2
        const val CONTENT_TYPE = "audio/wav"
        private const val PATH = "speakerbridge-phone.wav"
        private val END = ByteArray(0)

        /** A WAV header for a stream of unknown length (both sizes at their maximum). */
        fun wavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort())
            .putInt(RATE).putInt(RATE * CHANNELS * 2).putShort((CHANNELS * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(-1)
            .array()
    }
}
