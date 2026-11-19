package app.aifiles

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VM(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    private val dao = Room.databaseBuilder(app, Db::class.java, "idx").build().dao()
    val indexer = Indexer(dao)
    init { Cloud.init(app) }
    val vaultDir = File(app.filesDir, "vault").apply { mkdirs() }
    var vaultUnlocked by mutableStateOf(false)
    var showVault by mutableStateOf(false)
    var player by mutableStateOf<File?>(null)
    var cloudBusy by mutableStateOf(false)

    // gallery + AI
    var media by mutableStateOf<List<Media>>(emptyList()); private set
    var idx by mutableStateOf<Map<String, Idx>>(emptyMap()); private set
    var viewer by mutableStateOf<Pair<List<Media>, Int>?>(null)
    val gsel = mutableStateListOf<Media>()

    // files
    var dir by mutableStateOf<File?>(null)
    var cat by mutableStateOf<String?>(null)
    var sort by mutableStateOf("Name")
    var showHidden by mutableStateOf(false)
    var tick by mutableIntStateOf(0)
    val selected = mutableStateListOf<File>()
    var clip by mutableStateOf<Pair<List<File>, Boolean>?>(null) // files, isMove

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        media = repo.media(); idx = dao.all().associateBy { it.path }
    }
    fun startIndex() = viewModelScope.launch {
        indexer.run(media) { idx = dao.all().associateBy { it.path } }
    }

    fun listFiles(): List<File> = when {
        cat != null -> if (cat == "Downloads") repo.list(File(repo.root, "Download"), showHidden, sort) else repo.category(cat!!)
        dir != null -> repo.list(dir!!, showHidden, sort)
        else -> emptyList()
    }

    fun io(block: Repo.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.block() }
        withContext(Dispatchers.Main) { selected.clear(); tick++ }
        refresh()
    }
    fun paste() { val (fs, mv) = clip ?: return; val d = dir ?: return; clip = null
        io { fs.forEach { if (mv) move(it, d) else copy(it, d) } } }

    fun deletePaths(paths: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        paths.forEach { runCatching { File(it).delete() } }
        repo.scanPaths(paths); dao.del(paths)
        withContext(Dispatchers.Main) { gsel.clear(); selected.clear(); tick++ }
        refresh()
    }

    fun smartRename(m: Media) = viewModelScope.launch(Dispatchers.IO) {
        repo.rename(File(m.path), Smart.smartName(m, idx[m.path])); refresh()
    }

    fun toVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        val fs = files.filter { it.isFile }
        fs.forEach { f -> runCatching { f.copyTo(repo.unique(vaultDir, f.name)); f.delete() } }
        repo.scanPaths(fs.map { it.path }); dao.del(fs.map { it.path })
        withContext(Dispatchers.Main) { selected.clear(); gsel.clear(); tick++ }
        refresh()
    }
    fun fromVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        val dest = File(repo.root, "Pictures/Restored").apply { mkdirs() }
        files.forEach { f -> runCatching { val d = repo.unique(dest, f.name); f.copyTo(d); f.delete(); repo.scan(d) } }
        withContext(Dispatchers.Main) { tick++ }; refresh()
    }
    fun deleteVault(files: List<File>) = viewModelScope.launch(Dispatchers.IO) {
        files.forEach { it.delete() }; withContext(Dispatchers.Main) { tick++ }
    }
    fun cloudOcr(m: Media) = viewModelScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) { cloudBusy = true }
        val text = Cloud.ocr(m.path)
        if (text != null) {
            val old = idx[m.path] ?: Idx(m.path, "", "", 0L, m.dateMs)
            dao.put(old.copy(ocr = text.take(4000))); idx = dao.all().associateBy { it.path }
        }
        withContext(Dispatchers.Main) { cloudBusy = false }
    }

    // cleaner sources
    suspend fun cleanSource(kind: String): List<CleanItem> = withContext(Dispatchers.IO) {
        when (kind) {
            "dups" -> Smart.dupes(idx.values.filter { File(it.path).exists() }, media.associate { it.path to it.size })
            "large" -> repo.large().map { CleanItem(it.path, it.length(), it.parent.orEmpty()) }
            "shots" -> media.filter { it.bucket.contains("screenshot", true) && System.currentTimeMillis() - it.dateMs > 30L * 86400_000 }
                .map { CleanItem(it.path, it.size, "Older than 30 days") }
            else -> repo.byExt(Cats.exts["APKs"]!!).map { CleanItem(it.path, it.length(), "Installer file") }
        }
    }
}
