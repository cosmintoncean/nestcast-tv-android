package com.castbox.tv

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A MediaLog account that one or more of the PCs report to (GET /api/medialog, servers 0.3.0+). */
class MediaAccount(val accountId: String, val username: String?, val label: String, val servers: List<MediaServer>) {
    /** What the person recognises: their MediaLog username, or the connection's name if they have none. */
    val display: String get() = username?.let { "@$it" } ?: label
}

/** Where a finished-video event is sent. */
class ReportTarget(val baseUrl: String, val serverId: String)

/** What one PC says about its MediaLog connection; null when it has none, is unreachable or is an older server. */
class PcAccount(val accountId: String, val username: String?, val label: String)

fun fetchPcAccount(base: String): PcAccount? = runCatching {
    val connection = (URL(base.trimEnd('/') + "/api/medialog").openConnection() as HttpURLConnection).apply {
        connectTimeout = 1500
        readTimeout = 2000
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        val o = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val id = o.optString("accountId")
        if (!o.optBoolean("connected") || id.isBlank()) null
        else PcAccount(id, o.optString("username").ifBlank { null }, o.optString("label").ifBlank { "MediaLog" })
    } finally { connection.disconnect() }
}.getOrNull()

/** Groups the PCs by the MediaLog account they report to; two PCs on the same account make one entry. */
fun groupAccounts(perPc: List<Pair<MediaServer, PcAccount?>>): List<MediaAccount> =
    perPc.mapNotNull { (server, account) -> account?.let { server to it } }
        .groupBy { it.second.accountId }
        .map { (id, pairs) ->
            val first = pairs.first().second
            MediaAccount(id, pairs.firstNotNullOfOrNull { it.second.username }, first.label, pairs.map { it.first })
        }
        .sortedBy { it.display.lowercase() }

/**
 * Which PCs a finished video is reported to. Returns null for "the old way" (the PC the file came from), which
 * is used when no PC reports an account or when there are several accounts and nobody has been asked yet.
 * An empty list means nobody is being tracked.
 */
fun planTargets(accounts: List<MediaAccount>, choice: Set<String>?, filePc: String): List<ReportTarget>? {
    if (accounts.isEmpty()) return null
    val chosen = when {
        accounts.size == 1 -> accounts          // one account is the only place it can go; no need to ask
        choice == null -> return null
        // An answer about accounts that no longer exist says nothing about these ones.
        choice.isNotEmpty() && accounts.none { it.accountId in choice } -> return null
        else -> accounts.filter { it.accountId in choice }
    }
    return chosen.map { account ->
        val pc = account.servers.firstOrNull { it.baseUrl == filePc } ?: account.servers.first()
        ReportTarget(pc.baseUrl, pc.serverId)
    }
}

/** The last "Who's watching?" answer, as account IDs. null = never asked; empty = nobody. */
class WhoChoice(private val prefs: SharedPreferences) {
    fun last(): Set<String>? = prefs.getString(KEY, null)?.let { saved ->
        runCatching { JSONArray(saved).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
    }

    fun save(ids: Set<String>) {
        prefs.edit().putString(KEY, JSONArray(ids.toList()).toString()).apply()
    }

    private companion object { const val KEY = "who_choice" }
}

/** Asked before playback when two or more different MediaLog accounts exist. Back cancels (nothing plays). */
@Composable
fun WhoIsWatchingDialog(accounts: List<MediaAccount>, last: Set<String>?, onChoose: (Set<String>) -> Unit, onCancel: () -> Unit) {
    val all = accounts.map { it.accountId }.toSet()
    // The button for the previous answer starts focused, so repeating it is one press of OK.
    val preselected = when {
        last == null -> accounts.first().accountId
        last.isEmpty() -> "nobody"
        last == all -> "both"
        else -> accounts.firstOrNull { it.accountId in last }?.accountId ?: accounts.first().accountId
    }
    val focus = remember(preselected) { FocusRequester() }
    LaunchedEffect(preselected) { runCatching { focus.requestFocus() } }

    @Composable
    fun choice(key: String, text: String, ids: Set<String>) {
        val modifier = Modifier.fillMaxWidth().let { if (key == preselected) it.focusRequester(focus) else it }
        if (key == preselected) Button(onClick = { onChoose(ids) }, modifier = modifier) { Text(text) }
        else OutlinedButton(onClick = { onChoose(ids) }, modifier = modifier) { Text(text) }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Who's watching?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { choice(it.accountId, it.display, setOf(it.accountId)) }
                choice("both", if (accounts.size == 2) "Both" else "Everyone", all)
                choice("nobody", "Nobody (don't track this)", emptySet())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
