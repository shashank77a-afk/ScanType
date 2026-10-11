package com.scantype.documentscanner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

@Composable
private fun Setting(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(prefs: Prefs, close: () -> Unit) {
    val ctx = LocalContext.current
    var useAi by remember { mutableStateOf(prefs.useAi) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var confirm by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var autoCrop by remember { mutableStateOf(prefs.autoCrop) }
    var autoCap by remember { mutableStateOf(prefs.autoCapture) }
    var lockOn by remember { mutableStateOf(prefs.lockEnabled) }
    var bioOn by remember { mutableStateOf(prefs.lockBio) }
    var pinDlg by remember { mutableIntStateOf(0) }
    val bioAvail = remember { (ctx as? FragmentActivity)?.let { Biometric.available(it) } ?: false }

    Scaffold(topBar = {
        TopAppBar({ Text("Settings") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Setting("AI text recognition (Google Gemini)",
                        "Much better Hindi handwriting and printed text. Needs internet and your own API key.", useAi) { on ->
                        if (on) confirm = true else { useAi = false; prefs.useAi = false }
                    }
                    if (useAi) {
                        OutlinedTextField(key, { key = it; prefs.apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(model, { model = it; prefs.model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") })
                        Text("Get a key at aistudio.google.com. When ON, the page images you choose for text recognition are sent to Google.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Text language", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Langs.all.forEach { l ->
                            FilterChip(lang == l.code, { lang = l.code; prefs.lang = l.code }, { Text(l.label + if (l.offline) "" else " (AI)") })
                        }
                    }
                    Text("Works offline: Auto, Hindi, English, Marathi, Nepali. Languages marked (AI) need AI recognition turned on above.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Setting("Auto-crop after capture", "Finds the page edges and straightens it. You can go back to the original.", autoCrop) {
                        autoCrop = it; prefs.autoCrop = it
                    }
                    Setting("Auto capture", "Takes the photo by itself when the document is steady.", autoCap) {
                        autoCap = it; prefs.autoCapture = it
                    }
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("System default", "Light", "Dark").forEachIndexed { i, l ->
                            FilterChip(prefs.theme == i, { prefs.theme = i }, { Text(l) })
                        }
                    }
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Setting("App lock", "Ask for a PIN when the app is opened again.", lockOn) { on -> pinDlg = if (on) 1 else 2 }
                    if (lockOn) {
                        if (bioAvail) Setting("Unlock with fingerprint", "Use your fingerprint instead of typing the PIN.", bioOn) {
                            bioOn = it; prefs.lockBio = it
                        }
                        TextButton({ pinDlg = 1 }) { Text("Change PIN") }
                    }
                }
            }
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text("Your documents are stored privately on this device. Scanning, filters, PDF tools and on-device text recognition work offline. Nothing is uploaded unless you turn on AI recognition above.")
            Text("ScanType 1.4.0", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirm) AlertDialog({ confirm = false }, title = { Text("Send images to Google?") },
        text = { Text("With AI recognition ON, the pages you choose for text recognition are sent over the internet to Google's Gemini service, using your API key. Nothing is sent when it is OFF. Depending on your key type, Google's terms decide how the data is used, so avoid sensitive documents on a free key.") },
        confirmButton = { TextButton({ useAi = true; prefs.useAi = true; prefs.consented = true; confirm = false }) { Text("I agree") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })

    if (pinDlg != 0) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        AlertDialog({ pinDlg = 0 }, title = { Text(if (pinDlg == 1) "Set a PIN" else "Enter your PIN to turn off") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text("PIN (4 to 8 digits)") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (pinDlg == 1) OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton({
                    if (pinDlg == 1) {
                        if (p1.length < 4) err = "Use at least 4 digits."
                        else if (p1 != p2) err = "The two PINs do not match."
                        else {
                            val s = Pin.newSalt()
                            prefs.lockSalt = s; prefs.lockHash = Pin.hash(p1, s); prefs.lockEnabled = true
                            lockOn = true; pinDlg = 0
                        }
                    } else {
                        if (Pin.verify(prefs, p1)) { prefs.lockEnabled = false; prefs.lockBio = false; lockOn = false; bioOn = false; pinDlg = 0 }
                        else err = "Wrong PIN."
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pinDlg = 0 }) { Text("Cancel") } })
    }
}
