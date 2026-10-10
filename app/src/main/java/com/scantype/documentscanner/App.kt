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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

class ToolItem(val icon: ImageVector, val label: String, val tint: Color, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    val store = remember { DocStore(ctx) }
    val prefs = remember { Prefs(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var docs by remember { mutableStateOf(store.list()) }
    var editor by remember { mutableStateOf<String?>(null) }
    var editorIsHandwriting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Mode.SCAN) }
    var batch by remember { mutableStateOf(true) }
    var camMode by remember { mutableStateOf<CamMode?>(null) }
    var review by remember { mutableStateOf<ReviewState?>(null) }
    var toolStart by remember { mutableStateOf<Tool?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var aiHint by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<File>() }
    val snack = remember { SnackbarHostState() }
    var cropIdx by remember { mutableStateOf<Int?>(null) }
    var idQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var idDone by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var studio by remember { mutableStateOf<StudioInit?>(null) }
    val gate = rememberWriteGate { scope.launch { snack.showSnackbar("Storage permission is needed to save to Gallery on this Android version.") } }
    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }
    fun refresh() { docs = store.list(); sel.retainAll { it.exists() } }

    fun runOcr(uris: List<Uri>, handwriting: Boolean) {
        scope.launch {
            val engine = if (prefs.aiReady) GeminiOcrEngine(prefs) else if (handwriting) Engines.handwriting else Engines.printed
            val out = StringBuilder(); var failed = 0; var unreadable = 0
            uris.forEachIndexed { i, u ->
                busy = "Reading page ${i + 1} of ${uris.size}…"
                engine.recognize(ctx, u)
                    .onSuccess { if (it.trim() == "UNREADABLE") unreadable++ else out.append(it.trim()).append("\n\n") }
                    .onFailure { failed++ }
            }
            busy = null
            if (out.isBlank()) toast(
                if (unreadable > 0) "The text is not clear enough. Please rescan closer, in focus and in better light."
                else if (prefs.aiReady) "AI recognition failed. Check your internet connection and API key in Settings."
                else "We couldn't recognize this page. Please try again with better lighting.")
            else {
                editorIsHandwriting = handwriting; editor = out.toString().trim()
                if (failed + unreadable > 0) toast("Some pages could not be read. You can rescan them.")
                else if (Regex("\\[\\?\\]").findAll(out).count() >= 3) toast("Some words are unclear and marked [?]. Review them, or rescan in better light.")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        if (res != null) scope.launch {
            val pdf = res.pdf?.uri
            val pgs = res.pages?.map { it.imageUri } ?: emptyList()
            if (mode == Mode.SCAN) {
                if (pgs.isNotEmpty()) { val rv = review; if (rv != null) rv.add(pgs) else review = ReviewState(pgs) }
            } else {
                if (pdf != null) withContext(Dispatchers.IO) { runCatching { store.importPdf(pdf, "Handwriting_" + stamp()) } }
                refresh()
                runOcr(pgs, true)
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
            if (ok) { tab = 0; toast("PDF created") } else toast("PDF creation failed. Please try again.")
        }
    }
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.importPdf(u, "Imported_" + stamp()) } }
            refresh(); tab = 0
        }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    fun openCam(m: CamMode) { if (m == CamMode.HAND && !prefs.aiReady) aiHint = true else camMode = m }

    fun onCaptured(uris: List<Uri>, m: CamMode) {
        camMode = null
        when (m) {
            CamMode.DOCS -> { val rv = review; if (rv != null) rv.add(uris) else review = ReviewState(uris) }
            CamMode.TEXT -> runOcr(uris, false)
            CamMode.HAND -> runOcr(uris, true)
            CamMode.ID -> { idQueue = uris; idDone = emptyList() }
        }
    }

    fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { store.uri(it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }

    fun mergeSel() {
        val files = sel.toList()
        if (files.size < 2) { toast("Select 2 or more PDFs to merge."); return }
        scope.launch {
            busy = "Merging…"
            val ok = withContext(Dispatchers.IO) {
                runCatching { store.rebuild(files.flatMap { f -> (0 until store.pageCount(f)).map { f to it } }, 1400, "Merged") }.isSuccess
            }
            busy = null; selecting = false; sel.clear(); refresh()
            toast(if (ok) "Merged PDF saved" else "PDF creation failed. Please try again.")
        }
    }

    fun galleryFiles() {
        val files = sel.toList()
        if (files.isEmpty()) return
        gate {
            scope.launch {
                busy = "Saving to Gallery…"
                val n = withContext(Dispatchers.IO) { files.sumOf { Export.savePdfToGallery(ctx, it, false) } }
                busy = null; selecting = false; sel.clear()
                toast(if (n > 0) "Saved $n image(s) to Gallery" else "Could not save to Gallery. Please try again.")
            }
        }
    }

    val ts = toolStart
    if (ts != null) {
        BackHandler { toolStart = null }
        PdfToolsScreen(ts, docs, store, { toolStart = null }, ::refresh, snack, scope)
        return
    }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsPage(prefs) { showSettings = false }
        return
    }
    val st = studio
    if (st != null) {
        BackHandler { studio = null }
        StudioScreen(st, store, snack, scope, { studio = null },
            { studio = null; editor = null; refresh(); tab = 0; toast("Saved to Documents") })
        return
    }
    val ed = editor
    if (ed != null) {
        BackHandler { editor = null }
        EditorScreen(ed, editorIsHandwriting, store, { editor = null }, ::refresh, snack, scope) { s -> studio = StudioInit(s, DocTemplate.PLAIN) }
        return
    }
    val cm = camMode
    if (cm != null) {
        BackHandler { camMode = null; if (review?.pages?.isEmpty() == true) review = null }
        CameraScreen(cm, { camMode = it }, batch, { batch = it }, prefs.aiReady,
            { uris, m -> onCaptured(uris, m) },
            { camMode = null; if (review?.pages?.isEmpty() == true) review = null },
            { camMode = null; startScan(Mode.SCAN) })
        return
    }
    if (idQueue.isNotEmpty()) {
        val k = idDone.size
        BackHandler { idQueue = emptyList(); idDone = emptyList() }
        CropScreen(idQueue[k], 0, if (k == 0) "Crop the FRONT side" else "Crop the BACK side",
            onDone = { u ->
                val d = idDone + u
                if (d.size >= idQueue.size) {
                    idQueue = emptyList(); idDone = emptyList()
                    scope.launch {
                        busy = "Preparing ID card page…"
                        val res = withContext(Dispatchers.IO) { runCatching { IdCard.combine(ctx, d) }.getOrNull() }
                        busy = null
                        if (res != null) review = ReviewState(listOf(res)) else toast("Could not prepare the ID card page. Please try again.")
                    }
                } else idDone = d
            },
            onCancel = { idQueue = emptyList(); idDone = emptyList() })
        return
    }
    val rv = review
    val ci = cropIdx
    if (rv != null && ci != null && ci < rv.pages.size) {
        BackHandler { cropIdx = null }
        CropScreen(rv.pages[ci], rv.rots[ci], "Crop page ${ci + 1}",
            onDone = { u -> rv.pages[ci] = u; rv.rots[ci] = 0; cropIdx = null },
            onCancel = { cropIdx = null })
        return
    }
    if (rv != null) {
        BackHandler { review = null }
        ScanReviewScreen(rv, store, snack, scope,
            onBack = { review = null },
            onSaved = { review = null; refresh(); tab = 0; toast("Saved to Documents") },
            onExtract = { runOcr(rv.pages.toList(), false) },
            onAdd = { camMode = CamMode.DOCS },
            onRetake = { i -> rv.remove(i); camMode = CamMode.DOCS },
            onCrop = { i -> cropIdx = i })
        busy?.let { BusyDialog(it) }
        return
    }

    val sections = listOf(
        "Scan" to listOf(
            ToolItem(Icons.Filled.DocumentScanner, "Scan Docs", Blue) { openCam(CamMode.DOCS) },
            ToolItem(Icons.Filled.Description, "ID Card", Color(0xFF00897B)) { openCam(CamMode.ID) },
            ToolItem(Icons.Filled.TextFields, "To Text", Color(0xFF2E7D32)) { openCam(CamMode.TEXT) },
            ToolItem(Icons.Filled.Edit, "Handwriting", Blue) { openCam(CamMode.HAND) },
            ToolItem(Icons.Filled.Crop, "Auto-crop", Color(0xFF00695C)) { startScan(Mode.SCAN) }),
        "Create" to listOf(
            ToolItem(Icons.Filled.Edit, "Text to PDF / Word", Blue) { studio = StudioInit("", DocTemplate.PLAIN) },
            ToolItem(Icons.Filled.Description, "Official Letter", Color(0xFF00897B)) { studio = StudioInit("", DocTemplate.LETTER) }),
        "Convert" to listOf(
            ToolItem(Icons.Filled.PictureAsPdf, "Image to PDF", Color(0xFFE53935)) { pickImages.launch(imageOnly) },
            ToolItem(Icons.Filled.Image, "Select Image to Text", Color(0xFF2E7D32)) { pickImage.launch(imageOnly) },
            ToolItem(Icons.Filled.Folder, "Import PDF", Blue) { importPdf.launch(arrayOf("application/pdf")) }),
        "PDF Tools" to listOf(
            ToolItem(Icons.Filled.Layers, "Merge", Blue) { toolStart = Tool.MERGE },
            ToolItem(Icons.Filled.ContentCut, "Split", Color(0xFF00897B)) { toolStart = Tool.EXTRACT },
            ToolItem(Icons.Filled.Delete, "Delete pages", Color(0xFFE53935)) { toolStart = Tool.DELETE },
            ToolItem(Icons.Filled.Archive, "Compress", Color(0xFF00695C)) { toolStart = Tool.COMPRESS }))

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (selecting) SelectionBar(sel.size, { shareFiles(sel.toList()) }, { galleryFiles() }, { moveDialog = true }, { mergeSel() }, { deleteDialog = true })
            else MainBar(tab, { tab = it }) { openCam(CamMode.DOCS) }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> DocsScreen(docs, store, ::refresh, ::toast, selecting, sel,
                    { selecting = it; if (!it) sel.clear() }, { showSettings = true },
                    { openCam(CamMode.HAND) }, { openCam(CamMode.TEXT) }, { studio = StudioInit("", DocTemplate.PLAIN) })
                else -> ToolsScreen(sections)
            }
            if (aiHint) AlertDialog({ aiHint = false }, title = { Text("Better Hindi handwriting?") },
                text = { Text("Offline recognition is weak on handwriting. Turn on AI recognition in Settings (needs a free Gemini API key) for much better results.") },
                confirmButton = { TextButton({ aiHint = false; showSettings = true }) { Text("Open Settings") } },
                dismissButton = { TextButton({ aiHint = false; camMode = CamMode.HAND }) { Text("Continue offline") } })
            if (moveDialog) AlertDialog({ moveDialog = false }, title = { Text("Move to folder") },
                text = {
                    Column {
                        (listOf("") + store.folders()).forEach { fl ->
                            TextButton({
                                sel.toList().forEach { store.move(it, fl) }
                                moveDialog = false; selecting = false; sel.clear(); refresh()
                            }) { Text(if (fl.isEmpty()) "No folder" else fl) }
                        }
                    }
                },
                confirmButton = {}, dismissButton = { TextButton({ moveDialog = false }) { Text("Cancel") } })
            if (deleteDialog) AlertDialog({ deleteDialog = false }, title = { Text("Delete ${sel.size} document(s)?") },
                text = { Text("This can't be undone.") },
                confirmButton = {
                    TextButton({
                        sel.toList().forEach { store.delete(it) }
                        deleteDialog = false; selecting = false; sel.clear(); refresh()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton({ deleteDialog = false }) { Text("Cancel") } })
            busy?.let { BusyDialog(it) }
        }
    }
}

@Composable
fun MainBar(tab: Int, onTab: (Int) -> Unit, onScan: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        NavigationBar(Modifier.padding(top = 28.dp)) {
            NavigationBarItem(tab == 0, { onTab(0) }, { Icon(Icons.Filled.Description, null) }, label = { Text("Docs") })
            NavigationBarItem(false, {}, {}, enabled = false)
            NavigationBarItem(tab == 1, { onTab(1) }, { Icon(Icons.Filled.Apps, null) }, label = { Text("Tools") })
        }
        FloatingActionButton(onScan, Modifier.align(Alignment.TopCenter).size(64.dp), shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Icon(Icons.Filled.CameraAlt, "Scan", Modifier.size(30.dp))
        }
    }
}

@Composable
fun SelectionBar(count: Int, onShare: () -> Unit, onGallery: () -> Unit, onMove: () -> Unit, onMerge: () -> Unit, onDelete: () -> Unit) {
    NavigationBar {
        NavigationBarItem(false, { if (count > 0) onShare() }, { Icon(Icons.Filled.Share, "Share") }, label = { Text("Share") })
        NavigationBarItem(false, { if (count > 0) onGallery() }, { Icon(Icons.Filled.Image, "Save to Gallery") }, label = { Text("Gallery") })
        NavigationBarItem(false, { if (count > 0) onMove() }, { Icon(Icons.Filled.Folder, "Move") }, label = { Text("Move") })
        NavigationBarItem(false, { if (count > 0) onMerge() }, { Icon(Icons.Filled.Layers, "Merge") }, label = { Text("Merge") })
        NavigationBarItem(false, { if (count > 0) onDelete() }, { Icon(Icons.Filled.Delete, "Delete") }, label = { Text("Delete") })
    }
}

@Composable
fun ToolsScreen(sections: List<Pair<String, List<ToolItem>>>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Tools", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        sections.forEach { (title, tools) ->
            item { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            items(tools.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t -> ToolTile(t, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item { Text("Everything here works offline except AI text recognition.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun ToolTile(t: ToolItem, modifier: Modifier) {
    Card(t.onClick, modifier.heightIn(min = 112.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(t.tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(t.icon, null, tint = t.tint)
            }
            Spacer(Modifier.height(8.dp))
            Text(t.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}

@Composable
fun DocsScreen(docs: List<File>, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit,
               selecting: Boolean, sel: SnapshotStateList<File>, onSelecting: (Boolean) -> Unit,
               onSettings: () -> Unit, onHand: () -> Unit, onText: () -> Unit, onPdf: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val folders = remember(docs) { store.folders() }
    val shown = docs.filter { f ->
        (folder == null || store.folderOf(f) == folder) &&
            (q.isBlank() || f.nameWithoutExtension.contains(q, true) || store.searchText(f).contains(q, true))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.scantype_logo), "ScanType logo", Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
                Text("ScanType", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton({ searching = !searching; if (!searching) q = "" }) { Icon(Icons.Filled.Search, "Search") }
                IconButton(onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
        if (searching) item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or text") },
                singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        if (!selecting) item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Blue, Cyan))).padding(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Scan. Convert. Edit. PDF.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Hindi & English handwriting to typed text", color = Color.White.copy(alpha = 0.92f))
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pad = PaddingValues(horizontal = 6.dp)
                        Button(onHand, Modifier.weight(1f), contentPadding = pad,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Blue)) { Text("Handwriting", fontSize = 12.sp) }
                        OutlinedButton(onText, Modifier.weight(1f), contentPadding = pad,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Image→Text", fontSize = 12.sp) }
                        OutlinedButton(onPdf, Modifier.weight(1f), contentPadding = pad,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Text→PDF", fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onSelecting(false) }) { Icon(Icons.Filled.Close, "Cancel") }
                Text("${sel.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton({ sel.clear(); sel.addAll(shown) }) { Text("Select all") }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { folderMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text("${folder ?: "All docs"} (${shown.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(folderMenu, { folderMenu = false }) {
                        DropdownMenuItem({ Text("All docs") }, { folder = null; folderMenu = false })
                        folders.forEach { fl -> DropdownMenuItem({ Text(fl) }, { folder = fl; folderMenu = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton({ newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                IconButton({ onSelecting(true) }) { Icon(Icons.Filled.CheckCircle, "Select") }
            }
        }
        if (shown.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your scanned documents will appear here.")
                Spacer(Modifier.height(8.dp))
                Text("Tap the camera button to scan your first document.", style = MaterialTheme.typography.bodySmall)
            }
        } else items(shown, key = { it.path }) { f ->
            DocRow(f, store, refresh, toast, selecting, f in sel) { if (f in sel) sel.remove(f) else sel.add(f) }
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
fun DocRow(f: File, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit,
           selecting: Boolean, selected: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val rowScope = rememberCoroutineScope()
    val rowGate = rememberWriteGate { toast("Storage permission is needed to save to Gallery on this Android version.") }
    val thumb by produceState<Bitmap?>(null, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.thumb(f) } }
    val pages by produceState(0, f.path, f.lastModified()) { value = withContext(Dispatchers.IO) { store.pageCount(f) } }
    fun send(action: String) {
        val u = store.uri(f)
        val i = if (action == Intent.ACTION_SEND) Intent.createChooser(
            Intent(action).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        else Intent(action).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { toast("No app found to open this file.") }
    }
    Card(onClick = { if (selecting) onToggle() else send(Intent.ACTION_VIEW) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                val b = thumb
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.PictureAsPdf, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.nameWithoutExtension, maxLines = 2, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(DateFormat.getDateInstance().format(Date(f.lastModified())), style = MaterialTheme.typography.bodySmall)
                Text("$pages pages · ${sizeText(f.length())}", style = MaterialTheme.typography.bodySmall)
            }
            if (selecting) Checkbox(selected, null)
            else Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Open") }, { menu = false; send(Intent.ACTION_VIEW) })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                    DropdownMenuItem({ Text("Share") }, { menu = false; send(Intent.ACTION_SEND) })
                    DropdownMenuItem({ Text("Print") }, { menu = false; Export.print(ctx, f, f.nameWithoutExtension) })
                    DropdownMenuItem({ Text("Save to Gallery") }, {
                        menu = false
                        rowGate {
                            rowScope.launch {
                                val n = withContext(Dispatchers.IO) { Export.savePdfToGallery(ctx, f, false) }
                                toast(if (n > 0) "Saved $n image(s) to Gallery" else "Could not save to Gallery. Please try again.")
                            }
                        }
                    })
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
fun SettingsPage(prefs: Prefs, close: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar({ Text("Settings") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad -> Box(Modifier.padding(pad)) { SettingsScreen(prefs) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(initial: String, handwriting: Boolean, store: DocStore, close: () -> Unit, refresh: () -> Unit,
                 snack: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, onCreate: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    Scaffold(
        topBar = { TopAppBar({ Text("Recognized Text") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (handwriting) Text("Handwriting recognition can make mistakes. Please review and correct the text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (text.contains("[?]")) Text("Words marked [?] are unclear. Please check them, or rescan in better light.",
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
                Button({ onCreate(text) }) { Text("Create PDF") }
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
