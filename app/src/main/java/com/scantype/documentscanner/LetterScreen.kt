package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun Chips(vararg items: Pair<String, Boolean>, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (label, on) -> FilterChip(on, { onPick(i) }, { Text(label) }) }
    }
}

/**
 * Handwritten letter -> typed PDF. Photo, Recognize, Edit layout, Preview and export.
 * Preview and export use the very same renderer, so they always match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterScreen(s: LetterState, store: DocStore, prefs: Prefs, snack: SnackbarHostState, scope: CoroutineScope,
                 onBack: () -> Unit, onAddCamera: () -> Unit, onCrop: (Int) -> Unit, onAccount: () -> Unit, onSettings: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var showAiHint by remember { mutableStateOf(false) }
    var opts by remember { mutableStateOf(LetterOpts()) }
    var name by remember { mutableStateOf("Letter_" + stamp()) }
    var open by remember { mutableIntStateOf(-1) }
    val gate = rememberWriteGate { scope.launch { snack.showSnackbar("Storage permission is needed to save to Downloads on this Android version.") } }
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }

    val pickG = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { us ->
        if (us.isNotEmpty()) scope.launch {
            working = "Preparing…"
            val p = withContext(Dispatchers.IO) { us.map { if (prefs.autoCrop) runCatching { EdgeDetect.autoCrop(ctx, it) }.getOrDefault(it) else it } }
            s.images.addAll(p); s.origs.addAll(us); working = null
        }
    }

    fun runRecognition() {
        if (s.images.isEmpty() || working != null) return
        scope.launch {
            working = "Reading text and reconstructing the layout…"
            val res = runCatching { LetterAnalyzer.analyze(ctx, prefs, s.images.toList()) }
            working = null
            res.onSuccess { r ->
                s.blocks.clear(); s.blocks.addAll(r.blocks)
                s.original = r.blocks; s.source = r.source; s.note = r.note; s.needLogin = r.needLogin
                if (r.blocks.isEmpty()) {
                    step = 0
                    say(r.note.ifBlank { "No readable text was found. Try a brighter, straight-on photo." })
                } else {
                    step = 1
                }
            }.onFailure {
                s.note = "Recognition failed. Check your internet/AI settings, or try a clearer photo."
                step = 0
                say(s.note)
            }
        }
    }

    fun recognize() {
        if (s.images.isEmpty() || working != null) return
        if (!prefs.aiReady) showAiHint = true else runRecognition()
    }

    fun render(f: File): LayoutReport = if (opts.clean) LayoutPdf.clean(s.blocks.toList(), opts, f) else LayoutPdf.render(s.blocks.toList(), opts, f)
    fun safe() = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Letter" }
    fun tmp(): File = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, safe() + ".pdf")
    fun work(msg: String, block: suspend () -> String) {
        scope.launch { working = msg; val m = block(); working = null; if (m.isNotEmpty()) say(m) }
    }
    val fail = "Could not create the PDF. Please try again."

    Scaffold(
        topBar = { TopAppBar({ Text("Same-Layout Typed PDF") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = step) {
                Tab(step == 0, { step = 0 }, text = { Text("1 Photo") })
                Tab(step == 1, { if (s.blocks.isNotEmpty()) step = 1 }, text = { Text("2 Edit") })
                Tab(step == 2, { if (s.blocks.isNotEmpty()) step = 2 }, text = { Text("3 Preview") })
            }
            if (working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (step) {
                0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Take a clear photo of the full letter page, or choose one from the gallery. Keep all page edges visible and avoid perspective tilt so the layout can be rebuilt.")
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (prefs.aiReady) "AI layout recognition is enabled" else "AI layout recognition is off",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (prefs.aiReady) "The app will use AI to identify Hindi/English text blocks and their positions. Review the result before saving."
                                else "Without AI, on-device OCR may miss Hindi handwriting and page positions. Enable AI for the best chance of matching the original layout, or continue offline and correct the text manually.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!prefs.aiReady) TextButton(onSettings) { Text("Open AI Settings") }
                        }
                    }
                    if (s.note.isNotEmpty()) Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onAddCamera, Modifier.weight(1f)) { Text("Camera") }
                        OutlinedButton({ pickG.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f)) { Text("Gallery") }
                    }
                    s.images.forEachIndexed { i, u ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LetterThumb(u)
                            Column(Modifier.weight(1f)) { Text("Page ${i + 1}", style = MaterialTheme.typography.titleSmall) }
                            OutlinedButton({ onCrop(i) }) { Text("Crop") }
                            TextButton({ s.images.removeAt(i); if (i < s.origs.size) s.origs.removeAt(i) }) { Text("Remove") }
                        }
                    }
                    Button({ recognize() }, Modifier.fillMaxWidth(), enabled = s.images.isNotEmpty() && working == null) { Text("Recognize Text & Layout") }
                    Text(if (prefs.aiReady) "AI recognition is on (internet needed)." else "AI is off: on-device recognition will be used. It is weak on handwriting; turn AI on in Settings for better results.",
                        style = MaterialTheme.typography.bodySmall)
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Recognised with: ${s.source}", style = MaterialTheme.typography.titleSmall)
                            if (s.note.isNotEmpty()) Text(s.note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (s.needLogin) TextButton(onAccount) { Text("Sign in for AI recognition") }
                            Text("Check every block. Words marked [?] were unclear. Names, dates, numbers and amounts are never changed by the app.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Output", style = MaterialTheme.typography.titleSmall)
                            Chips("Same-Layout Typed PDF" to !opts.clean, "Clean professional letter" to opts.clean) { opts = opts.copy(clean = it == 1) }
                            Text("Font size", style = MaterialTheme.typography.titleSmall)
                            Chips(*listOf(11f, 12f, 13f, 14f, 16f).map { "${it.toInt()}" to (opts.baseFont == it) }.toTypedArray()) { opts = opts.copy(baseFont = listOf(11f, 12f, 13f, 14f, 16f)[it]) }
                            Text("Line spacing", style = MaterialTheme.typography.titleSmall)
                            Chips(*listOf(1.0f, 1.15f, 1.25f, 1.5f).map { "$it" to (opts.lineSpacing == it) }.toTypedArray()) { opts = opts.copy(lineSpacing = listOf(1.0f, 1.15f, 1.25f, 1.5f)[it]) }
                            Text("Margins", style = MaterialTheme.typography.titleSmall)
                            Chips(*listOf(24 to "Narrow", 36 to "Normal", 56 to "Wide").map { it.second to (opts.margin == it.first) }.toTypedArray()) { opts = opts.copy(margin = listOf(24, 36, 56)[it]) }
                            Text("Paragraph alignment", style = MaterialTheme.typography.titleSmall)
                            Chips(*DocAlign.values().map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to (opts.align == it) }.toTypedArray()) { opts = opts.copy(align = DocAlign.values()[it]) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ s.blocks.clear(); s.blocks.addAll(s.original); open = -1; say("Original layout restored") }) { Text("Restore original layout") }
                                OutlinedButton({
                                    val bottom = s.blocks.maxOfOrNull { it.y1 } ?: 0.1f
                                    val y = (bottom + 0.02f).coerceAtMost(0.9f)
                                    s.blocks.add(LBlock((s.blocks.maxOfOrNull { it.id } ?: 0) + 1, 0, "New text", "body", DocAlign.LEFT, 0.1f, y, 0.9f, (y + 0.05f).coerceAtMost(1f)))
                                    open = s.blocks.size - 1
                                }) { Text("Add block") }
                            }
                        }
                    }
                    items(s.blocks.size) { i ->
                        val b = s.blocks[i]
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${i + 1}. ${b.role.replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                                        Text(b.text.take(60), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton({ if (i > 0) { val t = s.blocks[i - 1]; s.blocks[i - 1] = b; s.blocks[i] = t } }) { Text("↑") }
                                    TextButton({ if (i < s.blocks.size - 1) { val t = s.blocks[i + 1]; s.blocks[i + 1] = b; s.blocks[i] = t } }) { Text("↓") }
                                    TextButton({ open = if (open == i) -1 else i }) { Text(if (open == i) "Close" else "Edit") }
                                }
                                if (open == i) {
                                    OutlinedTextField(b.text, { s.blocks[i] = b.copy(text = it) }, Modifier.fillMaxWidth())
                                    Chips(*DocAlign.values().map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to (b.align == it) }.toTypedArray()) { s.blocks[i] = b.copy(align = DocAlign.values()[it]) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Bold", Modifier.weight(1f)); Switch(b.bold, { s.blocks[i] = b.copy(bold = it) })
                                    }
                                    Text("Block font size: " + if (b.fontSize <= 0f) "Auto" else "${b.fontSize.toInt()}", style = MaterialTheme.typography.bodySmall)
                                    Chips(*(listOf(0f, 10f, 11f, 12f, 13f, 14f, 16f).map { (if (it == 0f) "Auto" else "${it.toInt()}") to (b.fontSize == it) }).toTypedArray()) {
                                        s.blocks[i] = b.copy(fontSize = listOf(0f, 10f, 11f, 12f, 13f, 14f, 16f)[it])
                                    }
                                    Text("Left position", style = MaterialTheme.typography.bodySmall)
                                    Slider(b.x0, { v -> val w = b.x1 - b.x0; s.blocks[i] = b.copy(x0 = v, x1 = (v + w).coerceAtMost(1f)) }, valueRange = 0f..0.9f)
                                    Text("Top position", style = MaterialTheme.typography.bodySmall)
                                    Slider(b.y0, { v -> val h = b.y1 - b.y0; s.blocks[i] = b.copy(y0 = v, y1 = (v + h).coerceAtMost(1f)) }, valueRange = 0f..0.95f)
                                    Text("Width", style = MaterialTheme.typography.bodySmall)
                                    Slider(b.x1 - b.x0, { v -> s.blocks[i] = b.copy(x1 = (b.x0 + v).coerceAtMost(1f)) }, valueRange = 0.1f..1f)
                                    TextButton({ s.blocks.removeAt(i); open = -1 }) { Text("Delete block", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    item { Button({ step = 2 }, Modifier.fillMaxWidth()) { Text("Preview PDF") } }
                }
                else -> {
                    val snapshot = s.blocks.toList()
                    val result by produceState<Pair<List<Bitmap>, LayoutReport?>>(Pair(emptyList(), null), snapshot, opts) {
                        value = withContext(Dispatchers.Default) {
                            runCatching {
                                val f = File(ctx.cacheDir, "letter_preview.pdf")
                                val rep = render(f)
                                Pair(DocPdf.rasterize(f, 800, 8), rep)
                            }.getOrDefault(Pair(emptyList(), null))
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("File name") })
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (result.first.isEmpty()) item { Text("Preparing preview…") }
                            items(result.first) { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().border(1.dp, Color.LightGray), contentScale = ContentScale.FillWidth) }
                            val notes = result.second?.notes ?: emptyList()
                            if (notes.isNotEmpty()) item {
                                Text("Automatic adjustments (you can change them in step 2):", style = MaterialTheme.typography.titleSmall)
                                notes.forEach { Text("• $it", fontSize = 12.sp) }
                            }
                            item { Text(if (opts.clean) "The PDF contains selectable typed text in a clean letter format. Your original photo is kept separately on this phone." else "The PDF contains selectable text placed to resemble the photographed letter. OCR and layout can vary, so review every block before saving.", style = MaterialTheme.typography.bodySmall) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                work("Saving…") {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val f = store.newFileExt(name, "pdf")
                                            render(f)
                                            runCatching { store.sidecar(f).writeText(s.blocks.joinToString("\n\n") { it.text }) }
                                            val dir = File(ctx.filesDir, "originals").apply { mkdirs() }
                                            s.origs.forEachIndexed { i, u ->
                                                runCatching { ctx.contentResolver.openInputStream(u)?.use { ins -> File(dir, "${f.nameWithoutExtension}_${i + 1}.jpg").outputStream().use { ins.copyTo(it) } } }
                                            }
                                        }.isSuccess
                                    }
                                    if (ok) { onSaved(); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = working == null) { Text("Save PDF", fontSize = 13.sp) }
                            OutlinedButton({
                                gate {
                                    work("Saving…") {
                                        val ok = withContext(Dispatchers.IO) { runCatching { val f = tmp(); render(f); Export.saveToDownloads(ctx, f, f.name, "application/pdf") }.getOrDefault(false) }
                                        if (ok) "Saved to Downloads/ScanType" else fail
                                    }
                                }
                            }, Modifier.weight(1f), enabled = working == null) { Text("PDF to phone", fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                work("Preparing…") {
                                    val f = withContext(Dispatchers.IO) { runCatching { tmp().also { render(it) } }.getOrNull() }
                                    if (f != null) { Export.share(ctx, f, "application/pdf"); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = working == null) { Text("Share PDF", fontSize = 13.sp) }
                            OutlinedButton({
                                work("Preparing…") {
                                    val f = withContext(Dispatchers.IO) { runCatching { tmp().also { render(it) } }.getOrNull() }
                                    if (f != null) { Export.print(ctx, f, safe()); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = working == null) { Text("Print", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
    if (showAiHint) {
        AlertDialog(
            onDismissRequest = { showAiHint = false },
            title = { Text("Enable AI for better layout matching?") },
            text = { Text("AI is currently off. On-device OCR can be unreliable on handwritten Hindi letters, so the generated PDF may not preserve the original layout. Open Settings to enable AI, or continue with on-device recognition and review every text block.") },
            confirmButton = {
                TextButton(onClick = { showAiHint = false; onSettings() }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAiHint = false; runRecognition() }) { Text("Continue offline") }
            }
        )
    }
}

@Composable
private fun LetterThumb(uri: Uri) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) { runCatching { ImageFilters.load(ctx, uri, 200) }.getOrNull() }
    }
    Box(Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
