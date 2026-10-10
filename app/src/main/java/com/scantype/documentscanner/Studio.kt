package com.scantype.documentscanner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

data class StudioInit(val text: String, val template: DocTemplate)

private const val LETTER_SKELETON =
    "सेवा में,\n[अधिकारी का पद]\n[कार्यालय / स्थान]\n\nपत्रांक:- \nदिनांक:- \n\nविषय:- \n\nमहोदय,\n\n[पत्र की सामग्री यहाँ लिखें।]\n\nसंलग्नक:-\n1. \n\nभवदीय\n[नाम]\n[पद]"

/** Write -> Format -> Preview & export. Makes a real-text PDF or an editable Word (.docx) file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(start: StudioInit, store: DocStore, snack: SnackbarHostState, scope: CoroutineScope,
                 onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf(start.text) }
    var template by remember { mutableStateOf(start.template) }
    var align by remember { mutableStateOf(DocAlign.JUSTIFY) }
    var size by remember { mutableIntStateOf(13) }
    var spacing by remember { mutableStateOf(1.3f) }
    var page by remember { mutableStateOf(PageSize.A4) }
    var join by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("Document_" + stamp()) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (start.template == DocTemplate.LETTER && text.isBlank()) text = LETTER_SKELETON }
    val gate = rememberWriteGate { scope.launch { snack.showSnackbar("Storage permission is needed to save to Downloads on this Android version.") } }
    val spec = DocSpec(text, template, align, size, spacing, page, join)

    fun safe() = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Document" }
    fun tmp(ext: String): File = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, safe() + "." + ext)
    fun work(block: suspend () -> String) {
        scope.launch {
            working = true
            val m = block()
            working = false
            if (m.isNotEmpty()) snack.showSnackbar(m)
        }
    }
    val fail = "Could not create the file. Please try again."

    Scaffold(
        topBar = { TopAppBar({ Text("Create document") }, navigationIcon = { IconButton(onClose) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = step) {
                Tab(step == 0, { step = 0 }, text = { Text("Write") })
                Tab(step == 1, { step = 1 }, text = { Text("Format") })
                Tab(step == 2, { step = 2 }, text = { Text("Preview") })
            }
            when (step) {
                0 -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f),
                        placeholder = { Text("Type or paste your text here…") })
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton({ template = DocTemplate.LETTER; if (text.isBlank()) text = LETTER_SKELETON }) { Text("Official letter layout") }
                        Spacer(Modifier.weight(1f))
                        Button({ step = 1 }) { Text("Next: Format") }
                    }
                }
                1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Layout", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(template == DocTemplate.PLAIN, { template = DocTemplate.PLAIN }, { Text("Plain document") })
                        FilterChip(template == DocTemplate.LETTER, { template = DocTemplate.LETTER }, { Text("Official letter") })
                    }
                    Text("Text alignment", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DocAlign.values().forEach { a ->
                            FilterChip(align == a, { align = a }, { Text(a.name.lowercase().replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    Text("Font size", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(11, 12, 13, 14, 16, 18, 20).forEach { s -> FilterChip(size == s, { size = s }, { Text("$s") }) }
                    }
                    Text("Line spacing", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.0f, 1.15f, 1.3f, 1.5f, 2.0f).forEach { s -> FilterChip(spacing == s, { spacing = s }, { Text("$s") }) }
                    }
                    Text("Page size", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageSize.values().forEach { p -> FilterChip(page == p, { page = p }, { Text(p.label) }) }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Join wrapped lines into paragraphs", style = MaterialTheme.typography.titleSmall)
                            Text("Turn off to keep every line break exactly as typed.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(join, { join = it })
                    }
                    Button({ step = 2 }, Modifier.fillMaxWidth()) { Text("Next: Preview") }
                }
                else -> {
                    val pages by produceState<List<Bitmap>>(emptyList(), spec) {
                        value = withContext(Dispatchers.Default) {
                            runCatching {
                                val f = File(ctx.cacheDir, "preview.pdf")
                                DocPdf.render(spec, f)
                                DocPdf.rasterize(f, 800, 8)
                            }.getOrDefault(emptyList())
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("File name") })
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pages.isEmpty()) item { Text("Preparing preview…") }
                            items(pages) { b ->
                                Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().border(1.dp, Color.LightGray), contentScale = ContentScale.FillWidth)
                            }
                            item { Text("Preview shows up to 8 pages. The saved file contains every page.", style = MaterialTheme.typography.bodySmall) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                work {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val f = store.newFileExt(name, "pdf")
                                            DocPdf.render(spec, f)
                                            runCatching { store.sidecar(f).writeText(text) }
                                        }.isSuccess
                                    }
                                    if (ok) { onSaved(); "" } else "PDF creation failed. Please try again."
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("Save PDF", fontSize = 13.sp) }
                            OutlinedButton({
                                gate {
                                    work {
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching { val f = tmp("pdf"); DocPdf.render(spec, f); Export.saveToDownloads(ctx, f, f.name, "application/pdf") }.getOrDefault(false)
                                        }
                                        if (ok) "Saved to Downloads/ScanType" else fail
                                    }
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("PDF to phone", fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                work {
                                    val f = withContext(Dispatchers.IO) { runCatching { tmp("pdf").also { DocPdf.render(spec, it) } }.getOrNull() }
                                    if (f != null) { Export.share(ctx, f, "application/pdf"); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("Share PDF", fontSize = 13.sp) }
                            OutlinedButton({
                                work {
                                    val f = withContext(Dispatchers.IO) { runCatching { tmp("pdf").also { DocPdf.render(spec, it) } }.getOrNull() }
                                    if (f != null) { Export.print(ctx, f, safe()); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("Print", fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                gate {
                                    work {
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching { val f = tmp("docx"); DocxWriter.write(spec, f); Export.saveToDownloads(ctx, f, f.name, Export.DOCX) }.getOrDefault(false)
                                        }
                                        if (ok) "Word file saved to Downloads/ScanType" else fail
                                    }
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("DOCX to phone", fontSize = 13.sp) }
                            OutlinedButton({
                                work {
                                    val f = withContext(Dispatchers.IO) { runCatching { tmp("docx").also { DocxWriter.write(spec, it) } }.getOrNull() }
                                    if (f != null) { Export.share(ctx, f, Export.DOCX); "" } else fail
                                }
                            }, Modifier.weight(1f), enabled = !working) { Text("Share DOCX", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}
