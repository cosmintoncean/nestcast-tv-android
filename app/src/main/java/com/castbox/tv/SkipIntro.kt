package com.castbox.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Where a video's intro runs, as reported by the server from the video's own chapters. */
data class IntroRange(val startMs: Long, val endMs: Long)

/** The button goes away this long before the intro ends: skipping the last moments isn't worth a press. */
private const val SKIP_TAIL_MS = 3_000L

/** Reads the server's `GET /api/media/{id}/intro` answer. */
fun parseIntro(json: String): IntroRange? = runCatching {
    val o = JSONObject(json)
    val start = o.getLong("startMs"); val end = o.getLong("endMs")
    if (start >= 0 && end > start) IntroRange(start, end) else null
}.getOrNull()

/** True while the "Skip intro" button should show: from the start of the intro until just before it ends. */
fun inIntro(intro: IntroRange, positionMs: Long): Boolean =
    positionMs >= intro.startMs && positionMs < intro.endMs - SKIP_TAIL_MS

/**
 * Asks the PC whether this video's own chapters name an intro. Null when there is none, when the PC is an older
 * NestCast Server without this feature, for personal videos, or on any error: the button simply doesn't appear.
 * Blocks (the server may need a moment to read the file), so call it off the main thread.
 */
fun fetchIntro(mediaUrl: String): IntroRange? {
    if (!mediaUrl.contains("/api/media/")) return null
    return runCatching {
        val connection = (URL("${mediaUrl.trimEnd('/')}/intro").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 25000
        }
        try {
            if (connection.responseCode == 200) parseIntro(connection.inputStream.bufferedReader().use { it.readText() }) else null
        } finally { connection.disconnect() }
    }.getOrNull()
}
