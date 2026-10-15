package com.speakerbridge.discovery

import com.speakerbridge.compat.Compat
import com.speakerbridge.model.Device
import com.speakerbridge.model.ProtocolHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/** Runs mDNS + SSDP and merges results; a port of linux/speakerbridge/scanner.py. */
class Scanner(private val compat: Compat) {

    suspend fun scan(
        verifiedKeys: Set<String>,
        timeoutMs: Long = 4000,
        iface: NetworkInterface? = null,
    ): List<Device> = withContext(Dispatchers.IO) {
        coroutineScope {
            val types = compat.mdnsTypes.keys.toList()
            val m = async { MdnsBrowser.browse(types, timeoutMs, iface) }
            val s = async { Ssdp.discover(timeoutMs, iface) }
            val devices = merge(devicesFromMdns(m.await()), s.await())
            devices.forEach { compat.classify(it, verifiedKeys) }
            val order = mapOf("verified" to 0, "group" to 1, "ready_to_test" to 2,
                "control_only" to 3, "not_speaker" to 4)
            devices.sortedWith(compareBy<Device>({ order[it.status] ?: 9 }, { it.name.lowercase() }))
        }
    }

    fun devicesFromMdns(instances: List<ServiceInstance>): List<Device> {
        val out = linkedMapOf<String, Device>()
        for (inst in instances) {
            val proto = compat.mdnsTypes[inst.serviceType] ?: continue
            val ip = inst.ipv4
            if (ip.isEmpty()) continue
            val txt = inst.txt
            var name = inst.label
            if (proto == "raop" && '@' in name) name = name.substringAfter('@')
            val isGroup = proto == "googlecast" && txt["md"].orEmpty().equals("Google Cast Group", true)
            val key = if (isGroup) "$ip#group:${inst.instance}" else ip
            val dev = out.getOrPut(key) { Device(ip) }
            dev.isCastGroup = dev.isCastGroup || isGroup
            dev.rawTypes += inst.serviceType

            val details = txt.toMutableMap()
            when (proto) {
                "googlecast" -> {
                    dev.name = txt["fn"].orEmpty().ifEmpty { dev.name.ifEmpty { name } }
                    dev.model = txt["md"].orEmpty().ifEmpty { dev.model }
                    details["cast_id"] = txt["id"].orEmpty()
                }
                "airplay" -> {
                    details["airplay2"] = isAirPlay2(txt["features"].orEmpty()).toString()
                    if (dev.model.isEmpty()) dev.model = txt["model"].orEmpty()
                    if (dev.manufacturer.isEmpty()) dev.manufacturer = txt["manufacturer"].orEmpty()
                }
            }
            if (dev.name.isEmpty()) dev.name = name
            dev.add(ProtocolHit(proto, "mdns", inst.instance, inst.port, details))
        }
        return out.values.toList()
    }

    private fun applyUpnp(dev: Device, u: UpnpDevice) {
        val blob = (u.deviceTypes + u.serviceTypes + u.targets).joinToString(" ")
        dev.rawTypes += u.deviceTypes.ifEmpty { u.targets.toList() }
        if (dev.name.isEmpty()) dev.name = u.friendlyName
        if (dev.manufacturer.isEmpty()) dev.manufacturer = u.manufacturer
        if (dev.model.isEmpty()) dev.model = listOf(u.modelName, u.modelNumber).filter { it.isNotEmpty() }.joinToString(" ")
        val details = mutableMapOf("location" to u.location, "udn" to u.udn, "server" to u.server,
            "manufacturer" to u.manufacturer, "model" to u.modelName)
        if ("MediaRenderer" in blob || "AVTransport" in blob) dev.add(ProtocolHit("dlna_renderer", "ssdp", u.usn, 0, details))
        if ("ScalarWebAPI" in u.rawXml) dev.add(ProtocolHit("sony_scalar", "ssdp", u.usn, 0, mutableMapOf("location" to u.location)))
        if (compat.looksLikeNonSpeaker(blob, u.friendlyName, u.manufacturer)) dev.looksLikeNonSpeaker = true
    }

    private fun finish(dev: Device) {
        for ((proto, field) in listOf("googlecast" to "cast_id", "airplay" to "deviceid",
            "raop" to "deviceid", "dlna_renderer" to "udn")) {
            val v = dev.protocols[proto]?.details?.get(field).orEmpty()
            if (v.isNotEmpty()) { dev.deviceId = "$proto:$v"; break }
        }
        if (dev.isCastGroup && dev.deviceId.isEmpty()) dev.deviceId = "group:${dev.name}"
        val guessed = compat.guessBrand(dev.manufacturer, dev.model, dev.name)
        if (guessed.isNotEmpty() && (dev.manufacturer.isEmpty() || dev.manufacturer.contains(guessed, true))) {
            dev.manufacturer = guessed
        }
    }

    fun merge(mdns: List<Device>, upnp: List<UpnpDevice>): List<Device> {
        val byIp = linkedMapOf<String, Device>()
        val groups = mutableListOf<Device>()
        for (d in mdns) {
            if (d.isCastGroup) groups.add(d) else byIp.putIfAbsent(d.ip, d)
        }
        for (u in upnp) applyUpnp(byIp.getOrPut(u.ip) { Device(u.ip) }, u)
        return (byIp.values + groups).onEach { finish(it) }
    }

    companion object {
        /** AirPlay feature bit 38 (buffered audio) is only set by AirPlay 2 receivers. */
        fun isAirPlay2(features: String): Boolean = runCatching {
            val parts = features.split(',').map { it.trim().removePrefix("0x").removePrefix("0X").toLong(16) }
            val value = parts[0] or (if (parts.size > 1) parts[1] shl 32 else 0L)
            value and (1L shl 38) != 0L
        }.getOrDefault(false)
    }
}
