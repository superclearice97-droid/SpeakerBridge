package com.speakerbridge.playback

import com.speakerbridge.model.Device
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

/**
 * Lets several speakers start at the same moment: each one loads the sound, arrives, and
 * waits until all have arrived (or failed) before it is told to play.
 */
class StartGate(parties: Int) {
    private val latch = CountDownLatch(parties)
    val isOpen: Boolean get() = latch.count == 0L

    fun party() = Party()

    inner class Party {
        private var arrived = false
        val isOpen: Boolean get() = this@StartGate.isOpen
        @Synchronized fun arrive() {
            if (!arrived) { arrived = true; latch.countDown() }
        }
    }
}

/** Plays the test chime on one or more speakers (or Cast groups) so the user can confirm they work. */
object TestSound {
    private const val TITLE = "SpeakerBridge test sound"

    /** True when the device has a protocol this build can send audio over (Cast or DLNA). */
    fun canPlay(d: Device): Boolean =
        "googlecast" in d.protocols || dlnaLocation(d).isNotEmpty()

    /** Blocking; call from Dispatchers.IO. Returns the message to show once it has played. */
    fun play(d: Device, onStep: (String) -> Unit): String {
        val r = playMany(listOf(d)) { _, step -> onStep(step) }.getValue(d)
        return r.getOrThrow()
    }

    /**
     * Blocking; plays the chime on every device at once, started together. Returns each
     * device's outcome: a "done" message, or the error that stopped it.
     */
    fun playMany(devices: List<Device>, onStep: (Device, String) -> Unit): Map<Device, Result<String>> {
        require(devices.isNotEmpty())
        val local = localAddressFor(devices.first().ip)
        val gate = StartGate(devices.size)
        val results = java.util.concurrent.ConcurrentHashMap<Device, Result<String>>()
        ToneServer(local, TestTone.wav(), TestTone.CONTENT_TYPE).use { server ->
            devices.map { d ->
                Thread {
                    val party = gate.party()
                    results[d] = runCatching { playOne(d, server.url, party) { onStep(d, it) } }
                        .also { party.arrive() }   // a failed speaker must not hold the others back
                }.also { it.start() }
            }.forEach { it.join() }
        }
        return results
    }

    private fun playOne(d: Device, url: String, party: StartGate.Party, onStep: (String) -> Unit): String {
        val cast = d.protocols["googlecast"]
        return if (cast != null) {
            onStep("Connecting to the speaker…")
            val port = if (cast.port > 0) cast.port else 8009
            CastPlayer(d.ip, port).play(url, TestTone.CONTENT_TYPE, TITLE, TestTone.DURATION_MS, onStep, party)
            if (d.isCastGroup) "Done. Every speaker in this group should have played the chime together."
            else "Done. Did you hear a short chime?"
        } else {
            val location = dlnaLocation(d)
            require(location.isNotEmpty()) { "this speaker only offers AirPlay, which SpeakerBridge can't send to yet" }
            onStep("Sending the test sound over DLNA…")
            DlnaPlayer.play(location, url, TestTone.CONTENT_TYPE, TITLE, party)
            onStep("Playing now. Listen for the chime.")
            // DLNA gives no "finished" signal; keep the file available while the speaker fetches and plays it.
            Thread.sleep(TestTone.DURATION_MS + 8000)
            "Done. Did you hear a short chime?"
        }
    }

    internal fun dlnaLocation(d: Device): String = d.protocols["dlna_renderer"]?.details?.get("location").orEmpty()

    /** The phone's own address on the network that reaches [ip] (no packet is sent). */
    internal fun localAddressFor(ip: String): InetAddress = DatagramSocket().use { s ->
        s.connect(InetAddress.getByName(ip), 9)
        s.localAddress.takeIf { !it.isAnyLocalAddress } ?: error("your phone has no route to the speaker")
    }
}
