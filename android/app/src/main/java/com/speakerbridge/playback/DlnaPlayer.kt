package com.speakerbridge.playback

import android.util.Xml
import com.speakerbridge.discovery.Ssdp
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/** Plays one URL on a DLNA / UPnP renderer with the standard AVTransport actions. */
object DlnaPlayer {

    /** Blocking; call from Dispatchers.IO. [location] is the renderer's description URL from SSDP. */
    fun play(location: String, url: String, contentType: String, title: String) {
        val loc = URL(location)
        require(Ssdp.isLan(loc.host)) { "the speaker's address is not on your local network" }
        val (serviceType, control, base) = avTransport(get(loc))
            ?: error("this speaker doesn't offer DLNA playback (no AVTransport service)")
        val controlUrl = URL(base?.let { URL(it) } ?: loc, control)
        require(Ssdp.isLan(controlUrl.host)) { "the speaker's control address is not on your local network" }

        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$contentType:*\">${esc(url)}</res></item></DIDL-Lite>"

        runCatching { soap(controlUrl, serviceType, "Stop", "") }   // some renderers need an idle transport first
        soap(controlUrl, serviceType, "SetAVTransportURI",
            "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        soap(controlUrl, serviceType, "Play", "<Speed>1</Speed>")
    }

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

    private fun soap(control: URL, serviceType: String, action: String, args: String) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\"><InstanceID>0</InstanceID>$args</u:$action>" +
            "</s:Body></s:Envelope>"
        val conn = control.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val reason = Regex("<errorDescription>(.*?)</errorDescription>").find(err)?.groupValues?.get(1)
                error("the speaker rejected $action (${reason ?: "HTTP $code"})")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns (serviceType, controlURL, URLBase) of the first AVTransport service in a description. */
    private fun avTransport(xml: String): Triple<String, String, String?>? {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var base: String? = null
        var type = ""
        var control = ""
        var current = ""
        var found: Pair<String, String>? = null
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    current = p.name
                    if (p.name == "service") { type = ""; control = "" }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "service" && found == null && "AVTransport" in type && control.isNotEmpty()) {
                        found = type to control
                    }
                    current = ""
                }
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    when (current) {
                        "URLBase" -> if (t.isNotEmpty()) base = t
                        "serviceType" -> type += t
                        "controlURL" -> control += t
                    }
                }
            }
            p.next()
        }
        return found?.let { Triple(it.first, it.second, base) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
