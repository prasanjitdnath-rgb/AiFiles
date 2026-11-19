package app.aifiles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTab(vm: VM) {
    val ctx = LocalContext.current
    var section by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val running by vm.indexer.running.collectAsState()
    val (done, total) = vm.indexer.progress.collectAsState().value
    val photos = vm.media.count { !it.isVideo }
    BackHandler(section != null) { section = null }

    if (section != null) { CleanScreen(vm, section!!) { section = null }; return }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("AI tools") })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Try “bills last week”, “food”, “videos today”") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } })

        if (query.isNotBlank()) {
            val res = remember(query, vm.media, vm.idx) { Smart.search(query, vm.media, vm.idx) }
            Text("${res.size} results", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge)
            MediaGrid(vm, res)
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Smart index", style = MaterialTheme.typography.titleMedium)
                        Text("Labels objects, reads text (OCR) and fingerprints each photo on-device. Nothing leaves your phone.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("${vm.idx.size} of $photos photos analysed")
                        if (running) { LinearProgressIndicator({ if (total == 0) 0f else done.toFloat() / total }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                        Button({ vm.startIndex() }, enabled = !running) { Text(if (running) "Scanning… $done/$total" else "Scan photos") }
                    }
                }
            }
            item {
                var key by remember { mutableStateOf(Cloud.key) }
                var model by remember { mutableStateOf(Cloud.model) }
                var auto by remember { mutableStateOf(Cloud.auto) }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cloud OCR (optional)", style = MaterialTheme.typography.titleMedium)
                        Text("Reads Bengali and handwriting using your own Gemini API key. Images are sent to Google only when you use it.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(key, { key = it; Cloud.key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Gemini API key") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        OutlinedTextField(model, { model = it; Cloud.model = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Model") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(auto, { auto = it; Cloud.auto = it }); Spacer(Modifier.width(8.dp)); Text("Auto-read screenshots during scan")
                        }
                    }
                }
            }
            item { Text("Clean up", style = MaterialTheme.typography.titleMedium) }
            items(listOf(
                Triple("dups", "Similar photos", Icons.Default.PhotoLibrary),
                Triple("large", "Large files (100 MB+)", Icons.Default.Storage),
                Triple("shots", "Old screenshots", Icons.Default.Screenshot),
                Triple("apks", "Installer files (APK)", Icons.Default.Android))) { (k, t, ic) ->
                ListItem(Modifier.clip(RoundedCornerShape(16.dp)).clickable { section = k },
                    leadingContent = { Icon(ic, null) }, headlineContent = { Text(t) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
            }
            item { Text("Smart albums appear in the Gallery tab once photos are scanned.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanScreen(vm: VM, kind: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<CleanItem>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(kind, vm.tick, vm.media) {
        val r = vm.cleanSource(kind); items = r
        picked.clear(); if (kind == "dups") picked.addAll(r.map { it.path })
    }
    val list = items
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (kind) { "dups" -> "Similar photos"; "large" -> "Large files"; "shots" -> "Old screenshots"; else -> "Installer files" }) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } })
    }, bottomBar = {
        if (picked.isNotEmpty()) {
            val bytes = list?.filter { it.path in picked }?.sumOf { it.size } ?: 0L
            Button({ vm.deletePaths(picked.toList()) }, Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Text("Delete ${picked.size} · free ${Formatter.formatFileSize(ctx, bytes)}")
            }
        }
    }) { pad ->
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                Text(if (kind == "dups") "Nothing found. Scan photos first, or you’re all clean." else "Nothing to clean up")
            }
            else -> LazyColumn(Modifier.padding(pad)) {
                items(list, key = { it.path }) { c ->
                    val f = File(c.path)
                    ListItem(Modifier.clickable { if (c.path in picked) picked.remove(c.path) else picked.add(c.path) },
                        leadingContent = { if (isMedia(f)) AsyncImage(f, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) else Icon(Icons.Default.InsertDriveFile, null, Modifier.size(48.dp)) },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${Formatter.formatFileSize(ctx, c.size)} · ${c.note}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Checkbox(c.path in picked, { if (it) picked.add(c.path) else picked.remove(c.path) }) })
                }
            }
        }
    }
}
