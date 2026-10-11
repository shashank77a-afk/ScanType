package com.scantype.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Languages the user can pick. "offline" means the on-device engine can really read it. */
object Langs {
    class L(val code: String, val label: String, val offline: Boolean)
    val all = listOf(
        L("auto", "Auto detect", true), L("hi", "Hindi", true), L("en", "English", true), L("mr", "Marathi", true), L("ne", "Nepali", true),
        L("bn", "Bengali", false), L("ta", "Tamil", false), L("te", "Telugu", false), L("gu", "Gujarati", false), L("pa", "Punjabi", false),
        L("ur", "Urdu", false), L("kn", "Kannada", false), L("ml", "Malayalam", false), L("or", "Odia", false),
        L("ar", "Arabic", false), L("fr", "French", false), L("es", "Spanish", false))
    fun offline(code: String) = all.firstOrNull { it.code == code }?.offline ?: true
    fun label(code: String) = all.firstOrNull { it.code == code }?.label ?: "Auto detect"
}

/** User settings, stored privately on the device. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("scantype", Context.MODE_PRIVATE)
    private val themeState = mutableIntStateOf(sp.getInt("theme", 0))

    var theme: Int
        get() = themeState.intValue
        set(v) { themeState.intValue = v; sp.edit().putInt("theme", v).apply() }
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
    var lang: String
        get() = sp.getString("lang", "auto") ?: "auto"
        set(v) { sp.edit().putString("lang", v).apply() }
    var autoCrop: Boolean
        get() = sp.getBoolean("auto_crop", true)
        set(v) { sp.edit().putBoolean("auto_crop", v).apply() }
    var autoCapture: Boolean
        get() = sp.getBoolean("auto_capture", false)
        set(v) { sp.edit().putBoolean("auto_capture", v).apply() }
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_on", false)
        set(v) { sp.edit().putBoolean("lock_on", v).apply() }
    var lockBio: Boolean
        get() = sp.getBoolean("lock_bio", false)
        set(v) { sp.edit().putBoolean("lock_bio", v).apply() }
    var lockHash: String
        get() = sp.getString("lock_hash", "") ?: ""
        set(v) { sp.edit().putString("lock_hash", v).apply() }
    var lockSalt: String
        get() = sp.getString("lock_salt", "") ?: ""
        set(v) { sp.edit().putString("lock_salt", v).apply() }
    val aiReady: Boolean get() = useAi && consented && apiKey.isNotBlank()
}

private const val PROMPT =
    "You are an expert transcriber of Hindi (Devanagari) and English handwriting and print. " +
    "Transcribe ALL the text in the image exactly as written.\n" +
    "Rules:\n" +
    "1. Do not summarize, translate, correct, modernize spelling or add any words. Keep names, numbers, dates, abbreviations and " +
    "punctuation (like । , - :-) exactly as written.\n" +
    "2. Keep the original line breaks and structure: headings, addresses, subject lines, list items (1. 2. 3.), signatures and closing " +
    "lines each on their own line, and a blank line between separate paragraphs.\n" +
    "3. Write each language in its own script. Keep mixed text as it is.\n" +
    "4. If a word is unclear, write your best reading followed by [?]. Never invent text that is not visible.\n" +
    "5. Output ONLY the transcribed text, with no explanation and no code fences. If nothing is legible, output exactly: UNREADABLE"

private fun prompt(lang: String) = PROMPT + if (lang == "auto") "" else "\nThe text is written in ${Langs.label(lang)}."

/** Cloud recognition via Google Gemini, using the user's own API key. Only used when the user turns it on. */
class GeminiOcrEngine(private val prefs: Prefs) : OcrEngine {
    override val requiresInternet = true
    override suspend fun recognize(ctx: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageFilters.load(ctx, uri, 2000)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos); bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt(prefs.lang)))
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

/** AI when the user turned it on, otherwise the on-device engine. */
fun pickEngine(prefs: Prefs, handwriting: Boolean): OcrEngine =
    if (prefs.aiReady) GeminiOcrEngine(prefs) else if (handwriting) Engines.handwriting else Engines.printed
