package com.castbox.tv

import android.net.Uri
import android.os.Bundle
import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MediaServer(val name: String, val baseUrl: String)

data class Movie(
    val id: String,
    val title: String,
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val url: String
)

private val Bg = Color(0xFF101114)
private val Panel = Color(0xFF1A1C21)
private val Accent = Color(0xFFFFB139)
private val Muted = Color(0xFF9CA3AF)

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("castbox", MODE_PRIVATE)
        val savedServers = runCatching {
            val legacy = prefs.getString("server", null)
            val savedJson = prefs.getString("servers", null) ?: if (!legacy.isNullOrBlank()) {
                JSONArray().put(JSONObject().put("name", "Media PC 1").put("url", legacy)).toString()
            } else "[]"
            val arr = JSONArray(savedJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").trim().trimEnd('/')
                if (url.startsWith("http://") || url.startsWith("https://")) MediaServer(o.optString("name", "Media PC").ifBlank { "Media PC" }, url) else null
            }
        }.getOrDefault(emptyList())
        setContent {
            var servers by remember { mutableStateOf(savedServers) }
            var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
            var selected by remember { mutableStateOf<Movie?>(null) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var isConnected by remember { mutableStateOf(false) }
            var manageSources by remember { mutableStateOf(true) }

            fun persistServers(updated: List<MediaServer>) {
                servers = updated
                val arr = JSONArray()
                updated.forEach { server -> arr.put(JSONObject().put("name", server.name).put("url", server.baseUrl)) }
                prefs.edit().putString("servers", arr.toString()).apply()
            }

            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Panel, primary = Accent,
                onBackground = Color.White, onSurface = Color.White
            )) {
                if (selected != null) {
                    PlaybackScreen(
                        movie = selected!!,
                        onBack = { player?.stop(); selected = null },
                        getPlayer = { url ->
                            player?.release()
                            ExoPlayer.Builder(this).build().also { p ->
                                player = p
                                p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                                p.prepare()
                                p.playWhenReady = true
                            }
                        }
                    )
                } else if (!isConnected || manageSources) {
                    ServerSourcesScreen(
                        servers = servers, loading = loading, error = error,
                        onAdd = { name, url ->
                            val normalized = url.trim().trimEnd('/')
                            val cleanName = name.trim().ifBlank { "Media PC ${servers.size + 1}" }
                            if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                                persistServers(servers.filterNot { it.baseUrl.equals(normalized, ignoreCase = true) } + MediaServer(cleanName, normalized))
                                error = ""
                            } else error = "Enter a full address, e.g. http://192.168.1.25:5078"
                        },
                        onRemove = { target -> persistServers(servers.filterNot { it.baseUrl == target.baseUrl }) },
                        onConnect = {
                            if (servers.isEmpty()) error = "Add at least one media PC first."
                            else {
                                loading = true; error = ""
                                lifecycleScope.launch {
                                    try {
                                        val results = withContext(Dispatchers.IO) {
                                            servers.map { source ->
                                                source to runCatching { fetchLibrary(source.baseUrl).map { movie ->
                                                    movie.copy(
                                                        id = "${source.baseUrl}|${movie.id}",
                                                        url = if (movie.url.startsWith("http://") || movie.url.startsWith("https://")) movie.url else source.baseUrl.trimEnd('/') + "/" + movie.url.trimStart('/')
                                                    )
                                                } }
                                            }
                                        }
                                        val good = results.filter { it.second.isSuccess }
                                        if (good.isEmpty()) {
                                            error = results.joinToString("\n") { (source, result) -> "${source.name}: ${result.exceptionOrNull()?.message ?: "Connection failed"}" }
                                        } else {
                                            movies = good.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
                                            val failed = results.filter { it.second.isFailure }
                                            error = if (failed.isNotEmpty()) "Connected to ${good.size} of ${servers.size} PCs. Unavailable: ${failed.joinToString { it.first.name }}." else ""
                                            isConnected = true; manageSources = false
                                        }
                                    } finally { loading = false }
                                }
                            }
                        },
                        onBackToLibrary = { if (isConnected) manageSources = false }
                    )
                } else {
                    LibraryScreen(
                        server = servers.joinToString(" · ") { it.name }, movies = movies,
                        loading = loading, error = error,
                        onBackToConnect = { manageSources = true },
                        onRefresh = {
                            loading = true; error = ""
                            lifecycleScope.launch {
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        servers.map { source ->
                                            source to runCatching { fetchLibrary(source.baseUrl).map { movie ->
                                                movie.copy(
                                                    id = "${source.baseUrl}|${movie.id}",
                                                    url = if (movie.url.startsWith("http://") || movie.url.startsWith("https://")) movie.url else source.baseUrl.trimEnd('/') + "/" + movie.url.trimStart('/')
                                                )
                                            } }
                                        }
                                    }
                                    val good = results.filter { it.second.isSuccess }
                                    movies = good.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
                                    val failed = results.filter { it.second.isFailure }
                                    error = if (failed.isNotEmpty()) "Unavailable sources: ${failed.joinToString { it.first.name }}" else if (movies.isEmpty()) "No videos found. Check the PCs and media folders." else ""
                                } catch (e: Exception) { error = "Refresh failed: ${e.message ?: "check network"}" }
                                finally { loading = false }
                            }
                        },
                        onPlay = { selected = it }
                    )
                }
            }
        }
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }
}

private fun fetchLibrary(base: String): List<Movie> {
    val url = URL(base.trimEnd('/') + "/api/library")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 15000
        requestMethod = "GET"
    }
    try {
        if (connection.responseCode !in 200..299) error("Server returned HTTP ${connection.responseCode}")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title", o.optString("fileName", "Untitled"))
            val ext = o.optString("extension", "").lowercase()
            if (ext !in setOf("mp4", "m4v", "mov", "mkv", "avi", "webm", "mpeg", "mpg", "ts")) return@mapNotNull null
            Movie(
                id = o.optString("id"), title = title,
                fileName = o.optString("fileName", title), extension = ext,
                sizeBytes = o.optLong("sizeBytes", 0L), url = o.optString("url")
            ).takeIf { it.url.startsWith("http://") || it.url.startsWith("https://") }
        }.sortedBy { it.title.lowercase() }
    } finally { connection.disconnect() }
}

@Composable
private fun ServerSourcesScreen(
    servers: List<MediaServer>, loading: Boolean, error: String,
    onAdd: (String, String) -> Unit, onRemove: (MediaServer) -> Unit,
    onConnect: () -> Unit, onBackToLibrary: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CASTBOX", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Text("Your media PCs", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Add each Windows PC once. CastBox combines their libraries and streams each movie from its source PC.", color = Muted, fontSize = 16.sp)
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 850.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a media server", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("PC name (e.g. Living Room PC)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.25:5078") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onAdd(name, address); if (address.trim().startsWith("http")) { name = ""; address = "" } }) { Text("Add PC") }
                    Button(onClick = onConnect, enabled = !loading && servers.isNotEmpty()) {
                        if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black); Spacer(Modifier.width(8.dp)) }
                        Text(if (loading) "Connecting…" else "Connect to library")
                    }
                    if (servers.isNotEmpty()) OutlinedButton(onClick = onBackToLibrary) { Text("Cancel") }
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
            }
        }
        Text("Configured PCs (${servers.size})", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (servers.isEmpty()) Text("No PCs added yet.", color = Muted)
        servers.forEach { server ->
            Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 850.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(server.baseUrl, color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = { onRemove(server) }) { Text("Remove", color = Color(0xFFFFB4A8)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(
    server: String, loading: Boolean, error: String,
    onServerChange: (String) -> Unit, onConnect: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("CASTBOX", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(10.dp))
        Text("Connect to your media PC", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Enter the Windows PC's address to browse and play its videos on this TV.", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Media server address", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    singleLine = true,
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.50:5078") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Example: http://192.168.1.50:5078", color = Muted, fontSize = 13.sp)
                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
                }
                Button(onClick = onConnect, enabled = !loading && server.isNotBlank(), modifier = Modifier.height(52.dp)) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (loading) "Connecting…" else "Connect to library")
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    server: String, movies: List<Movie>, loading: Boolean, error: String,
    onBackToConnect: () -> Unit, onRefresh: () -> Unit, onPlay: (Movie) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(movies, query) {
        movies.filter { it.title.contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 42.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CASTBOX", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Your movie library", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("${movies.size} videos  •  $server", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvActionButton("Refresh", onClick = onRefresh, enabled = !loading)
            Spacer(Modifier.width(10.dp))
            TvActionButton("Change server", onClick = onBackToConnect)
        }
        Spacer(Modifier.height(16.dp))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A8), modifier = Modifier.padding(bottom = 10.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search movies") }, modifier = Modifier.fillMaxWidth(0.62f))
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching videos", fontSize = 22.sp) }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { movie -> MovieRow(movie = movie, onClick = { onPlay(movie) }) }
            }
        }
    }
}

@Composable
private fun TvActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Accent else Color(0xFF292B31),
            contentColor = if (focused) Color.Black else Color.White
        ),
        modifier = Modifier.onFocusChangedCompat { focused = it }.border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) Color.White else Color(0xFF454852),
            shape = RoundedCornerShape(50)
        )
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun MovieRow(movie: Movie, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (focused) Color(0xFF33302A) else Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().onFocusChangedCompat { focused = it }
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 70.dp).background(Color(0xFF292B31), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(movie.extension.uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${movie.fileName}  •  ${formatBytes(movie.sizeBytes)}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("PLAY  ›", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun Modifier.onFocusChangedCompat(onChanged: (Boolean) -> Unit): Modifier = this.onFocusChanged {
    onChanged(it.isFocused)
}

@OptIn(UnstableApi::class)
@Composable
private fun PlaybackScreen(movie: Movie, onBack: () -> Unit, getPlayer: (String) -> ExoPlayer) {
    val player = remember(movie.url) { getPlayer(movie.url) }
    val context = LocalContext.current
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Playback error: ${error.errorCodeName}. ${error.message ?: "Check whether this video or audio format is supported."}"
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) playbackError = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            playerView?.player = null
            // The Activity owns the player lifecycle and also releases it when leaving playback.
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 5000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                    })
                    playerView = this
                    requestFocus()
                }
            },
            update = { view ->
                playerView = view
                if (view.player !== player) view.player = player
                view.useController = true
            },
            modifier = Modifier.fillMaxSize().background(Color.Black)
        )

        // These actions follow the native player's controller visibility, so they don't
        // permanently cover the movie. The native controller supplies seek/play/pause.
        if (controlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvActionButton("Audio", onClick = {
                    val activity = context as? Activity
                    if (activity != null) TrackSelectionDialogBuilder(activity, "Audio track", player, C.TRACK_TYPE_AUDIO).build().show()
                })
                TvActionButton("Subtitles", onClick = {
                    val activity = context as? Activity
                    if (activity != null) TrackSelectionDialogBuilder(activity, "Subtitles", player, C.TRACK_TYPE_TEXT)
                        .setShowDisableOption(true).build().show()
                })
            }
        }

        if (playbackError != null) {
            Surface(
                color = Color(0xEE3A2520),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp)
            ) {
                Text(
                    text = playbackError!!,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(bytes / 1024.0)
}
