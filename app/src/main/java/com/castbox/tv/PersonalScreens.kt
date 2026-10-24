package com.castbox.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat

/** Marks a video as coming from a personal folder: it resumes like any video but is never reported to MediaLog. */
const val PERSONAL_PREFIX = "personal|"

private const val PAGE_SIZE = 120
private val PBg = Color(0xFF101114)
private val PPanel = Color(0xFF1A1C21)
private val PAccent = Color(0xFFFFB139)
private val PMuted = Color(0xFF9CA3AF)

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

// ------------------------------------------------------------------------------------------------------------
// What the PCs share (GET /api/personal/roots and /api/personal/browse, servers 0.5.0+)
//
// A personal library can hold hundreds of thousands of pictures, so nothing here ever loads "everything":
// the top level is a handful of folders, and a folder is read one page at a time as the person scrolls.
// ------------------------------------------------------------------------------------------------------------

/** One shared folder on one PC; [index] is the PC's number for it. */
class PersonalRoot(val pc: MediaServer, val index: Int, val name: String, val items: Int, val coverUrl: String?)

/** All shared folders of all PCs, with the totals for the home screen. */
class PersonalSummary(val pictures: Int, val videos: Int, val roots: List<PersonalRoot>) {
    val isEmpty get() = roots.isEmpty()
}

class PersonalFolderEntry(val name: String, val items: Int, val coverUrl: String?)

/** One picture or video of a personal folder. */
class PersonalItem(
    val id: String, val isImage: Boolean, val title: String, val fileName: String, val sizeBytes: Long,
    val url: String, val thumbUrl: String?, val folderPath: List<String>, val pc: String
) {
    /** A video as the player and watch store know it; its key marks it personal so nothing is reported. */
    fun asMovie() = Movie(
        id = id, title = title, fileName = fileName, extension = fileName.substringAfterLast('.', "").lowercase(),
        sizeBytes = sizeBytes, url = url, folderPath = folderPath, watchKey = PERSONAL_PREFIX + pc + "|" + id
    )
}

/** A folder to look at: a shared root plus the folders opened below it. */
class PersonalLocation(val root: PersonalRoot, val segments: List<String>) {
    val key: String get() = "${root.pc.baseUrl}|${root.index}|" + segments.joinToString("/")
}

/** How many shared folders a PC has (/api/health, servers 0.4.0+); 0 for older servers or when unreachable. */
fun fetchPersonalFolderCount(base: String): Int = runCatching {
    val connection = (URL(base.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection).apply {
        connectTimeout = 1500
        readTimeout = 2000
    }
    try {
        if (connection.responseCode !in 200..299) 0
        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optInt("personalFolders", 0)
    } finally { connection.disconnect() }
}.getOrDefault(0)

private fun getJson(url: String, readTimeoutMs: Int): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000
        readTimeout = readTimeoutMs
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
}

/** The shared folders of one PC and the totals; throws for servers that predate the browse API (0.5.0). */
fun fetchPersonalRoots(server: MediaServer, prefixWithPc: Boolean): PersonalSummary {
    // The first request after the server starts waits for it to read the folders, which can take a while.
    val o = getJson(server.baseUrl.trimEnd('/') + "/api/personal/roots", 60_000)
    val roots = o.optJSONArray("roots")
    val list = (0 until (roots?.length() ?: 0)).mapNotNull { i ->
        val r = roots!!.optJSONObject(i) ?: return@mapNotNull null
        val name = r.optString("name")
        PersonalRoot(server, r.optInt("index"), if (prefixWithPc) "${server.name}  ›  $name" else name, r.optInt("items"), r.optString("coverUrl").takeIf { it.startsWith("http") })
    }
    return PersonalSummary(o.optInt("pictures"), o.optInt("videos"), list)
}

private class Page(val items: Int, val hasMore: Boolean, val folders: List<PersonalFolderEntry>, val files: List<PersonalItem>)

private fun fetchBrowse(location: PersonalLocation, offset: Int, limit: Int): Page {
    val path = URLEncoder.encode(location.segments.joinToString("/"), "UTF-8")
    val o = getJson(
        location.root.pc.baseUrl.trimEnd('/') + "/api/personal/browse?root=${location.root.index}&path=$path&offset=$offset&limit=$limit", 30_000
    )
    val folders = o.optJSONArray("folders")
    val files = o.optJSONArray("files")
    val shown = listOf(location.root.name) + location.segments
    return Page(
        items = o.optInt("items"), hasMore = o.optBoolean("hasMore"),
        folders = (0 until (folders?.length() ?: 0)).mapNotNull { i ->
            folders!!.optJSONObject(i)?.let { PersonalFolderEntry(it.optString("name"), it.optInt("items"), it.optString("coverUrl").takeIf { u -> u.startsWith("http") }) }
        },
        files = (0 until (files?.length() ?: 0)).mapNotNull { i ->
            val f = files!!.optJSONObject(i) ?: return@mapNotNull null
            val url = f.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            PersonalItem(
                id = f.optString("id"), isImage = f.optString("kind") == "image", title = f.optString("title"),
                fileName = f.optString("fileName"), sizeBytes = f.optLong("sizeBytes"), url = url,
                thumbUrl = f.optString("thumbUrl").takeIf { it.startsWith("http") }, folderPath = shown, pc = location.root.pc.name
            )
        }
    )
}

/** What has been read of one folder so far; more pages are added as the person scrolls or swipes through the pictures. */
class PersonalFolderState(val location: PersonalLocation) {
    val folders = mutableStateListOf<PersonalFolderEntry>()
    val files = mutableStateListOf<PersonalItem>()
    /** Just the pictures of [files], in the same order, for the viewer. */
    val images = mutableStateListOf<PersonalItem>()
    var totalItems by mutableIntStateOf(0)
    var hasMore by mutableStateOf(true)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var first = true

    suspend fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        try {
            val page = withContext(Dispatchers.IO) { fetchBrowse(location, files.size, PAGE_SIZE) }
            if (first) { folders += page.folders; totalItems = page.items; first = false }
            files += page.files
            images += page.files.filter { it.isImage }
            hasMore = page.hasMore
            error = null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this folder"
            hasMore = false
        } finally { loading = false }
    }
}

/** The folders read so far and where each grid was scrolled to, kept while the person moves between folders and the viewer. */
class PersonalStates {
    private val folders = HashMap<String, PersonalFolderState>()
    private val grids = HashMap<String, LazyGridState>()
    fun folder(location: PersonalLocation) = folders.getOrPut(location.key) { PersonalFolderState(location) }
    fun grid(key: String) = grids.getOrPut(key) { LazyGridState() }
    fun clear() { folders.clear(); grids.clear() }
}

/** The folder to show: the one opened, or the only shared folder when there is just one. null is the list of shared folders. */
fun effectiveLocation(summary: PersonalSummary, opened: PersonalLocation?): PersonalLocation? =
    opened ?: summary.roots.singleOrNull()?.let { PersonalLocation(it, emptyList()) }

// ------------------------------------------------------------------------------------------------------------
// Home: the two places to go
// ------------------------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(movieCount: Int, pictureCount: Int, videoCount: Int, onMovies: () -> Unit, onPersonal: () -> Unit, onChangeServer: () -> Unit) {
    val first = remember { FocusRequester() }
    // The cards must be laid out before they can take focus, so ask a moment after the screen appears.
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(PBg).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text("CASTBOX", color = PAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(6.dp))
        Text("What would you like to watch?", fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            HomeCard(
                title = "Movies & Shows", subtitle = if (movieCount == 1) "1 video" else "${count(movieCount)} videos",
                glyph = "▶", from = Color(0xFF3A2A10), to = Color(0xFF1A1C21), onClick = onMovies, modifier = Modifier.focusRequester(first)
            )
            val personalText = buildList {
                if (pictureCount > 0) add(if (pictureCount == 1) "1 picture" else "${count(pictureCount)} pictures")
                if (videoCount > 0) add(if (videoCount == 1) "1 video" else "${count(videoCount)} videos")
            }.joinToString("  •  ").ifBlank { "Nothing yet" }
            HomeCard(title = "Personal", subtitle = personalText, glyph = "❀", from = Color(0xFF14313A), to = Color(0xFF1A1C21), onClick = onPersonal)
        }
        Spacer(Modifier.height(32.dp))
        PersonalActionButton("Change server", onChangeServer)
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, glyph: String, from: Color, to: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "homeCardScale")
    Box(
        modifier
            .width(380.dp).height(240.dp).scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(from, to)))
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) PAccent else Color(0xFF34363D)), RoundedCornerShape(24.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(28.dp)
    ) {
        Text(glyph, color = PAccent.copy(alpha = if (focused) 1f else 0.7f), fontSize = 54.sp, modifier = Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PMuted, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PersonalActionButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) PAccent else Color(0xFF292B31))
            .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454852)), RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) { Text(label, color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.SemiBold) }
}

// ------------------------------------------------------------------------------------------------------------
// Personal: folders and thumbnails
// ------------------------------------------------------------------------------------------------------------

/**
 * The folder at [location]; null is the list of shared folders. Back climbs one folder at a time and finally returns home.
 * Pass the result of [effectiveLocation] so a lone shared folder is opened straight away.
 */
@Composable
fun PersonalScreen(
    summary: PersonalSummary, location: PersonalLocation?, states: PersonalStates, onOpen: (PersonalLocation?) -> Unit,
    lastOpenedId: String, onBackHome: () -> Unit, onViewImage: (PersonalFolderState, PersonalItem) -> Unit, onPlayVideo: (PersonalItem) -> Unit
) {
    val state = location?.let { states.folder(it) }
    val gridState = states.grid(location?.key ?: "top")
    val firstItem = remember { FocusRequester() }
    val onlyRoot = summary.roots.size == 1
    BackHandler {
        when {
            location == null -> onBackHome()
            location.segments.isNotEmpty() -> onOpen(PersonalLocation(location.root, location.segments.dropLast(1)))
            onlyRoot -> onBackHome()
            else -> onOpen(null)
        }
    }

    // The first page, and the next ones as the person scrolls near the end of what has been read.
    LaunchedEffect(state) { if (state != null && state.files.isEmpty() && state.hasMore) state.loadMore() }
    LaunchedEffect(state, gridState) {
        if (state == null) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (state.hasMore && !state.loading && last >= state.folders.size + state.files.size - 40) state.loadMore()
        }
    }
    // Hand focus to the picture last viewed, else the first tile, whenever the folder changes (once its tiles exist).
    LaunchedEffect(location?.key, state?.files?.isEmpty()) {
        val target = state?.files?.indexOfFirst { it.id == lastOpenedId } ?: -1
        if (target >= 0) runCatching { gridState.scrollToItem((state?.folders?.size ?: 0) + target) }
        delay(120)
        runCatching { firstItem.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(PBg).padding(horizontal = 42.dp, vertical = 24.dp)) {
        Text("CASTBOX", color = PAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        val title = when {
            location == null -> "Personal"
            location.segments.isEmpty() -> if (onlyRoot) "Personal" else location.root.name
            else -> location.segments.last()
        }
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val where = if (location == null) "${count(summary.roots.size)} folders" else (listOf(location.root.name) + location.segments).joinToString("  ›  ")
        val total = if (state != null) "${count(state.totalItems)} items  •  " else "${count(summary.pictures)} pictures  •  ${count(summary.videos)} videos  •  "
        Text(total + where, color = PMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))

        val entries: List<PersonalFolderEntry> = if (state == null) summary.roots.map { PersonalFolderEntry(it.name, it.items, it.coverUrl) } else state.folders
        val files: List<PersonalItem> = state?.files ?: emptyList()
        when {
            state != null && state.error != null && files.isEmpty() && entries.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Couldn't open this folder: ${state.error}", fontSize = 18.sp, color = Color(0xFFFFB4A8)) }
            state != null && state.loading && files.isEmpty() && entries.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PAccent) }
            entries.isEmpty() && files.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here", fontSize = 22.sp, color = Color.White) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 230.dp), state = gridState, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp, start = 8.dp, end = 8.dp)
            ) {
                val focusTarget = files.firstOrNull { it.id == lastOpenedId }?.id
                items(entries, key = { "folder:" + it.name }) { entry ->
                    FolderTile(
                        entry,
                        onClick = {
                            if (location == null) onOpen(PersonalLocation(summary.roots.first { it.name == entry.name }, emptyList()))
                            else onOpen(PersonalLocation(location.root, location.segments + entry.name))
                        },
                        modifier = if (focusTarget == null && entry === entries.first()) Modifier.focusRequester(firstItem) else Modifier
                    )
                }
                items(files, key = { it.id }) { item ->
                    MediaTile(
                        item, onClick = { if (item.isImage) onViewImage(state!!, item) else onPlayVideo(item) },
                        modifier = if (item.id == focusTarget || (focusTarget == null && entries.isEmpty() && item === files.first())) Modifier.focusRequester(firstItem) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTile(entry: PersonalFolderEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "folderTileScale")
    Box(
        modifier
            .aspectRatio(1.5f).scale(scale)
            .clip(RoundedCornerShape(18.dp)).background(PPanel)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) PAccent else Color(0xFF2A2C33)), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (entry.coverUrl != null) AsyncImage(model = entry.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("📁", fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)), startY = 90f)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(entry.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (entry.items == 1) "1 item" else "${count(entry.items)} items", color = Color(0xFFD0D3DA), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MediaTile(item: PersonalItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "mediaTileScale")
    Box(
        modifier
            .aspectRatio(1.5f).scale(scale)
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFF22242A))
            .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) PAccent else Color.Transparent), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (item.thumbUrl != null) {
            AsyncImage(model = item.thumbUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2F3A), Color(0xFF181A1F)))), contentAlignment = Alignment.Center) {
                Box(Modifier.size(58.dp).background(Color(0x66000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("▶", color = PAccent, fontSize = 26.sp)
                }
            }
        }
        if (!item.isImage) {
            Text("VIDEO", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(PAccent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        // The name appears while the tile is focused, so the grid stays clean otherwise.
        AnimatedVisibility(visible = focused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------
// Viewer: one picture at a time, with an optional slideshow
// ------------------------------------------------------------------------------------------------------------

/**
 * Full-screen pictures of one folder. Left/right move (reading more of a big folder as needed), OK starts or stops the
 * slideshow, Back returns to the grid.
 */
@Composable
fun PhotoViewer(state: PersonalFolderState, startId: String, onClose: () -> Unit, onShown: (PersonalItem) -> Unit) {
    val pictures = state.images
    var index by remember { mutableIntStateOf(pictures.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    var slideshow by remember { mutableStateOf(false) }
    var hudUntil by remember { mutableStateOf(System.currentTimeMillis() + 4000) }
    var hudVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (pictures.isEmpty()) { onClose(); return }
    val current = pictures[index.coerceIn(0, pictures.size - 1)]

    fun go(step: Int) {
        hudUntil = System.currentTimeMillis() + 4000
        val next = index + step
        when {
            next in pictures.indices -> index = next
            next >= pictures.size && state.hasMore -> scope.launch { state.loadMore(); if (index + 1 < pictures.size) index += 1 }
            next >= pictures.size -> index = 0
            else -> index = pictures.size - 1
        }
    }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(index) {
        onShown(pictures[index.coerceIn(0, pictures.size - 1)])
        // Read ahead in a big folder, and load the next picture so a slideshow never waits.
        if (state.hasMore && index >= pictures.size - 5) state.loadMore()
        pictures.getOrNull(index + 1)?.let { next ->
            coil.Coil.imageLoader(context).enqueue(ImageRequest.Builder(context).data(next.url).build())
        }
    }
    LaunchedEffect(slideshow, index) {
        if (slideshow && (pictures.size > 1 || state.hasMore)) { delay(7000); go(1) }
    }
    LaunchedEffect(hudUntil) {
        hudVisible = true
        delay(maxOf(0L, hudUntil - System.currentTimeMillis()) + 50)
        if (System.currentTimeMillis() >= hudUntil) hudVisible = false
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.MediaNext, Key.MediaFastForward -> { go(1); true }
                    Key.DirectionLeft, Key.MediaPrevious, Key.MediaRewind -> { go(-1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        slideshow = !slideshow; hudUntil = System.currentTimeMillis() + 4000; true
                    }
                    Key.DirectionUp, Key.DirectionDown -> { hudUntil = System.currentTimeMillis() + 4000; true }
                    else -> false
                }
            }
    ) {
        AsyncImage(model = current.url, contentDescription = current.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(visible = hudVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 48.dp, vertical = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(current.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.folderPath.joinToString("  ›  "), color = PMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val of = if (state.hasMore) "${index + 1}+" else "${index + 1} / ${pictures.size}"
                Text((if (slideshow) "❚❚ Slideshow   " else "▶ OK for slideshow   ") + of, color = PAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
