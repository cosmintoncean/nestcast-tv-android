package com.castbox.tv

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Tells the PC when a video was finished (POST /api/watched, see docs/medialog-sync-design.md) so it can mark it
 * watched in MediaLog. Events are saved on the TV first and resent until the PC accepts them, so a PC that is
 * asleep or has a new address doesn't lose them. Nothing here is shown to the person.
 */
class WatchReporter(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    /** The PC's current address for a saved server ID (it may have changed since the event); null if unknown. */
    private val currentBase: (serverId: String) -> String?
) {
    private class Event(val eventId: String, val baseUrl: String, val serverId: String, val mediaId: String, val atMs: Long)

    private val lock = Mutex()
    private val events = mutableListOf<Event>()
    private var worker: Job? = null

    init {
        runCatching {
            val saved = JSONArray(prefs.getString(PREF_KEY, "[]") ?: "[]")
            for (i in 0 until saved.length()) {
                val o = saved.getJSONObject(i)
                events += Event(o.getString("e"), o.getString("b"), o.optString("s"), o.getString("m"), o.getLong("t"))
            }
        }
    }

    /** Records that [movie] was finished just now and starts sending. */
    fun completed(movie: Movie) {
        val base = movie.id.substringBefore('|', "")
        val mediaId = movie.watchKey.substringAfter('|', "")
        if (base.isBlank() || mediaId.isBlank()) return
        val serverId = movie.watchKey.substringBefore('|', "").takeIf { it != base }.orEmpty()
        synchronized(events) {
            events += Event(UUID.randomUUID().toString(), base, serverId, mediaId, System.currentTimeMillis())
            while (events.size > MAX_EVENTS) events.removeAt(0)
            persist()
        }
        flushSoon()
    }

    /** Sends anything still waiting, e.g. after the app starts. */
    fun flushSoon() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            var wait = FIRST_RETRY_MS
            while (true) {
                val failedAny = lock.withLock { sendAll() }
                if (!failedAny) return@launch
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    /** One pass over the queue; returns true if some event should be retried later. */
    private fun sendAll(): Boolean {
        var retryLater = false
        val now = System.currentTimeMillis()
        for (event in synchronized(events) { events.toList() }) {
            if (now - event.atMs > MAX_AGE_MS) { remove(event); continue }
            when (send(event)) {
                Result.Done -> remove(event)
                Result.Retry -> retryLater = true
            }
        }
        return retryLater
    }

    private enum class Result { Done, Retry }

    private fun send(event: Event): Result {
        val base = (event.serverId.takeIf { it.isNotBlank() }?.let(currentBase) ?: event.baseUrl).trimEnd('/')
        return try {
            val body = JSONObject()
                .put("id", event.mediaId).put("event", "completed")
                .put("at", isoUtc(event.atMs)).put("clientEventId", event.eventId).toString()
            val connection = (URL("$base/api/watched").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val text = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                when {
                    code in 200..299 -> Result.Done
                    // The PC understood and will never accept this event; resending can't help.
                    code == 400 || code == 413 -> Result.Done
                    code == 404 && text.contains("unknown_media") -> Result.Done
                    // Anything else (older server without the endpoint, 5xx) may be fixed by an update: keep it.
                    else -> Result.Retry
                }
            } finally { connection.disconnect() }
        } catch (e: Exception) {
            Result.Retry
        }
    }

    private fun remove(event: Event) = synchronized(events) { events.remove(event); persist() }

    private fun persist() {
        val out = JSONArray()
        for (e in events) out.put(JSONObject().put("e", e.eventId).put("b", e.baseUrl).put("s", e.serverId).put("m", e.mediaId).put("t", e.atMs))
        prefs.edit().putString(PREF_KEY, out.toString()).apply()
    }

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private companion object {
        const val PREF_KEY = "report_queue"
        const val MAX_EVENTS = 200
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 10 * 60_000L
    }
}
