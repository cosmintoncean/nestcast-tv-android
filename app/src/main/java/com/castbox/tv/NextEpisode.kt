package com.castbox.tv

/** How long before the end of an episode the "Next episode" button appears (the credits usually start about then). */
const val NEXT_EPISODE_LEAD_MS = 75_000L

/** Shorter videos never get the button: a clip has no credits to skip. */
private const val MIN_EPISODE_DURATION_MS = 10 * 60_000L

private val episodeMarker = Regex("""(?i)(\bS\d{1,2}\s?[ ._-]?E\d{1,3}\b|\b\d{1,2}x\d{2,3}\b|\bEp(isode)?[ ._-]?\d{1,3}\b)""")
private val tokens = Regex("""\d+|\D+""")

/** True for files named like an episode (S01E02, 1x02, Episode 2), so a folder of unrelated movies gets no "next". */
fun looksLikeEpisode(movie: Movie): Boolean = episodeMarker.containsMatchIn(movie.fileName)

/** Compares names so that "E2" comes before "E10". */
private val naturalOrder = Comparator<String> { a, b ->
    val partsA = tokens.findAll(a.lowercase()).map { it.value }.toList()
    val partsB = tokens.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(partsA.size, partsB.size)) {
        val x = partsA[i]; val y = partsB[i]
        val result = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (result != 0) return@Comparator result
    }
    partsA.size.compareTo(partsB.size)
}

/** The episode after [current] in the same folder (same PC), or null when [current] is not an episode or is the last one. */
fun nextEpisodeAfter(current: Movie, all: List<Movie>): Movie? {
    if (!looksLikeEpisode(current)) return null
    val siblings = all.filter { it.folderPath == current.folderPath && it.url.substringBefore("/api/") == current.url.substringBefore("/api/") && looksLikeEpisode(it) }
        .sortedWith { a, b -> naturalOrder.compare(a.fileName, b.fileName) }
    val index = siblings.indexOfFirst { it.id == current.id }
    return if (index >= 0) siblings.getOrNull(index + 1) else null
}

/** True from [NEXT_EPISODE_LEAD_MS] before the end of a long enough video. */
fun inCreditsWindow(positionMs: Long, durationMs: Long): Boolean =
    durationMs >= MIN_EPISODE_DURATION_MS && positionMs >= 0 && durationMs - positionMs <= NEXT_EPISODE_LEAD_MS
