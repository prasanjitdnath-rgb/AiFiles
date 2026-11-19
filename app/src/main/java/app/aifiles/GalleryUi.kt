package app.aifiles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface GalleryCell {
    data class Header(val label: String) : GalleryCell
    data class Photo(val media: Media, val index: Int) : GalleryCell
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTab(vm: VM) {
    var album by remember { mutableStateOf("All") }
    val smart = remember(vm.media, vm.idx) { Smart.albums(vm.media, vm.idx) }
    val buckets = remember(vm.media) {
        vm.media.groupBy { it.bucket }
            .filterKeys { it.isNotBlank() }
            .toList()
            .sortedByDescending { (_, items) -> items.size }
    }
    val chips = remember(smart, buckets) {
        buildList {
            add("All")
            addAll(smart.keys)
            addAll(buckets.map { it.first }.filterNot { it in smart.keys })
        }
    }
    LaunchedEffect(chips) {
        if (album !in chips) album = "All"
    }
    val list = when {
        album == "All" -> vm.media
        smart.containsKey(album) -> smart[album].orEmpty()
        else -> buckets.firstOrNull { it.first == album }?.second.orEmpty()
    }

    BackHandler(vm.gallerySelection.isNotEmpty()) { vm.gallerySelection.clear() }

    Scaffold(
        topBar = {
            if (vm.gallerySelection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${vm.gallerySelection.size} selected") },
                    navigationIcon = {
                        IconButton({ vm.gallerySelection.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        val ctx = LocalContext.current
                        IconButton({ shareFiles(ctx, vm.gallerySelection.map(::File)) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton({ vm.toVault(vm.gallerySelection.map(::File)) }) {
                            Icon(Icons.Default.Lock, contentDescription = "Move to vault")
                        }
                        IconButton({ vm.deletePaths(vm.gallerySelection.toList()) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text("Gallery") })
            }
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it }) { name ->
                    FilterChip(
                        selected = album == name,
                        onClick = { album = name },
                        label = { Text(if (name in smart.keys) "✦ $name" else name) },
                    )
                }
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No media found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MediaGrid(vm = vm, list = list)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(vm: VM, list: List<Media>) {
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    val cells = remember(list) {
        buildList<GalleryCell> {
            var last = ""
            list.forEachIndexed { index, media ->
                val label = dateFormat.format(Date(media.dateMs))
                if (label != last) {
                    add(GalleryCell.Header(label))
                    last = label
                }
                add(GalleryCell.Photo(media, index))
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = cells,
            key = { cell ->
                when (cell) {
                    is GalleryCell.Header -> "h_${cell.label}"
                    is GalleryCell.Photo -> "m_${cell.media.id}_${cell.media.path}"
                }
            },
            span = { cell -> if (cell is GalleryCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { cell ->
            when (cell) {
                is GalleryCell.Header -> Text(
                    text = cell.label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is GalleryCell.Photo -> {
                    val selected = cell.media.path in vm.gallerySelection
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    if (vm.gallerySelection.isNotEmpty()) {
                                        if (selected) vm.gallerySelection.remove(cell.media.path) else vm.gallerySelection.add(cell.media.path)
                                    } else {
                                        vm.openViewer(list, cell.index)
                                    }
                                },
                                onLongClick = {
                                    if (!selected) vm.gallerySelection.add(cell.media.path)
                                },
                            ),
                    ) {
                        AsyncImage(
                            model = File(cell.media.path),
                            contentDescription = cell.media.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (cell.media.isVideo) {
                            Text(
                                text = formatDuration(cell.media.durationMs),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x553D5AFE)),
                            )
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun Viewer(vm: VM, list: List<Media>, start: Int) {
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { vm.viewer = null }
        return
    }
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.lastIndex)) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val current = list.getOrNull(pager.currentPage)

    BackHandler { vm.viewer = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            val item = list[page]
            if (item.isVideo) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = File(item.path),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    IconButton(
                        onClick = { vm.player = File(item.path) },
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0x99000000), CircleShape),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(40.dp),
                            tint = Color.White,
                        )
                    }
                }
            } else {
                ZoomImage(item) { zoomed = it }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ vm.viewer = null }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
            }
            Text(
                text = current?.name.orEmpty(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (current != null) {
                Text(
                    text = "${pager.currentPage + 1}/${list.size}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color(0x66000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton({ current?.let { shareFiles(ctx, listOf(File(it.path))) } }, enabled = current != null) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
            IconButton({ showInfo = true }, enabled = current != null) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Info", tint = Color.White)
            }
            IconButton(
                onClick = {
                    current?.let { vm.deletePaths(listOf(it.path)) }
                },
                enabled = current != null,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    }

    if (showInfo && current != null) {
        val meta = vm.idx[current.path]
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
                Text(current.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Formatter.formatShortFileSize(ctx, current.size)} • ${current.bucket.ifBlank { "Unknown album" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.dateMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (meta == null) {
                    Text("Not analyzed yet. Run indexing from the AI tab to generate labels and OCR text.")
                } else {
                    val labels = meta.labels.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    if (labels.isNotEmpty()) {
                        Text("AI labels", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(labels) { label -> AssistChip(onClick = {}, label = { Text(label) }) }
                        }
                    }
                    if (meta.ocr.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Detected text", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer { Text(meta.ocr.take(800), style = MaterialTheme.typography.bodyMedium) }
                        TextButton({ clipboard.setText(AnnotatedString(meta.ocr)) }) { Text("Copy text") }
                    }
                }

                if (!current.isVideo) {
                    Spacer(Modifier.height(8.dp))
                    if (Cloud.ready) {
                        FilledTonalButton(onClick = { vm.cloudOcr(current) }, enabled = !vm.cloudBusy) {
                            Text(if (vm.cloudBusy) "Reading…" else "Read text with Gemini")
                        }
                        Text(
                            "Sends this image to Google using your API key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Add a Gemini API key in the AI tab to read handwriting or non-Latin text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        vm.smartRename(current)
                        showInfo = false
                    }) { Text("Smart rename") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomImage(media: Media, onZoom: (Boolean) -> Unit) {
    var scale by remember(media.path) { mutableFloatStateOf(1f) }
    var offset by remember(media.path) { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 6f)
        scale = newScale
        offset = if (newScale == 1f) Offset.Zero else offset + panChange
        onZoom(newScale > 1f)
    }
    AsyncImage(
        model = File(media.path),
        contentDescription = media.name,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(media.path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                    onZoom(scale > 1f)
                })
            }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
    )
}
