package com.speakerbridge.discovery

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP (UPnP) discovery, a port of linux/speakerbridge/ssdp.py. */
data class UpnpDevice(
    val location: String,
    val ip: String,
    var usn: String = "",
    var server: String = "",
    val targets: MutableSet<String> = mutableSetOf(),
    var friendlyName: String = "",
    var manufacturer: String = "",
    var modelName: String = "",
    var modelNumber: String = "",
    var udn: String = "",
    val deviceTypes: MutableList<String> = mutableListOf(),
    val serviceTypes: MutableList<String> = mutableListOf(),
    var rawXml: String = "",
    var fetchError: String = "",
)

object Ssdp {
    private val GROUP = InetSocketAddress("239.255.255.250", 1900)
    private val TARGETS = listOf(
        "ssdp:all",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:dial-multiscreen-org:service:dial:1",
    )

    fun buildMsearch(st: String, mx: Int = 2): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: Android UPnP/1.1 SpeakerBridge/0.1\r\n\r\n"
        ).toByteArray()

    fun parseResponse(text: String): Map<String, String>? {
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.uppercase() ?: return null
        if (!first.startsWith("HTTP/") && !first.startsWith("NOTIFY")) return null
        val h = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return if ("location" in h) h else null
    }

    fun parseDescription(xml: String, dev: UpnpDevice) {
        dev.rawXml = xml
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var deviceDepth = 0       // nesting of <device> elements
        var seenRootDevice = false
        var current = ""
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    current = p.name
                    if (p.name == "device") deviceDepth++
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "device") { deviceDepth--; seenRootDevice = true }
                    current = ""
                }
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) {
                        val isRoot = deviceDepth == 1 && !seenRootDevice
                        when (current) {
                            "deviceType" -> dev.deviceTypes += t
                            "serviceType" -> dev.serviceTypes += t
                            "friendlyName" -> if (isRoot) dev.friendlyName = t
                            "manufacturer" -> if (isRoot) dev.manufacturer = t
                            "modelName" -> if (isRoot) dev.modelName = t
                            "modelNumber" -> if (isRoot) dev.modelNumber = t
                            "UDN" -> if (isRoot) dev.udn = t
                        }
                    }
                }
            }
            p.next()
        }
    }

    internal fun isLan(host: String): Boolean = runCatching {
        // Only accept numeric addresses so we never trigger a DNS lookup off the LAN.
        if (!host.all { it.isDigit() || it == '.' || it == ':' }) return false
        val a = InetAddress.getByName(host)
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress
    }.getOrDefault(false)

    private fun fetch(dev: UpnpDevice) {
        try {
            val url = URL(dev.location)
            require(isLan(url.host)) { "description URL is not a LAN address" }
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("User-Agent", "SpeakerBridge/0.1")
            val body = conn.inputStream.use { s -> s.readBytes().take(512 * 1024).toByteArray() }
            conn.disconnect()
            parseDescription(String(body, Charsets.UTF_8), dev)
        } catch (e: Exception) {
            dev.fetchError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Blocking; call from Dispatchers.IO. */
    fun discover(timeoutMs: Long = 4000, iface: NetworkInterface? = null): List<UpnpDevice> {
        val found = linkedMapOf<String, UpnpDevice>()
        MulticastSocket(0).use { sock ->
            sock.timeToLive = 2
            iface?.let { runCatching { sock.networkInterface = it } }
            sock.soTimeout = 250
            repeat(2) {
                for (st in TARGETS) {
                    val m = buildMsearch(st)
                    runCatching { sock.send(DatagramPacket(m, m.size, GROUP)) }
                }
            }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val h = parseResponse(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) ?: continue
                val loc = h.getValue("location")
                val dev = found.getOrPut(loc) {
                    UpnpDevice(location = loc, ip = pkt.address.hostAddress ?: "",
                        usn = h["usn"].orEmpty(), server = h["server"].orEmpty())
                }
                h["st"]?.let { dev.targets += it }
            }
        }
        // Small fan-out; each fetch has its own short timeout.
        val threads = found.values.map { d -> Thread { fetch(d) }.apply { start() } }
        threads.forEach { it.join(4000) }
        return found.values.toList()
    }
}
