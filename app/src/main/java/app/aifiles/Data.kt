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
        val proj = if (video) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                "_data",
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                "datetaken",
                "bucket_display_name",
                MediaStore.MediaColumns.SIZE,
                "duration",
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                "_data",
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                "datetaken",
                "bucket_display_name",
                MediaStore.MediaColumns.SIZE,
            )
        }
        cr.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                val taken = c.getLong(4)
                out += Media(
                    c.getLong(0), path, c.getString(2) ?: File(path).name, video,
                    if (taken > 0) taken else c.getLong(3) * 1000,
                    c.getString(5) ?: "", c.getLong(6), if (video) c.getLong(7) else 0L,
                )
            }
        }
    }

    private fun files(sel: String?, args: Array<String>?, order: String, limit: Int): List<File> {
        val out = ArrayList<File>()
        cr.query(MediaStore.Files.getContentUri("external"), arrayOf("_data"), sel, args, order)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val f = File(c.getString(0) ?: continue)
                if (f.isFile) out += f
            }
        }
        return out
    }

    fun recent(): List<File> = files(
        "${MediaStore.Files.FileColumns.SIZE}>0 AND _data NOT LIKE '%/Android/%' AND _data NOT LIKE '%/.%'",
        null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 40)

    fun byExt(exts: List<String>, limit: Int = 1000): List<File> {
        val sel = exts.joinToString(" OR ") { "_data LIKE ?" }
        return files(sel, exts.map { "%.$it" }.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit)
    }

    fun large(minBytes: Long = 100L * 1024 * 1024): List<File> =
        files("${MediaStore.Files.FileColumns.SIZE}>?", arrayOf(minBytes.toString()), "${MediaStore.Files.FileColumns.SIZE} DESC", 200)

    fun category(name: String): List<File> = when (name) {
        "Large files" -> large()
        else -> byExt(Cats.exts[name] ?: emptyList())
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
    fun copy(src: File, dir: File) { val d = unique(dir, src.name); src.copyRecursively(d); scan(d) }
    fun move(src: File, dir: File) {
        val d = unique(dir, src.name)
        if (!src.renameTo(d)) { src.copyRecursively(d); src.deleteRecursively() }
        scan(src, d)
    }
    fun delete(f: File) { f.deleteRecursively(); scan(f) }
    fun rename(f: File, name: String): Boolean { val d = File(f.parentFile, name); val ok = !d.exists() && f.renameTo(d); if (ok) scan(f, d); return ok }
    fun mkdir(dir: File, name: String) = File(dir, name).mkdirs()
    fun zip(files: List<File>, dir: File) {
        val out = unique(dir, (if (files.size == 1) files[0].nameWithoutExtension else "Archive") + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            files.forEach { root ->
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry(f.relativeTo(root.parentFile!!).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        scan(out)
    }
    fun scan(vararg f: File) = MediaScannerConnection.scanFile(ctx, f.map { it.path }.toTypedArray(), null, null)
    fun scanPaths(p: List<String>) = MediaScannerConnection.scanFile(ctx, p.toTypedArray(), null, null)
}
