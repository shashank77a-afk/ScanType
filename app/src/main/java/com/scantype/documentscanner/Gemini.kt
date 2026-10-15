package com.scantype.documentscanner

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

class UsageInfo(val used: Int, val limit: Int, val resetsAt: Long)

/** User settings, stored privately on the device. The session token is encrypted with the Android Keystore. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("scantype", Context.MODE_PRIVATE)
    private val themeState = mutableIntStateOf(sp.getInt("theme", 0))
    private val signedState = mutableStateOf(!sp.getString("session", null).isNullOrEmpty())
    var usage by mutableStateOf<UsageInfo?>(loadUsage())
        private set

    private fun loadUsage(): UsageInfo? =
        if (sp.contains("u_limit")) UsageInfo(sp.getInt("u_used", 0), sp.getInt("u_limit", 0), sp.getLong("u_reset", 0L)) else null

    fun applyUsage(j: JSONObject) {
        val u = UsageInfo(j.optInt("used"), j.optInt("limit"), j.optLong("resetsAt"))
        usage = u
        sp.edit().putInt("u_used", u.used).putInt("u_limit", u.limit).putLong("u_reset", u.resetsAt).apply()
    }

    /** Removes the session and everything cached about the account. */
    fun clearSession() {
        sp.edit().remove("session").remove("session_exp").remove("user_email").remove("user_name")
            .remove("u_used").remove("u_limit").remove("u_reset").apply()
        usage = null
        signedState.value = false
    }

    var theme: Int
        get() = themeState.intValue
        set(v) { themeState.intValue = v; sp.edit().putInt("theme", v).apply() }
    var useAi: Boolean
        get() = sp.getBoolean("use_ai", false)
        set(v) { sp.edit().putBoolean("use_ai", v).apply() }
    var consented: Boolean
        get() = sp.getBoolean("consented", false)
        set(v) { sp.edit().putBoolean("consented", v).apply() }
    /** Developer mode only: a personal Gemini key, used when no server address is set. */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }
    var model: String
        get() = sp.getString("model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(v) { sp.edit().putString("model", v.trim()).apply() }
    var backendUrl: String
        get() = sp.getString("backend_url", BuildConfig.BACKEND_URL) ?: ""
        set(v) { sp.edit().putString("backend_url", v.trim()).apply() }
    var googleClientId: String
        get() = sp.getString("google_client", BuildConfig.GOOGLE_CLIENT_ID) ?: ""
        set(v) { sp.edit().putString("google_client", v.trim()).apply() }
    var session: String?
        get() = sp.getString("session", null)?.let { SecureStore.decrypt(it) }
        set(v) {
            sp.edit().apply { if (v == null) remove("session") else putString("session", SecureStore.encrypt(v)) }.apply()
            signedState.value = v != null
        }
    var sessionExpires: Long
        get() = sp.getLong("session_exp", 0L)
        set(v) { sp.edit().putLong("session_exp", v).apply() }
    var userEmail: String
        get() = sp.getString("user_email", "") ?: ""
        set(v) { sp.edit().putString("user_email", v).apply() }
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) { sp.edit().putString("user_name", v).apply() }
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

    val signedIn: Boolean get() = signedState.value
    val aiViaBackend: Boolean get() = backendUrl.isNotBlank()
    /** AI is used only when the user turned it on AND agreed, and a server (or developer key) is set up. */
    val aiReady: Boolean get() = useAi && consented && (aiViaBackend || apiKey.isNotBlank())
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

fun langNote(lang: String) = if (lang == "auto") "" else "\nThe text is written in ${Langs.label(lang)}."

/** Developer mode: calls Gemini directly with a personal key stored on this phone. Not used when a server address is set. */
suspend fun geminiDirect(prefs: Prefs, promptText: String, jpeg: ByteArray, json: Boolean): String = withContext(Dispatchers.IO) {
    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
        .put(JSONObject().put("text", promptText))
        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
    if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
    val url = "https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent"
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 90000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", prefs.apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) throw AiException("ai_error", "The AI request failed (code ${c.responseCode}).")
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
    } catch (e: java.io.IOException) {
        throw AiException("network", "No internet connection.")
    } finally { c.disconnect() }
}

/** Text recognition through the ScanType server (all AI traffic goes there; no key is inside the app). */
class BackendOcrEngine(private val prefs: Prefs) : OcrEngine {
    override val requiresInternet = true
    override suspend fun recognize(ctx: Context, uri: Uri): Result<String> = runCatching {
        Backend.ocr(prefs, jpegBytes(ctx, uri), prefs.lang)
    }
}

/** Developer mode engine (personal key). */
class GeminiOcrEngine(private val prefs: Prefs) : OcrEngine {
    override val requiresInternet = true
    override suspend fun recognize(ctx: Context, uri: Uri): Result<String> = runCatching {
        geminiDirect(prefs, PROMPT + langNote(prefs.lang), jpegBytes(ctx, uri), false)
    }
}

/** Server when one is set up, otherwise the developer key, otherwise the on-device engine. */
fun pickEngine(prefs: Prefs, handwriting: Boolean): OcrEngine =
    if (prefs.aiReady) (if (prefs.aiViaBackend) BackendOcrEngine(prefs) else GeminiOcrEngine(prefs))
    else if (handwriting) Engines.handwriting else Engines.printed
