package app.aifiles

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

fun uriFor(c: Context, f: File) = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
fun openFile(c: Context, f: File) = runCatching {
    c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(c, f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
fun shareFiles(c: Context, fs: List<File>) = runCatching {
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(fs.filter { it.isFile }.map { uriFor(c, it) }))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, "Share"))
}
fun isMedia(f: File) = f.extension.lowercase().let { it in Cats.exts["Images"]!! || it in Cats.exts["Videos"]!! }

private sealed class Dlg { object NewDir : Dlg(); class Rename(val f: File) : Dlg(); object Delete : Dlg() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesTab(vm: VM) {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val files by produceState(emptyList<File>(), vm.dir, vm.cat, vm.tick, vm.sort, vm.showHidden) {
        value = withContext(Dispatchers.IO) { vm.listFiles() }
    }
    val home = vm.dir == null && vm.cat == null
    val recent by produceState(emptyList<File>(), vm.tick) { value = withContext(Dispatchers.IO) { vm.repo.recent() } }

    BackHandler(enabled = !home || vm.selected.isNotEmpty()) {
        when {
            vm.selected.isNotEmpty() -> vm.selected.clear()
            vm.cat != null -> vm.cat = null
            vm.dir == vm.repo.root -> vm.dir = null
            else -> vm.dir = vm.dir?.parentFile
        }
    }

    Scaffold(
        topBar = {
            if (vm.selected.isNotEmpty()) TopAppBar(
                title = { Text("${vm.selected.size} selected") },
                navigationIcon = { IconButton({ vm.selected.clear() }) { Icon(Icons.Default.Close, null) } },
                actions = {
                    IconButton({ vm.selected.clear(); vm.selected.addAll(files) }) { Icon(Icons.Default.SelectAll, null) }
                    IconButton({ vm.clip = vm.selected.toList() to false; vm.selected.clear() }) { Icon(Icons.Default.ContentCopy, null) }
                    IconButton({ vm.clip = vm.selected.toList() to true; vm.selected.clear() }) { Icon(Icons.Default.ContentCut, null) }
                    IconButton({ shareFiles(ctx, vm.selected.toList()) }) { Icon(Icons.Default.Share, null) }
                    IconButton({ vm.toVault(vm.selected.toList()) }) { Icon(Icons.Default.Lock, null) }
                    IconButton({ val d = vm.dir ?: vm.selected[0].parentFile!!; val s = vm.selected.toList(); vm.io { zip(s, d) } }) { Icon(Icons.Default.FolderZip, null) }
                    if (vm.selected.size == 1) IconButton({ dlg = Dlg.Rename(vm.selected[0]) }) { Icon(Icons.Default.Edit, null) }
                    IconButton({ dlg = Dlg.Delete }) { Icon(Icons.Default.Delete, null) }
                })
            else TopAppBar(
                title = { Text(vm.cat ?: vm.dir?.let { if (it == vm.repo.root) "Internal storage" else it.name } ?: "Files") },
                navigationIcon = { if (!home) IconButton({ vm.cat = null; vm.dir = if (vm.dir == vm.repo.root || vm.dir == null) null else vm.dir?.parentFile }) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton({ sortMenu = true }) { Icon(Icons.Default.Sort, null) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Name", "Date", "Size").forEach { s -> DropdownMenuItem({ Text(if (vm.sort == s) "✓ $s" else s) }, { vm.sort = s; sortMenu = false }) }
                        DropdownMenuItem({ Text(if (vm.showHidden) "✓ Show hidden" else "Show hidden") }, { vm.showHidden = !vm.showHidden; sortMenu = false })
                    }
                })
        },
        floatingActionButton = {
            val d = vm.dir
            if (d != null && vm.clip != null)
                ExtendedFloatingActionButton(
                    text = { Text("Paste (${vm.clip!!.first.size})") },
                    icon = { Icon(Icons.Default.ContentPaste, null) },
                    onClick = { vm.paste() })
            else if (d != null) FloatingActionButton({ dlg = Dlg.NewDir }) { Icon(Icons.Default.CreateNewFolder, null) }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (home) {
                item {
                    val (used, total) = remember(vm.tick) { vm.repo.storage() }
                    ElevatedCard(Modifier.padding(16.dp).fillMaxWidth().clickable { vm.dir = vm.repo.root }) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator({ used.toFloat() / total }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                            Spacer(Modifier.height(6.dp))
                            Text("${Formatter.formatFileSize(ctx, used)} used of ${Formatter.formatFileSize(ctx, total)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    val icons = mapOf("Images" to Icons.Default.Image, "Videos" to Icons.Default.Movie, "Audio" to Icons.Default.MusicNote,
                        "Documents" to Icons.Default.Description, "APKs" to Icons.Default.Android, "Archives" to Icons.Default.FolderZip,
                        "Large files" to Icons.Default.Storage, "Downloads" to Icons.Default.Download)
                    val all = Cats.exts.keys.toList() + Cats.extra
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        all.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { c ->
                                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { vm.cat = c }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Icon(icons[c]!!, null, Modifier.padding(14.dp).size(26.dp))
                                        }
                                        Text(c, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    ListItem(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { vm.showVault = true },
                        leadingContent = { Icon(Icons.Default.Lock, null) }, headlineContent = { Text("Private vault") },
                        supportingContent = { Text("Locked with fingerprint / PIN") })
                }
                item { Text("Recent", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { it.path }) { f -> FileRow(f, vm, ctx) }
            } else {
                if (vm.dir != null) item { Crumbs(vm) }
                if (files.isEmpty()) item { Text("Empty", Modifier.padding(32.dp)) }
                items(files, key = { it.path }) { f -> FileRow(f, vm, ctx) }
            }
        }
    }

    when (val d = dlg) {
        Dlg.NewDir -> TextDlg("New folder", "", { dlg = null }) { n -> val p = vm.dir!!; vm.io { mkdir(p, n) }; dlg = null }
        is Dlg.Rename -> TextDlg("Rename", d.f.name, { dlg = null }) { n -> vm.io { rename(d.f, n) }; dlg = null }
        Dlg.Delete -> AlertDialog({ dlg = null },
            title = { Text("Delete ${vm.selected.size} item(s)?") }, text = { Text("This cannot be undone.") },
            confirmButton = { TextButton({ val s = vm.selected.toList(); vm.io { s.forEach { delete(it) } }; dlg = null }) { Text("Delete") } },
            dismissButton = { TextButton({ dlg = null }) { Text("Cancel") } })
        null -> {}
    }
}

@Composable
private fun TextDlg(title: String, init: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(init) }
    AlertDialog(onDismiss, title = { Text(title) }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("OK") } })
}

@Composable
private fun Crumbs(vm: VM) {
    val parts = generateSequence(vm.dir) { if (it == vm.repo.root) null else it.parentFile }.toList().reversed()
    LazyRow(Modifier.padding(horizontal = 12.dp)) {
        items(parts) { p ->
            TextButton({ vm.dir = p }) { Text(if (p == vm.repo.root) "Internal" else p.name, maxLines = 1) }
            Text("›", Modifier.align(Alignment.CenterVertically))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: File, vm: VM, ctx: Context) {
    val sel = f in vm.selected
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {
                when {
                    vm.selected.isNotEmpty() -> if (sel) vm.selected.remove(f) else vm.selected.add(f)
                    f.isDirectory -> { vm.cat = null; vm.dir = f }
                    isPlayable(f) -> vm.player = f
                    else -> openFile(ctx, f)
                }
            },
            onLongClick = { if (!sel) vm.selected.add(f) }),
        colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        leadingContent = {
            if (sel) Icon(Icons.Default.CheckCircle, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            else if (isMedia(f)) AsyncImage(f, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            else Icon(when {
                f.isDirectory -> Icons.Default.Folder
                f.extension.lowercase() in Cats.exts["Audio"]!! -> Icons.Default.AudioFile
                f.extension.lowercase() == "apk" -> Icons.Default.Android
                f.extension.lowercase() in Cats.exts["Archives"]!! -> Icons.Default.FolderZip
                else -> Icons.Default.InsertDriveFile
            }, null, Modifier.size(44.dp), tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val d = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.lastModified()))
            Text(if (f.isDirectory) "${f.list()?.size ?: 0} items · $d" else "${Formatter.formatFileSize(ctx, f.length())} · $d")
        })
}
