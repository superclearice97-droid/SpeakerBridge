package com.speakerbridge.compat

import android.content.Context
import com.speakerbridge.model.Device
import org.json.JSONObject

data class ProtocolInfo(
    val key: String,
    val label: String,
    val role: String,          // "stream" or "control"
    val syncTier: Int,
    val notes: String,
    val mdnsType: String?,
)

/**
 * Loads shared/compatibility/compatibility.json (bundled as an asset) and
 * classifies devices exactly like linux/speakerbridge/compat.py.
 */
class Compat(json: String) {
    val protocols: Map<String, ProtocolInfo>
    val statusLabels: Map<String, String>
    private val brandHints: List<Pair<String, List<String>>>
    private val notSpeakerHints: List<String>

    init {
        val root = JSONObject(json)
        val p = root.getJSONObject("protocols")
        protocols = p.keys().asSequence().associateWith { k ->
            val o = p.getJSONObject(k)
            val disc = o.getJSONObject("discovery")
            ProtocolInfo(
                key = k,
                label = o.getString("label"),
                role = o.optString("role", "control"),
                syncTier = o.optInt("sync_tier", 3),
                notes = o.optString("notes"),
                mdnsType = if (disc.has("mdns")) disc.getString("mdns") else null,
            )
        }
        val hints = root.getJSONArray("brand_hints")
        brandHints = (0 until hints.length()).map { i ->
            val h = hints.getJSONObject(i)
            val kw = h.getJSONArray("keywords")
            h.getString("brand") to (0 until kw.length()).map { kw.getString(it) }
        }
        val ns = root.getJSONArray("not_speaker_hints")
        notSpeakerHints = (0 until ns.length()).map { ns.getString(it) }
        val sl = root.getJSONObject("status_labels")
        statusLabels = sl.keys().asSequence().associateWith { sl.getString(it) }
    }

    /** {"_googlecast._tcp" -> "googlecast", ...} */
    val mdnsTypes: Map<String, String> =
        protocols.values.mapNotNull { pi -> pi.mdnsType?.let { it to pi.key } }.toMap()

    fun info(key: String): ProtocolInfo =
        protocols[key] ?: ProtocolInfo(key, key, "control", 3, "", null)

    fun guessBrand(vararg texts: String): String {
        val blob = texts.filter { it.isNotEmpty() }.joinToString(" ").lowercase() + " "
        return brandHints.firstOrNull { (_, kws) -> kws.any { it in blob } }?.first ?: ""
    }

    fun looksLikeNonSpeaker(vararg texts: String): Boolean {
        val blob = texts.joinToString(" ").lowercase()
        return notSpeakerHints.any { it in blob }
    }

    fun classify(dev: Device, verifiedKeys: Set<String>): Device {
        val streamers = dev.protocols.keys.filter { info(it).role == "stream" }
        val notes = mutableListOf<String>()
        dev.syncTier = dev.protocols.keys.minOfOrNull { info(it).syncTier } ?: 3
        dev.status = when {
            dev.key in verifiedKeys -> "verified"
            dev.isCastGroup -> {
                notes += "This group already plays in sync across its members. Play Test Sound sends " +
                    "the chime to the whole group at once."
                "group"
            }
            streamers.isNotEmpty() -> "ready_to_test"
            dev.looksLikeNonSpeaker && dev.protocols.isEmpty() -> "not_speaker"
            dev.protocols.isNotEmpty() -> "control_only"
            else -> "not_speaker"
        }
        for (k in dev.protocols.keys) {
            val pi = info(k)
            if (pi.notes.isNotEmpty()) notes += "${pi.label}: ${pi.notes}"
        }
        if ("googlecast" in dev.protocols && dev.manufacturer.isNotEmpty() && dev.manufacturer != "Google") {
            notes.add(0, "${dev.manufacturer} device with Chromecast built-in: it can join a Google Home " +
                "speaker group with your Nest speakers for native sync.")
        }
        if (dev.status == "control_only" && dev.manufacturer in setOf("Sony", "Vizio")) {
            notes.add(0, "No audio-streaming protocol was advertised. Check the soundbar's settings for " +
                "'Chromecast built-in' or 'AirPlay' and make sure it is enabled.")
        }
        dev.notes = notes
        return dev
    }

    companion object {
        fun fromAssets(ctx: Context): Compat =
            Compat(ctx.assets.open("compatibility.json").bufferedReader().use { it.readText() })
    }
}
