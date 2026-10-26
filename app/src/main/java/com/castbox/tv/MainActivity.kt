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
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ConnectedTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** [serverId] is the PC's permanent ID from /api/health (servers 0.1.6+); blank for older servers. */
data class MediaServer(val name: String, val baseUrl: String, val serverId: String = "")

data class Movie(
    val id: String,
    val title: String,
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val url: String,
    /** Folder names from the library root down to the file's directory. */
    val folderPath: List<String> = emptyList(),
    /** Identifies the file for watched/resume state; uses the PC's permanent ID so it survives IP changes. */
    val watchKey: String = ""
)

/** One folder in the browsable library tree; [movies] are the files directly inside it. */
class FolderNode(val name: String) {
    val children = java.util.TreeMap<String, FolderNode>(String.CASE_INSENSITIVE_ORDER)
    val movies = mutableListOf<Movie>()
    var totalMovies = 0

    fun allMovies(): List<Movie> = movies + children.values.flatMap { it.allMovies() }
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
    private var watchStore: WatchStore? = null
    private var playingKey: String = ""
    private var playingMovie: Movie? = null
    private var reporter: WatchReporter? = null
    private var whoChoice: WhoChoice? = null
    @Volatile private var accounts: List<MediaAccount> = emptyList()
    @Volatile private var knownServers: List<MediaServer> = emptyList()

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("castbox", MODE_PRIVATE)
        val watch = WatchStore(prefs).also { watchStore = it }
        val posters = PosterRepo(prefs)
        val me = TvIdentity(prefs, this)
        whoChoice = WhoChoice(prefs)
        val who = whoChoice!!
        val report = WatchReporter(prefs, lifecycleScope) { id -> knownServers.firstOrNull { it.serverId == id }?.baseUrl }.also { reporter = it }
        val savedServers = runCatching {
            val legacy = prefs.getString("server", null)
            val savedJson = prefs.getString("servers", null) ?: if (!legacy.isNullOrBlank()) {
                JSONArray().put(JSONObject().put("name", "Media PC 1").put("url", legacy)).toString()
            } else "[]"
            val arr = JSONArray(savedJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").trim().trimEnd('/')
                if (url.startsWith("http://") || url.startsWith("https://")) MediaServer(o.optString("name", "Media PC").ifBlank { "Media PC" }, url, o.optString("id")) else null
            }
        }.getOrDefault(emptyList())
        knownServers = savedServers
        report.flushSoon()
        setContent {
            var servers by remember { mutableStateOf(savedServers) }
            var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
            var tree by remember { mutableStateOf(FolderNode("")) }
            var selected by remember { mutableStateOf<Movie?>(null) }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var isConnected by remember { mutableStateOf(false) }
            var manageSources by remember { mutableStateOf(true) }
            // Library navigation lives here so it survives playing a video.
            var searchQuery by remember { mutableStateOf("") }
            var lastPlayedId by remember { mutableStateOf("") }
            var openFolders by remember { mutableStateOf<List<List<String>>>(emptyList()) }
            var accountList by remember { mutableStateOf<List<MediaAccount>>(emptyList()) }
            // Casting a PC's screen to this TV: picking a screen, waiting for the PC's yes, watching.
            var cast by remember { mutableStateOf<CastState?>(null) }
            // "home" (movies or personal), "library" or "personal"; only PCs with personal folders make a home screen.
            var section by remember { mutableStateOf("library") }
            var personalSummary by remember { mutableStateOf(PersonalSummary(0, 0, emptyList())) }
            val personalStates = remember { PersonalStates() }
            var personalOpened by remember { mutableStateOf<PersonalLocation?>(null) }
            var lastOpenedPersonal by remember { mutableStateOf("") }
            var viewerFolder by remember { mutableStateOf<PersonalFolderState?>(null) }
            var viewerStartId by remember { mutableStateOf("") }
            var askWho by remember { mutableStateOf<Movie?>(null) }

            fun persistServers(updated: List<MediaServer>) {
                servers = updated
                knownServers = updated
                val arr = JSONArray()
                updated.forEach { server -> arr.put(JSONObject().put("name", server.name).put("url", server.baseUrl).put("id", server.serverId)) }
                prefs.edit().putString("servers", arr.toString()).apply()
            }

            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Panel, primary = Accent,
                onBackground = Color.White, onSurface = Color.White
            )) {
              // Text without an explicit colour would otherwise default to black on the dark background.
              // From API 35 apps draw edge to edge; keep the content clear of any system bars (a TV has none).
              Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = Bg, contentColor = Color.White) {
                if (cast is CastState.Watching) {
                    val watching = cast as CastState.Watching
                    CastPlayerScreen(url = watching.url, title = watching.title, onClose = { cast = null })
                } else if (selected != null) {
                    PlaybackScreen(
                        movie = selected!!,
                        watch = watch, onCompleted = { reportCompleted(it) },
                        onBack = { player?.stop(); playingKey = ""; playingMovie = null; selected = null },
                        getPlayer = { movie ->
                            player?.release()
                            ExoPlayer.Builder(this, CastBoxRenderersFactory(this)).build().also { p ->
                                player = p
                                playingKey = movie.watchKey
                                playingMovie = movie
                                // Pick up where the user left off if they backed out earlier.
                                p.setMediaItem(MediaItem.fromUri(Uri.parse(movie.url)), watch.resumePosition(movie.watchKey))
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
                                        if (result.servers != servers) persistServers(result.servers)
                                        if (!result.anySuccess) {
                                            error = listOf(result.errors, result.notes).filter { it.isNotBlank() }.joinToString("\n")
                                        } else {
                                            movies = result.movies; tree = result.tree
                                            accounts = result.accounts; accountList = result.accounts
                                            personalStates.clear(); personalOpened = null
                                            personalSummary = result.personal
                                            section = if (!result.personal.isEmpty) "home" else "library"
                                            openFolders = emptyList(); searchQuery = ""
                                            val unavailable = if (result.failed.isNotEmpty()) "Connected to ${servers.size - result.failed.size} of ${servers.size} PCs. Unavailable: ${result.failed.joinToString { it.name }}." else ""
                                            error = listOf(unavailable, result.notes).filter { it.isNotBlank() }.joinToString(" ")
                                            isConnected = true; manageSources = false
                                        }
                                    } finally { loading = false }
                                }
                            }
                        },
                        onBackToLibrary = if (isConnected) ({ manageSources = false }) else null,
                        onCast = { cast = CastState.Picking(it) }
                    )
                } else if (viewerFolder != null) {
                    PhotoViewer(
                        state = viewerFolder!!, startId = viewerStartId,
                        onClose = { viewerFolder = null },
                        onShown = { lastOpenedPersonal = it.id }
                    )
                } else if (section == "home") {
                    HomeScreen(
                        movieCount = movies.size,
                        pictureCount = personalSummary.pictures, videoCount = personalSummary.videos,
                        onMovies = { section = "library" }, onPersonal = { section = "personal" },
                        onChangeServer = { manageSources = true }
                    )
                } else if (section == "personal") {
                    PersonalScreen(
                        summary = personalSummary, location = effectiveLocation(personalSummary, personalOpened), states = personalStates,
                        onOpen = { personalOpened = it },
                        lastOpenedId = lastOpenedPersonal, onBackHome = { personalOpened = null; section = "home" },
                        onViewImage = { folder, item -> lastOpenedPersonal = item.id; viewerStartId = item.id; viewerFolder = folder },
                        onPlayVideo = { lastOpenedPersonal = it.id; selected = it.asMovie() }
                    )
                } else {
                    LibraryScreen(
                        server = servers.joinToString(" · ") { it.name }, movies = movies, tree = tree,
                        watch = watch, posters = posters, onCompleted = { reportCompleted(it) },
                        query = searchQuery, onQueryChange = { searchQuery = it },
                        openFolders = openFolders, onOpenFoldersChange = { openFolders = it },
                        lastPlayedId = lastPlayedId,
                        loading = loading, error = error,
                        onBackToConnect = { manageSources = true },
                        onHome = if (!personalSummary.isEmpty) ({ section = "home" }) else null,
                        onRefresh = {
                            loading = true; error = ""
                            lifecycleScope.launch {
                                try {
                                    val result = loadLibraries(servers)
                                    if (result.servers != servers) persistServers(result.servers)
                                    movies = result.movies; tree = result.tree
                                    accounts = result.accounts; accountList = result.accounts
                                    personalStates.clear(); personalSummary = result.personal
                                    if (result.personal.isEmpty && section != "library") section = "library"
                                    val problem = if (result.failed.isNotEmpty()) "Unavailable sources: ${result.failed.joinToString { it.name }}" else if (movies.isEmpty()) "No videos found. Check the PCs and media folders." else ""
                                    error = listOf(problem, result.notes).filter { it.isNotBlank() }.joinToString(" ")
                                } catch (e: Exception) { error = "Refresh failed: ${e.message ?: "check network"}" }
                                finally { loading = false }
                            }
                        },
                        // With two or more different MediaLog accounts, ask who to track this for before starting.
                        onPlay = { lastPlayedId = it.id; if (accountList.size >= 2) askWho = it else selected = it }
                    )
                    askWho?.let { pending ->
                        WhoIsWatchingDialog(
                            accounts = accountList, last = who.last(),
                            onChoose = { ids -> who.save(ids); askWho = null; selected = pending },
                            onCancel = { askWho = null }
                        )
                    }
                }
                CastDialogs(cast, me) { cast = it }
              }
            }
            // A PC can send one of its screens to this TV ("Share screen" in the server window): ask every PC now and then
            // while the app is on screen and nothing is playing.
            LaunchedEffect(servers, cast == null, selected == null) {
                if (cast != null || selected != null || servers.isEmpty()) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    while (true) {
                        var offered = false
                        for (server in servers) {
                            val offer = withContext(Dispatchers.IO) { fetchCastOffer(server, me, if (servers.size == 1) 20 else 4) }
                            if (offer != null) { cast = CastState.Watching(offer.streamUrl, "${offer.screenName} of ${offer.pcName}"); offered = true; break }
                        }
                        if (!offered) delay(500)
                    }
                }
            }
        }
    }

    /** Sends a finished video to the MediaLog account(s) chosen in "Who's watching?" (see planTargets). */
    private fun reportCompleted(movie: Movie) {
        if (movie.watchKey.startsWith(PERSONAL_PREFIX)) return // personal videos are nobody's MediaLog business
        val plan = planTargets(accounts, whoChoice?.last(), movie.id.substringBefore('|', ""))
        reporter?.completed(movie, plan)
    }

    override fun onStop() {
        // Save progress before the player is released (the app can be left mid-video via Home or sleep).
        player?.let { p ->
            val finished = watchStore?.save(playingKey, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L) == true
            if (finished) playingMovie?.let { reportCompleted(it) }
        }
        player?.release()
        player = null
        super.onStop()
    }
}

private class LibraryResult(
    val movies: List<Movie>, val tree: FolderNode,
    val failed: List<MediaServer>, val errors: String, val anySuccess: Boolean,
    /** The servers as they should be saved: addresses and IDs may have been updated. */
    val servers: List<MediaServer>, val notes: String,
    val accounts: List<MediaAccount> = emptyList(),
    val personal: PersonalSummary = PersonalSummary(0, 0, emptyList())
)

/** Fetches every server at the same time so one offline PC doesn't delay the others. */
private suspend fun loadLibraries(servers: List<MediaServer>): LibraryResult = coroutineScope {
    val accountOf = java.util.concurrent.ConcurrentHashMap<String, PcAccount>()
    val personalOf = java.util.concurrent.ConcurrentHashMap<String, PersonalSummary>()
    val results = servers.map { original ->
        async(Dispatchers.IO) {
            val (resolved, note) = resolveServer(original)
            val raw = runCatching { fetchLibrary(resolved.baseUrl) }
            // Remember the PC's ID the first time we reach it so it can be found again if its address changes.
            var learned = resolved
            val account = if (raw.isSuccess) fetchPcAccount(learned.baseUrl) else null
            if (raw.isSuccess && learned.serverId.isBlank()) fetchServerId(learned.baseUrl)?.let { learned = learned.copy(serverId = it) }
            val source = learned
            // Personal folders (pictures and videos) are listed separately; PCs without any, or older servers, add nothing.
            if (raw.isSuccess && fetchPersonalFolderCount(source.baseUrl) > 0) {
                runCatching { fetchPersonalRoots(source, servers.size > 1) }.getOrNull()?.let { personalOf[source.baseUrl] = it }
            }
            val identity = source.serverId.ifBlank { source.baseUrl }
            val library = raw.map { movies ->
                movies.map { movie ->
                    movie.copy(
                        id = "${source.baseUrl}|${movie.id}",
                        url = if (movie.url.startsWith("http://") || movie.url.startsWith("https://")) movie.url else source.baseUrl.trimEnd('/') + "/" + movie.url.trimStart('/'),
                        folderPath = if (servers.size > 1) listOf(source.name) + movie.folderPath else movie.folderPath,
                        watchKey = "$identity|${movie.id}"
                    )
                }
            }
            // ConcurrentHashMap rejects null values: a PC without an account simply has no entry.
            if (account != null) accountOf[source.baseUrl] = account
            Triple(source, library, note)
        }
    }.awaitAll()
    withContext(Dispatchers.Default) {
        val good = results.filter { it.second.isSuccess }
        val failed = results.filter { it.second.isFailure }
        val movies = good.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.url }.sortedBy { it.title.lowercase() }
        LibraryResult(
            movies = movies, tree = buildFolderTree(movies),
            failed = failed.map { it.first },
            errors = failed.joinToString("\n") { (source, result, _) -> "${source.name}: ${result.exceptionOrNull()?.message ?: "Connection failed"}" },
            anySuccess = good.isNotEmpty(),
            servers = results.map { it.first },
            notes = results.map { it.third }.filter { it.isNotBlank() }.joinToString(" "),
            accounts = groupAccounts(results.map { (source, _, _) -> source to accountOf[source.baseUrl] }),
            personal = results.mapNotNull { (source, _, _) -> personalOf[source.baseUrl] }.let { all ->
                PersonalSummary(all.sumOf { it.pictures }, all.sumOf { it.videos }, all.flatMap { it.roots })
            }
        )
    }
}

/** The server's permanent ID from /api/health, or null if unreachable or the server predates IDs. */
private fun fetchServerId(base: String, connectTimeoutMs: Int = 1500): String? = runCatching {
    val connection = (URL(base.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = 2000
    }
    try {
        if (connection.responseCode !in 200..299) null
        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("serverId").ifBlank { null }
    } finally { connection.disconnect() }
}.getOrNull()

/**
 * Makes sure [source]'s saved address still points at that PC. If the PC's ID doesn't answer there (its
 * network address changed), scans the local network for it and returns the source with the new address.
 */
private fun resolveServer(source: MediaServer): Pair<MediaServer, String> {
    if (source.serverId.isBlank()) return source to ""
    if (fetchServerId(source.baseUrl, 2000) == source.serverId) return source to ""
    val port = runCatching { URL(source.baseUrl).port.takeIf { it > 0 } }.getOrNull() ?: 5078
    val moved = findServerOnLan(source.serverId, port) ?: return source to ""
    return source.copy(baseUrl = moved) to "${source.name} moved; saved address updated to $moved."
}

/** The first three octets of this device's LAN address, e.g. "192.168.1.", or null if offline. */
private fun localSubnetPrefix(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress?.substringBeforeLast('.')?.plus(".")
}.getOrNull()

/** Probes every address on the local /24 for a NestCast server with [serverId]; returns its base URL. */
private fun findServerOnLan(serverId: String, port: Int): String? {
    val prefix = localSubnetPrefix() ?: return null
    val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
    try {
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val tasks = (1..254).map { host ->
            java.util.concurrent.Callable {
                if (found.get() == null) {
                    val base = "http://$prefix$host:$port"
                    if (fetchServerId(base, 600) == serverId) found.compareAndSet(null, base)
                }
            }
        }
        pool.invokeAll(tasks)
        return found.get()
    } finally { pool.shutdownNow() }
}

private fun fetchLibrary(base: String): List<Movie> {
    val url = URL(base.trimEnd('/') + "/api/library")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000
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
    onConnect: () -> Unit, onBackToLibrary: (() -> Unit)?, onCast: (MediaServer) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val primaryAction = remember { FocusRequester() }
    if (showSupport) SupportDialog(onClose = { showSupport = false })
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
    Box(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 880.dp).padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("NESTCAST", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
            Text("Your media, on the big screen", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Watch movies and shows, browse your personal photos and videos, or cast your PC screen to your TV.", color = Muted, fontSize = 16.sp)
            if (servers.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ConnectedTv, contentDescription = null, tint = Color.White, modifier = Modifier.padding(top = 2.dp).size(26.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Connect your media PCs", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                        Text("Add a Windows PC to access its media. Connect multiple PCs to bring their libraries together.", color = Muted, fontSize = 14.sp)
                    }
                }
                Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth().height(54.dp).focusRequester(primaryAction)) {
                    Text("+ Add a PC", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(onClick = onConnect, enabled = !loading, modifier = Modifier.fillMaxWidth().height(54.dp).focusRequester(primaryAction)) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loading) "Connecting to unified library…" else "Open unified library", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f).height(48.dp)) { Text("+ Add a PC") }
                    if (onBackToLibrary != null) OutlinedButton(onClick = onBackToLibrary, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                }
            }
            OutlinedButton(onClick = { showSupport = true }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Outlined.Coffee, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Support NestCast", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (error.isNotBlank() && !showAddDialog) Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
            HorizontalDivider(color = Color(0xFF2A2D34))
            Text("Connected PCs (${servers.size})", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (servers.isEmpty()) Text("No PCs connected yet. Add a Windows PC to get started.", color = Muted)
            servers.forEach { server ->
                Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(server.baseUrl, color = Muted, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { onCast(server) }) { Text("Cast screen") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onRemove(server) }) { Text("Remove", color = Color(0xFFFFB4A8)) }
                    }
                }
            }
        }
    }
}

/** Name + URL entry in a dialog, so the on-screen keyboard only appears when the user chooses to add a PC. */
@Composable
private fun AddServerDialog(error: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Pre-filled with this TV's own network and the default port, with the last two digits of the PC's address selected,
    // so typing replaces just those.
    var address by remember {
        val head = "http://${localSubnetPrefix() ?: "192.168.1."}"
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("${head}25:5078", androidx.compose.ui.text.TextRange(head.length, head.length + 2)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a media PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("PC name (e.g. Living Room PC)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A8), fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, address.text) }) { Text("Add") } },
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
        Text("NESTCAST", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
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
    server: String, movies: List<Movie>, tree: FolderNode, watch: WatchStore, posters: PosterRepo, onCompleted: (Movie) -> Unit,
    query: String, onQueryChange: (String) -> Unit,
    openFolders: List<List<String>>, onOpenFoldersChange: (List<List<String>>) -> Unit,
    lastPlayedId: String,
    loading: Boolean, error: String,
    onBackToConnect: () -> Unit, onHome: (() -> Unit)?, onRefresh: () -> Unit, onPlay: (Movie) -> Unit
) {
    // Each entry is the folder path (from the library root) the user has opened; the last one is on screen.
    // The state lives in the Activity so it survives watching a video (this screen leaves composition).
    val stack = openFolders.ifEmpty { listOf(tree.initialPath()) }
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

    if (searchOpen) SearchDialog(query = query, onQueryChange = onQueryChange, onClose = { searchOpen = false })
    // Back clears an active search first, then climbs out of folders.
    var returnedFrom by remember { mutableStateOf<List<String>?>(null) } // folder just left, to refocus it
    BackHandler(enabled = searching || stack.size > 1 || onHome != null) {
        if (searching) onQueryChange("")
        else if (stack.size > 1) { returnedFrom = stack.last(); onOpenFoldersChange(stack.dropLast(1)) }
        else onHome?.invoke()
    }
    // Rows from the previous folder leave composition, so hand focus to the first row of the new one.
    LaunchedEffect(tree, path, searching, loading) {
        if (!loading) runCatching { firstItem.requestFocus() }
    }
    // Look up show posters for the folders on screen (cached, so each show is fetched only once).
    LaunchedEffect(folders) { posters.ensure(folders.map { it.segments.last() }) }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 42.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NESTCAST", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("Your movie library", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                val where = if (searching) "Search results" else if (path.isEmpty()) server else path.joinToString("  ›  ") { posters.displayName(it) }
                val watchedTotal = movies.count { watch.isWatched(it.watchKey) }
                Text("${movies.size} videos  •  $watchedTotal watched  •  $where", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Hold OK on a video or folder to mark it watched or unwatched", color = Muted, fontSize = 11.sp, maxLines = 1)
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
                // After watching something, focus returns to that video instead of the top of the list.
                if (searching) {
                    val target = filtered.firstOrNull { it.id == lastPlayedId } ?: filtered.first()
                    items(filtered, key = { it.id }) { movie ->
                        MovieRow(
                            movie = movie, progress = watch.progress(movie.watchKey), watched = watch.isWatched(movie.watchKey),
                            onClick = { onPlay(movie) }, onToggleWatched = { toggleWatched(watch, onCompleted, listOf(movie)) },
                            modifier = if (movie === target) Modifier.focusRequester(firstItem) else Modifier
                        )
                    }
                } else {
                    items(folders, key = { "folder:" + it.segments.joinToString("/") }) { entry ->
                        val inside = entry.node.allMovies()
                        FolderRow(
                            label = entry.segments.joinToString("  ›  ") { posters.displayName(it) }, count = entry.node.totalMovies,
                            watchedCount = inside.count { watch.isWatched(it.watchKey) },
                            posterUrl = posters.posterFor(entry.segments.last()),
                            modifier = if (isFolderFocusTarget(entry, folders, path, returnedFrom, current, lastPlayedId)) Modifier.focusRequester(firstItem) else Modifier,
                            onClick = { onOpenFoldersChange(stack + listOf(path + entry.segments)) },
                            onToggleWatched = { toggleWatched(watch, onCompleted, inside) }
                        )
                    }
                    items(current.movies, key = { it.id }) { movie ->
                        MovieRow(
                            movie = movie, progress = watch.progress(movie.watchKey), watched = watch.isWatched(movie.watchKey),
                            onClick = { onPlay(movie) }, onToggleWatched = { toggleWatched(watch, onCompleted, listOf(movie)) },
                            modifier = if (movie.id == lastPlayedId || (folders.isEmpty() && movie === current.movies.first() && current.movies.none { it.id == lastPlayedId })) Modifier.focusRequester(firstItem) else Modifier
                        )
                    }
                }
            }
        }
    }
}

/**
 * Focusable row behaviour for a TV remote: a tap of OK runs [onClick]; holding OK runs [onLongClick] once.
 * (Compose's combinedClickable ignores held keys, so key repeats are handled here directly.)
 */
private fun Modifier.longPressClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    var longPressHandled by remember { mutableStateOf(false) }
    this
        .onPreviewKeyEvent { event ->
            val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
            if (!isSelect) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) longPressHandled = false
                    else if (!longPressHandled) { longPressHandled = true; onLongClick() }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (!longPressHandled) onClick()
                    longPressHandled = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}

/** Which folder row should take focus: the one just left, else the first (unless a played video is on this level). */
private fun isFolderFocusTarget(
    entry: FolderEntry, folders: List<FolderEntry>, path: List<String>, returnedFrom: List<String>?,
    current: FolderNode, lastPlayedId: String
): Boolean {
    val returned = folders.firstOrNull { path + it.segments == returnedFrom }
    return if (returned != null) entry === returned
    else entry === folders.first() && current.movies.none { it.id == lastPlayedId }
}

/** Marks every video in [videos] watched, or clears them all if they already are. */
private fun toggleWatched(watch: WatchStore, onCompleted: (Movie) -> Unit, videos: List<Movie>) {
    val allWatched = videos.isNotEmpty() && videos.all { watch.isWatched(it.watchKey) }
    // Only marking as watched is reported; un-marking is never sent.
    videos.forEach { if (allWatched) watch.markUnwatched(it.watchKey) else if (watch.markWatched(it.watchKey)) onCompleted(it) }
}

@Composable
private fun FolderRow(
    label: String, count: Int, watchedCount: Int, posterUrl: String?, modifier: Modifier = Modifier,
    onClick: () -> Unit, onToggleWatched: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = if (focused) Color(0xFF33302A) else Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().onFocusChangedCompat { focused = it }
            .clip(RoundedCornerShape(12.dp)).longPressClickable(onClick = onClick, onLongClick = onToggleWatched)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF292B31))
                )
            } else {
                Box(Modifier.size(width = 56.dp, height = 56.dp).background(Color(0xFF292B31), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text("DIR", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val videos = if (count == 1) "1 video" else "$count videos"
                val status = when {
                    count > 0 && watchedCount >= count -> "  •  ✓ all watched"
                    watchedCount > 0 -> "  •  $watchedCount watched"
                    else -> ""
                }
                Text(videos + status, color = if (watchedCount >= count && count > 0) Accent else Muted, fontSize = 12.sp)
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
private fun MovieRow(
    movie: Movie, progress: WatchStore.Progress?, watched: Boolean, onClick: () -> Unit, onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = if (focused) Color(0xFF33302A) else Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().onFocusChangedCompat { focused = it }
            .clip(RoundedCornerShape(12.dp)).longPressClickable(onClick = onClick, onLongClick = onToggleWatched)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 70.dp).background(Color(0xFF292B31), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(if (watched) "✓" else movie.extension.uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = if (watched) 22.sp else 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (watched) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${movie.fileName}  •  ${formatBytes(movie.sizeBytes)}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!watched && progress != null && progress.durationMs > 0 && progress.positionMs >= 10_000L) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.5f).height(4.dp), color = Accent, trackColor = Color(0xFF34363D)
                    )
                }
            }
            val action = when {
                watched -> "WATCHED"
                progress != null && progress.positionMs >= 10_000L -> "RESUME  ${formatClock(progress.positionMs)}  ›"
                else -> "PLAY  ›"
            }
            Text(action, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
private fun PlaybackScreen(movie: Movie, watch: WatchStore, onCompleted: (Movie) -> Unit, onBack: () -> Unit, getPlayer: (Movie) -> ExoPlayer) {
    val player = remember(movie.url) { getPlayer(movie) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun saveProgress() {
        val duration = player.duration
        if (duration != C.TIME_UNSET && watch.save(movie.watchKey, player.currentPosition, duration)) onCompleted(movie)
    }

    // Save on the way out, so backing out by mistake doesn't lose the place.
    BackHandler { saveProgress(); onBack() }
    // ...and every few seconds, in case the app is killed or the TV loses power mid-video.
    LaunchedEffect(player) {
        while (true) { delay(5000); if (player.isPlaying) saveProgress() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Playback error: ${error.errorCodeName}. ${error.message ?: "Check whether this video or audio format is supported."}"
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error == null) playbackError = null
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && watch.markWatched(movie.watchKey)) onCompleted(movie)
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress()
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
