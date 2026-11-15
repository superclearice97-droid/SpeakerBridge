package com.speakerbridge.playback

import com.speakerbridge.model.Device
import com.speakerbridge.model.ProtocolHit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.random.Random

/* Stand-ins for real speakers, a room and a microphone, so everything can be tested without hardware. */

fun now(): Double = System.nanoTime() / 1e9

/** Remembers per-speaker settings in memory. */
class MapPrefs(
    val delays: MutableMap<String, Int> = ConcurrentHashMap(),
    val routes: MutableMap<String, String> = ConcurrentHashMap(),
    val forced: MutableMap<String, String> = ConcurrentHashMap(),
) : SpeakerPrefs {
    override fun delayMs(key: String) = delays[key] ?: 0
    override fun route(key: String) = routes[key]
    override fun saveRoute(key: String, id: String) { routes[key] = id }
    override fun forcedRoute(key: String) = forced[key]
}

/**
 * Plays a stream the way a real speaker does: starts [bufferS] after the first audio arrives,
 * then runs on its own clock, [ppm] fast (+) or slow (-). Logs when each click (sound after
 * 100 ms of silence) would be heard.
 */
class FakePlayer(private val bufferS: Double = 0.3, ppm: Double = 0.0) {
    private val rate = 1 + ppm * 1e-6
    val heard = CopyOnWriteArrayList<Double>()
    @Volatile var tStart = Double.NaN
    @Volatile var first = ByteArray(0)          // the first 20 ms of audio it received
    @Volatile var contentType = ""
    @Volatile var contentLength = ""
    @Volatile var requests = 0
    @Volatile private var socket: Socket? = null
    private val ready = CountDownLatch(1)

    /** Starts fetching and "playing" [url]; returns once audio is arriving (like a speaker reporting PLAYING). */
    fun start(url: String): Boolean {
        Thread { runCatching { play(url) } }.apply { isDaemon = true }.start()
        return ready.await(6, TimeUnit.SECONDS)
    }

    fun stop() { runCatching { socket?.close() } }

    val playing: Boolean get() = !tStart.isNaN()

    /** Seconds of the stream played so far, by the speaker's own clock. */
    fun position(): Double = if (tStart.isNaN()) 0.0 else maxOf(0.0, now() - tStart) * rate

    private fun play(url: String) {
        val u = URL(url)
        Socket(u.host, u.port).use { s ->
            socket = s
            requests++
            s.getOutputStream().write("GET ${u.path} HTTP/1.1\r\nHost: x\r\nUser-Agent: FakePlayer/1\r\nRange: bytes=0-\r\n\r\n".toByteArray())
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
            if (" 200" !in head.lineSequence().first()) return
            contentType = Regex("Content-Type: (.*)\r").find(head)?.groupValues?.get(1).orEmpty()
            contentLength = Regex("Content-Length: (.*)\r").find(head)?.groupValues?.get(1).orEmpty()
            if (contentType.startsWith("audio/wav")) input.readFully(ByteArray(44))
            val buf = ByteArray(Pcm.CHUNK)
            var pos = 0L
            var quiet = 1L shl 40
            while (true) {
                input.readFully(buf)
                if (tStart.isNaN()) {
                    tStart = now() + bufferS
                    first = buf.copyOf()
                    ready.countDown()
                }
                val i = buf.indexOfFirst { it != 0.toByte() }
                if (i >= 0) {
                    if (quiet + i >= 100 * Pcm.BYTES_PER_MS) heard += tStart + (pos + (i and 3.inv())) / (Pcm.BYTES_PER_SECOND * rate)
                    quiet = (buf.size - 1 - buf.indexOfLast { it != 0.toByte() }).toLong()
                } else {
                    quiet += buf.size
                }
                pos += buf.size
            }
        }
    }
}

/**
 * A DLNA renderer. By default it plays anything; [plays] makes it picky, like a TV that takes
 * the command and opens its player but only really plays some forms of the stream, and
 * [rejects] makes it refuse a type outright.
 */
class FakeRenderer(
    val player: FakePlayer = FakePlayer(),
    private val sinks: String = "",
    private val plays: (mime: String, sized: Boolean) -> Boolean = { _, _ -> true },
    private val rejects: (mime: String) -> Boolean = { false },
    private val wholeSeconds: Boolean = true,
    private val reportsPosition: Boolean = true,
) : Closeable {
    val actions = CopyOnWriteArrayList<String>()
    val offered = CopyOnWriteArrayList<String>()      // "mime|live" or "mime|sized" per SetAVTransportURI
    @Volatile var state = "NO_MEDIA_PRESENT"
    @Volatile private var uri = ""
    @Volatile private var mime = ""
    private val http = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val location: String get() = "http://127.0.0.1:${http.localPort}/desc.xml"

    init {
        Thread {
            while (!http.isClosed) {
                val c = runCatching { http.accept() }.getOrNull() ?: break
                Thread { runCatching { c.use(::serve) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** One HTTP request: the description, or a SOAP action. */
    private fun serve(c: Socket) {
        val input = DataInputStream(BufferedInputStream(c.getInputStream()))
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
        val path = head.lineSequence().first().split(' ')[1]
        fun header(name: String) = Regex("(?i)\\n$name:\\s*(.*)\r").find(head)?.groupValues?.get(1).orEmpty()
        val body = String(ByteArray(header("Content-Length").toIntOrNull() ?: 0).also { input.readFully(it) })
        fun respond(code: Int, text: String) {
            val bytes = text.toByteArray()
            c.getOutputStream().write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
        }
        if (path == "/desc.xml") return respond(200, DESCRIPTION.replace("{port}", http.localPort.toString()))
        val action = header("SOAPACTION").trim('"').substringAfter('#')
        actions += action
        var code = 200
        var reply = ""
        when {
            path == "/upnp/cm" && action == "GetProtocolInfo" -> reply = "<Source></Source><Sink>$sinks</Sink>"
            path != "/upnp/av" -> code = 404
            action == "SetAVTransportURI" -> {
                uri = body.substringAfter("<CurrentURI>").substringBefore("</CurrentURI>")
                mime = body.substringAfter("http-get:*:", "").substringBefore(":DLNA").substringBefore(":*")
                offered += "$mime|${if ("/sized/" in uri) "sized" else "live"}"
                if (rejects(mime)) { code = 500; reply = "<errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription>" }
                else state = "STOPPED"
            }
            action == "Play" -> {
                if (plays(mime, "/sized/" in uri)) {
                    state = "PLAYING"
                    Thread { player.start(uri) }.apply { isDaemon = true }.start()
                } else {
                    // Opens its player, looks at the stream, can't play it, gives up.
                    state = "TRANSITIONING"
                    Thread {
                        runCatching { URL(uri).openStream().use { it.read(ByteArray(2000)) } }
                        Thread.sleep(300)
                        state = "STOPPED"
                    }.apply { isDaemon = true }.start()
                }
            }
            action == "Stop" -> { player.stop(); if (state != "NO_MEDIA_PRESENT") state = "STOPPED" }
            action == "GetTransportInfo" ->
                reply = "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
            action == "GetPositionInfo" -> {
                if (!reportsPosition) { code = 500; reply = "<errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription>" }
                else {
                    val pos = player.position()
                    val whole = pos.toInt()
                    reply = "<RelTime>%d:%02d:%02d%s</RelTime>".format(whole / 3600, whole / 60 % 60, whole % 60,
                        if (wholeSeconds) "" else "%.3f".format(pos - whole).substring(1))
                }
            }
        }
        respond(code, "<s:Envelope><s:Body>$reply</s:Body></s:Envelope>")
    }

    fun device(name: String = "Fake TV", key: String = "dlna:$name"): Device = Device("127.0.0.1").also {
        it.name = name
        it.deviceId = key
        it.manufacturer = "Fake"
        it.add(ProtocolHit("dlna_renderer", "test", details = mutableMapOf("location" to location)))
    }

    override fun close() = http.close()

    private companion object {
        const val DESCRIPTION = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <URLBase>http://127.0.0.1:{port}/</URLBase>
  <device><friendlyName>Fake TV</friendlyName>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/upnp/rc</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <controlURL>/upnp/cm</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>upnp/av</controlURL></service>
    </serviceList></device>
</root>"""
    }
}

/**
 * A Cast receiver (TLS, like the Default Media Receiver). [loadState] "PAUSED" makes it load
 * a stream paused, as some TVs do; it then plays only when told to, or never ([playsOnPlay]).
 */
class FakeCast(
    val player: FakePlayer = FakePlayer(),
    private val loadState: String = "PLAYING",
    private val playsOnPlay: Boolean = true,
    private val refusesLaunch: Boolean = false,
) : Thread() {
    val events = CopyOnWriteArrayList<String>()
    val server: SSLServerSocket = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    @Volatile var load: JSONObject? = null
    @Volatile var playSessionId = -1

    init { isDaemon = true }

    fun device(name: String = "Fake Nest", key: String = "cast:$name"): Device = Device("127.0.0.1").also {
        it.name = name
        it.deviceId = key
        it.manufacturer = "Fake"
        it.add(ProtocolHit("googlecast", "test", port = server.localPort))
    }

    override fun run() {
        runCatching {
            server.accept().use { c ->
                val input = DataInputStream(c.getInputStream())
                val out = DataOutputStream(c.getOutputStream())
                fun send(src: String, ns: String, payload: JSONObject) {
                    val b = CastPlayer.encode(src, "sender-0", ns, payload.toString())
                    synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
                }
                fun media(state: String) = JSONObject().put("type", "MEDIA_STATUS").put("status", JSONArray().put(
                    JSONObject().put("playerState", state).put("mediaSessionId", 7).put("currentTime", player.position())))
                var url = ""
                while (true) {
                    val m = CastPlayer.decode(ByteArray(input.readInt()).also { input.readFully(it) })
                    val p = JSONObject(m.payload)
                    val type = p.optString("type")
                    events += type
                    when (type) {
                        "LAUNCH" ->
                            if (refusesLaunch) send("receiver-0", CastPlayer.NS_RECEIVER, JSONObject().put("type", "LAUNCH_ERROR").put("reason", "NOT_ALLOWED"))
                            else send("receiver-0", CastPlayer.NS_RECEIVER, JSONObject().put("type", "RECEIVER_STATUS")
                                .put("status", JSONObject().put("applications", JSONArray().put(
                                    JSONObject().put("appId", CastPlayer.APP_ID).put("transportId", "web-1").put("sessionId", "S1")))))
                        "LOAD" -> {
                            load = p
                            url = p.getJSONObject("media").getString("contentId")
                            if (loadState == "PLAYING") player.start(url)
                            send("web-1", CastPlayer.NS_MEDIA, media(loadState))
                        }
                        "PLAY" -> {
                            playSessionId = p.optInt("mediaSessionId", -1)
                            if (playsOnPlay) { player.start(url); send("web-1", CastPlayer.NS_MEDIA, media("PLAYING")) }
                        }
                        "GET_STATUS" -> if (player.playing) send("web-1", CastPlayer.NS_MEDIA, media("PLAYING"))
                        "STOP" -> player.stop()
                    }
                }
            }
        }
        player.stop()
    }

    private companion object {
        /** One self-signed certificate for every fake receiver (made with the JDK's keytool). */
        val context: SSLContext by lazy {
            val dir = File.createTempFile("cast", "").apply { delete(); mkdirs() }
            val ks = File(dir, "k.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            ProcessBuilder(keytool, "-genkeypair", "-alias", "tv", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=fake-tv", "-validity", "1", "-storetype", "PKCS12", "-keystore", ks.path,
                "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start().waitFor()
            val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "secret".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
    }
}

/**
 * Stands in for the microphone: "hears" the fake players' clicks (each at its own loudness),
 * plus hiss and a room echo, through a microphone that adds its own fixed lag.
 */
class FakeRoom(private val players: List<Pair<FakePlayer, Double>>) : RoomRecorder {
    var cleared = 0
    var closed = false

    override fun between(fromS: Double, toS: Double): Pair<ShortArray, Double> {
        val events = players.flatMap { (p, gain) -> p.heard.filter { it in fromS..toS }.map { it - fromS + LAG to gain } }
        return recording(toS - fromS, events) to fromS
    }

    override fun clear() { cleared++ }
    override fun close() { closed = true }

    companion object {
        const val LAG = 0.023

        private fun monoClick(): ShortArray {
            val c = Clicks.chunk()
            return ShortArray(c.size / 4) { i -> ((c[i * 4].toInt() and 0xff) or (c[i * 4 + 1].toInt() shl 8)).toShort() }
        }

        /** Hiss plus each (time, gain) click, with a quieter echo 37 ms later. */
        fun recording(seconds: Double, clicks: List<Pair<Double, Double>>, seed: Int = 5): ShortArray {
            val rng = Random(seed)
            val out = ShortArray((seconds * MicTiming.RATE).toInt()) { rng.nextInt(-60, 61).toShort() }
            val click = monoClick()
            for ((t, gain) in clicks) for ((time, g) in listOf(t to gain, t + 0.037 to gain * 0.4)) {
                val i = (time * MicTiming.RATE).toInt()
                for (k in click.indices) if (i + k in out.indices) {
                    out[i + k] = (out[i + k] + (click[k] * g).toInt()).coerceIn(-32768, 32767).toShort()
                }
            }
            return out
        }
    }
}
