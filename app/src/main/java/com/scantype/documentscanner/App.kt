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
    val snack = remember { SnackbarHostState() }
    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }
    fun refresh() { docs = store.list() }

    fun runOcr(uris: List<Uri>, handwriting: Boolean) {
        scope.launch {
            val engine = if (handwriting) Engines.handwriting else Engines.printed
            val out = StringBuilder(); var failed = 0
            uris.forEachIndexed { i, u ->
                busy = "Reading page ${i + 1} of ${uris.size}…"
                engine.recognize(ctx, u).onSuccess { out.append(it.trim()).append("\n\n") }.onFailure { failed++ }
            }
            busy = null
            if (out.isBlank()) toast("We couldn't recognize this page. Please try again with better lighting.")
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
            if (pdf != null) {
                busy = "Saving…"
                withContext(Dispatchers.IO) { runCatching { store.importPdf(pdf, if (mode == Mode.SCAN) "Scan" else "Handwriting") } }
                busy = null; refresh()
            }
            if (mode == Mode.HANDWRITING) runOcr(res.pages?.map { it.imageUri } ?: emptyList(), true)
            else { tab = 1; toast("Saved to Documents") }
        }
    }

    fun startScan(m: Mode) {
        mode = m
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true).setPageLimit(30)
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

    if (editor != null) {
        BackHandler { editor = null }
        EditorScreen(editor!!, editorIsHandwriting, store, { editor = null }, ::refresh, snack, scope)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                NavigationBarItem(false, { startScan(Mode.SCAN) }, { Icon(Icons.Filled.DocumentScanner, null) }, label = { Text("Scan") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Folder, null) }, label = { Text("Documents") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(docs, store, ::refresh, ::toast, { tab = 1 },
                    onScan = { startScan(Mode.SCAN) }, onHand = { startScan(Mode.HANDWRITING) },
                    onImgText = { pickImage.launch(imageOnly) }, onImgPdf = { pickImages.launch(imageOnly) })
                1 -> DocumentsScreen(docs, store, ::refresh, ::toast)
                else -> SettingsScreen()
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
               onScan: () -> Unit, onHand: () -> Unit, onImgText: () -> Unit, onImgPdf: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.scantype_logo), "ScanType logo", Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ScanType", fontSize = 24.sp, style = MaterialTheme.typography.headlineSmall)
                    Text("Scan. Convert. Edit. PDF.", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(Icons.Filled.DocumentScanner, "Scan Document", "Scan paper into PDF · Offline", onScan, Modifier.weight(1f))
                    ActionCard(Icons.Filled.Edit, "Handwriting to Text", "Convert handwriting into editable text", onHand, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(Icons.Filled.Image, "Image to Text", "Extract printed Hindi/English text · Offline", onImgText, Modifier.weight(1f))
                    ActionCard(Icons.Filled.PictureAsPdf, "Image to PDF", "Create PDF from images · Offline", onImgPdf, Modifier.weight(1f))
                }
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
fun ActionCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick, modifier.heightIn(min = 140.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DocumentsScreen(docs: List<File>, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = docs.filter { it.nameWithoutExtension.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search documents") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) Text("No documents found.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.path }) { DocRow(it, store, refresh, toast) }
        }
    }
}

@Composable
fun DocRow(f: File, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.thumb(f) } }
    val pages by produceState(0, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.pageCount(f) } }
    fun send(action: String) {
        val u = store.uri(f)
        val i = if (action == Intent.ACTION_SEND) Intent.createChooser(
            Intent(action).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        else Intent(action).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { toast("No app found to open this file.") }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), onClick = { send(Intent.ACTION_VIEW) }) {
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
    if (deleting) AlertDialog({ deleting = false }, title = { Text("Delete this document?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton({ f.delete(); deleting = false; refresh() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
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
fun SettingsScreen() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text("Your documents are stored privately on this device. Scanning, printed-text recognition and PDF creation work offline. Nothing is uploaded.")
        Text("Theme follows your device's Light/Dark setting.")
        Text("ScanType 1.0.0", style = MaterialTheme.typography.bodySmall)
    }
}
