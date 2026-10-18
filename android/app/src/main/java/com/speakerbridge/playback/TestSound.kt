package com.speakerbridge.playback

import com.speakerbridge.model.Device
import java.net.DatagramSocket
import java.net.InetAddress

/** Plays the test chime on one speaker (or one Cast group) so the user can confirm it works. */
object TestSound {
    private const val TITLE = "SpeakerBridge test sound"

    /** True when the device has a protocol this build can send audio over (Cast or DLNA). */
    fun canPlay(d: Device): Boolean =
        "googlecast" in d.protocols || dlnaLocation(d).isNotEmpty()

    /** Blocking; call from Dispatchers.IO. Returns the message to show once it has played. */
    fun play(d: Device, onStep: (String) -> Unit): String {
        val local = localAddressFor(d.ip)
        return ToneServer(local, TestTone.wav(), TestTone.CONTENT_TYPE).use { server ->
            val cast = d.protocols["googlecast"]
            if (cast != null) {
                onStep("Connecting to the speaker…")
                val port = if (cast.port > 0) cast.port else 8009
                CastPlayer(d.ip, port).play(server.url, TestTone.CONTENT_TYPE, TITLE, TestTone.DURATION_MS, onStep)
                if (d.isCastGroup) "Done. Every speaker in this group should have played the chime together."
                else "Done. Did you hear a short chime?"
            } else {
                val location = dlnaLocation(d)
                require(location.isNotEmpty()) { "this speaker only offers AirPlay, which SpeakerBridge can't send to yet" }
                onStep("Sending the test sound over DLNA…")
                DlnaPlayer.play(location, server.url, TestTone.CONTENT_TYPE, TITLE)
                onStep("Playing now. Listen for the chime.")
                // DLNA gives no "finished" signal; keep the file available while the speaker fetches and plays it.
                Thread.sleep(TestTone.DURATION_MS + 8000)
                "Done. Did you hear a short chime?"
            }
        }
    }

    private fun dlnaLocation(d: Device): String = d.protocols["dlna_renderer"]?.details?.get("location").orEmpty()

    /** The phone's own address on the network that reaches [ip] (no packet is sent). */
    private fun localAddressFor(ip: String): InetAddress = DatagramSocket().use { s ->
        s.connect(InetAddress.getByName(ip), 9)
        s.localAddress.takeIf { !it.isAnyLocalAddress } ?: error("your phone has no route to the speaker")
    }
}
