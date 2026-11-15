package com.speakerbridge.playback

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A short running record of what happened, with times, for the "Copy report" button. */
class DiagLog {
    private val t0 = System.nanoTime()
    private val lines = ArrayDeque<String>()

    @Synchronized fun add(line: String) {
        lines.addLast("%7.2f  %s".format((System.nanoTime() - t0) / 1e9, line))
        if (lines.size > 600) lines.removeFirst()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}

/**
 * The forms the same live audio is offered in. Speakers differ in what they'll play: many
 * DLNA TVs want raw "L16" PCM rather than a WAV file, and a few want to be told a length.
 */
enum class Variant(val file: String, val contentType: String, val sized: Boolean, val label: String) {
    L16("live/stream.l16", "audio/L16;rate=48000;channels=2", false, "L16"),
    WAV("live/stream.wav", "audio/wav", false, "WAV"),
    L16_SIZED("sized/stream.l16", "audio/L16;rate=48000;channels=2", true, "L16 with a length"),
    WAV_SIZED("sized/stream.wav", "audio/wav", true, "WAV with a length");

    val isL16: Boolean get() = this == L16 || this == L16_SIZED

    /** The DLNA description of this stream (4th field of protocolInfo, contentFeatures header). */
    val dlnaFeatures: String
        get() = (if (isL16) "DLNA.ORG_PN=LPCM;" else "") +
            "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
}

/** What one speaker has done with its stream so far. */
class SpeakerStats {
    @Volatile var open = 0            // connections open now
    @Volatile var connections = 0     // connections ever
    @Volatile var bytes = 0L
    @Volatile var lastWriteNanos = 0L

    /** True while the speaker is connected and has taken audio within the last 1.5 s. */
    fun flowing(nowNanos: Long = System.nanoTime()): Boolean =
        open > 0 && bytes > 0 && nowNanos - lastWriteNanos < 1_500_000_000L
}

/**
 * Serves live audio on the phone's Wi-Fi address as an endless stream, to every speaker that
 * connects. LAN only, at an address that can't be guessed; it stops as soon as it is closed.
 *
 * Speaker n fetches its own URL and hears the audio delayMs(n) later, so a speaker that plays
 * early can wait for slower ones. A speaker starts playing once it has buffered a fixed amount
 * and then keeps its own clock, so only holding audio back before it starts adds delay; a
 * change mid-stream is made by inserting silence or skipping audio instead. Each speaker's
 * [Timeline] also takes small drift corrections.
 */
class LiveStreamServer(
    bindAddr: InetAddress,
    val log: DiagLog = DiagLog(),
    private val name: (Int) -> String = { "speaker $it" },
    private val delayMs: (Int) -> Int = { 0 },
) : Closeable {
    private val server = ServerSocket(0, 8, bindAddr)
    private val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val base = "http://${bindAddr.hostAddress}:${server.localPort}/$token"
    private val route = Regex("^/$token/(\\d{1,3})/(live/stream\\.l16|live/stream\\.wav|sized/stream\\.l16|sized/stream\\.wav)$")
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val timelines = ConcurrentHashMap<Int, Timeline>()
    private val stats = ConcurrentHashMap<Int, SpeakerStats>()

    private class Chunk(val capturedNanos: Long, val pcm: ByteArray)

    /** One connected speaker; holds about 5 s of audio beyond the longest delay before it counts as fallen behind. */
    private class Listener(val socket: Socket) {
        val queue = ArrayBlockingQueue<Chunk>(250 + Pcm.MAX_DELAY_MS / 20)
    }

    init {
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: IOException) { break }
                Thread { runCatching { c.use { serve(it) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** The stream URL for speaker [n] in the given form. */
    fun url(n: Int, variant: Variant): String = "$base/$n/${variant.file}"

    fun timeline(n: Int): Timeline? = timelines[n]

    fun stats(n: Int): SpeakerStats = stats.getOrPut(n) { SpeakerStats() }

    /** Sends one chunk of PCM (see [Pcm]) to every speaker. */
    fun push(pcm: ByteArray, capturedNanos: Long = System.nanoTime()) {
        val chunk = Chunk(capturedNanos, pcm)
        for (l in listeners) {
            if (!l.queue.offer(chunk)) drop(l)   // a speaker that fell far behind gets cut off
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 5000
        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        val wanted = listOf("range", "user-agent", "getcontentfeatures.dlna.org", "transfermode.dlna.org", "icy-metadata")
        val headers = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (wanted.any { line.startsWith("$it:", ignoreCase = true) }) headers += line.trim()
        }
        val parts = request.split(' ')
        val out = c.getOutputStream()
        val m = parts.getOrNull(1)?.substringBefore('?')?.let(route::matchEntire)
        if (m == null) {
            // Never log the path itself: a wrong one may be a guess at the token.
            log.add("stream: refused a request from ${c.inetAddress.hostAddress} (wrong address)")
            out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val n = m.groupValues[1].toInt()
        val variant = Variant.entries.first { it.file == m.groupValues[2] }
        log.add("stream: ${name(n)} asked ${parts[0]} ${variant.label} ${parts.getOrElse(2) { "" }} ${headers.joinToString(" | ")}".trimEnd())
        // HTTP/1.0 with no length: the stream runs until the connection closes.
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: ${variant.contentType}\r\n" +
            (if (variant.sized) "Content-Length: $SIZED_LENGTH\r\n" else "") +
            "Cache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n" +
            "transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: ${variant.dlnaFeatures}\r\n\r\n").toByteArray())
        if (parts[0] == "HEAD") return

        val st = stats(n)
        val tl = Timeline()
        val l = Listener(c)
        fun delay() = runCatching { delayMs(n) }.getOrDefault(0).coerceIn(0, Pcm.MAX_DELAY_MS)
        fun send(b: ByteArray) {
            out.write(if (variant.isL16) swapped(b) else b)
            st.bytes += b.size
            st.lastWriteNanos = System.nanoTime()
        }
        val started = System.nanoTime()
        timelines[n] = tl
        st.open++
        st.connections++
        listeners.add(l)
        try {
            if (!variant.isL16) out.write(wavHeader())
            out.flush()
            var held = delay()
            var skip = 0
            var fadeIn = false
            while (true) {
                val chunk = l.queue.take()
                if (chunk === END) return
                var b = chunk.pcm
                var captured = chunk.capturedNanos
                if (skip > 0) {
                    val cut = minOf(skip, b.size)
                    b = b.copyOfRange(cut, b.size)
                    skip -= cut
                    captured += cut * 1_000_000L / Pcm.BYTES_PER_MS
                }
                val wait = captured + held * 1_000_000L - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                // A new delay changes `held` too, so the speaker's buffer stays as full as it was;
                // a drift nudge changes only what is sent. Both apply after this chunk, so the
                // silence goes out before the next chunk is held back.
                val want = delay()
                val change = want - held + tl.takeNudge()
                if (b.isNotEmpty()) {
                    if (fadeIn) { b = Pcm.fade(b, out = false); fadeIn = false }
                    if (change != 0) b = Pcm.fade(b, out = true)
                    tl.add(b.size, captured / 1e9)
                    send(b)
                }
                if (change > 0) {
                    tl.add(change * Pcm.BYTES_PER_MS, null)
                    send(ByteArray(change * Pcm.BYTES_PER_MS))
                } else if (change < 0) {
                    skip += -change * Pcm.BYTES_PER_MS
                }
                if (change != 0) { tl.changed(change); fadeIn = true }
                held = want
            }
        } finally {
            listeners.remove(l)
            st.open--
            log.add("stream: ${name(n)} disconnected after %.1f s and %d kB".format((System.nanoTime() - started) / 1e9, st.bytes / 1000))
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
        private val END = Chunk(0, ByteArray(0))
        /** The length claimed for a "sized" stream: far more than anyone will play (100 GB). */
        const val SIZED_LENGTH = 100_000_000_000L

        /** A WAV header for a stream of unknown length (both sizes at their maximum). */
        fun wavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(Pcm.CHANNELS.toShort())
            .putInt(Pcm.RATE).putInt(Pcm.BYTES_PER_SECOND).putShort((Pcm.CHANNELS * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(-1)
            .array()

        /** 16-bit samples with their bytes swapped: L16 is big-endian, the capture is little-endian. */
        fun swapped(b: ByteArray): ByteArray {
            val o = ByteArray(b.size)
            var i = 0
            while (i + 1 < b.size) { o[i] = b[i + 1]; o[i + 1] = b[i]; i += 2 }
            return o
        }
    }
}
