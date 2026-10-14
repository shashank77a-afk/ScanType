package com.scantype.documentscanner

import android.app.Activity
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

private const val DEFAULT_SCANTYPE_BACKEND_URL = "https://scantype-api.scantype.workers.dev"
private const val DEFAULT_GOOGLE_WEB_CLIENT_ID = "374340878770-ggk3oif0h68vmv3mpteeidpoa87lqak2.apps.googleusercontent.com"

/** Google Sign-In with the official Credential Manager API. Only the ID token is used, and the server verifies it. */
object GoogleAuth {
    suspend fun idToken(activity: Activity, webClientId: String): String {
        if (webClientId.isBlank()) throw AiException("no_client", "Google sign-in is not configured. Please contact support.")
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(activity).getCredential(activity, request).credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(cred.data).idToken
        }
        throw AiException("bad_credential", "Google sign-in returned an unsupported result.")
    }

    fun message(e: Exception): String = when (e) {
        is GetCredentialCancellationException -> "Sign-in was cancelled."
        is NoCredentialException -> "No Google account was found on this phone. Add a Google account in the phone Settings and try again."
        is GetCredentialException -> "Google sign-in failed. Please try again."
        is AiException -> if (e.code == "no_client") (e.message ?: "") else aiMessage(e)
        else -> "Sign-in failed. Please try again."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(prefs: Prefs, snack: SnackbarHostState, scope: CoroutineScope, close: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    LaunchedEffect(prefs) {
        if (prefs.backendUrl.isBlank()) prefs.backendUrl = DEFAULT_SCANTYPE_BACKEND_URL
        if (prefs.googleClientId.isBlank()) prefs.googleClientId = DEFAULT_GOOGLE_WEB_CLIENT_ID
    }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val usage = prefs.usage
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }

    fun withGoogle(after: suspend (String) -> Unit) {
        scope.launch {
            working = true
            try { after(GoogleAuth.idToken(activity, prefs.googleClientId)) }
            catch (e: Exception) { say(GoogleAuth.message(e)) }
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
                            withGoogle { token -> Backend.signIn(prefs, token); say("Signed in") }
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
                    Text("When you use AI recognition, the page image you choose is sent over HTTPS to your ScanType server, which forwards it to Google Gemini and returns the text. The server does not store your documents or the recognised text. Turn AI on or off in Settings.",
                        style = MaterialTheme.typography.bodySmall)
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
