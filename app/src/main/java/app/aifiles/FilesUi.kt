package app.aifiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

fun uriFor(c: Context, f: File) = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)

fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun openFile(c: Context, f: File) {
    runCatching {
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(c, f), mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }.onFailure {
        if (it is ActivityNotFoundException) {
            // no-op
        }
    }
}

fun shareFiles(c: Context, fs: List<File>) {
    val items = ArrayList(fs.filter { it.isFile }.map { uriFor(c, it) })
    if (items.isEmpty()) return
    runCatching {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, items)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(intent, "Share files"))
    }
}

fun isMedia(f: File): Boolean = f.extension.lowercase().let { ext ->
    ext in Cats.exts["Images"].orEmpty() || ext in Cats.exts["Videos"].orEmpty()
}

fun formatSize(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

private sealed interface FileDialog {
    data object NewFolder : FileDialog
    data class Rename(val file: File) : FileDialog
    data object Delete : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesTab(vm: VM) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    val files by produceState(initialValue = emptyList<File>(), vm.dir, vm.cat, vm.tick, vm.sort, vm.showHidden) {
        value = withContext(Dispatchers.IO) { vm.listFiles() }
    }
    val home = vm.dir == null && vm.cat == null
    val recent by produceState(initialValue = emptyList<File>(), vm.tick) {
        value = withContext(Dispatchers.IO) { vm.repo.recent() }
    }

    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.notice = null
        }
    }

    BackHandler(enabled = !home || vm.selected.isNotEmpty()) {
        when {
            vm.selected.isNotEmpty() -> vm.selected.clear()
            vm.cat != null -> vm.cat = null
            vm.dir == vm.repo.root -> vm.dir = null
            else -> vm.dir = vm.dir?.parentFile
        }
    }

    val selectedFiles = files.filter { it.path in vm.selected }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${vm.selected.size} selected") },
                    navigationIcon = {
                        IconButton({ vm.selected.clear() }) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        IconButton({
                            vm.selected.clear()
                            vm.selected.addAll(files.map { it.path })
                        }) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
                        IconButton({
                            vm.clip = selectedFiles.ifEmpty { vm.selected.map(::File) } to false
                            vm.selected.clear()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                        IconButton({
                            vm.clip = selectedFiles.ifEmpty { vm.selected.map(::File) } to true
                            vm.selected.clear()
                        }) { Icon(Icons.Default.ContentCut, contentDescription = "Move") }
                        IconButton({ shareFiles(ctx, selectedFiles) }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                        IconButton({ vm.toVault(selectedFiles) }) { Icon(Icons.Default.Lock, contentDescription = "Move to vault") }
                        IconButton({
                            val dest = vm.dir ?: selectedFiles.firstOrNull()?.parentFile ?: vm.repo.root
                            val items = selectedFiles.toList()
                            if (items.isNotEmpty()) vm.io { zip(items, dest) }
                        }) { Icon(Icons.Default.FolderZip, contentDescription = "Zip") }
                        if (vm.selected.size == 1) {
                            IconButton({ dialog = FileDialog.Rename(File(vm.selected.first())) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename")
                            }
                        }
                        IconButton({ dialog = FileDialog.Delete }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            vm.cat ?: vm.dir?.let { if (it == vm.repo.root) "Internal storage" else it.name } ?: "Files",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (!home) {
                            IconButton({
                                when {
                                    vm.cat != null -> vm.cat = null
                                    vm.dir == vm.repo.root -> vm.dir = null
                                    else -> vm.dir = vm.dir?.parentFile
                                }
                            }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                        }
                    },
                    actions = {
                        Box {
                            IconButton({ sortMenu = true }) { Icon(Icons.Default.Sort, contentDescription = "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                listOf("Name", "Date", "Size").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(if (vm.sort == option) "✓ $option" else option) },
                                        onClick = {
                                            vm.sort = option
                                            sortMenu = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (vm.showHidden) "✓ Show hidden" else "Show hidden") },
                                    onClick = {
                                        vm.showHidden = !vm.showHidden
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val currentDir = vm.dir
            when {
                currentDir != null && vm.clip != null -> ExtendedFloatingActionButton(
                    onClick = { vm.paste() },
                    icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    text = { Text("Paste (${vm.clip!!.first.size})") },
                )

                currentDir != null && vm.selected.isEmpty() -> FloatingActionButton({ dialog = FileDialog.NewFolder }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                }
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (home) {
                item {
                    val (used, total) = remember(vm.tick) { vm.repo.storage() }
                    val progress = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    ElevatedCard(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .clickable {
                                vm.cat = null
                                vm.dir = vm.repo.root
                            },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${formatSize(ctx, used)} used of ${formatSize(ctx, total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    val icons = mapOf(
                        "Images" to Icons.Default.Image,
                        "Videos" to Icons.Default.Movie,
                        "Audio" to Icons.Default.MusicNote,
                        "Documents" to Icons.Default.Description,
                        "APKs" to Icons.Default.Android,
                        "Archives" to Icons.Default.FolderZip,
                        "Large files" to Icons.Default.Storage,
                        "Downloads" to Icons.Default.Download,
                    )
                    val all = Cats.exts.keys.toList() + Cats.extra
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        all.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { category ->
                                    CategoryShortcut(
                                        label = category,
                                        icon = icons[category] ?: Icons.Default.InsertDriveFile,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        vm.dir = null
                                        vm.cat = category
                                    }
                                }
                                repeat((4 - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item {
                    ListItem(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { vm.showVault = true },
                        leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                        headlineContent = { Text("Private vault") },
                        supportingContent = { Text("Protected with fingerprint, face, or device PIN") },
                    )
                }
                item {
                    Text(
                        "Recent files",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (recent.isEmpty()) {
                    item { EmptyState("No recent files yet") }
                } else {
                    items(recent, key = { it.path }) { file -> FileRow(file, vm, ctx) }
                }
            } else {
                if (vm.dir != null) item { Crumbs(vm) }
                if (files.isEmpty()) {
                    item { EmptyState("This folder is empty") }
                } else {
                    items(files, key = { it.path }) { file -> FileRow(file, vm, ctx) }
                }
            }
        }
    }

    when (val d = dialog) {
        FileDialog.NewFolder -> TextPromptDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { dialog = null },
        ) { name ->
            val parent = vm.dir
            if (parent != null) vm.io { mkdir(parent, name) }
            dialog = null
        }

        is FileDialog.Rename -> TextPromptDialog(
            title = "Rename",
            initial = d.file.name,
            confirmLabel = "Save",
            onDismiss = { dialog = null },
        ) { name ->
            vm.io { rename(d.file, name) }
            dialog = null
        }

        FileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${vm.selected.size} item(s)?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton({
                    val targets = vm.selected.toList()
                    vm.io { targets.forEach { delete(File(it)) } }
                    dialog = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )

        null -> Unit
    }
}

@Composable
private fun CategoryShortcut(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(14.dp).size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Crumbs(vm: VM) {
    val current = vm.dir ?: return
    val parts = generateSequence(current) { if (it == vm.repo.root) null else it.parentFile }.toList().reversed()
    LazyRow(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(parts) { index, part ->
            TextButton(onClick = { vm.dir = part }) {
                Text(if (part == vm.repo.root) "Internal storage" else part.name, maxLines = 1)
            }
            if (index < parts.lastIndex) {
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: File, vm: VM, ctx: Context) {
    val selected = file.path in vm.selected
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified()))
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {
                    when {
                        vm.selected.isNotEmpty() -> {
                            if (selected) vm.selected.remove(file.path) else vm.selected.add(file.path)
                        }

                        file.isDirectory -> {
                            vm.cat = null
                            vm.dir = file
                        }

                        isPlayable(file) -> vm.player = file

                        else -> openFile(ctx, file)
                    }
                },
                onLongClick = {
                    if (!selected) vm.selected.add(file.path)
                },
            ),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        leadingContent = {
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            } else if (isMedia(file)) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val icon = when {
                    file.isDirectory -> Icons.Default.Folder
                    file.extension.lowercase() in Cats.exts["Audio"].orEmpty() -> Icons.Default.AudioFile
                    file.extension.lowercase() in Cats.exts["Archives"].orEmpty() -> Icons.Default.FolderZip
                    file.extension.lowercase() == "apk" -> Icons.Default.Android
                    file.extension.lowercase() in Cats.exts["Documents"].orEmpty() -> Icons.Default.Description
                    else -> Icons.Default.InsertDriveFile
                }
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val meta = if (file.isDirectory) {
                "${file.listFiles()?.size ?: 0} items • $date"
            } else {
                "${formatSize(ctx, file.length())} • $date"
            }
            Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}