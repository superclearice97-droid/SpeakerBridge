package com.speakerbridge.data

import android.content.Context
import com.speakerbridge.playback.Pcm
import com.speakerbridge.playback.SpeakerPrefs
import org.json.JSONObject

/**
 * Per-speaker sync settings, kept on the phone: its delay, the DLNA form that worked last
 * time, and any route the user picked by hand. Same idea as Linux delays.json.
 */
class SyncStore(context: Context) : SpeakerPrefs {
    private val prefs = context.getSharedPreferences("speakerbridge", Context.MODE_PRIVATE)

    private fun load(name: String): JSONObject = runCatching {
        JSONObject(prefs.getString(name, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun put(name: String, key: String, value: Any?) {
        val all = load(name)
        if (value == null) all.remove(key) else all.put(key, value)
        prefs.edit().putString(name, all.toString()).apply()
    }

    override fun delayMs(key: String): Int = load("delays").optInt(key, 0)

    fun setDelay(key: String, ms: Int) = put("delays", key, ms.coerceIn(0, Pcm.MAX_DELAY_MS).takeIf { it != 0 })

    override fun route(key: String): String? = load("routes").optString(key).ifEmpty { null }

    override fun saveRoute(key: String, id: String) = put("routes", key, id)

    override fun forcedRoute(key: String): String? = load("forced").optString(key).ifEmpty { null }

    fun setForcedRoute(key: String, id: String?) = put("forced", key, id)
}
