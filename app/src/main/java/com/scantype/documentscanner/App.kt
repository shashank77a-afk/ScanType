package com.scantype.documentscanner

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.lazy.LazyRow
import java.text.DateFormat
import java.util.Date

enum class Mode { SCAN, HANDWRITING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    val store = remember { DocStore(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var docs by remember { mutableStateOf(store.list()) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editorIsHandwriting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Mode.SCAN) }
    val prefs = remember { Prefs(ctx) }
    var batch by remember { mutableStateOf(true) }
    var reviewPages by remember { mutableStateOf<List<Uri>?>(null) }
    var showTools by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }
    fun refresh() { docs = store.list() }

    fun runOcr(uris: List<Uri>, handwriting: Boolean) {
        scope.launch {
            val engine = if (prefs.aiReady) GeminiOcrEngine(prefs) else if (handwriting) Engines.handwriting else Engines.printed
            val out = StringBuilder(); var failed = 0
            uris.forEachIndexed { i, u ->
                busy = "Reading page ${i + 1} of ${uris.size}…"
                engine.recognize(ctx, u).onSuccess { out.append(it.trim()).append("\n\n") }.onFailure { failed++ }
            }
            busy = null
            if (out.isBlank()) toast(if (prefs.aiReady) "AI recognition failed. Check your internet connection and API key in Settings." else "We couldn't recognize this page. Please try again with better lighting.")
            else {
                editorIsHandwriting = handwriting; editor = out.toString().trim()
                if (failed > 0) toast("Some pages could not be read.")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        if (res != null) scope.launch {
            val pdf = res.pdf?.uri
            if (mode == Mode.SCAN) reviewPages = res.pages?.map { it.imageUri }
            else {
                if (pdf != null) withContext(Dispatchers.IO) { runCatching { store.importPdf(pdf, "Handwriting_" + stamp()) } }
                refresh()
                runOcr(res.pages?.map { it.imageUri } ?: emptyList(), true)
            }
        }
    }

    fun startScan(m: Mode) {
        mode = m
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true).setPageLimit(if (batch) 30 else 1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast("The scanner isn't available. Please update Google Play services and try again.") }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) runOcr(listOf(u), false)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) scope.launch {
            busy = "Creating PDF…"
            val ok = withContext(Dispatchers.IO) { runCatching { store.imagesToPdf(us) }.isSuccess }
            busy = null; refresh()
            if (ok) { tab = 1; toast("PDF created") } else toast("PDF creation failed. Please try again.")
        }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    if (showTools) {
        BackHandler { showTools = false }
        PdfToolsScreen(docs, store, { showTools = false }, ::refresh, snack, scope)
        return
    }
    if (editor != null) {
        BackHandler { editor = null }
        EditorScreen(editor!!, editorIsHandwriting, store, { editor = null }, ::refresh, snack, scope)
        return
    }

    val rp = reviewPages
    if (rp != null) {
        BackHandler { reviewPages = null }
        ScanReviewScreen(rp, store, { reviewPages = null },
            { reviewPages = null; refresh(); tab = 1; toast("Saved to Documents") }, { runOcr(rp, false) }, snack, scope)
        busy?.let { BusyDialog(it) }
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (tab != 2) ExtendedFloatingActionButton(onClick = { startScan(Mode.SCAN) },
                icon = { Icon(Icons.Filled.DocumentScanner, null) }, text = { Text("Scan") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Folder, null) }, label = { Text("Documents") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(docs, store, ::refresh, ::toast, { tab = 1 },
                    onScan = { startScan(Mode.SCAN) }, onHand = { startScan(Mode.HANDWRITING) },
                    onImgText = { pickImage.launch(imageOnly) }, onImgPdf = { pickImages.launch(imageOnly) }, onTools = { showTools = true }, batch = batch, onBatch = { batch = it })
                1 -> DocumentsScreen(docs, store, ::refresh, ::toast)
                else -> SettingsScreen(prefs)
            }
            busy?.let {
                AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(16.dp)); Text(it)
                    }
                })
            }
        }
    }
}

@Composable
fun HomeScreen(docs: List<File>, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit, seeAll: () -> Unit,
               onScan: () -> Unit, onHand: () -> Unit, onImgText: () -> Unit, onImgPdf: () -> Unit, onTools: () -> Unit,
               batch: Boolean, onBatch: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Blue, Cyan))).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.scantype_logo), "ScanType logo", Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("ScanType", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Scan. Convert. Edit. PDF.", color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan mode", style = MaterialTheme.typography.titleSmall)
                FilterChip(!batch, { onBatch(false) }, { Text("Single page") })
                FilterChip(batch, { onBatch(true) }, { Text("Batch") })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(Icons.Filled.DocumentScanner, "Scan Document", "Scan paper into PDF · Offline", Blue, onScan, Modifier.weight(1f))
                    ActionCard(Icons.Filled.Edit, "Handwriting to Text", "Handwriting into editable text", Color(0xFF3D5AFE), onHand, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(Icons.Filled.Image, "Image to Text", "Printed Hindi/English text", Color(0xFF00A3D9), onImgText, Modifier.weight(1f))
                    ActionCard(Icons.Filled.PictureAsPdf, "Image to PDF", "Create PDF from images · Offline", Color(0xFFE53935), onImgPdf, Modifier.weight(1f))
                }
            }
        }
        item {
            OutlinedButton(onTools, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Build, null); Spacer(Modifier.width(8.dp)); Text("PDF Tools: merge, split, compress")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Documents", style = MaterialTheme.typography.titleMedium)
                if (docs.isNotEmpty()) TextButton(seeAll) { Text("See all") }
            }
        }
        if (docs.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your scanned documents will appear here.")
                Spacer(Modifier.height(12.dp))
                Button(onScan) { Text("Scan Your First Document") }
            }
        } else items(docs.take(4), key = { it.path }) { DocRow(it, store, refresh, toast) }
    }
}

@Composable
fun ActionCard(icon: ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick, modifier.heightIn(min = 150.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DocumentsScreen(docs: List<File>, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val folders = remember(docs) { store.folders() }
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.importPdf(u, "Imported_" + stamp()) } }
            refresh()
        }
    }
    val shown = docs.filter { f ->
        (folder == null || store.folderOf(f) == folder) &&
            (q.isBlank() || f.nameWithoutExtension.contains(q, true) || store.searchText(f).contains(q, true))
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search name or text") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            IconButton({ importPdf.launch(arrayOf("application/pdf")) }) { Icon(Icons.Filled.Add, "Import PDF") }
        }
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(folder == null, { folder = null }, { Text("All") }) }
            items(folders) { fl -> FilterChip(folder == fl, { folder = fl }, { Text(fl) }) }
            item { AssistChip({ newFolder = true }, { Text("+ Folder") }) }
        }
        if (shown.isEmpty()) Text("No documents found.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(shown, key = { it.path }) { DocRow(it, store, refresh, toast) }
        }
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog({ newFolder = false }, title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.createFolder(name); newFolder = false; refresh() }) { Text("Create") } },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } })
    }
}

@Composable
fun DocRow(f: File, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.thumb(f) } }
    val pages by produceState(0, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.pageCount(f) } }
    fun send(action: String) {
        val u = store.uri(f)
        val i = if (action == Intent.ACTION_SEND) Intent.createChooser(
            Intent(action).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        else Intent(action).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { toast("No app found to open this file.") }
    }
    Card(onClick = { send(Intent.ACTION_VIEW) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp))) {
                thumb?.let { Image(it.asImageBitmap(), null) } ?: Icon(Icons.Filled.PictureAsPdf, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.nameWithoutExtension, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text("${DateFormat.getDateInstance().format(Date(f.lastModified()))} · $pages pages · ${f.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Open") }, { menu = false; send(Intent.ACTION_VIEW) })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                    DropdownMenuItem({ Text("Share") }, { menu = false; send(Intent.ACTION_SEND) })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; store.duplicate(f); refresh() })
                    DropdownMenuItem({ Text("Move") }, { menu = false; moving = true })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = true })
                }
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog({ renaming = false }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.rename(f, name); renaming = false; refresh() }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } })
    }
    if (moving) AlertDialog({ moving = false }, title = { Text("Move to folder") },
        text = {
            Column {
                (listOf("") + store.folders()).forEach { fl ->
                    TextButton({ store.move(f, fl); moving = false; refresh() }) { Text(if (fl.isEmpty()) "No folder" else fl) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ moving = false }) { Text("Cancel") } })
    if (deleting) AlertDialog({ deleting = false }, title = { Text("Delete this document?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton({ store.delete(f); deleting = false; refresh() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(initial: String, handwriting: Boolean, store: DocStore, close: () -> Unit, refresh: () -> Unit,
                 snack: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    Scaffold(
        topBar = { TopAppBar({ Text("Recognized Text") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (handwriting) Text("Handwriting recognition can make mistakes. Please review and correct the text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                    scope.launch { snack.showSnackbar("Copied") }
                }) { Text("Copy") }
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                }) { Text("Share") }
                Button({
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { store.textToPdf(text) }.isSuccess }
                        refresh()
                        snack.showSnackbar(if (ok) "Typed PDF saved to Documents" else "PDF creation failed. Please try again.")
                    }
                }) { Text("Typed PDF") }
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: Prefs) {
    var useAi by remember { mutableStateOf(prefs.useAi) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI text recognition (Google Gemini)", style = MaterialTheme.typography.titleSmall)
                        Text("Much better Hindi handwriting and printed text. Needs internet and your own API key.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(useAi, { on -> if (on) confirm = true else { useAi = false; prefs.useAi = false } })
                }
                if (useAi) {
                    OutlinedTextField(key, { key = it; prefs.apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(model, { model = it; prefs.model = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Model") })
                    Text("Get a key at aistudio.google.com. When ON, the page images you choose for text recognition are sent to Google.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text("Your documents are stored privately on this device. Scanning, filters, PDF tools and on-device text recognition work offline. Nothing is uploaded unless you turn on AI recognition above.")
        Text("Theme follows your device's Light/Dark setting.")
        Text("ScanType 1.1.0", style = MaterialTheme.typography.bodySmall)
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Send images to Google?") },
        text = { Text("With AI recognition ON, the pages you choose for Image to Text or Handwriting to Text are sent over the internet to Google's Gemini service, using your API key. Nothing is sent when it is OFF. Depending on your key type, Google's terms decide how the data is used, so avoid sensitive documents on a free key.") },
        confirmButton = { TextButton({ useAi = true; prefs.useAi = true; prefs.consented = true; confirm = false }) { Text("I agree") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
