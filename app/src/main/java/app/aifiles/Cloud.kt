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

/** Optional, opt-in cloud OCR via the Gemini API. The user supplies their own API key. */
object Cloud {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        }
    }

    var key: String
        get() = if (::prefs.isInitialized) prefs.getString("key", "").orEmpty() else ""
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString("key", value.trim()).apply()
        }

    var model: String
        get() = if (::prefs.isInitialized) prefs.getString("model", "gemini-2.5-flash").orEmpty().ifBlank { "gemini-2.5-flash" } else "gemini-2.5-flash"
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString("model", value.trim().ifBlank { "gemini-2.5-flash" }).apply()
        }

    var auto: Boolean
        get() = ::prefs.isInitialized && prefs.getBoolean("auto", false)
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean("auto", value).apply()
        }

    val ready: Boolean get() = key.isNotBlank()

    /** Blocking call; must be invoked off the main thread. Returns null on failure. */
    fun ocr(path: String): String? {
        if (!ready) return null
        val bitmap = decodeBitmap(path, 1600) ?: return null
        return try {
            val bytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                stream.toByteArray()
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val request = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", "Extract all visible text exactly as written. Preserve language, numbers, and line breaks. Output only the text."))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", base64))))))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("x-goog-api-key", key)

                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(response)
                val candidates = root.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts") ?: return null
                if (parts.length() == 0) return null
                parts.getJSONObject(0).optString("text", "").trim().ifBlank { null }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}