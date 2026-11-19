package app.aifiles

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ViewerState(val items: List<Media>, val index: Int)

class VM(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    private val dao = Room.databaseBuilder(app, Db::class.java, "idx").fallbackToDestructiveMigration().build().dao()
    val indexer = Indexer(dao)

    init {
        Cloud.init(app)
    }

    val vaultDir: File = File(app.filesDir, "vault").apply { mkdirs() }
    var vaultUnlocked by mutableStateOf(false)
    var showVault by mutableStateOf(false)
    var player by mutableStateOf<File?>(null)
    var cloudBusy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    // gallery + AI
    var media by mutableStateOf<List<Media>>(emptyList())
        private set
    var idx by mutableStateOf<Map<String, Idx>>(emptyMap())
        private set
    var viewer by mutableStateOf<ViewerState?>(null)
    val gallerySelection = mutableStateListOf<String>()

    // files
    var dir by mutableStateOf<File?>(null)
    var cat by mutableStateOf<String?>(null)
    var sort by mutableStateOf("Name")
    var showHidden by mutableStateOf(false)
    var tick by mutableIntStateOf(0)
    val selected = mutableStateListOf<String>()
    var clip by mutableStateOf<Pair<List<File>, Boolean>?>(null) // files, isMove

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val nextMedia = repo.media()
            val nextIdx = dao.all().associateBy { it.path }
            withContext(Dispatchers.Main) {
                media = nextMedia
                idx = nextIdx
            }
        }
    }

    fun startIndex() = viewModelScope.launch {
        indexer.run(media) { idx = dao.all().associateBy { it.path } }
    }

    fun lockVault() {
        vaultUnlocked = false
    }

    fun openViewer(items: List<Media>, index: Int) {
        viewer = ViewerState(items, index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }

    fun notify(message: String) {
        notice = message
    }

    fun listFiles(): List<File> = when {
        cat != null -> if (cat == "Downloads") repo.list(File(repo.root, "Download"), showHidden, sort) else repo.category(cat!!)
        dir != null -> repo.list(dir!!, showHidden, sort)
        else -> emptyList()
    }

    fun io(onDone: ((Boolean) -> Unit)? = null, block: Repo.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching { repo.block() }.isSuccess
        withContext(Dispatchers.Main) {
            selected.clear()
            tick++
            if (!ok) notify("Action could not be completed")
            onDone?.invoke(ok)
        }
        refresh()
    }

    fun paste() {
        val (files, isMove) = clip ?: return
        val destination = dir ?: return
        val canonicalDestination = destination.canonicalPath
        val invalid = files.any { source ->
            runCatching {
                val canonicalSource = source.canonicalPath
                canonicalDestination == canonicalSource || canonicalDestination.startsWith("$canonicalSource${File.separator}")
            }.getOrDefault(false)
        }
        clip = null
        if (invalid) {
            notify("A folder cannot be pasted inside itself")
            return
        }
        io { files.forEach { if (isMove) move(it, destination) else copy(it, destination) } }
    }

    fun deletePaths(paths: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        paths.forEach { path -> runCatching { File(path).deleteRecursively() } }
        repo.scanPaths(paths)
        dao.del(paths)
        withContext(Dispatchers.Main) {
            gallerySelection.removeAll(paths.toSet())
            selected.removeAll(paths.toSet())
            viewer = viewer?.let { state ->
                val remaining = state.items.filterNot { it.path in paths }
                if (remaining.isEmpty()) null else ViewerState(remaining, state.index.coerceAtMost(remaining.lastIndex))
            }
            tick++
        }
        refresh()
    }

    fun smartRename(m: Media) = viewModelScope.launch(Dispatchers.IO) {
        val renamed = repo.rename(File(m.path), Smart.smartName(m, idx[m.path]))
        withContext(Dispatchers.Main) {
            if (!renamed) notify("Could not rename file")
            tick++
        }
        refresh()
    }

    fun toVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        val candidates = files.filter { it.isFile }
        candidates.forEach { file ->
            runCatching {
                val destination = repo.unique(vaultDir, file.name)
                file.copyTo(destination, overwrite = false)
                file.delete()
            }
        }
        repo.scanPaths(candidates.map { it.path })
        dao.del(candidates.map { it.path })
        withContext(Dispatchers.Main) {
            selected.clear()
            gallerySelection.clear()
            tick++
            notify("Moved ${candidates.size} item(s) to vault")
        }
        refresh()
    }

    fun fromVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        val dest = File(repo.root, "Pictures/Restored").apply { mkdirs() }
        files.forEach { file ->
            runCatching {
                val destination = repo.unique(dest, file.name)
                file.copyTo(destination, overwrite = false)
                file.delete()
                repo.scan(destination)
            }
        }
        withContext(Dispatchers.Main) {
            tick++
            notify("Restored ${files.size} item(s)")
        }
        refresh()
    }

    fun deleteVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        files.forEach { runCatching { it.deleteRecursively() } }
        withContext(Dispatchers.Main) { tick++ }
    }

    fun cloudOcr(m: Media) = viewModelScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) { cloudBusy = true }
        val text = Cloud.ocr(m.path)
        if (text != null) {
            val old = idx[m.path] ?: Idx(m.path, "", "", 0L, m.dateMs)
            dao.put(old.copy(ocr = text.take(4000)))
            idx = dao.all().associateBy { it.path }
            withContext(Dispatchers.Main) { notify("Cloud OCR completed") }
        } else {
            withContext(Dispatchers.Main) { notify("Cloud OCR failed. Check your API key, model, and network.") }
        }
        withContext(Dispatchers.Main) { cloudBusy = false }
    }

    suspend fun cleanSource(kind: String): List<CleanItem> = withContext(Dispatchers.IO) {
        when (kind) {
            "dups" -> Smart.dupes(idx.values.filter { File(it.path).exists() }, media.associate { it.path to it.size })
            "large" -> repo.large().map { CleanItem(it.path, it.length(), it.parent.orEmpty()) }
            "shots" -> media.filter { it.bucket.contains("screenshot", true) && System.currentTimeMillis() - it.dateMs > 30L * 86_400_000 }
                .map { CleanItem(it.path, it.size, "Older than 30 days") }
            else -> repo.byExt(Cats.exts["APKs"].orEmpty()).map { CleanItem(it.path, it.length(), "Installer file") }
        }
    }
}
