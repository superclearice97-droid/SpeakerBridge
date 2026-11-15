package com.speakerbridge.playback

import com.speakerbridge.model.Device
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** The speaker took the stream but never got as far as playing it; another route may work. */
class NeverPlayed(message: String) : Exception(message)

/** One way of getting audio to a speaker: over Google Cast, or over DLNA in a given form. */
data class Route(val cast: Boolean, val variant: Variant) {
    val id: String get() = if (cast) "cast" else "dlna:${variant.name}"
    val label: String get() = if (cast) "Google Cast" else "DLNA, ${variant.label}"

    companion object {
        val CAST = Route(true, Variant.WAV)

        /** Every route this speaker could be reached by. */
        fun possible(d: Device): List<Route> =
            (if ("googlecast" in d.protocols) listOf(CAST) else emptyList()) +
                (if (TestSound.dlnaLocation(d).isNotEmpty()) Variant.entries.map { Route(false, it) } else emptyList())

        fun byId(id: String?): Route? = when {
            id == null -> null
            id == "cast" -> CAST
            else -> Variant.entries.firstOrNull { "dlna:${it.name}" == id }?.let { Route(false, it) }
        }
    }
}

/** What is remembered per speaker (by its key): on the phone, app storage; in tests, a map. */
interface SpeakerPrefs {
    fun delayMs(key: String): Int
    /** The DLNA route that worked last time, to try first. */
    fun route(key: String): String?
    fun saveRoute(key: String, id: String)
    /** A route the user picked by hand, or null to work it out automatically. */
    fun forcedRoute(key: String): String?
}

/** Reports of recent sessions, kept in memory for the "Copy report" button. Never sent anywhere. */
object Diagnostics {
    @Volatile var header = ""
    private val kept = ArrayDeque<String>()

    @Synchronized fun keep(report: String) {
        kept.addLast(report)
        while (kept.size > 4) kept.removeFirst()
    }

    @Synchronized fun text(): String = (listOf("SpeakerBridge report", header) + kept).filter { it.isNotEmpty() }.joinToString("\n\n")
}

/**
 * Streams one live source to several speakers at once and keeps them together. For each
 * speaker it works through the routes that could reach it (Google Cast first, then DLNA in
 * the forms the speaker says it plays) until one really plays, remembers which, applies the
 * speaker's sync delay, and corrects clock drift while it runs. Audio is fed in with [push].
 */
class StreamSession(
    private val devices: List<Device>,
    private val prefs: SpeakerPrefs,
    private val stopped: () -> Boolean,
    private val onLine: (Int, String) -> Unit,
    private val onPlaying: (Int) -> Unit = {},
) : Closeable {
    val log = DiagLog()
    private val delays = AtomicIntegerArray(devices.size)
    private val plays = ConcurrentHashMap<Int, String>()
    private val server: LiveStreamServer

    init {
        require(devices.isNotEmpty())
        devices.forEachIndexed { i, d -> delays.set(i, prefs.delayMs(d.key).coerceIn(0, Pcm.MAX_DELAY_MS)) }
        server = LiveStreamServer(TestSound.localAddressFor(devices.first().ip), log,
            { devices.getOrNull(it)?.name ?: "speaker $it" }, { if (it < devices.size) delays.get(it) else 0 })
    }

    /** Feeds one chunk of audio (see [Pcm]) to every speaker. */
    fun push(pcm: ByteArray, capturedNanos: Long = System.nanoTime()) = server.push(pcm, capturedNanos)

    /** Changes a speaker's sync delay while it plays. */
    fun setDelay(key: String, ms: Int) {
        devices.forEachIndexed { i, d -> if (d.key == key) delays.set(i, ms.coerceIn(0, Pcm.MAX_DELAY_MS)) }
    }

    /** Blocking: runs until [stopped], or until every speaker has stopped or failed. */
    fun run() {
        val threads = devices.mapIndexed { i, d -> Thread { speak(i, d) }.also { it.start() } }
        threads.forEach { it.join() }
    }

    override fun close() {
        server.close()
        Diagnostics.keep(report())
    }

    /** Everything that happened, for working out why a speaker didn't play. */
    fun report(): String = buildString {
        devices.forEachIndexed { i, d ->
            append("${i + 1}. ${d.name} | ${d.manufacturer} ${d.model} | ${d.ip} | ")
            append(d.protocols.values.joinToString { it.protocol + if (it.port > 0) ":${it.port}" else "" })
            append(" | delay ${delays.get(i)} ms")
            prefs.forcedRoute(d.key)?.let { append(" | set to ${Route.byId(it)?.label ?: it}") }
            append('\n')
            plays[i]?.let { append("   says it plays: $it\n") }
        }
        append(log.text())
    }

    private fun speak(i: Int, d: Device) {
        val say = { msg: String -> onLine(i, "${d.name}: $msg") }
        fun note(msg: String) = log.add("${d.name}: $msg")
        try {
            val possible = Route.possible(d)
            require(possible.isNotEmpty()) { "this speaker only offers AirPlay, which SpeakerBridge can't send to yet" }
            val forced = Route.byId(prefs.forcedRoute(d.key))?.takeIf { it in possible }
            var renderer: DlnaRenderer? = null
            fun dlnaRoutes(): List<Route> {
                val r = DlnaRenderer(TestSound.dlnaLocation(d), ::note).also { renderer = it }
                val sinks = r.sinks()
                plays[i] = sinks.mapNotNull { it.split(':').getOrNull(2) }.filter { it.startsWith("audio", true) }
                    .distinct().joinToString().ifEmpty { "(it didn't say)" }
                val remembered = Route.byId(prefs.route(d.key))?.takeIf { !it.cast }
                return (listOfNotNull(remembered) + DlnaRenderer.order(sinks).map { Route(false, it) }).distinct()
            }
            val routes = sequence {
                if (forced?.cast == true || (forced == null && Route.CAST in possible)) yield(Route.CAST)
                if (forced?.cast != true && possible.any { !it.cast }) {
                    for (r in dlnaRoutes()) if (forced == null || r == forced) yield(r)
                }
            }
            var failure: String? = null
            val tried = mutableListOf<String>()
            for (route in routes) {
                if (stopped()) break
                tried += route.label
                note("trying ${route.label}")
                try {
                    if (route.cast) playCast(i, d, say, ::note) else playDlna(i, d, renderer!!, route.variant, say, ::note)
                    say("stopped.")
                    return
                } catch (e: NeverPlayed) {
                    failure = e.message
                    note("${route.label} didn't play: ${e.message}")
                }
            }
            if (stopped()) say("stopped.")
            else say("couldn't stream: ${failure ?: "no way to reach it"}" + if (tried.size > 1) " (tried ${tried.joinToString("; ")})" else "")
        } catch (e: Exception) {
            note("failed: ${e.javaClass.simpleName} ${e.message}")
            say("couldn't stream: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun playCast(i: Int, d: Device, say: (String) -> Unit, note: (String) -> Unit) {
        val port = d.protocols.getValue("googlecast").port
        val monitor = DriftMonitor({ server.timeline(i) }) { say(it); note(it) }
        CastPlayer(d.ip, if (port > 0) port else 8009).stream(
            server.url(i, Variant.WAV), Variant.WAV.contentType, TITLE, say, stopped,
            onPlaying = { onPlaying(i) },
            onPosition = { monitor.sample(System.nanoTime() / 1e9, it) },
            log = note,
        )
    }

    private fun playDlna(i: Int, d: Device, renderer: DlnaRenderer, v: Variant, say: (String) -> Unit, note: (String) -> Unit) {
        say("Starting the stream over DLNA (${v.label})…")
        var playing = false
        try {
            runCatching { renderer.stop() }   // some renderers only accept a new URL from an idle transport
            renderer.setUri(server.url(i, v), v.contentType, v.dlnaFeatures, TITLE)
            renderer.play()
            val ok = confirm(i, renderer, note)
            if (stopped()) return
            if (!ok) throw NeverPlayed("it took the stream but didn't play it")
            playing = true
            prefs.saveRoute(d.key, Route(false, v).id)
            onPlaying(i)
            say("Streaming over DLNA.")
            hold(i, renderer, say, note)
        } catch (e: DlnaError) {
            throw if (playing) e else NeverPlayed(e.message ?: "the speaker refused it")
        } finally {
            runCatching { renderer.stop() }
        }
    }

    /**
     * Waits until the speaker really is playing: it says so (when it reports its state) and
     * it has been taking audio from the stream steadily for two seconds. False if it reports
     * an error, sits idle without taking audio, or the time runs out.
     */
    private fun confirm(i: Int, renderer: DlnaRenderer, note: (String) -> Unit): Boolean {
        val st = server.stats(i)
        val start = System.nanoTime()
        var flowingSince = 0L
        var last = "?"
        while (!stopped()) {
            val now = System.nanoTime()
            val elapsed = (now - start) / 1e9
            val info = renderer.transport()
            val state = info?.first.orEmpty()
            if (state != last) { note("says it is ${state.ifEmpty { "(no answer)" }} ${info?.second.orEmpty()}".trimEnd()); last = state }
            if (info?.second == "ERROR_OCCURRED") return false
            flowingSince = if (!st.flowing(now)) 0L else if (flowingSince == 0L) now else flowingSince
            if (flowingSince != 0L && now - flowingSince >= 2_000_000_000L && (info == null || state == "PLAYING")) return true
            if (elapsed > IDLE_GRACE_S && state in IDLE_STATES && !st.flowing(now)) return false
            if (elapsed > CONFIRM_TIMEOUT_S) {
                note("gave up after ${elapsed.toInt()} s: ${st.connections} connection(s), ${st.bytes / 1000} kB taken")
                return false
            }
            Thread.sleep(400)
        }
        return true
    }

    /** While it plays: reads its position for drift correction, and notices if it stops. */
    private fun hold(i: Int, renderer: DlnaRenderer, say: (String) -> Unit, note: (String) -> Unit) {
        val monitor = DriftMonitor({ server.timeline(i) }) { say(it); note(it) }
        val began = System.nanoTime()
        var answered = false
        var idle = 0
        var nextStateCheck = 0L
        while (!stopped()) {
            val wake = System.nanoTime() + Random.nextLong(DLNA_POLL_MS.first, DLNA_POLL_MS.last + 1) * 1_000_000L
            while (!stopped() && System.nanoTime() < wake) Thread.sleep(50)   // random gaps: whole-second readings land at every phase
            if (stopped()) return
            if (monitor.enabled) {
                val t0 = System.nanoTime()
                val pos = renderer.position()
                val t1 = System.nanoTime()
                if (pos != null) {
                    answered = true
                    monitor.sample((t0 + t1) / 2e9, pos.first, pos.second, (t1 - t0) / 2e9)
                } else if (!answered && (t1 - began) / 1e9 > DriftMonitor.WARMUP_S) {
                    monitor.giveUp("it doesn't report how far it has played")
                }
            }
            if (System.nanoTime() >= nextStateCheck) {
                nextStateCheck = System.nanoTime() + 3_000_000_000L
                val state = renderer.transport()?.first
                idle = if (state in IDLE_STATES && !server.stats(i).flowing()) idle + 1 else 0
                if (idle >= 2) throw IOException("the speaker stopped the stream (stopped from elsewhere, or it lost the connection)")
            }
        }
    }

    companion object {
        const val TITLE = "This phone (SpeakerBridge)"
        private val IDLE_STATES = setOf("STOPPED", "NO_MEDIA_PRESENT")
        /** How long a DLNA speaker gets to start playing one form of the stream before the next is tried. */
        @JvmField var CONFIRM_TIMEOUT_S = 15.0
        /** A speaker sitting idle and taking no audio this long after Play has given up on this form. */
        @JvmField var IDLE_GRACE_S = 5.0
        @JvmField var DLNA_POLL_MS = 500L..1500L
    }
}

/**
 * Streams sync clicks (not the phone's audio) to speakers: one a second for lining them up
 * by ear, or the uneven pattern for timing them with the microphone. [sent] collects when
 * each click went out.
 */
class ClickRun(
    devices: List<Device>, prefs: SpeakerPrefs, private val gapsS: DoubleArray,
    onLine: (Int, String) -> Unit, onPlaying: (Int) -> Unit = {},
) : Closeable {
    @Volatile private var stop = false
    @Volatile var ended = false
        private set
    val sent = CopyOnWriteArrayList<Double>()
    val session = StreamSession(devices, prefs, { stop }, onLine, onPlaying)
    private val threads = listOf(
        Thread { Clicks.run(session::push, { stop }, gapsS) { sent += it } },
        Thread { try { session.run() } finally { ended = true } },
    )

    fun start() = threads.forEach { it.start() }

    override fun close() {
        stop = true
        threads.forEach { it.join(15_000) }
        session.close()
    }
}

/** Lines speakers up with the microphone: measures each in turn, then works out the delays. */
object MicCalibration {
    /** After a speaker starts: long enough to hear one full pattern however late it plays. */
    @JvmField var LISTEN_S = 0.0   // 0 = MAX_LATENCY_S plus one pattern
    @JvmField var START_TIMEOUT_S = 80.0

    /** Seconds from the phone to the room for one speaker, with no sync delay applied. */
    fun measure(d: Device, prefs: SpeakerPrefs, rec: RoomRecorder, cancelled: () -> Boolean, say: (String) -> Unit): Double {
        val undelayed = object : SpeakerPrefs by prefs { override fun delayMs(key: String) = 0 }
        val playing = AtomicBoolean(false)
        val line = AtomicReference("")
        val run = ClickRun(listOf(d), undelayed, Clicks.PATTERN, { _, m -> line.set(m); say(m) }, { playing.set(true) })
        fun wait(seconds: Double, until: () -> Boolean) {
            val deadline = System.nanoTime() + (seconds * 1e9).toLong()
            while (!until() && System.nanoTime() < deadline) {
                if (cancelled()) throw MicError("stopped")
                Thread.sleep(100)
            }
        }
        val from: Double
        val to: Double
        try {
            run.start()
            wait(START_TIMEOUT_S) { playing.get() || run.ended }
            if (!playing.get()) throw MicError(line.get().substringAfter(": ", "").ifEmpty { "it didn't start playing" })
            from = System.nanoTime() / 1e9
            wait(if (LISTEN_S > 0) LISTEN_S else MicTiming.MAX_LATENCY_S + Clicks.PATTERN.sum()) { run.ended }
            to = System.nanoTime() / 1e9
        } finally {
            run.close()
        }
        val (samples, t0) = rec.between(from, to)
        rec.clear()
        val lag = MicTiming.match(run.sent, MicTiming.onsets(samples, MicTiming.RATE, t0))
        samples.fill(0)
        return lag ?: throw MicError("the microphone couldn't pick out its clicks. Turn it up, bring the phone " +
            "closer or quieten the room, then try again")
    }

    /**
     * Measures every speaker and works out the delays that line them up. Returns
     * (device key -> delay ms, what went wrong with speakers that couldn't be measured).
     */
    fun calibrate(
        devices: List<Device>, prefs: SpeakerPrefs, recorder: () -> RoomRecorder,
        cancelled: () -> Boolean, say: (String) -> Unit,
    ): Pair<Map<String, Int>, List<String>> {
        val latency = LinkedHashMap<String, Double>()
        val problems = mutableListOf<String>()
        recorder().use { rec ->
            for (d in devices) {
                if (cancelled()) throw MicError("stopped")
                say("${d.name}: playing clicks and listening…")
                try {
                    latency[d.key] = measure(d, prefs, rec, cancelled, say)
                    say("${d.name}: heard ${Math.round(latency.getValue(d.key) * 1000)} ms after it was sent.")
                } catch (e: MicError) {
                    if (cancelled()) throw e
                    problems += "${d.name}: ${e.message}"
                }
                Thread.sleep(1000)   // let its last clicks die away before the next speaker
            }
        }
        return MicTiming.delaysFor(latency) to problems
    }
}
