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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/** Marks a video as coming from a personal folder: it resumes like any video but is never reported to MediaLog. */
const val PERSONAL_PREFIX = "personal|"

private val PBg = Color(0xFF101114)
private val PPanel = Color(0xFF1A1C21)
private val PAccent = Color(0xFFFFB139)
private val PMuted = Color(0xFF9CA3AF)

/** One picture or video from a PC's personal folders (GET /api/personal, servers 0.4.0+). */
class PersonalItem(
    val id: String, val isImage: Boolean, val title: String, val fileName: String, val sizeBytes: Long,
    val url: String, val thumbUrl: String?, val folderPath: List<String>, val modifiedMs: Long, val pc: String
) {
    /** A video as the player and watch store know it; its key marks it personal so nothing is reported. */
    fun asMovie() = Movie(
        id = id, title = title, fileName = fileName, extension = fileName.substringAfterLast('.', "").lowercase(),
        sizeBytes = sizeBytes, url = url, folderPath = folderPath, watchKey = PERSONAL_PREFIX + pc + "|" + id
    )
}

/** A folder in the personal tree: sub-folders plus the pictures and videos directly inside it. */
class PersonalNode(val name: String) {
    val children = TreeMap<String, PersonalNode>(String.CASE_INSENSITIVE_ORDER)
    val items = mutableListOf<PersonalItem>()
    var total = 0

    fun all(): List<PersonalItem> = items + children.values.flatMap { it.all() }
    /** A picture to stand for the folder: the first one inside it or in any folder below. */
    fun cover(): String? = items.firstOrNull { it.thumbUrl != null }?.thumbUrl ?: children.values.firstNotNullOfOrNull { it.cover() }
}

fun buildPersonalTree(items: List<PersonalItem>): PersonalNode {
    val root = PersonalNode("")
    for (item in items) {
        var node = root
        node.total++
        for (segment in item.folderPath) {
            node = node.children.getOrPut(segment) { PersonalNode(segment) }
            node.total++
        }
        node.items += item
    }
    return root
}

/** Skips straight into a folder that holds nothing but one other folder, as the movie library does. */
fun PersonalNode.initialPath(): List<String> {
    val path = mutableListOf<String>()
    var node = this
    while (node.items.isEmpty() && node.children.size == 1) { node = node.children.values.first(); path += node.name }
    return path
}

/** The folder actually shown: what the person opened, but never above the first real folder. */
fun PersonalNode.effectivePath(open: List<String>): List<String> = initialPath().let { base -> if (open.size < base.size) base else open }

private fun PersonalNode.resolve(path: List<String>): PersonalNode {
    var node = this
    for (segment in path) node = node.children[segment] ?: return node
    return node
}

/** How many personal folders a PC shares (/api/health, servers 0.4.0+); 0 for older servers or when unreachable. */
fun fetchPersonalFolderCount(base: String): Int = runCatching {
    val connection = (URL(base.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection).apply {
        connectTimeout = 1500
        readTimeout = 2000
    }
    try {
        if (connection.responseCode !in 200..299) 0
        else org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optInt("personalFolders", 0)
    } finally { connection.disconnect() }
}.getOrDefault(0)

/** The pictures and videos a PC shares; [pcName] is shown as the top folder when several PCs are combined. */
fun fetchPersonal(base: String, pcName: String, prefixWithPc: Boolean): List<PersonalItem> {
    val connection = (URL(base.trimEnd('/') + "/api/personal").openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000
        readTimeout = 30000
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("http")) return@mapNotNull null
            val folders = (if (prefixWithPc) listOf(pcName) else emptyList()) +
                listOf(o.optString("rootName")).filter { it.isNotBlank() } +
                o.optString("relativeFolder").split('/', '\\').filter { it.isNotBlank() }
            val modified = runCatching { java.time.Instant.parse(o.optString("modifiedUtc")).toEpochMilli() }.getOrDefault(0L)
            PersonalItem(
                id = o.optString("id"), isImage = o.optString("kind") == "image", title = o.optString("title"),
                fileName = o.optString("fileName"), sizeBytes = o.optLong("sizeBytes"), url = url,
                thumbUrl = o.optString("thumbUrl").takeIf { it.startsWith("http") }, folderPath = folders,
                modifiedMs = modified, pc = pcName
            )
        }
    } finally { connection.disconnect() }
}

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
                title = "Movies & Shows", subtitle = if (movieCount == 1) "1 video" else "$movieCount videos",
                glyph = "▶", from = Color(0xFF3A2A10), to = Color(0xFF1A1C21), onClick = onMovies, modifier = Modifier.focusRequester(first)
            )
            val personalText = buildList {
                if (pictureCount > 0) add(if (pictureCount == 1) "1 picture" else "$pictureCount pictures")
                if (videoCount > 0) add(if (videoCount == 1) "1 video" else "$videoCount videos")
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

@Composable
fun PersonalScreen(
    items: List<PersonalItem>, tree: PersonalNode, openPath: List<String>, onOpenPathChange: (List<String>) -> Unit,
    lastOpenedId: String, onBackHome: () -> Unit, onViewImage: (PersonalItem) -> Unit, onPlayVideo: (PersonalItem) -> Unit,
    gridState: LazyGridState
) {
    val base = remember(tree) { tree.initialPath() }
    val path = tree.effectivePath(openPath)
    val current = remember(tree, path) { tree.resolve(path) }
    val folders = remember(current) { current.children.values.toList() }
    val media = remember(current) { current.items.sortedWith(compareBy({ !it.isImage }, { it.title.lowercase() })) }
    val firstItem = remember { FocusRequester() }
    BackHandler { if (path.size <= base.size) onBackHome() else onOpenPathChange(path.dropLast(1)) }
    // Hand focus to the picture last viewed, else the first tile, whenever the folder changes.
    LaunchedEffect(current) {
        // A lazy grid only attaches the tiles near the screen, so bring the wanted tile into view before asking for focus.
        val target = media.indexOfFirst { it.id == lastOpenedId }
        if (target >= 0) runCatching { gridState.scrollToItem(folders.size + target) }
        delay(120)
        runCatching { firstItem.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(PBg).padding(horizontal = 42.dp, vertical = 24.dp)) {
        Text("CASTBOX", color = PAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Text(if (path.size <= base.size) "Personal" else path.last(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val pictures = items.count { it.isImage }; val videos = items.size - pictures
        val where = if (path.isEmpty()) "All folders" else path.joinToString("  ›  ")
        Text("$pictures pictures  •  $videos videos  •  $where", color = PMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        if (folders.isEmpty() && media.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here", fontSize = 22.sp, color = Color.White) }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 230.dp), state = gridState, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp, start = 8.dp, end = 8.dp)
            ) {
                val focusTarget = media.firstOrNull { it.id == lastOpenedId }?.id
                items(folders, key = { "folder:" + it.name }) { node ->
                    FolderTile(
                        node, onClick = { onOpenPathChange(path + node.name) },
                        modifier = if (focusTarget == null && node === folders.first()) Modifier.focusRequester(firstItem) else Modifier
                    )
                }
                items(media, key = { it.id }) { item ->
                    MediaTile(
                        item, onClick = { if (item.isImage) onViewImage(item) else onPlayVideo(item) },
                        modifier = if (item.id == focusTarget || (focusTarget == null && folders.isEmpty() && item === media.first())) Modifier.focusRequester(firstItem) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderTile(node: PersonalNode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "folderTileScale")
    val cover = remember(node) { node.cover() }
    Box(
        modifier
            .aspectRatio(1.5f).scale(scale)
            .clip(RoundedCornerShape(18.dp)).background(PPanel)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) PAccent else Color(0xFF2A2C33)), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (cover != null) AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("📁", fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)), startY = 90f)))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(node.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (node.total == 1) "1 item" else "${node.total} items", color = Color(0xFFD0D3DA), fontSize = 12.sp)
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

/** Full-screen pictures of one folder. Left/right move, OK starts or stops the slideshow, Back returns to the grid. */
@Composable
fun PhotoViewer(pictures: List<PersonalItem>, startId: String, onClose: () -> Unit, onShown: (PersonalItem) -> Unit) {
    var index by remember { mutableIntStateOf(pictures.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    var slideshow by remember { mutableStateOf(false) }
    var hudUntil by remember { mutableStateOf(System.currentTimeMillis() + 4000) }
    var hudVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val current = pictures[index]

    fun go(step: Int) { index = (index + step + pictures.size) % pictures.size; hudUntil = System.currentTimeMillis() + 4000 }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(index) {
        onShown(pictures[index])
        // Load the next picture ahead of time so a slideshow never waits.
        pictures.getOrNull((index + 1) % pictures.size)?.let { next ->
            coil.Coil.imageLoader(context).enqueue(ImageRequest.Builder(context).data(next.url).build())
        }
    }
    LaunchedEffect(slideshow, index) {
        if (slideshow && pictures.size > 1) { delay(7000); go(1) }
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
                Text(
                    (if (slideshow) "❚❚ Slideshow   " else "▶ OK for slideshow   ") + "${index + 1} / ${pictures.size}",
                    color = PAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** The pictures of a folder in the order the grid shows them, for the viewer's left/right. */
fun PersonalNode.resolveForViewer(path: List<String>): List<PersonalItem> =
    resolve(path).items.filter { it.isImage }.sortedBy { it.title.lowercase() }
