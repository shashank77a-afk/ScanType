package com.scantype.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** User settings, stored privately on the device. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("scantype", Context.MODE_PRIVATE)
    var useAi: Boolean
        get() = sp.getBoolean("use_ai", false)
        set(v) { sp.edit().putBoolean("use_ai", v).apply() }
    var consented: Boolean
        get() = sp.getBoolean("consented", false)
        set(v) { sp.edit().putBoolean("consented", v).apply() }
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }
    var model: String
        get() = sp.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
        set(v) { sp.edit().putString("model", v.trim()).apply() }
    val aiReady: Boolean get() = useAi && consented && apiKey.isNotBlank()
}

private const val PROMPT =
    "Transcribe all the text in this image exactly as written, keeping the line breaks. " +
    "The text may be Hindi (Devanagari), English or mixed, printed or handwritten. " +
    "If a word is unclear, give your best reading. Output only the transcribed text, with no commentary."

/** Cloud recognition via Google Gemini, using the user's own API key. Only used when the user turns it on. */
class GeminiOcrEngine(private val prefs: Prefs) : OcrEngine {
    override val requiresInternet = true
    override suspend fun recognize(ctx: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageFilters.load(ctx, uri, 1600)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos); bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent"
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 90000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", prefs.apiKey)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
            } finally { c.disconnect() }
        }
    }
}
