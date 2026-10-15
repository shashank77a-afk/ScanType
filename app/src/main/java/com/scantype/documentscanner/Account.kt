package com.scantype.documentscanner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Google Sign-In with the official Credential Manager API. Only the ID token is used, and the server verifies it. */
object GoogleAuth {
    suspend fun idToken(activity: Activity, webClientId: String): String {
        if (webClientId.isBlank()) throw AiException("no_client", "The Google client ID is not set. Enter it in Server settings.")
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(activity).getCredential(activity, request).credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(cred.data).idToken
        }
        throw AiException("bad_credential", "Google sign-in returned an unsupported result.")
    }

    /** SHA-1 of the certificate this APK is signed with. It must match the Android OAuth client in Google Cloud. */
    fun apkSha1(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        if (cert == null) "unknown"
        else MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    fun message(e: Exception): String = when (e) {
        is GetCredentialCancellationException -> "Sign-in did not complete. If you did not cancel it, the app signature (SHA-1) or the Google client ID is not set up correctly. open Advanced troubleshooting."
        is NoCredentialException -> "No Google account was found on this phone. Add a Google account in the phone Settings and try again."
        is GetCredentialException -> "Google sign-in failed. Please try again."
        is AiException -> if (e.code == "no_client") (e.message ?: "") else aiMessage(e)
        else -> "Sign-in failed. Please try again."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(prefs: Prefs, snack: SnackbarHostState, scope: CoroutineScope, onSignedIn: () -> Unit = {}, close: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    // Scanner.ai deployment defaults supplied by the app owner.
    val defaultBackendUrl = "https://scantype-api.scantype.workers.dev"
    val defaultGoogleWebClientId = "374340878770-ggk3oif0h68vmv3mpteeidpoa87lqak2.apps.googleusercontent.com"

    // Keep the displayed values and the values used by the sign-in request in sync.
    if (prefs.backendUrl != defaultBackendUrl) prefs.backendUrl = defaultBackendUrl
    if (prefs.googleClientId != defaultGoogleWebClientId) prefs.googleClientId = defaultGoogleWebClientId

    var url by remember { mutableStateOf(defaultBackendUrl) }
    var cid by remember { mutableStateOf(defaultGoogleWebClientId) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    val sha1 = remember { GoogleAuth.apkSha1(ctx) }
    val usage = prefs.usage
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }

    fun withGoogle(after: suspend (String) -> Unit) {
        scope.launch {
            working = true
            try { after(GoogleAuth.idToken(activity, prefs.googleClientId)); if (prefs.signedIn) onSignedIn() }
            catch (e: Exception) { lastError = e::class.java.simpleName + ": " + (e.message ?: ""); say(GoogleAuth.message(e)) }
            finally { working = false }
        }
    }

    Scaffold(
        topBar = { TopAppBar({ Text("Account") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prefs.signedIn) {
                        Text("Signed in", style = MaterialTheme.typography.titleMedium)
                        if (prefs.userName.isNotBlank()) Text(prefs.userName)
                        Text(prefs.userEmail, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton({ scope.launch { working = true; Backend.logout(prefs); working = false; say("Signed out. Local features keep working.") } },
                            enabled = !working) { Text("Sign out") }
                    } else {
                        Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                        Text("Scanning, crop, PDF and Word work without an account. Sign in only to use AI handwriting recognition.",
                            style = MaterialTheme.typography.bodySmall)
                        Button({
                            if (prefs.backendUrl.isBlank()) say("Enter the server address below first.")
                            else withGoogle { token -> Backend.signIn(prefs, token); say("Signed in") }
                        }, enabled = !working) { Text("Sign in with Google") }
                    }
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (prefs.signedIn) Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI usage today", style = MaterialTheme.typography.titleMedium)
                    if (usage != null) {
                        Text("${usage.used} of ${usage.limit} used. ${(usage.limit - usage.used).coerceAtLeast(0)} left.")
                        if (usage.resetsAt > 0) Text("Resets at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(usage.resetsAt))}",
                            style = MaterialTheme.typography.bodySmall)
                    } else Text("Not loaded yet.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({
                        scope.launch { working = true; runCatching { Backend.usage(prefs) }.onFailure { say(aiMessage(it)) }; working = false }
                    }, enabled = !working) { Text("Refresh") }
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Text("When you use AI recognition, the chosen page image is sent over HTTPS to Scanner.ai server and forwarded to Google Gemini. Documents and recognised text are not stored by the app server.", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced troubleshooting" else "Advanced troubleshooting") }
            if (advanced) {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server settings", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(url, { url = it; prefs.backendUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server address") })
                        OutlinedTextField(cid, { cid = it; prefs.googleClientId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Google Web client ID") })
                        Text("Gemini API key remains on the Cloudflare server.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sign-in help", style = MaterialTheme.typography.titleMedium)
                        Text("Package name: " + ctx.packageName, style = MaterialTheme.typography.bodySmall)
                        Text("This APK SHA-1: " + sha1, style = MaterialTheme.typography.bodySmall)
                        Text("Web client ID: " + cid.take(12) + "…", style = MaterialTheme.typography.bodySmall)
                        Text("If sign-in fails, verify the Android OAuth client package name and SHA-1, web client ID, and Google test-user settings.", style = MaterialTheme.typography.bodySmall)
                        if (lastError.isNotEmpty()) Text("Last error: " + lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton({
                            val details = "package=" + ctx.packageName + "\nsha1=" + sha1 + "\nwebClientId=" + cid + "\nserver=" + url + "\nlastError=" + lastError
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Scanner.ai sign-in details", details))
                            say("Details copied")
                        }) { Text("Copy details") }
                    }
                }
            }
            if (prefs.signedIn) OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !working) {
                Text("Delete my account", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete your account?") },
        text = { Text("This removes your account, sessions and AI usage record from the server. Your scanned files on this phone are not touched. You will be asked to confirm with Google again.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                withGoogle { token -> Backend.deleteAccount(prefs, token); say("Account deleted") }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
