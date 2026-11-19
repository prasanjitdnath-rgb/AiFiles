package app.aifiles

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.room.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Media(
    val id: Long, val path: String, val name: String, val isVideo: Boolean,
    val dateMs: Long, val bucket: String, val size: Long, val durationMs: Long,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

data class CleanItem(val path: String, val size: Long, val note: String)

// ---------------- Room: on-device AI index ----------------
@Entity data class Idx(
    @PrimaryKey val path: String,
    val labels: String,   // comma separated ML Kit labels
    val ocr: String,      // recognised text
    val hash: Long,       // 64-bit dHash for near-duplicate detection
    val mod: Long,
)
data class Stamp(val path: String, val mod: Long)

@Dao interface IdxDao {
    @Query("SELECT * FROM Idx") suspend fun all(): List<Idx>
    @Query("SELECT path, mod FROM Idx") suspend fun stamps(): List<Stamp>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(i: Idx)
    @Query("DELETE FROM Idx WHERE path IN (:p)") suspend fun del(p: List<String>)
}
@Database(entities = [Idx::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() { abstract fun dao(): IdxDao }

// ---------------- Storage access ----------------
object Cats {
    val exts = linkedMapOf(
        "Images" to listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"),
        "Videos" to listOf("mp4", "mkv", "webm", "3gp", "mov", "avi"),
        "Audio" to listOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus"),
        "Documents" to listOf("pdf", "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "txt", "epub"),
        "APKs" to listOf("apk", "apks", "xapk"),
        "Archives" to listOf("zip", "rar", "7z", "tar", "gz"),
    )
    val extra = listOf("Large files", "Downloads")
}

class Repo(private val ctx: Context) {
    private val cr = ctx.contentResolver
    val root: File = Environment.getExternalStorageDirectory()

    fun storage(): Pair<Long, Long> {
        val s = StatFs(root.path); return (s.totalBytes - s.availableBytes) to s.totalBytes
    }

    fun media(): List<Media> {
        val out = ArrayList<Media>()
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out)
        return out.sortedByDescending { it.dateMs }
    }

    private fun read(uri: Uri, video: Boolean, out: MutableList<Media>) {
        val m = MediaStore.MediaColumns
        val proj = arrayOf(
            m._ID,
            m.DATA,
            m.DISPLAY_NAME,
            m.DATE_MODIFIED,
            m.DATE_TAKEN,
            m.BUCKET_DISPLAY_NAME,
            m.SIZE,
            m.DURATION,
        )
        cr.query(uri, proj, null, null, "${m.DATE_MODIFIED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(m._ID)
            val dataCol = c.getColumnIndexOrThrow(m.DATA)
            val nameCol = c.getColumnIndex(m.DISPLAY_NAME)
            val modCol = c.getColumnIndex(m.DATE_MODIFIED)
            val takenCol = c.getColumnIndex(m.DATE_TAKEN)
            val bucketCol = c.getColumnIndex(m.BUCKET_DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(m.SIZE)
            val durCol = c.getColumnIndex(m.DURATION)
            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                val file = File(path)
                if (!file.exists()) continue
                val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                val modified = if (modCol >= 0) c.getLong(modCol) * 1000 else file.lastModified()
                out += Media(
                    id = c.getLong(idCol),
                    path = path,
                    name = if (nameCol >= 0) c.getString(nameCol) ?: file.name else file.name,
                    isVideo = video,
                    dateMs = if (taken > 0) taken else modified,
                    bucket = if (bucketCol >= 0) c.getString(bucketCol).orEmpty() else file.parentFile?.name.orEmpty(),
                    size = if (sizeCol >= 0) c.getLong(sizeCol) else file.length(),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                )
            }
        }
    }

    private fun files(sel: String?, args: Array<String>?, order: String, limit: Int): List<File> {
        val out = ArrayList<File>()
        val f = MediaStore.Files.FileColumns
        cr.query(MediaStore.Files.getContentUri("external"), arrayOf(f.DATA), sel, args, order)?.use { c ->
            val dataCol = c.getColumnIndex(f.DATA)
            if (dataCol < 0) return emptyList()
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(dataCol) ?: continue
                val file = File(path)
                if (file.isFile) out += file
            }
        }
        return out
    }

    fun recent(): List<File> = files("${MediaStore.Files.FileColumns.SIZE}>0 AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE '%/Android/%' AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE '%/.%'",
        null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 40)

    fun byExt(exts: List<String>, limit: Int = 1000): List<File> {
        val sel = exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }
        return files(sel, exts.map { "%.$it" }.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)
    }

    fun large(minBytes: Long = 100L * 1024 * 1024): List<File> =
        files("${MediaStore.Files.FileColumns.SIZE}>?", arrayOf(minBytes.toString()), "${MediaStore.Files.FileColumns.SIZE} DESC", 200)

    fun category(name: String): List<File> = when (name) {
        "Large files" -> large()
        "Downloads" -> list(File(root, "Download"), hidden = false, sort = "Date").filter { it.isFile }
        else -> byExt(Cats.exts[name].orEmpty())
    }

    fun list(dir: File, hidden: Boolean, sort: String): List<File> {
        val l = (dir.listFiles() ?: return emptyList()).filter { hidden || !it.name.startsWith(".") }
        val cmp: Comparator<File> = when (sort) {
            "Size" -> compareByDescending { if (it.isDirectory) -1L else it.length() }
            "Date" -> compareByDescending { it.lastModified() }
            else -> compareBy { it.name.lowercase() }
        }
        return l.sortedWith(compareByDescending<File> { it.isDirectory }.then(cmp))
    }

    // ---- operations ----
    fun unique(dir: File, name: String): File {
        var f = File(dir, name); var i = 1
        val base = f.nameWithoutExtension; val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }
    fun copy(src: File, dir: File) {
        if (!src.exists()) return
        dir.mkdirs()
        val destination = unique(dir, src.name)
        if (src.isDirectory) {
            src.copyRecursively(destination, overwrite = false)
        } else {
            src.copyTo(destination, overwrite = false)
        }
        scan(destination)
    }

    fun move(src: File, dir: File) {
        if (!src.exists()) return
        dir.mkdirs()
        val destination = unique(dir, src.name)
        val renamed = src.parentFile?.path == dir.path && src.renameTo(destination)
        if (!renamed) {
            val copied = runCatching {
                if (src.isDirectory) src.copyRecursively(destination, overwrite = false) else src.copyTo(destination, overwrite = false)
            }.isSuccess
            if (copied) src.deleteRecursively()
        }
        scan(src, destination)
    }

    fun delete(f: File) {
        if (!f.exists()) return
        f.deleteRecursively()
        scan(f)
    }

    fun rename(f: File, name: String): Boolean {
        val parent = f.parentFile ?: return false
        val dest = File(parent, name)
        if (dest.exists()) return false
        val ok = f.renameTo(dest)
        if (ok) scan(f, dest)
        return ok
    }

    fun mkdir(dir: File, name: String): Boolean = File(dir, name).mkdirs()

    fun zip(files: List<File>, dir: File) {
        if (files.isEmpty()) return
        dir.mkdirs()
        val archiveName = (if (files.size == 1) files.first().nameWithoutExtension.ifBlank { "Archive" } else "Archive") + ".zip"
        val out = unique(dir, archiveName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            files.forEach { rootFile ->
                val baseParent = rootFile.parentFile ?: dir
                if (rootFile.isDirectory) {
                    rootFile.walkTopDown().filter { it.isFile }.forEach { file ->
                        val entryName = file.relativeTo(baseParent).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                } else if (rootFile.isFile) {
                    zip.putNextEntry(ZipEntry(rootFile.name))
                    rootFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        scan(out)
    }

    fun scan(vararg f: File) {
        val paths = f.map { it.path }.filter { it.isNotBlank() }.toTypedArray()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths, null, null)
    }

    fun scanPaths(p: List<String>) {
        val paths = p.filter { it.isNotBlank() }.toTypedArray()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths, null, null)
    }
}
