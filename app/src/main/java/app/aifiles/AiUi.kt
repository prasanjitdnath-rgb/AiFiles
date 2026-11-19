package app.aifiles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTab(vm: VM) {
    var section by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val running by vm.indexer.running.collectAsState()
    val (done, total) = vm.indexer.progress.collectAsState().value
    val photoCount = remember(vm.media) { vm.media.count { !it.isVideo } }

    BackHandler(section != null) { section = null }

    if (section != null) {
        CleanScreen(vm = vm, kind = section!!) { section = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("AI tools") })

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Try \"bills last week\", \"food\", \"videos today\"") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton({ query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                }
            },
        )

        if (query.isNotBlank()) {
            val results = remember(query, vm.media, vm.idx) { Smart.search(query, vm.media, vm.idx) }
            Text(
                "${results.size} result(s)",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching media found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                MediaGrid(vm = vm, list = results)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Smart index", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Labels objects, reads text (OCR), and fingerprints photos on-device. Nothing leaves your phone unless you use cloud OCR.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("${vm.idx.size} of $photoCount photos analyzed", style = MaterialTheme.typography.bodyMedium)
                            if (running) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    gapSize = 0.dp,
                                    drawStopIndicator = {},
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.startIndex() }, enabled = !running && photoCount > 0) {
                                Text(if (running) "Scanning… $done/$total" else "Scan photos")
                            }
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
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Use your own Gemini API key to read handwriting or non-Latin text. Images are sent to Google only when you request it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = key,
                                onValueChange = {
                                    key = it
                                    Cloud.key = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Gemini API key") },
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = model,
                                onValueChange = {
                                    model = it
                                    Cloud.model = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Model") },
                                supportingText = { Text("Example: gemini-2.5-flash") },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = auto,
                                    onCheckedChange = {
                                        auto = it
                                        Cloud.auto = it
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Auto-read screenshots during on-device scan")
                            }
                        }
                    }
                }

                item { Text("Cleanup", style = MaterialTheme.typography.titleMedium) }

                items(
                    listOf(
                        Triple("dups", "Similar photos", Icons.Default.PhotoLibrary),
                        Triple("large", "Large files (100 MB+)", Icons.Default.Storage),
                        Triple("shots", "Old screenshots", Icons.Default.Screenshot),
                        Triple("apks", "Installer files (APK)", Icons.Default.Android),
                    ),
                ) { (key, title, icon) ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        ListItem(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { section = key },
                            leadingContent = { Icon(icon, contentDescription = null) },
                            headlineContent = { Text(title) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        )
                    }
                }

                item {
                    Text(
                        "Smart albums appear in the Gallery tab after scanning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanScreen(vm: VM, kind: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<CleanItem>?>(null) }
    val picked = remember { mutableStateListOf<String>() }

    LaunchedEffect(kind, vm.tick, vm.media, vm.idx) {
        val result = vm.cleanSource(kind)
        items = result
        picked.clear()
        if (kind == "dups") picked.addAll(result.map { it.path })
    }

    val list = items

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (kind) {
                            "dups" -> "Similar photos"
                            "large" -> "Large files"
                            "shots" -> "Old screenshots"
                            else -> "Installer files"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (!list.isNullOrEmpty() && picked.isNotEmpty()) {
                val bytes = list.filter { it.path in picked }.sumOf { it.size }
                Button(
                    onClick = { vm.deletePaths(picked.toList()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .navigationBarsPadding(),
                ) {
                    Text("Delete ${picked.size} • free ${Formatter.formatShortFileSize(ctx, bytes)}")
                }
            }
        },
    ) { pad ->
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }

            list.isEmpty() -> Box(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (kind) {
                        "dups" -> "No similar photos found. Scan photos first, or everything looks unique."
                        "large" -> "No large files found."
                        "shots" -> "No old screenshots found."
                        else -> "No installer files found."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(modifier = Modifier.padding(pad).fillMaxSize()) {
                items(list, key = { it.path }) { item ->
                    val file = File(item.path)
                    val checked = item.path in picked
                    ListItem(
                        modifier = Modifier.clickable {
                            if (checked) picked.remove(item.path) else picked.add(item.path)
                        },
                        leadingContent = {
                            if (isMedia(file)) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(40.dp))
                            }
                        },
                        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                "${Formatter.formatShortFileSize(ctx, item.size)} • ${item.note}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Checkbox(checked = checked, onCheckedChange = { on ->
                                if (on) picked.add(item.path) else picked.remove(item.path)
                            })
                        },
                    )
                }
            }
        }
    }
}