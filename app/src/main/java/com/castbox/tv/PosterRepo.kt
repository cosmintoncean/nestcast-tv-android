package com.castbox.tv

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What to look up for a folder, parsed from a scene-style name such as `Pam.and.Tommy.S01.1080p.WEB-DL`. */
class PosterQuery(val key: String, val title: String, val exactOnly: Boolean)

/**
 * Finds poster images for TV shows on TVmaze (free, no API key) and remembers the results, so each show is
 * looked up once. The only data sent is the cleaned show title. Movies are not covered: there is no free
 * key-less poster source for them, so they keep the plain file-type icon.
 */
class PosterRepo(private val prefs: SharedPreferences) {
    // value: poster URL, or "!<epoch ms>" for a lookup that found nothing (retried after a few days)
    private val cache = mutableStateMapOf<String, String>()

    init {
        runCatching {
            val saved = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            for (key in saved.keys()) cache[key] = saved.getString(key)
        }
    }

    /** The cached poster URL for a folder name, or null if none is known (yet). */
    fun posterFor(folderName: String): String? {
        val query = parse(folderName) ?: return null
        return cache[query.key]?.takeIf { it.startsWith("http") }
    }

    /** Looks up posters for any of [folderNames] not already cached. Safe to call repeatedly. */
    suspend fun ensure(folderNames: Collection<String>) {
        val pending = folderNames.mapNotNull { parse(it) }.distinctBy { it.key }.filter { needsLookup(cache[it.key]) }
        for (query in pending) {
            val result = withContext(Dispatchers.IO) { runCatching { lookupShow(query) } }
            if (result.isSuccess) {
                cache[query.key] = result.getOrNull() ?: "!${System.currentTimeMillis()}"
                persist()
            }
            delay(REQUEST_GAP_MS) // TVmaze allows about 20 calls per 10 seconds
        }
    }

    private fun needsLookup(value: String?): Boolean = when {
        value == null -> true
        value.startsWith("http") -> false
        else -> System.currentTimeMillis() - (value.removePrefix("!").toLongOrNull() ?: 0L) > RETRY_AFTER_MS
    }

    private fun persist() {
        val out = JSONObject()
        for ((key, value) in cache) out.put(key, value)
        prefs.edit().putString(PREF_KEY, out.toString()).apply()
    }

    private fun lookupShow(query: PosterQuery): String? {
        val url = URL("https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(query.title, "UTF-8"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("User-Agent", "CastBoxTV/0.3 (personal media browser)")
        }
        try {
            if (connection.responseCode == 429) throw java.io.IOException("rate limited") // not cached; retried later
            if (connection.responseCode !in 200..299) return null
            val results = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val wanted = normalize(query.title)
            var fallback: String? = null
            for (i in 0 until results.length()) {
                val show = results.getJSONObject(i).optJSONObject("show") ?: continue
                val image = show.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("http") } ?: continue
                if (normalize(show.optString("name")) == wanted) return image
                if (fallback == null) fallback = image
            }
            return if (query.exactOnly) null else fallback
        } finally { connection.disconnect() }
    }

    companion object {
        private const val PREF_KEY = "posters"
        private const val REQUEST_GAP_MS = 600L
        private const val RETRY_AFTER_MS = 3L * 24 * 60 * 60 * 1000

        private val tokens = Regex(
            "(?i)(?<![A-Za-z0-9])(S\\d{1,2}(?:E\\d{1,3})?|(?:19|20)\\d{2}|480p|576p|720p|1080p|2160p|4k|uhd|web[- ]?dl|webrip|bluray|brrip|bdrip|hdtv|dvdrip|x26[45]|h[ .]?26[45]|hevc|dd\\+?\\d|aac|amzn|dsnp|nf|hmax)(?![A-Za-z0-9])"
        )

        private fun normalize(text: String): String =
            text.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

        /** Null when the name looks like a movie (has a year) or has no usable title. */
        fun parse(folderName: String): PosterQuery? {
            val spaced = folderName.replace('.', ' ').replace('_', ' ')
            val first = tokens.find(spaced)
            val title: String
            val exactOnly: Boolean
            if (first == null) {
                title = spaced; exactOnly = true            // a plain folder name: accept only an exact title match
            } else {
                val token = first.value
                val isSeason = token[0].equals('S', ignoreCase = true) && token.length > 1 && token[1].isDigit()
                val isYear = token.length == 4 && token.all { it.isDigit() }
                if (isYear) return null                      // "Title 2006 ..." is a movie
                title = spaced.substring(0, first.range.first); exactOnly = !isSeason
            }
            val clean = title.trim(' ', '-', '(', '[', '_')
            if (clean.length < 2) return null
            return PosterQuery("tv:" + normalize(clean) + if (exactOnly) ":x" else "", clean, exactOnly)
        }
    }
}
