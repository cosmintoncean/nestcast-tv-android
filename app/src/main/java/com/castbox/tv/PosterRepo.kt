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
    // value: "<poster URL>\t<official show name>", or "!<epoch ms>" for a lookup that found nothing
    // (retried after a few days)
    private val cache = mutableStateMapOf<String, String>()

    private class Hit(val posterUrl: String, val name: String)

    init {
        runCatching {
            val saved = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            for (key in saved.keys()) cache[key] = saved.getString(key)
        }
    }

    /** The cached poster URL for a folder name, or null if none is known (yet). */
    fun posterFor(folderName: String): String? {
        val query = parse(folderName) ?: return null
        return cache[query.key]?.substringBefore('\t')?.takeIf { it.startsWith("http") }
    }

    /**
     * A readable name for a folder: the show's real title plus season for TV folders (once looked up; the
     * cleaned folder name until then), "Title (Year)" for movies, and the original name for anything else.
     */
    fun displayName(folderName: String): String {
        val cleaned = clean(folderName) ?: return folderName
        val official = parse(folderName)?.let { cache[it.key]?.substringAfter('\t', "")?.ifBlank { null } }
        return when {
            cleaned.season != null -> "${official ?: cleaned.title}  ·  Season ${cleaned.season}"
            cleaned.year != null -> "${cleaned.title} (${cleaned.year})"
            official != null -> official
            cleaned.hasTokens -> cleaned.title
            else -> folderName
        }
    }

    /** Looks up posters for any of [folderNames] not already cached. Safe to call repeatedly. */
    suspend fun ensure(folderNames: Collection<String>) {
        val pending = folderNames.mapNotNull { parse(it) }.distinctBy { it.key }.filter { needsLookup(cache[it.key]) }
        for (query in pending) {
            val result = withContext(Dispatchers.IO) { runCatching { lookupShow(query) } }
            if (result.isSuccess) {
                cache[query.key] = result.getOrNull()?.let { "${it.posterUrl}\t${it.name}" } ?: "!${System.currentTimeMillis()}"
                persist()
            }
            delay(REQUEST_GAP_MS) // TVmaze allows about 20 calls per 10 seconds
        }
    }

    private fun needsLookup(value: String?): Boolean = when {
        value == null -> true
        value.startsWith("http") -> !value.contains('\t') // entries saved before names were stored are looked up again
        else -> System.currentTimeMillis() - (value.removePrefix("!").toLongOrNull() ?: 0L) > RETRY_AFTER_MS
    }

    private fun persist() {
        val out = JSONObject()
        for ((key, value) in cache) out.put(key, value)
        prefs.edit().putString(PREF_KEY, out.toString()).apply()
    }

    private fun lookupShow(query: PosterQuery): Hit? {
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
            var fallback: Hit? = null
            for (i in 0 until results.length()) {
                val show = results.getJSONObject(i).optJSONObject("show") ?: continue
                val name = show.optString("name")
                if (name.isBlank()) continue
                val image = show.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("http") } ?: continue
                if (normalize(name) == wanted) return Hit(image, name)
                if (fallback == null) fallback = Hit(image, name)
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

        private val seasonToken = Regex("(?i)(?<![A-Za-z0-9])S(\\d{1,2})(?:E\\d{1,3})?(?![A-Za-z0-9])")

        /** A folder name split into its title and the season/year it carries; [hasTokens] if quality tags etc. were found. */
        private class Cleaned(val title: String, val season: Int?, val year: Int?, val hasTokens: Boolean)

        private fun clean(folderName: String): Cleaned? {
            val spaced = folderName.replace('.', ' ').replace('_', ' ')
            val first = tokens.find(spaced)
            val title = spaced.substring(0, first?.range?.first ?: spaced.length)
                .trim(' ', '-', '(', '[', '_').replace(Regex("\\s+"), " ")
            if (title.length < 2) return null
            val season = seasonToken.find(spaced)?.groupValues?.get(1)?.toIntOrNull()
            // A year only means "movie" when there is no season marker ("Show 2022 S01" is still a show).
            val year = if (season == null) first?.value?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toIntOrNull() else null
            return Cleaned(title, season, year, first != null)
        }

        /** Null when the name looks like a movie (has a year) or has no usable title. */
        fun parse(folderName: String): PosterQuery? {
            val cleaned = clean(folderName) ?: return null
            if (cleaned.year != null) return null            // "Title 2006 ..." is a movie
            val exactOnly = cleaned.season == null           // no season marker: accept only an exact title match
            return PosterQuery("tv:" + normalize(cleaned.title) + if (exactOnly) ":x" else "", cleaned.title, exactOnly)
        }
    }
}
