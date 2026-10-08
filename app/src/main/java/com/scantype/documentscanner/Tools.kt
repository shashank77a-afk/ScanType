package com.scantype.documentscanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
fun sizeText(b: Long): String = if (b >= 1_048_576) String.format(Locale.US, "%.1f MB", b / 1048576.0) else "${b / 1024} KB"

/** "1-3,5" -> zero-based page indexes, or null if invalid. */
fun parseRange(s: String, n: Int): List<Int>? = runCatching {
    val l = s.split(",").flatMap { part ->
        val p = part.trim().split("-")
        if (p.size == 1) listOf(p[0].trim().toInt()) else (p[0].trim().toInt()..p[1].trim().toInt()).toList()
    }
    require(l.isNotEmpty() && l.all { it in 1..n })
    l.map { it - 1 }.distinct().sorted()
}.getOrNull()

@Composable
fun SaveScanDialog(onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("Scan_" + stamp()) }
    AlertDialog(onCancel, title = { Text("Save scan") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
        confirmButton = { TextButton({ onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onCancel) { Text("Discard") } })
}

enum class Tool(val title: String, val hint: String) {
    MERGE("Merge", "Tap 2 or more PDFs, in the order you want."),
    EXTRACT("Split / Extract", "Pick one PDF and the pages to keep, e.g. 1-3,5"),
    DELETE("Delete pages", "Pick one PDF and the pages to remove, e.g. 2,4-5"),
    COMPRESS("Compress", "Pick one PDF and a size. Works offline."),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(docs: List<File>, store: DocStore, close: () -> Unit, refresh: () -> Unit,
                   snack: SnackbarHostState, scope: CoroutineScope) {
    var tool by remember { mutableStateOf(Tool.MERGE) }
    var picked by remember { mutableStateOf(listOf<File>()) }
    var range by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(1) }
    var working by remember { mutableStateOf(false) }
    val levels = listOf("Small" to 800, "Medium" to 1200, "High" to 1700)

    fun run() {
        scope.launch {
            working = true
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val f = picked[0]
                    when (tool) {
                        Tool.MERGE -> {
                            store.rebuild(picked.flatMap { p -> (0 until store.pageCount(p)).map { p to it } }, 1400, "Merged")
                            "Merged PDF saved"
                        }
                        Tool.EXTRACT, Tool.DELETE -> {
                            val n = store.pageCount(f)
                            val sel = parseRange(range, n) ?: return@runCatching "Please enter pages like 1-3,5 (this PDF has $n)."
                            val keep = if (tool == Tool.EXTRACT) sel else (0 until n).filter { it !in sel }
                            if (keep.isEmpty()) return@runCatching "That would leave no pages."
                            store.rebuild(keep.map { f to it }, 1400, if (tool == Tool.EXTRACT) "Split" else "Edited")
                            "New PDF saved"
                        }
                        Tool.COMPRESS -> {
                            val n = store.pageCount(f)
                            val out = store.rebuild((0 until n).map { f to it }, levels[level].second, "Compressed")
                            val before = f.length(); val after = out.length()
                            if (after >= before) { out.delete(); "This PDF is already small (${sizeText(before)}). Compressing wouldn't help." }
                            else "Before: ${sizeText(before)}  →  After: ${sizeText(after)}"
                        }
                    }
                }.getOrElse { "PDF creation failed. Please try again." }
            }
            working = false; refresh(); snack.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = { TopAppBar({ Text("PDF Tools") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tool.values().toList()) { t -> FilterChip(tool == t, { tool = t; picked = emptyList() }, { Text(t.title) }) }
            }
            Text(tool.hint, style = MaterialTheme.typography.bodySmall)
            if (tool == Tool.EXTRACT || tool == Tool.DELETE)
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), label = { Text("Pages") }, singleLine = true)
            if (tool == Tool.COMPRESS) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEachIndexed { i, l -> FilterChip(level == i, { level = i }, { Text(l.first) }) }
            }
            if (docs.isEmpty()) Text("No documents yet. Scan or import a PDF first.")
            LazyColumn(Modifier.weight(1f)) {
                items(docs, key = { it.path }) { f ->
                    val idx = picked.indexOf(f)
                    Row(Modifier.fillMaxWidth().clickable {
                        picked = if (tool == Tool.MERGE) { if (idx >= 0) picked - f else picked + f } else listOf(f)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(idx >= 0, null)
                        Text(f.nameWithoutExtension + if (tool == Tool.MERGE && idx >= 0) "  (#${idx + 1})" else "", Modifier.weight(1f))
                    }
                }
            }
            Button({ run() }, Modifier.fillMaxWidth(),
                enabled = !working && picked.isNotEmpty() && (tool != Tool.MERGE || picked.size >= 2)) {
                Text(if (working) "Working…" else "Run")
            }
            Text("Note: these tools rebuild pages as images, so text inside the new PDF won't be selectable.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
