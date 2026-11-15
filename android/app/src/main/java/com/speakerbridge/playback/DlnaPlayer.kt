package com.speakerbridge.playback

import com.speakerbridge.discovery.Ssdp
import java.net.HttpURLConnection
import java.net.URL

class DlnaError(message: String) : Exception(message)

/**
 * One DLNA / UPnP renderer, driven with the standard AVTransport and ConnectionManager
 * actions. [location] is its description URL from SSDP. Every call is blocking (use
 * Dispatchers.IO or a thread) and only ever talks to addresses on the local network.
 */
class DlnaRenderer(location: String, private val log: (String) -> Unit = {}) {
    private val avTransport: Pair<String, URL>
    private val connectionManager: Pair<String, URL>?

    init {
        val loc = URL(location)
        if (!Ssdp.isLan(loc.host)) throw DlnaError("the speaker's address is not on your local network")
        val xml = get(loc)
        val base = Regex("<URLBase>\\s*(.*?)\\s*</URLBase>").find(xml)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() }?.let { URL(unescape(it)) } ?: loc
        fun service(kind: String): Pair<String, URL>? =
            SERVICE.findAll(xml).firstNotNullOfOrNull { m ->
                val type = tag(m.value, "serviceType").orEmpty()
                val control = tag(m.value, "controlURL").orEmpty()
                if (kind in type && control.isNotEmpty()) type to URL(base, control) else null
            }
        avTransport = service("AVTransport")
            ?: throw DlnaError("this speaker doesn't offer DLNA playback (no AVTransport service)")
        connectionManager = service("ConnectionManager")
        for (s in listOfNotNull(avTransport, connectionManager)) {
            if (!Ssdp.isLan(s.second.host)) throw DlnaError("the speaker's control address is not on your local network")
        }
    }

    /** What the speaker says it can play (protocolInfo strings), or empty if it won't say. */
    fun sinks(): List<String> {
        val cm = connectionManager ?: return emptyList()
        return runCatching {
            tag(soap(cm, "GetProtocolInfo", "", instance = false), "Sink").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    /** Hands the speaker a URL to play; throws [DlnaError] if it refuses (e.g. a type it can't play). */
    fun setUri(url: String, contentType: String, features: String, title: String) {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$contentType:$features\">${esc(url)}</res></item></DIDL-Lite>"
        soap(avTransport, "SetAVTransportURI",
            "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
    }

    fun play() { soap(avTransport, "Play", "<Speed>1</Speed>") }

    fun stop() { soap(avTransport, "Stop", "") }

    /** (CurrentTransportState, CurrentTransportStatus), e.g. ("PLAYING", "OK"); null if it won't say. */
    fun transport(): Pair<String, String>? = runCatching {
        val r = soap(avTransport, "GetTransportInfo", "", quiet = true)
        (tag(r, "CurrentTransportState") ?: return null) to tag(r, "CurrentTransportStatus").orEmpty()
    }.getOrNull()

    /** (seconds played, whether it only reports whole seconds), or null if it won't say. */
    fun position(): Pair<Double, Boolean>? = runCatching {
        val m = REL_TIME.find(soap(avTransport, "GetPositionInfo", "", quiet = true)) ?: return null
        val (h, mi, s, frac) = m.destructured
        (h.toInt() * 3600 + mi.toInt() * 60 + s.toInt() + (if (frac.isEmpty()) 0.0 else frac.toDouble())) to frac.isEmpty()
    }.getOrNull()

    private fun get(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return try {
            conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun soap(service: Pair<String, URL>, action: String, args: String, instance: Boolean = true, quiet: Boolean = false): String {
        val (type, control) = service
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$type\">${if (instance) "<InstanceID>0</InstanceID>" else ""}$args</u:$action>" +
            "</s:Body></s:Envelope>"
        val conn = control.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$type#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val reason = listOfNotNull(tag(err, "errorCode"), tag(err, "errorDescription")).joinToString(" ")
                    .ifEmpty { "HTTP $code" }
                if (!quiet) log("dlna: $action refused ($reason)")
                throw DlnaError("the speaker rejected $action ($reason)")
            }
            val reply = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            if (!quiet) log("dlna: $action ok")
            return reply
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val SERVICE = Regex("<service>.*?</service>", RegexOption.DOT_MATCHES_ALL)
        private val REL_TIME = Regex("<RelTime>\\s*(\\d+):(\\d\\d):(\\d\\d)(\\.\\d+)?\\s*</RelTime>")

        /** The text of the first <name> element (any namespace prefix), unescaped. */
        internal fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

        /**
         * The order to try stream forms in, given what the speaker says it plays. Raw L16 is
         * the one form every DLNA audio player must accept, so it goes first unless the
         * speaker lists WAV and not L16; the forms with a declared length come last.
         */
        fun order(sinks: List<String>): List<Variant> {
            val mimes = sinks.mapNotNull { it.split(':').getOrNull(2)?.lowercase() }
            val l16 = mimes.any { it.startsWith("audio/l16") }
            val wav = mimes.any { it.startsWith("audio/wav") || it.startsWith("audio/x-wav") || it.startsWith("audio/wave") }
            return if (wav && !l16) listOf(Variant.WAV, Variant.L16, Variant.WAV_SIZED, Variant.L16_SIZED)
            else listOf(Variant.L16, Variant.WAV, Variant.L16_SIZED, Variant.WAV_SIZED)
        }
    }
}
