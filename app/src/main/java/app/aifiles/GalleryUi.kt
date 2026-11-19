package app.aifiles

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class Cell { class Head(val label: String) : Cell(); class Pic(val m: Media, val i: Int) : Cell() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryTab(vm: VM) {
    var album by remember { mutableStateOf("All") }
    val smart = remember(vm.media, vm.idx) { Smart.albums(vm.media, vm.idx) }
    val buckets = remember(vm.media) { vm.media.groupBy { it.bucket }.filterKeys { it.isNotBlank() }.toList().sortedByDescending { it.second.size }.toMap() }
    val list = if (album == "All") vm.media else smart[album] ?: buckets[album] ?: emptyList()
    BackHandler(vm.gsel.isNotEmpty()) { vm.gsel.clear() }

    Column(Modifier.fillMaxSize()) {
        if (vm.gsel.isNotEmpty()) TopAppBar(
            title = { Text("${vm.gsel.size} selected") },
            navigationIcon = { IconButton({ vm.gsel.clear() }) { Icon(Icons.Default.Close, null) } },
            actions = {
                val ctx = LocalContext.current
                IconButton({ shareFiles(ctx, vm.gsel.map { File(it.path) }) }) { Icon(Icons.Default.Share, null) }
                IconButton({ vm.toVault(vm.gsel.map { File(it.path) }) }) { Icon(Icons.Default.Lock, null) }
                IconButton({ vm.deletePaths(vm.gsel.map { it.path }) }) { Icon(Icons.Default.Delete, null) }
            })
        else TopAppBar(title = { Text("Gallery") })

        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All") + smart.keys + buckets.keys.filter { it !in smart }) { a ->
                FilterChip(album == a, { album = a }, { Text(if (a in smart) "✨ $a" else a) })
            }
        }
        MediaGrid(vm, list)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(vm: VM, list: List<Media>) {
    val fmt = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    val cells = remember(list) {
        val out = ArrayList<Cell>(); var last = ""
        list.forEachIndexed { i, m -> val d = fmt.format(Date(m.dateMs)); if (d != last) { out += Cell.Head(d); last = d }; out += Cell.Pic(m, i) }
        out
    }
    LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(cells, span = { if (it is Cell.Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { c ->
            when (c) {
                is Cell.Head -> Text(c.label, Modifier.padding(10.dp, 12.dp, 10.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
                is Cell.Pic -> Box(Modifier.aspectRatio(1f).combinedClickable(
                    onClick = { if (vm.gsel.isNotEmpty()) { if (c.m in vm.gsel) vm.gsel.remove(c.m) else vm.gsel.add(c.m) } else vm.viewer = list to c.i },
                    onLongClick = { if (c.m !in vm.gsel) vm.gsel.add(c.m) })) {
                    AsyncImage(File(c.m.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (c.m.isVideo) Text(fmtDur(c.m.durationMs), Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    if (c.m in vm.gsel) Box(Modifier.fillMaxSize().background(Color(0x553366FF)), Alignment.TopEnd) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.padding(6.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(vm: VM, list: List<Media>, start: Int) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(start) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    BackHandler { vm.viewer = null }
    val cur = list.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { p ->
            val m = list[p]
            if (m.isVideo) Box(Modifier.fillMaxSize(), Alignment.Center) {
                AsyncImage(File(m.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton({ vm.player = File(m.path) }, Modifier.size(72.dp).background(Color(0x99000000), CircleShape)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(40.dp), tint = Color.White)
                }
            } else ZoomImage(m) { zoomed = it }
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.viewer = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Text(cur?.name.orEmpty(), color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(Color(0x66000000)).padding(8.dp), Arrangement.SpaceEvenly) {
            IconButton({ cur?.let { shareFiles(ctx, listOf(File(it.path))) } }) { Icon(Icons.Default.Share, null, tint = Color.White) }
            IconButton({ info = true }) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }
            IconButton({ cur?.let { vm.deletePaths(listOf(it.path)); vm.viewer = null } }) { Icon(Icons.Default.Delete, null, tint = Color.White) }
        }
    }

    if (info && cur != null) ModalBottomSheet({ info = false }) {
        val i = vm.idx[cur.path]; val clip = LocalClipboardManager.current
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(cur.name, style = MaterialTheme.typography.titleMedium)
            Text("${Formatter.formatFileSize(ctx, cur.size)} · ${cur.path}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (i == null) Text("Not analysed yet. Open the AI tab and tap “Scan photos”.")
            else {
                Text("AI labels", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(i.labels.split(",").filter { it.isNotBlank() }) { AssistChip({}, { Text(it) }) } }
                if (i.ocr.isNotBlank()) {
                    Spacer(Modifier.height(12.dp)); Text("Text found in image", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(i.ocr.take(600)) }
                    TextButton({ clip.setText(AnnotatedString(i.ocr)) }) { Text("Copy text") }
                }
            }
            if (!cur.isVideo) {
                Spacer(Modifier.height(8.dp))
                if (Cloud.ready) FilledTonalButton({ vm.cloudOcr(cur) }, enabled = !vm.cloudBusy) {
                    Text(if (vm.cloudBusy) "Reading…" else "Read text with Gemini (sends this image to Google)")
                } else Text("Add a Gemini API key in the AI tab to read Bengali / handwriting.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton({ vm.smartRename(cur); info = false; vm.viewer = null }) { Text("Smart rename from AI tags") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ZoomImage(m: Media, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 6f); off = if (scale == 1f) Offset.Zero else off + pan; onZoom(scale > 1f)
    }
    AsyncImage(File(m.path), null,
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; off = Offset.Zero; onZoom(scale > 1f) }) }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
        contentScale = ContentScale.Fit)
}
