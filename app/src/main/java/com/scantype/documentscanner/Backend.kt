package com.scantype.documentscanner

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiException(val code: String, message: String, val resetsAt: Long = 0L) : Exception(message)
class AiFail(val message: String, val code: String, val uris: List<Uri>, val handwriting: Boolean)

/** Encrypts the session token with a key that never leaves the Android Keystore. */
object SecureStore {
    private const val ALIAS = "scantype_session_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(s: String): String? = runCatching {
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}

fun jpegBytes(ctx: Context, uri: Uri, maxSide: Int = 2000): ByteArray {
    val bmp = ImageFilters.load(ctx, uri, maxSide)
    try {
        var q = 88
        while (true) {
            val bos = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos)
            if (bos.size() <= 2_800_000 || q <= 50) return bos.toByteArray()
            q -= 12
        }
    } finally { bmp.recycle() }
}

/** Short, friendly text for every failure the AI server can report. */
fun aiMessage(e: Throwable): String = when (e) {
    is AiException -> when (e.code) {
        "login_required", "session_expired", "invalid_session", "invalid_token", "token_expired" -> "Please sign in with Google to use AI features."
        "quota_exhausted" -> "Your free AI limit for today is used up" +
            (if (e.resetsAt > 0) ". It resets at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.resetsAt))}." else ".") +
            " Offline recognition still works."
        "network" -> "No internet connection, or the server could not be reached."
        "rate_limited" -> "Too many requests. Please wait a minute and try again."
        "no_backend" -> "The AI server is not set up yet. Open Account and enter the server address."
        else -> e.message ?: "The AI request failed."
    }
    else -> "The AI request failed. Please try again."
}

fun needsLogin(e: Throwable) = e is AiException && e.code in setOf("login_required", "session_expired", "invalid_session", "invalid_token", "token_expired")

/** Client for the ScanType Cloudflare Worker. The Gemini key lives only on that server. */
object Backend {
    // Default deployed ScanType backend. The Account screen can still override this URL.
    private const val DEFAULT_BASE_URL = "https://scantype-api.scantype.workers.dev"

    private suspend fun call(prefs: Prefs, method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            val base = prefs.backendUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            if (!base.startsWith("https://")) throw AiException("bad_backend", "The server address must start with https://")
            val c = URL(base + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 90000
                c.setRequestProperty("Content-Type", "application/json")
                if (auth) prefs.session?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(txt) }.getOrNull()
                if (code in 200..299) return@withContext json ?: JSONObject()
                val err = json?.optJSONObject("error")
                val ec = err?.optString("code") ?: "http_$code"
                if (code == 401 && ec in setOf("session_expired", "invalid_session")) prefs.clearSession()
                throw AiException(ec, err?.optString("message") ?: "The server returned an error ($code).", err?.optLong("resetsAt") ?: 0L)
            } catch (e: IOException) {
                throw AiException("network", "No internet connection.")
            } finally { c.disconnect() }
        }

    private fun saveSession(prefs: Prefs, r: JSONObject) {
        prefs.session = r.getString("token")
        prefs.sessionExpires = r.optLong("expiresAt")
        val u = r.optJSONObject("user")
        prefs.userEmail = u?.optString("email") ?: ""
        prefs.userName = u?.optString("name") ?: ""
    }

    suspend fun signIn(prefs: Prefs, idToken: String) {
        saveSession(prefs, call(prefs, "POST", "/api/auth/google", JSONObject().put("idToken", idToken), false))
        runCatching { usage(prefs) }
    }

    /** Renews the session shortly before it expires, so the user is not asked to sign in again. */
    private suspend fun refreshIfNeeded(prefs: Prefs) {
        if (prefs.session != null && prefs.sessionExpires - System.currentTimeMillis() < 7 * 86400000L) {
            runCatching { saveSession(prefs, call(prefs, "POST", "/api/auth/refresh", null, true)) }
        }
    }

    suspend fun logout(prefs: Prefs) {
        runCatching { call(prefs, "POST", "/api/auth/logout", null, true) }
        prefs.clearSession()
    }

    suspend fun usage(prefs: Prefs) {
        call(prefs, "GET", "/api/usage", null, true).optJSONObject("usage")?.let { prefs.applyUsage(it) }
    }

    suspend fun deleteAccount(prefs: Prefs, idToken: String) {
        call(prefs, "DELETE", "/api/account", JSONObject().put("confirm", "DELETE").put("idToken", idToken), true)
        prefs.clearSession()
    }

    suspend fun ocr(prefs: Prefs, jpeg: ByteArray, lang: String): String {
        refreshIfNeeded(prefs)
        val r = call(prefs, "POST", "/api/ai/handwriting-ocr", imageBody(jpeg, lang), true)
        r.optJSONObject("usage")?.let { prefs.applyUsage(it) }
        return r.getString("text")
    }

    suspend fun letterLayout(prefs: Prefs, jpeg: ByteArray, lang: String): JSONObject {
        refreshIfNeeded(prefs)
        val r = call(prefs, "POST", "/api/ai/letter-layout", imageBody(jpeg, lang), true)
        r.optJSONObject("usage")?.let { prefs.applyUsage(it) }
        val layout = r.optJSONObject("layout")
            ?: throw AiException("letter_layout_invalid", "The AI server did not return a valid layout object. Please retry.")
        // Worker can return a normalized layout or a paragraph-text fallback.
        val blocks = layout.optJSONArray("blocks")
            ?: layout.optJSONArray("elements")
            ?: layout.optJSONArray("paragraphs")
            ?: layout.optJSONArray("items")
            ?: layout.optJSONArray("lines")
        if (blocks == null || blocks.length() == 0) {
            val fallbackText = layout.optString("text", "").trim()
            if (fallbackText.isNotEmpty()) {
                return JSONObject()
                    .put("language", layout.optString("language", lang))
                    .put("pageAspect", layout.optDouble("pageAspect", 0.707))
                    .put("blocks", org.json.JSONArray().put(JSONObject().put("text", fallbackText)))
            }
            throw AiException("letter_layout_empty", "The AI returned no readable letter text. Please retry or use Recognized Text.")
        }
        return layout
    }

    private fun imageBody(jpeg: ByteArray, lang: String) = JSONObject()
        .put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg").put("lang", lang)
}
