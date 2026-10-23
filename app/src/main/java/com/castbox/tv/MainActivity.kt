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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val url: String,
    /** Folder names from the library root down to the file's directory. */
    val folderPath: List<String> = emptyList()
)

/** One folder in the browsable library tree; [movies] are the files directly inside it. */
class FolderNode(val name: String) {
    val children = java.util.TreeMap<String, FolderNode>(String.CASE_INSENSITIVE_ORDER)
    val movies = mutableListOf<Movie>()
    var totalMovies = 0
}

private fun buildFolderTree(movies: List<Movie>): FolderNode {
    val root = FolderNode("")
    for (movie in movies) {
        var node = root
        node.totalMovies++
        for (segment in movie.folderPath) {
            node = node.children.getOrPut(segment) { FolderNode(segment) }
            node.totalMovies++
        }
        node.movies += movie
    }
    return root
}

/** Follows [path] from [root]; stops at the deepest folder that exists. */
private fun FolderNode.resolve(path: List<String>): FolderNode {
    var node = this
    for (segment in path) node = node.children[segment] ?: return node
    return node
}

/** A displayed sub-folder: single-child chains with no files are shown as one entry. */
private class FolderEntry(val label: String, val segments: List<String>, val node: FolderNode)

private fun FolderNode.entries(): List<FolderEntry> = children.values.map { child ->
    var node = child
    val segments = mutableListOf(child.name)
    while (node.movies.isEmpty() && node.children.size == 1) {
        node = node.children.values.first()
        segments += node.name
    }
    FolderEntry(segments.joinToString("  ›  "), segments, node)
}

/** Skips straight into the only folder when a level has nothing else to show. */
private fun FolderNode.initialPath(): List<String> {
    val path = mutableListOf<String>()
    var node = this
    while (node.movies.isEmpty() && node.children.size == 1) {
        node = node.children.values.first()
        path += node.name
    }
    return path
}

private val Bg = Color(0xFF101114)
private val Panel = Color(0xFF1A1C21)
private val Accent = Color(0xFFFFB139)
private val Muted = Color(0xFF9CA3AF)

/**
 * Adds FFmpeg software audio decoding after the device's own decoders. Many TVs/dongles (including the
 * Chromecast with Google TV) can't decode E-AC-3 or output it as passthrough, which left those files silent.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class CastBoxRenderersFactory(context: android.content.Context) : DefaultRenderersFactory(context) {
    init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF) }

    override fun buildAudioRenderers(
        context: android.content.Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

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
            var tree by remember { mutableStateOf(FolderNode("")) }
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
              // Text without an explicit colour would otherwise default to black on the dark background.
              Surface(modifier = Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) {
                if (selected != null) {
                    PlaybackScreen(
                        movie = selected!!,
                        onBack = { player?.stop(); selected = null },
                        getPlayer = { url ->
                            player?.release()
                            ExoPlayer.Builder(this, CastBoxRenderersFactory(this)).build().also { p ->
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
                                        val result = loadLibraries(servers)
                                        if (!result.anySuccess) {
                                            error = result.errors
                                        } else {
                                            movies = result.movies; tree = result.tree
                                            error = if (result.failed.isNotEmpty()) "Connected to ${servers.size - result.failed.size} of ${servers.size} PCs. Unavailable: ${result.failed.joinToString { it.name }}." else ""
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
                        server = servers.joinToString(" · ") { it.name }, movies = movies, tree = tree,
                        loading = loading, error = error,
                        onBackToConnect = { manageSources = true },
                        onRefresh = {
                            loading = true; error = ""
                            lifecycleScope.launch {
                                try {
                                    val result = loadLibraries(servers)
                                    movies = result.movies; tree = result.tree
                                    error = if (result.failed.isNotEmpty()) "Unavailable sources: ${result.failed.joinToString { it.name }}" else if (movies.isEmpty()) "No videos found. Check the PCs and media folders." else ""
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
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }
}

private class LibraryResult(
    val movies: List<Movie>, val tree: FolderNode,
    val failed: List<MediaServer>, val errors: String, val anySuccess: Boolean
)

/** Fetches every server at the same time so one offline PC doesn't delay the others. */
private suspend fun loadLibraries(servers: List<MediaServer>): LibraryResult = coroutineScope {
    val results = servers.map { source ->
        async(Dispatchers.IO) {
            source to runCatching {
                fetchLibrary(source.baseUrl).map { movie ->
                    movie.copy(
                        id = "${source.baseUrl}|${movie.id}",
                        url = if (movie.url.startsWith("http://") || movie.url.startsWith("https://")) movie.url else source.baseUrl.trimEnd('/') + "/" + movie.url.trimStart('/'),
                        folderPath = if (servers.size > 1) listOf(source.name) + movie.folderPath else movie.folderPath
                    )
                }
            }
        }
    }.awaitAll()
    withContext(Dispatchers.Default) {
        val good = results.filter { it.second.isSuccess }
        val failed = results.filter { it.second.isFailure }
        val movies = good.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
        LibraryResult(
            movies = movies, tree = buildFolderTree(movies),
            failed = failed.map { it.first },
            errors = failed.joinToString("\n") { (source, result) -> "${source.name}: ${result.exceptionOrNull()?.message ?: "Connection failed"}" },
            anySuccess = good.isNotEmpty()
        )
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
            // Newer servers send rootName + relativeFolder; older ones only the absolute folder path.
            val folderPath = if (o.has("rootName")) {
                listOf(o.optString("rootName")).filter { it.isNotBlank() } +
                    o.optString("relativeFolder").split('/', '\\').filter { it.isNotBlank() }
            } else o.optString("folder").split('/', '\\').filter { it.isNotBlank() }
            Movie(
                id = o.optString("id"), title = title,
                fileName = o.optString("fileName", title), extension = ext,
                sizeBytes = o.optLong("sizeBytes", 0L), url = o.optString("url"),
                folderPath = folderPath
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
    var showAddDialog by remember { mutableStateOf(false) }
    val primaryAction = remember { FocusRequester() }
    // Start on the main button; the text fields live in a dialog so they never grab focus or the keyboard.
    LaunchedEffect(Unit) { runCatching { primaryAction.requestFocus() } }
    if (showAddDialog) {
        AddServerDialog(
            error = error,
            onDismiss = { showAddDialog = false },
            onAdd = { name, address ->
                onAdd(name, address)
                if (address.trim().startsWith("http")) showAddDialog = false
            }
        )
    }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CASTBOX", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Text("Your media PCs", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Add each Windows PC once. CastBox combines their libraries and streams each movie from its source PC.", color = Muted, fontSize = 16.sp)
        Button(
            onClick = onConnect,
            enabled = !loading && servers.isNotEmpty(),
            modifier = Modifier.height(54.dp).focusRequester(primaryAction)
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) "Connecting to unified library…" else "Open unified library", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (servers.isEmpty()) {
            Text("Add a PC to get started. Once at least one PC is configured, the button above opens the unified library.", color = Muted, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showAddDialog = true }) { Text("Add PC") }
            if (servers.isNotEmpty()) OutlinedButton(onClick = onBackToLibrary) { Text("Cancel") }
        }
        if (error.isNotBlank() && !showAddDialog) Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
        Text("Configured PCs (${servers.size})", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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

/** Name + URL entry in a dialog, so the on-screen keyboard only appears when the user chooses to add a PC. */
@Composable
private fun AddServerDialog(error: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a media PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("PC name (e.g. Living Room PC)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.25:5078") }, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, address) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Search text entry in a dialog; the library list keeps focus until the user asks to search. */
@Composable
private fun SearchDialog(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Search videos") },
        text = {
            OutlinedTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onClose() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field)
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onQueryChange(""); onClose() }) { Text("Clear") } }
    )
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
    server: String, movies: List<Movie>, tree: FolderNode, loading: Boolean, error: String,
    onBackToConnect: () -> Unit, onRefresh: () -> Unit, onPlay: (Movie) -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Each entry is the folder path (from the library root) the user has opened; the last one is on screen.
    var stack by remember(tree) { mutableStateOf(listOf(tree.initialPath())) }
    val path = stack.last()
    val current = remember(tree, path) { tree.resolve(path) }
    val folders = remember(current) { current.entries() }
    val searching = query.isNotBlank()
    val filtered = remember(movies, query) {
        if (!searching) emptyList()
        else movies.filter { it.title.contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) }
    }
    var searchOpen by remember { mutableStateOf(false) }
    val firstItem = remember { FocusRequester() }

    if (searchOpen) SearchDialog(query = query, onQueryChange = { query = it }, onClose = { searchOpen = false })
    // Back clears an active search first, then climbs out of folders.
    BackHandler(enabled = searching || stack.size > 1) { if (searching) query = "" else stack = stack.dropLast(1) }
    // Rows from the previous folder leave composition, so hand focus to the first row of the new one.
    LaunchedEffect(tree, path, searching, loading) {
        if (!loading) runCatching { firstItem.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 42.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CASTBOX", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Your movie library", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                val where = if (searching) "Search results" else if (path.isEmpty()) server else path.joinToString("  ›  ")
                Text("${movies.size} videos  •  $where", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvActionButton(if (searching) "Search: $query" else "Search", onClick = { searchOpen = true })
            Spacer(Modifier.width(10.dp))
            TvActionButton("Refresh", onClick = onRefresh, enabled = !loading)
            Spacer(Modifier.width(10.dp))
            TvActionButton("Change server", onClick = onBackToConnect)
        }
        Spacer(Modifier.height(16.dp))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A8), modifier = Modifier.padding(bottom = 10.dp))
        val empty = if (searching) filtered.isEmpty() else folders.isEmpty() && current.movies.isEmpty()
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching videos", fontSize = 22.sp, color = Color.White) }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (searching) {
                    items(filtered, key = { it.id }) { movie ->
                        MovieRow(movie = movie, onClick = { onPlay(movie) }, modifier = if (movie === filtered.first()) Modifier.focusRequester(firstItem) else Modifier)
                    }
                } else {
                    items(folders, key = { "folder:" + it.segments.joinToString("/") }) { entry ->
                        FolderRow(
                            label = entry.label, count = entry.node.totalMovies,
                            modifier = if (entry === folders.first()) Modifier.focusRequester(firstItem) else Modifier,
                            onClick = { stack = stack + listOf(path + entry.segments) }
                        )
                    }
                    items(current.movies, key = { it.id }) { movie ->
                        MovieRow(
                            movie = movie, onClick = { onPlay(movie) },
                            modifier = if (folders.isEmpty() && movie === current.movies.first()) Modifier.focusRequester(firstItem) else Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(label: String, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (focused) Color(0xFF33302A) else Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().onFocusChangedCompat { focused = it }
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 56.dp).background(Color(0xFF292B31), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("DIR", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (count == 1) "1 video" else "$count videos", color = Muted, fontSize = 12.sp)
            }
            Text("OPEN  ›", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
private fun MovieRow(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (focused) Color(0xFF33302A) else Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().onFocusChangedCompat { focused = it }
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


private const val CONTROLLER_TIMEOUT_MS = 5000

/**
 * Plays [movie] with the stock Media3 controller (seek bar, play/pause, subtitle and settings buttons),
 * which is built for D-pad use. The PlayerView must hold focus or the remote's keys never reach it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
@Composable
private fun PlaybackScreen(movie: Movie, onBack: () -> Unit, getPlayer: (String) -> ExoPlayer) {
    val player = remember(movie.url) { getPlayer(movie.url) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
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

    // A plain View.requestFocus() is ignored while the Compose host holds focus, so go through Compose's
    // focus system, which hands focus to the embedded PlayerView.
    val playerFocus = remember { FocusRequester() }
    LaunchedEffect(playerView) {
        val view = playerView ?: return@LaunchedEffect
        runCatching { playerFocus.requestFocus() }
        view.post { view.requestFocus(); view.showController() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            // Safety net: if the PlayerView still lacks focus, give it the key so the controller can appear.
            val view = playerView
            if (view != null && !view.hasFocus() && event.type == KeyEventType.KeyDown && event.key != Key.Back) {
                view.dispatchKeyEvent(event.nativeKeyEvent)
            } else false
        }
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
                    setShowSubtitleButton(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    playerView = this
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.useController = true
            },
            modifier = Modifier.fillMaxSize().background(Color.Black).focusRequester(playerFocus)
        )

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
