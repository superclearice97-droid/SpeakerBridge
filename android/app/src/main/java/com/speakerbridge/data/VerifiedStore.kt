package com.speakerbridge.data

import android.content.Context
import org.json.JSONObject

/** Devices the user confirmed actually play audio. Same shape as Linux verified.json. */
class VerifiedStore(context: Context) {
    private val prefs = context.getSharedPreferences("speakerbridge", Context.MODE_PRIVATE)

    private fun load(): JSONObject = runCatching {
        JSONObject(prefs.getString("verified", "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    fun keys(): Set<String> = load().keys().asSequence().toSet()

    fun mark(key: String, name: String, protocol: String) {
        val all = load()
        all.put(key, JSONObject()
            .put("name", name)
            .put("protocol", protocol)
            .put("verified_at", java.time.LocalDateTime.now().withNano(0).toString()))
        prefs.edit().putString("verified", all.toString()).apply()
    }

    fun unmark(key: String) {
        val all = load()
        all.remove(key)
        prefs.edit().putString("verified", all.toString()).apply()
    }
}
