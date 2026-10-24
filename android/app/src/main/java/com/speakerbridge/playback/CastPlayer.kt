package com.speakerbridge.playback

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Minimal Google Cast sender: opens the speaker's Cast channel, starts Google's
 * Default Media Receiver on it and asks it to play one URL. A Google Home speaker
 * group answers on its own port, and all of its members then play in sync.
 */
class CastPlayer(private val host: String, private val port: Int) {

    data class Msg(val source: String, val dest: String, val namespace: String, val payload: String)

    private lateinit var sock: SSLSocket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var requestId = 1
    private var lastPing = 0L
    private var sessionId = ""
    private val inbox = LinkedBlockingQueue<Msg>()
    @Volatile private var readError: Throwable? = null

    /**
     * Blocking; call from Dispatchers.IO. Throws with a readable message if the speaker refuses.
     * The file is loaded paused, then held at [gate] until every speaker in the batch is
     * ready, so they all get PLAY at the same moment.
     */
    fun play(
        url: String, contentType: String, title: String, durationMs: Long,
        onStep: (String) -> Unit, gate: StartGate.Party,
    ) {
        open()
        try {
            val transportId = launch(onStep)
            onStep("Sending the test sound…")
            val media = JSONObject().put("contentId", url).put("contentType", contentType)
                .put("streamType", "BUFFERED")
                .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
            send(transportId, NS_MEDIA, JSONObject().put("type", "LOAD").put("requestId", requestId++)
                .put("media", media).put("autoplay", false).toString())

            var mediaSessionId = 0
            var autoPlayed = false
            val loaded = waitFor(15_000) { m ->
                if (m.namespace != NS_MEDIA) return@waitFor false
                val j = JSONObject(m.payload)
                when (j.optString("type")) {
                    "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" ->
                        error("the speaker couldn't load the test sound (${j.optString("type")})")
                    "MEDIA_STATUS" -> {
                        val st = j.optJSONArray("status")?.optJSONObject(0) ?: return@waitFor false
                        mediaSessionId = st.optInt("mediaSessionId", mediaSessionId)
                        when (st.optString("playerState")) {
                            "PAUSED" -> true
                            "PLAYING" -> { autoPlayed = true; true }   // receiver ignored autoplay=false
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            if (!loaded) error("the speaker didn't finish loading the test sound")
            gate.arrive()
            if (!gate.isOpen) onStep("Ready. Waiting for the other speakers…")
            val gateDeadline = System.currentTimeMillis() + 20_000
            while (!gate.isOpen && System.currentTimeMillis() < gateDeadline) waitFor(5) { false }   // keeps heartbeats going
            if (!autoPlayed) {
                send(transportId, NS_MEDIA, JSONObject().put("type", "PLAY").put("requestId", requestId++)
                    .put("mediaSessionId", mediaSessionId).toString())
            }

            var started = false
            val finished = waitFor(15_000 + durationMs) { m ->
                if (m.namespace != NS_MEDIA) return@waitFor false
                val j = JSONObject(m.payload)
                when (j.optString("type")) {
                    "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" ->
                        error("the speaker couldn't load the test sound (${j.optString("type")})")
                    "MEDIA_STATUS" -> {
                        val s = j.optJSONArray("status")?.optJSONObject(0) ?: return@waitFor false
                        when (s.optString("playerState")) {
                            "PLAYING", "BUFFERING" -> {
                                if (!started) onStep("Playing now. Listen for the chime.")
                                started = true
                                false
                            }
                            "IDLE" -> when (s.optString("idleReason")) {
                                "FINISHED" -> true
                                "ERROR" -> error("the speaker couldn't play the test sound")
                                else -> started   // stopped from elsewhere after it started
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            if (!finished && !started) error("the speaker never started playing")
        } finally {
            stopAndClose()
        }
    }

    /**
     * Blocking; call from Dispatchers.IO. Plays an endless live stream until [stopped] returns
     * true, then closes the speaker's media player. Throws if the speaker refuses or ends it.
     */
    fun stream(url: String, contentType: String, title: String, onStep: (String) -> Unit, stopped: () -> Boolean) {
        open()
        try {
            val transportId = launch(onStep)
            onStep("Starting the stream…")
            val media = JSONObject().put("contentId", url).put("contentType", contentType)
                .put("streamType", "LIVE")
                .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
            send(transportId, NS_MEDIA, JSONObject().put("type", "LOAD").put("requestId", requestId++)
                .put("media", media).put("autoplay", true).toString())
            val started = waitFor(20_000) { m ->
                if (m.namespace != NS_MEDIA) return@waitFor false
                val j = JSONObject(m.payload)
                when (j.optString("type")) {
                    "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" ->
                        error("the speaker couldn't load the stream (${j.optString("type")})")
                    "MEDIA_STATUS" -> {
                        val st = j.optJSONArray("status")?.optJSONObject(0) ?: return@waitFor false
                        if (st.optString("playerState") == "IDLE" && st.optString("idleReason") == "ERROR")
                            error("the speaker couldn't play the stream")
                        st.optString("playerState") in setOf("PLAYING", "BUFFERING", "PAUSED")
                    }
                    else -> false
                }
            }
            if (!started) error("the speaker didn't start the stream")
            onStep("Streaming.")
            while (!stopped()) {
                val ended = waitFor(500) { m ->
                    val j = JSONObject(m.payload)
                    when {
                        m.namespace == NS_RECEIVER && j.optString("type") == "RECEIVER_STATUS" -> {
                            val apps = j.optJSONObject("status")?.optJSONArray("applications")
                            (0 until (apps?.length() ?: 0)).none { apps!!.getJSONObject(it).optString("appId") == APP_ID }
                        }
                        m.namespace == NS_MEDIA ->
                            j.optJSONArray("status")?.optJSONObject(0)?.optString("playerState") == "IDLE"
                        else -> false
                    }
                }
                if (ended) error("the speaker stopped the stream (stopped from elsewhere, or it lost the connection)")
            }
        } finally {
            stopAndClose()
        }
    }

    /** Starts the Default Media Receiver and connects to it. Returns its transport id. */
    private fun launch(onStep: (String) -> Unit): String {
        send(RECEIVER, NS_CONNECTION, """{"type":"CONNECT"}""")
        onStep("Starting the speaker's media player…")
        send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP_ID)
            .put("requestId", requestId++).toString())
        var transportId = ""
        val launched = waitFor(15_000) { m ->
            val j = JSONObject(m.payload)
            when (j.optString("type")) {
                "LAUNCH_ERROR" -> error("the speaker refused to start its media player (${j.optString("reason")})")
                "RECEIVER_STATUS" -> {
                    val apps = j.optJSONObject("status")?.optJSONArray("applications")
                    for (i in 0 until (apps?.length() ?: 0)) {
                        val a = apps!!.getJSONObject(i)
                        if (a.optString("appId") == APP_ID) {
                            transportId = a.optString("transportId")
                            sessionId = a.optString("sessionId")
                        }
                    }
                    transportId.isNotEmpty()
                }
                else -> false
            }
        }
        if (!launched) error("the speaker didn't start its media player in time")
        send(transportId, NS_CONNECTION, """{"type":"CONNECT"}""")
        return transportId
    }

    private fun stopAndClose() {
        if (sessionId.isNotEmpty()) runCatching {
            send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", sessionId)
                .put("requestId", requestId++).toString())
        }
        runCatching { sock.close() }
    }

    private fun open() {
        // Cast devices present certificates signed by Google's own device CA, not a public one,
        // so the channel can't be validated against Android's trust store. This socket only
        // ever connects to a speaker's numeric LAN address found by discovery.
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(AcceptLanCert), null)
        sock = ctx.socketFactory.createSocket() as SSLSocket
        sock.connect(InetSocketAddress(host, port), 4000)
        sock.soTimeout = 5000
        sock.startHandshake()
        sock.soTimeout = 0
        input = DataInputStream(sock.inputStream)
        output = DataOutputStream(sock.outputStream)
        // A reader thread keeps whole frames flowing into [inbox], so waiting never splits a frame.
        Thread {
            try {
                while (true) {
                    val len = input.readInt()
                    require(len in 0..(1 shl 20)) { "unexpected Cast frame size" }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    inbox.put(decode(buf))
                }
            } catch (e: Throwable) {
                readError = e
                inbox.put(CLOSED)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun send(dest: String, namespace: String, payload: String) {
        val b = encode(SENDER, dest, namespace, payload)
        output.writeInt(b.size)
        output.write(b)
        output.flush()
    }

    /** Reads messages (answering heartbeats) until [match] returns true or time runs out. */
    private fun waitFor(timeoutMs: Long, match: (Msg) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline) return false
            if (now - lastPing > 5000) {
                send(RECEIVER, NS_HEARTBEAT, """{"type":"PING"}""")
                lastPing = now
            }
            val m = inbox.poll(minOf(deadline - now, 1000L), TimeUnit.MILLISECONDS) ?: continue
            if (m === CLOSED) {
                inbox.put(CLOSED)
                throw IOException("the speaker closed the connection", readError)
            }
            if (m.namespace == NS_HEARTBEAT) {
                if ("PING" in m.payload) send(m.source, NS_HEARTBEAT, """{"type":"PONG"}""")
                continue
            }
            if (match(m)) return true
        }
    }

    private object AcceptLanCert : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        private val CLOSED = Msg("", "", "", "")
        const val APP_ID = "CC1AD845"   // Google's Default Media Receiver
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** CastMessage protobuf: version=0, source, dest, namespace, payload_type=STRING, payload. */
        fun encode(source: String, dest: String, namespace: String, payload: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x08); out.write(0x00)
            putString(out, 2, source)
            putString(out, 3, dest)
            putString(out, 4, namespace)
            out.write(0x28); out.write(0x00)
            putString(out, 6, payload)
            return out.toByteArray()
        }

        fun decode(b: ByteArray): Msg {
            var i = 0
            fun varint(): Long {
                var shift = 0
                var r = 0L
                while (true) {
                    val c = b[i++].toInt() and 0xff
                    r = r or ((c and 0x7f).toLong() shl shift)
                    if (c and 0x80 == 0) return r
                    shift += 7
                }
            }
            val fields = HashMap<Int, String>()
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) {
                    0 -> varint()
                    2 -> {
                        val n = varint().toInt()
                        fields[tag ushr 3] = String(b, i, n, Charsets.UTF_8)
                        i += n
                    }
                    else -> error("unexpected Cast message encoding")
                }
            }
            return Msg(fields[2].orEmpty(), fields[3].orEmpty(), fields[4].orEmpty(), fields[6].orEmpty())
        }

        private fun putString(out: ByteArrayOutputStream, field: Int, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            out.write((field shl 3) or 2)
            var n = bytes.size
            while (n >= 0x80) { out.write((n and 0x7f) or 0x80); n = n ushr 7 }
            out.write(n)
            out.write(bytes)
        }
    }
}
