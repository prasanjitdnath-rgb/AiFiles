package app.aifiles

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Optional, opt-in cloud OCR via the Gemini API (reads Bengali, handwriting, etc.). The user supplies their own API key. */
object Cloud {
    private lateinit var p: SharedPreferences
    fun init(c: Context) { if (!::p.isInitialized) p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    var key: String get() = p.getString("key", "")!!; set(v) = p.edit().putString("key", v.trim()).apply()
    var model: String get() = p.getString("model", "gemini-2.5-flash")!!; set(v) = p.edit().putString("model", v.trim()).apply()
    var auto: Boolean get() = p.getBoolean("auto", false); set(v) = p.edit().putBoolean("auto", v).apply()
    val ready get() = key.isNotBlank()

    /** Blocking call, run off the main thread. Returns null on failure. */
    fun ocr(path: String): String? = runCatching {
        val bmp = decodeBitmap(path, 1600) ?: return null
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos); bmp.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", "Extract all visible text exactly as written (Bengali, English, numbers, handwriting). Output only the text."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) return null
        JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }.getOrNull()
}
