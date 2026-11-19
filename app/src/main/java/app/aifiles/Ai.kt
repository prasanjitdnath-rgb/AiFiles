package app.aifiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Runs ML Kit entirely on-device: labels + OCR + perceptual hash for every photo. */
fun decodeBitmap(path: String, max: Int = 1280): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > max) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

class Indexer(private val dao: IdxDao) {
    val progress = MutableStateFlow(0 to 0)
    val running = MutableStateFlow(false)
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun run(items: List<Media>, onBatch: suspend () -> Unit) = withContext(Dispatchers.Default) {
        if (!running.compareAndSet(false, true)) return@withContext
        try {
            val done = dao.stamps().associate { it.path to it.mod }
            val todo = items.filter { !it.isVideo && done[it.path] != it.dateMs }
            todo.forEachIndexed { i, m ->
                progress.value = i to todo.size
                val bmp = decodeBitmap(m.path)
                if (bmp != null) {
                    val img = InputImage.fromBitmap(bmp, 0)
                    val labels = runCatching { labeler.process(img).await().joinToString(",") { it.text.lowercase() } }.getOrDefault("")
                    var text = runCatching { ocr.process(img).await().text }.getOrDefault("")
                    if (Cloud.ready && Cloud.auto && m.bucket.contains("screenshot", true)) Cloud.ocr(m.path)?.let { text = it }
                    dao.put(Idx(m.path, labels, text.take(4000), dHash(bmp), m.dateMs))
                    bmp.recycle()
                }
                if (i % 20 == 19) onBatch()
            }
            progress.value = todo.size to todo.size
            onBatch()
        } finally { running.value = false }
    }

    private fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8)
            h = (h shl 1) or (if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) 1L else 0L)
        if (s !== b) s.recycle()
        return h
    }
}

object Smart {
    private val stop = setOf("of", "the", "from", "my", "show", "me", "all", "a", "in", "with", "and", "photos", "photo", "pictures", "picture", "images", "image", "pics", "find")
    private val syn = mapOf(
        "bill" to listOf("bill", "receipt", "invoice", "challan", "total", "amount"),
        "bills" to listOf("bill", "receipt", "invoice", "challan", "total", "amount"),
        "document" to listOf("document", "text", "paper", "font", "handwriting"),
        "documents" to listOf("document", "text", "paper", "font", "handwriting"),
        "selfie" to listOf("selfie", "face", "smile", "person"),
        "people" to listOf("person", "face", "smile", "selfie"),
        "food" to listOf("food", "dish", "meal", "cuisine", "fruit", "vegetable"),
        "pet" to listOf("dog", "cat", "pet"), "pets" to listOf("dog", "cat", "pet"),
        "car" to listOf("car", "vehicle"), "nature" to listOf("sky", "tree", "flower", "plant", "mountain", "sea", "beach"),
    )

    private fun day(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    /** Understands things like "screenshots last week", "videos today", "bills with total", "food". */
    fun search(query: String, media: List<Media>, idx: Map<String, Idx>): List<Media> {
        var q = " ${query.lowercase().trim()} "
        var from = Long.MIN_VALUE; var to = Long.MAX_VALUE
        var video: Boolean? = null; var shots = false
        fun eat(s: String) = q.contains(" $s ").also { if (it) q = q.replace(" $s ", " ") }
        if (eat("today")) from = day(0)
        else if (eat("yesterday")) { from = day(-1); to = day(0) }
        else if (eat("last week")) { from = day(-14); to = day(-7) }
        else if (eat("this week")) from = day(-7)
        else if (eat("last month")) { from = day(-60); to = day(-30) }
        else if (eat("this month")) from = day(-30)
        if (eat("videos") || eat("video")) video = true
        if (eat("screenshots") || eat("screenshot")) shots = true
        val words = q.split(" ").filter { it.isNotBlank() && it !in stop }
        return media.filter { m ->
            if (m.dateMs < from || m.dateMs >= to) return@filter false
            if (video == true && !m.isVideo) return@filter false
            if (shots && !m.bucket.contains("screenshot", true)) return@filter false
            if (words.isEmpty()) return@filter true
            val i = idx[m.path]
            val hay = "${m.name} ${m.bucket} ${i?.labels.orEmpty()} ${i?.ocr.orEmpty()}".lowercase()
            words.all { w -> (syn[w] ?: listOf(w)).any { hay.contains(it) } }
        }
    }

    fun albums(media: List<Media>, idx: Map<String, Idx>): Map<String, List<Media>> {
        val rules = linkedMapOf(
            "People" to listOf("person", "selfie", "smile", "face", "skin", "child", "baby", "toddler", "hair"),
            "Food" to listOf("food", "dish", "cuisine", "meal", "fruit", "vegetable"),
            "Nature" to listOf("sky", "mountain", "tree", "flower", "plant", "beach", "sea", "lake", "cloud", "grass"),
            "Pets" to listOf("dog", "cat", "pet", "bird"),
            "Vehicles" to listOf("car", "vehicle", "motorcycle", "bus", "truck", "bicycle"),
        )
        val out = linkedMapOf<String, MutableList<Media>>()
        for (m in media) {
            if (m.isVideo) { out.getOrPut("Videos") { mutableListOf() } += m; continue }
            val i = idx[m.path] ?: continue
            if (i.ocr.length > 40) out.getOrPut("Documents") { mutableListOf() } += m
            val ls = i.labels.split(",")
            rules.forEach { (k, v) -> if (ls.any { it in v }) out.getOrPut(k) { mutableListOf() } += m }
        }
        return out
    }

    /** Near-duplicate groups via dHash Hamming distance (union-find). Keeps the largest file of each group. */
    fun dupes(idx: List<Idx>, sizes: Map<String, Long>, maxDist: Int = 4): List<CleanItem> {
        val l = idx.filter { it.hash != 0L }.take(8000)
        val p = IntArray(l.size) { it }
        fun f(x: Int): Int { var a = x; while (p[a] != a) { p[a] = p[p[a]]; a = p[a] }; return a }
        for (a in l.indices) for (b in a + 1 until l.size)
            if (java.lang.Long.bitCount(l[a].hash xor l[b].hash) <= maxDist) p[f(a)] = f(b)
        val out = ArrayList<CleanItem>()
        l.indices.groupBy { f(it) }.values.filter { it.size > 1 }.forEach { g ->
            val keep = g.maxBy { sizes[l[it].path] ?: 0L }
            val keepName = l[keep].path.substringAfterLast('/')
            g.filter { it != keep }.forEach { out += CleanItem(l[it].path, sizes[l[it].path] ?: 0L, "Similar to $keepName") }
        }
        return out
    }

    fun smartName(m: Media, i: Idx?): String {
        val ocr = i?.ocr.orEmpty().split(Regex("\\s+")).filter { it.length > 2 }.take(3).joinToString("_")
        val base = ocr.ifBlank { i?.labels.orEmpty().split(",").filter { it.isNotBlank() }.take(2).joinToString("_") }
            .replace(Regex("[^A-Za-z0-9_]"), "").ifBlank { "image" }
        val d = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(m.dateMs))
        return "${base}_$d.${m.name.substringAfterLast('.', "jpg")}"
    }
}
