package com.castbox.tv

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers how far each video has been watched, so playback can resume after backing out and the library
 * can show watched / in-progress state. Keyed by [Movie.watchKey], which survives a PC's IP address changing.
 */
class WatchStore(private val prefs: SharedPreferences) {
    class Progress(val positionMs: Long, val durationMs: Long)

    // Compose state map: reading it from a composable recomposes when a video's progress changes.
    private val map = mutableStateMapOf<String, Progress>()

    init {
        runCatching {
            val saved = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            for (key in saved.keys()) {
                val pair = saved.getJSONArray(key)
                map[key] = Progress(pair.getLong(0), pair.getLong(1))
            }
        }
    }

    fun progress(key: String): Progress? = map[key]

    fun isWatched(key: String): Boolean = map[key]?.let { it.durationMs > 0 && it.positionMs >= it.durationMs * WATCHED_FRACTION } == true

    /** Where to start playing: the saved position, unless it is barely started or already finished. */
    fun resumePosition(key: String): Long {
        val p = map[key] ?: return 0L
        return if (isWatched(key) || p.positionMs < MIN_RESUME_MS) 0L else p.positionMs
    }

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank() || durationMs <= 0 || positionMs < 0) return
        map[key] = Progress(positionMs.coerceAtMost(durationMs), durationMs)
        persist()
    }

    fun markWatched(key: String) {
        if (key.isBlank()) return
        val duration = map[key]?.durationMs?.takeIf { it > 0 } ?: 1L
        map[key] = Progress(duration, duration)
        persist()
    }

    fun markUnwatched(key: String) {
        if (map.remove(key) != null) persist()
    }

    private fun persist() {
        val out = JSONObject()
        for ((key, p) in map) out.put(key, JSONArray().put(p.positionMs).put(p.durationMs))
        prefs.edit().putString(PREF_KEY, out.toString()).apply()
    }

    private companion object {
        const val PREF_KEY = "watch"
        const val WATCHED_FRACTION = 0.92
        const val MIN_RESUME_MS = 10_000L
    }
}
