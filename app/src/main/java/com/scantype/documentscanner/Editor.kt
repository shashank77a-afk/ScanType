package com.scantype.documentscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Text with **bold**, *italic* markers shown as real bold/italic; unclear words ([?]) get a red background. */
private fun styled(text: String): AnnotatedString = buildAnnotatedString {
    for (r in Inline.runs(text)) {
        withStyle(SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null)) {
            var pos = 0
            val t = r.text
            while (true) {
                val k = t.indexOf("[?]", pos)
                if (k < 0) { append(t.substring(pos)); break }
                append(t.substring(pos, k))
                withStyle(SpanStyle(background = Color(0x66FF5252))) { append("[?]") }
                pos = k + 3
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(initial: String, handwriting: Boolean, snack: SnackbarHostState, scope: CoroutineScope,
                 close: () -> Unit, onCreate: (String) -> Unit) {
    val ctx = LocalContext.current
    var tv by remember { mutableStateOf(TextFieldValue(initial)) }
    val undo = remember { ArrayList<String>() }
    val redo = remember { ArrayList<String>() }
    var preview by remember { mutableStateOf(false) }
    var findDlg by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var fontSize by remember { mutableIntStateOf(16) }

    fun change(n: TextFieldValue) {
        if (n.text != tv.text) { undo.add(tv.text); if (undo.size > 200) undo.removeAt(0); redo.clear() }
        tv = n
    }
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }
    fun wrap(m: String) {
        val s = tv.selection; val t = tv.text
        if (s.collapsed) change(TextFieldValue(t.substring(0, s.start) + m + m + t.substring(s.start), TextRange(s.start + m.length)))
        else change(TextFieldValue(t.substring(0, s.min) + m + t.substring(s.min, s.max) + m + t.substring(s.max), TextRange(s.max + 2 * m.length)))
    }

    Scaffold(
        topBar = { TopAppBar({ Text("Recognized Text") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (handwriting) Text("Handwriting recognition can make mistakes. Please review and correct the text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (tv.text.contains("[?]")) Text("Words marked [?] are unclear. Check them, or rescan in better light.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip({ if (undo.isNotEmpty()) { redo.add(tv.text); val t = undo.removeAt(undo.size - 1); tv = TextFieldValue(t, TextRange(t.length)) } }, { Text("Undo") })
                AssistChip({ if (redo.isNotEmpty()) { undo.add(tv.text); val t = redo.removeAt(redo.size - 1); tv = TextFieldValue(t, TextRange(t.length)) } }, { Text("Redo") })
                AssistChip({ findDlg = true }, { Text("Find & Replace") })
                AssistChip({ wrap("**") }, { Text("Bold") })
                AssistChip({ wrap("*") }, { Text("Italic") })
                AssistChip({ tv = tv.copy(selection = TextRange(0, tv.text.length)) }, { Text("Select all") })
                AssistChip({ if (fontSize > 12) fontSize -= 2 }, { Text("A-") })
                AssistChip({ if (fontSize < 28) fontSize += 2 }, { Text("A+") })
                FilterChip(preview, { preview = !preview }, { Text(if (preview) "Preview" else "Edit") })
            }
            if (preview) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text(styled(tv.text), fontSize = fontSize.sp)
            } else OutlinedTextField(tv, { change(it) }, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                textStyle = TextStyle(fontSize = fontSize.sp))
            Text("Bold: **text**   Italic: *text*  (shown in Preview, kept in PDF and Word)", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", tv.text))
                    say("All text copied")
                }) { Text("Copy all") }
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tv.text), null))
                }) { Text("Share") }
                Button({ onCreate(tv.text) }) { Text("PDF / Word") }
            }
        }
    }
    if (findDlg) AlertDialog({ findDlg = false }, title = { Text("Find & Replace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(find, { find = it }, singleLine = true, label = { Text("Find") })
                OutlinedTextField(repl, { repl = it }, singleLine = true, label = { Text("Replace with") })
            }
        },
        confirmButton = {
            Row {
                TextButton({
                    if (find.isNotEmpty()) {
                        var i = tv.text.indexOf(find, tv.selection.max, ignoreCase = true)
                        if (i < 0) i = tv.text.indexOf(find, 0, ignoreCase = true)
                        if (i >= 0) { tv = tv.copy(selection = TextRange(i, i + find.length)); findDlg = false } else say("Not found")
                    }
                }) { Text("Find next") }
                TextButton({
                    if (find.isNotEmpty()) {
                        val rx = Regex(Regex.escape(find), RegexOption.IGNORE_CASE)
                        val c = rx.findAll(tv.text).count()
                        if (c > 0) change(TextFieldValue(rx.replace(tv.text, Regex.escapeReplacement(repl)), TextRange(0)))
                        findDlg = false; say(if (c > 0) "Replaced $c" else "Not found")
                    }
                }) { Text("Replace all") }
            }
        },
        dismissButton = { TextButton({ findDlg = false }) { Text("Close") } })
}
