package com.castbox.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

// ------------------------------------------------------------------------------------------------------------
// Casting a PC's screen to this TV (servers 0.6.0+): GET /api/screens, POST /api/screens/request, GET /api/screens/inbox
// ------------------------------------------------------------------------------------------------------------

/** Who this TV is to a PC: a permanent random ID (so "always allow" sticks) and a name the person recognises. */
class TvIdentity(prefs: SharedPreferences, context: Context) {
    val id: String = prefs.getString("tv_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("tv_id", it).apply() }
    val name: String = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: android.os.Build.MODEL.ifBlank { "Android TV" }
}

class CastScreen(val index: Int, val name: String, val width: Int, val height: Int, val primary: Boolean)
class CastScreens(val available: Boolean, val pcName: String, val screens: List<CastScreen>)

/** The answer to a request to watch a screen. [streamUrl] is set once the PC allowed it. */
class CastAnswer(val requestId: String, val status: String, val streamUrl: String?)

/** A screen the PC sends to this TV on its own (its "Share screen" button). */
class CastOffer(val screenName: String, val pcName: String, val streamUrl: String)

private fun call(method: String, url: String, body: JSONObject? = null, readTimeoutMs: Int = 8000): Pair<Int, JSONObject?> {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 3000; readTimeout = readTimeoutMs
        if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
    }
    try {
        if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    } finally { c.disconnect() }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** The screens of a PC; throws for a server that can't share screens (before 0.6.0) or can't be reached. */
fun fetchCastScreens(server: MediaServer, me: TvIdentity): CastScreens {
    val (code, o) = call("GET", server.baseUrl.trimEnd('/') + "/api/screens?tvId=${enc(me.id)}&tvName=${enc(me.name)}")
    if (code != 200 || o == null) error(if (code == 404) "old" else "HTTP $code")
    val list = o.optJSONArray("screens")
    return CastScreens(
        o.optBoolean("available"), o.optString("pcName", server.name),
        (0 until (list?.length() ?: 0)).mapNotNull { i ->
            list!!.optJSONObject(i)?.let { CastScreen(it.optInt("index"), it.optString("name"), it.optInt("width"), it.optInt("height"), it.optBoolean("primary")) }
        }
    )
}

fun requestCast(server: MediaServer, me: TvIdentity, screen: Int): CastAnswer {
    val (code, o) = call("POST", server.baseUrl.trimEnd('/') + "/api/screens/request", JSONObject().put("tvId", me.id).put("tvName", me.name).put("screen", screen))
    if (code !in 200..299 || o == null) error(if (code == 503) "ffmpeg" else "HTTP $code")
    return CastAnswer(o.optString("requestId"), o.optString("status"), o.optString("streamUrl").takeIf { it.startsWith("http") })
}

fun pollCast(server: MediaServer, requestId: String): CastAnswer {
    val (code, o) = call("GET", server.baseUrl.trimEnd('/') + "/api/screens/request/${enc(requestId)}")
    if (code != 200 || o == null) error("HTTP $code")
    return CastAnswer(requestId, o.optString("status"), o.optString("streamUrl").takeIf { it.startsWith("http") })
}

/** Waits up to [waitSeconds] for the PC to offer a screen; null if it didn't (or couldn't be reached). */
fun fetchCastOffer(server: MediaServer, me: TvIdentity, waitSeconds: Int): CastOffer? = runCatching {
    val (code, o) = call("GET", server.baseUrl.trimEnd('/') + "/api/screens/inbox?tvId=${enc(me.id)}&tvName=${enc(me.name)}&wait=$waitSeconds", readTimeoutMs = (waitSeconds + 10) * 1000)
    if (code != 200 || o == null) null
    else CastOffer(o.optString("name", "a screen"), o.optString("pcName", server.name), o.optString("streamUrl")).takeIf { it.streamUrl.startsWith("http") }
}.getOrNull()

// ------------------------------------------------------------------------------------------------------------
// The flow: pick a screen, wait for the PC's yes, watch
// ------------------------------------------------------------------------------------------------------------

sealed interface CastState {
    data class Picking(val server: MediaServer) : CastState
    data class Waiting(val server: MediaServer, val screen: CastScreen) : CastState
    data class Watching(val url: String, val title: String) : CastState
    data class Failed(val message: String) : CastState
}

/** The dialogs of the flow (everything except watching, which is [CastPlayerScreen]). */
@Composable
fun CastDialogs(state: CastState?, me: TvIdentity, onState: (CastState?) -> Unit) {
    when (state) {
        is CastState.Picking -> PickScreenDialog(state.server, me, onState)
        is CastState.Waiting -> WaitingDialog(state, me, onState)
        is CastState.Failed -> AlertDialog(
            onDismissRequest = { onState(null) }, title = { Text("Couldn't cast") }, text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { onState(null) }) { Text("OK") } }
        )
        else -> Unit
    }
}

@Composable
private fun PickScreenDialog(server: MediaServer, me: TvIdentity, onState: (CastState?) -> Unit) {
    var screens by remember(server) { mutableStateOf<CastScreens?>(null) }
    var problem by remember(server) { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(server) {
        try { screens = withContext(Dispatchers.IO) { fetchCastScreens(server, me) } }
        catch (e: Exception) {
            problem = if (e.message == "old") "This PC's NestCast is too old to share its screen. Update NestCast on ${server.name}." else "Couldn't reach ${server.name}."
        }
    }
    val loaded = screens
    LaunchedEffect(loaded) { if (loaded != null) { delay(100); runCatching { first.requestFocus() } } }
    AlertDialog(
        onDismissRequest = { onState(null) },
        title = { Text("Cast a screen of ${loaded?.pcName ?: server.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    problem != null -> Text(problem!!, color = Color(0xFFFFB4A8))
                    loaded == null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    !loaded.available -> Text("This PC can't share its screen yet (FFmpeg is missing from its NestCast).", color = Color(0xFFFFB4A8))
                    loaded.screens.isEmpty() -> Text("This PC reports no screens.")
                    else -> {
                        Text("The PC may ask you to allow it.", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                        loaded.screens.forEachIndexed { i, s ->
                            val label = "${s.name}    ${s.width}×${s.height}"
                            val click = { onState(CastState.Waiting(server, s)) }
                            if (i == loaded.screens.indexOfFirst { it.primary }.coerceAtLeast(0))
                                Button(onClick = click, modifier = Modifier.fillMaxWidth().focusRequester(first)) { Text(label) }
                            else OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onState(null) }) { Text("Cancel") } }
    )
}

@Composable
private fun WaitingDialog(state: CastState.Waiting, me: TvIdentity, onState: (CastState?) -> Unit) {
    LaunchedEffect(state) {
        try {
            var answer = withContext(Dispatchers.IO) { requestCast(state.server, me, state.screen.index) }
            val deadline = System.currentTimeMillis() + 70_000
            while (answer.status == "pending" && System.currentTimeMillis() < deadline) {
                delay(1000)
                answer = withContext(Dispatchers.IO) { pollCast(state.server, answer.requestId) }
            }
            when {
                answer.status == "approved" && answer.streamUrl != null ->
                    onState(CastState.Watching(answer.streamUrl!!, "${state.screen.name} of ${state.server.name}"))
                answer.status == "denied" -> onState(CastState.Failed("${state.server.name} didn't allow it."))
                else -> onState(CastState.Failed("Nobody answered on ${state.server.name}. Try again and allow it on the PC."))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            onState(CastState.Failed(if (e.message == "ffmpeg") "${state.server.name} can't share its screen yet (FFmpeg is missing)." else "Couldn't reach ${state.server.name}."))
        }
    }
    AlertDialog(
        onDismissRequest = { onState(null) },
        title = { Text("Waiting for ${state.server.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text("Allow “${me.name}” to watch ${state.screen.name} in the NestCast window on the PC.")
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onState(null) }) { Text("Cancel") } }
    )
}

/** Watches the stream full screen. Back stops it, which also stops the capture on the PC. */
@OptIn(UnstableApi::class)
@Composable
fun CastPlayerScreen(url: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf(true) }
    val player = remember(url) {
        // Small buffers keep the delay low; a screen is live, so there is nothing ahead to buffer anyway.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(800, 2500, 250, 500).setPrioritizeTimeOverSizeThresholds(true).build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply {
            setMediaItem(MediaItem.Builder().setUri(Uri.parse(url)).setMimeType(MimeTypes.VIDEO_MP2T).build())
            playWhenReady = true
            prepare()
        }
    }
    BackHandler(onBack = onClose)
    // Leaving the app (Home, the TV going to sleep) ends the stream, so the PC stops capturing too.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) onClose() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "The stream stopped (${e.errorCodeName}). The PC may have ended it." }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING; if (state == Player.STATE_ENDED) error = "The PC stopped sharing." }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(url) { delay(5000); hint = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; keepScreenOn = true; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) } },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFFFFB139))
        AnimatedVisibility(visible = hint || error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.padding(24.dp).background(Color(0xAA000000)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(error ?: "Press Back to stop watching", color = if (error != null) Color(0xFFFFB4A8) else Color(0xFFD0D3DA), fontSize = 14.sp)
            }
        }
    }
}
