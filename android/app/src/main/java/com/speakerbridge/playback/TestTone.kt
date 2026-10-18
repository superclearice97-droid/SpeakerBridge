package com.speakerbridge.playback

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** A short original chime (four rising bell notes), generated in code as a WAV file. */
object TestTone {
    const val SAMPLE_RATE = 44100
    const val CONTENT_TYPE = "audio/wav"
    private const val LEAD_IN_S = 0.3        // a little silence so the speaker doesn't clip the first note
    private const val NOTE_GAP_S = 0.25
    private const val NOTE_LEN_S = 1.8
    private val NOTES = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)   // C5 E5 G5 C6

    val DURATION_MS: Long = ((LEAD_IN_S + NOTE_GAP_S * (NOTES.size - 1) + NOTE_LEN_S) * 1000).toLong()

    fun wav(): ByteArray {
        val n = (DURATION_MS * SAMPLE_RATE / 1000).toInt()
        val mix = DoubleArray(n)
        NOTES.forEachIndexed { k, f ->
            val start = ((LEAD_IN_S + k * NOTE_GAP_S) * SAMPLE_RATE).toInt()
            val len = min((NOTE_LEN_S * SAMPLE_RATE).toInt(), n - start)
            for (i in 0 until len) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = min(1.0, t / 0.005)            // 5 ms fade-in avoids a click
                val env = attack * exp(-t / 0.45)
                mix[start + i] += env * (sin(2 * PI * f * t) + 0.25 * sin(4 * PI * f * t))
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in mix) data.putShort((v / peak * 0.6 * Short.MAX_VALUE).toInt().toShort())

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)   // PCM, mono
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(n * 2)
        return header.array() + data.array()
    }
}

/**
 * Serves one file over HTTP on the phone's Wi-Fi address so the speaker can fetch it.
 * LAN only; it stops as soon as it is closed.
 */
class ToneServer(bindAddr: InetAddress, private val body: ByteArray, private val contentType: String) : Closeable {
    private val server = ServerSocket(0, 8, bindAddr)
    val url = "http://${bindAddr.hostAddress}:${server.localPort}/$PATH"

    init {
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: IOException) { break }
                Thread { runCatching { serve(c) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(c: Socket) { c.use { respond(c) } }

    private fun respond(c: Socket) {
        c.soTimeout = 5000
        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val parts = request.split(' ')
        val out = c.getOutputStream()
        if (parts.size < 2 || parts[1].substringBefore('?') != "/$PATH") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        var start = 0
        var end = body.size - 1
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val (a, b) = m.destructured
            if (a.isNotEmpty()) start = a.toInt().coerceIn(0, body.size - 1)
            if (b.isNotEmpty()) end = b.toInt().coerceIn(start, body.size - 1)
            if (a.isEmpty() && b.isNotEmpty()) { start = (body.size - b.toInt()).coerceAtLeast(0); end = body.size - 1 }
        }
        val status = if (m != null) "206 Partial Content" else "200 OK"
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (m != null) append("Content-Range: bytes $start-$end/${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (parts[0] != "HEAD") out.write(body, start, end - start + 1)
        out.flush()
    }

    override fun close() = server.close()

    private companion object { const val PATH = "speakerbridge-test.wav" }
}
