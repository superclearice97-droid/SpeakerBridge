package com.speakerbridge.model

/** One protocol a device advertised. Mirrors linux/speakerbridge/model.py. */
data class ProtocolHit(
    val protocol: String,
    val source: String,
    val serviceName: String = "",
    var port: Int = 0,
    val details: MutableMap<String, String> = mutableMapOf(),
)

class Device(val ip: String) {
    var name: String = ""
    var manufacturer: String = ""
    var model: String = ""
    var deviceId: String = ""
    val protocols: LinkedHashMap<String, ProtocolHit> = linkedMapOf()
    val rawTypes: java.util.TreeSet<String> = java.util.TreeSet()
    var isCastGroup: Boolean = false
    var looksLikeNonSpeaker: Boolean = false
    var status: String = ""
    var syncTier: Int = 3
    var notes: List<String> = emptyList()

    /** Identity used for "verified" records; protocol ids survive DHCP changes. */
    val key: String get() = deviceId.ifEmpty { "ip:$ip" }

    fun add(hit: ProtocolHit) {
        val existing = protocols[hit.protocol]
        if (existing == null) {
            protocols[hit.protocol] = hit
        } else {
            hit.details.forEach { (k, v) -> if (v.isNotEmpty()) existing.details[k] = v }
            if (existing.port == 0) existing.port = hit.port
        }
    }
}
