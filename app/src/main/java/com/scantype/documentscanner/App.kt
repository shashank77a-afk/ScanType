package com.scantype.documentscanner

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    var pendingScan by remember { mutableStateOf<Uri?>(null) }
    var showTools by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    // 1. Runtime Permissions Setup
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasCameraPermission = perms[Manifest.permission.CAMERA] ?: hasCameraPermission
    }

    LaunchedEffect(Unit) {
        val req = mutableListOf<String>()
        if (!hasCameraPermission) req.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                req.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (req.isNotEmpty()) permLauncher.launch(req.toTypedArray())
    }

    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }
    fun refresh() { docs = store.list() }

    fun getBitmapFromUri(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        }
    }

    // 2. OCR Logic (Hindi ML Kit + Gemini API fallback)
    fun runOcr(uris: List<Uri>, handwriting: Boolean) {
        scope.launch {
            val out = StringBuilder()
            var failed = 0
            val apiKey = "YOUR_API_KEY" // Add your Google AI Studio key when ready

            if (handwriting && apiKey != "YOUR_API_KEY" && apiKey.isNotBlank()) {
                busy = "Gemini AI is reading handwriting..."
                try {
                    val generativeModel = GenerativeModel(
                        modelName = "gemini-1.5-flash",
                        apiKey = apiKey
                    )
                    uris.forEach { u ->
                        val bmp = withContext(Dispatchers.IO) { getBitmapFromUri(u) }
                        val inputContent = content {
                            image(bmp)
                            text("Extract all handwritten Hindi and English text accurately. Return only the extracted text without any preamble.")
                        }
                        val response = withContext(Dispatchers.IO) { generativeModel.generateContent(inputContent) }
                        response.text?.let { out.append(it.trim()).append("\n\n") }
                    }
                } catch (e: Exception) {
                    failed++
                }
            } else {
                busy = "Extracting text (Devanagari ML Kit)..."
                val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                uris.forEach { u ->
                    try {
                        val image = InputImage.fromFilePath(ctx, u)
                        val result = recognizer.process(image).await()
                        out.append(result.text).append("\n\n")
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }

            busy = null
            if (out.isBlank()) toast("We couldn't recognize this page. Please try again with better lighting.")
            else {
                editorIsHandwriting = handwriting
                editor = out.toString().trim()
                if (failed > 0) toast("Some pages could not be read.")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val res = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        if (res != null) scope.launch {
            val pdf = res.pdf?.uri
            if (mode == Mode.SCAN) pendingScan = pdf
            else {
                if (pdf != null) withContext(Dispatchers.IO) { runCatching { store.importPdf(pdf, "Handwriting_" + stamp()) } }
                refresh()
                runOcr(res.pages?.map { it.imageUri } ?: emptyList(), true)
            }
        }
    }

    fun startScan(m: Mode) {
        if (!hasCameraPermission) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        mode = m
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast("Scanner isn't available. Please update Google Play services.") }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) runOcr(listOf(u), false)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) scope.launch {
            busy = "Creating PDF…"
            val ok = withContext(Dispatchers.IO) { runCatching { store.imagesToPdf(us) }.isSuccess }
            busy = null
            refresh()
            if (ok) { tab = 1; toast("PDF created") } else toast("PDF creation failed.")
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { startScan(Mode.SCAN) },
                    icon = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.PhotoCamera,
                                    contentDescription = "Scan",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    label = {
                        Text(
                            "Scan",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = "Documents") },
                    label = { Text("Docs") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            when (tab) {
                0 -> HomeScreen(
                    docs = docs,
                    store = store,
                    refresh = ::refresh,
                    toast = ::toast,
                    seeAll = { tab = 1 },
                    onScan = { startScan(Mode.SCAN) },
                    onHand = { startScan(Mode.HANDWRITING) },
                    onImgText = { pickImage.launch(imageOnly) },
                    onImgPdf = { pickImages.launch(imageOnly) },
                    onTools = { showTools = true }
                )
                1 -> DocumentsScreen(docs, store, ::refresh, ::toast)
                else -> SettingsScreen()
            }
            pendingScan?.let { u ->
                SaveScanDialog(
                    onSave = { name ->
                        pendingScan = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { store.importPdf(u, name) } }
                            refresh()
                            tab = 1
                            toast("Saved to Documents")
                        }
                    },
                    onCancel = { pendingScan = null }
                )
            }
            busy?.let {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(it)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    docs: List<File>,
    store: DocStore,
    refresh: () -> Unit,
    toast: (String) -> Unit,
    seeAll: () -> Unit,
    onScan: () -> Unit,
    onHand: () -> Unit,
    onImgText: () -> Unit,
    onImgPdf: () -> Unit,
    onTools: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.scantype_logo),
                    contentDescription = "ScanType logo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "ScanType",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Scan. Convert. Edit. PDF.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PremiumActionCard(
                    icon = Icons.Default.DocumentScanner,
                    title = "Scan Document",
                    subtitle = "Paper to PDF",
                    onClick = onScan,
                    modifier = Modifier.weight(1f)
                )
                PremiumActionCard(
                    icon = Icons.Default.Edit,
                    title = "Handwriting",
                    subtitle = "Hindi/Eng to Text",
                    onClick = onHand,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PremiumActionCard(
                    icon = Icons.Default.Image,
                    title = "Image to Text",
                    subtitle = "Extract Text",
                    onClick = onImgText,
                    modifier = Modifier.weight(1f)
                )
                PremiumActionCard(
                    icon = Icons.Default.PictureAsPdf,
                    title = "Image to PDF",
                    subtitle = "Make PDF Files",
                    onClick = onImgPdf,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onTools,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("PDF Tools: Merge, Split, Compress", fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Documents",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (docs.isNotEmpty()) {
                    TextButton(onClick = seeAll) { Text("See all") }
                }
            }
        }

        if (docs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Your scanned documents will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onScan) { Text("Scan Now") }
                    }
                }
            }
        } else {
            items(docs.take(4), key = { it.path }) {
                DocRow(it, store, refresh, toast)
            }
        }
    }
}

@Composable
fun PremiumActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 14.sp
                )
            }
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
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search name or text") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            IconButton({ importPdf.launch(arrayOf("application/pdf")) }) {
                Icon(Icons.Filled.Add, "Import PDF")
            }
        }
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(folder == null, { folder = null }, { Text("All") }) }
            items(folders) { fl -> FilterChip(folder == fl, { folder = fl }, { Text(fl) }) }
            item { AssistChip({ newFolder = true }, { Text("+ Folder") }) }
        }
        if (shown.isEmpty()) Text("No documents found.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.path }) { DocRow(it, store, refresh, toast) }
        }
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.createFolder(name); newFolder = false; refresh() }) { Text("Create") } },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun DocRow(f: File, store: DocStore, refresh: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, f.path, f.lastModified()) {
        value = withContext(Dispatchers.IO) { store.thumb(f) }
    }
    val pages by produceState(0, f.path, f.lastModified()) {
        value = withContext(Dispatchers.IO) { store.pageCount(f) }
    }

    fun send(action: String) {
        val u = store.uri(f)
        val i = if (action == Intent.ACTION_SEND) {
            Intent(action)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(action)
                .setDataAndType(u, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(i) }.onFailure { toast("No app found to open this file.") }
    }

    Card(
        onClick = { send(Intent.ACTION_VIEW) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp))) {
                thumb?.let { Image(it.asImageBitmap(), null) } ?: Icon(Icons.Filled.PictureAsPdf, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.nameWithoutExtension, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${DateFormat.getDateInstance().format(Date(f.lastModified()))} · $pages pages · ${f.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall
                )
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
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ store.rename(f, name); renaming = false; refresh() }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } }
        )
    }
    if (moving) {
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to folder") },
            text = {
                Column {
                    (listOf("") + store.folders()).forEach { fl ->
                        TextButton({ store.move(f, fl); moving = false; refresh() }) {
                            Text(if (fl.isEmpty()) "No folder" else fl)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ moving = false }) { Text("Cancel") } }
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this document?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ store.delete(f); deleting = false; refresh() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initial: String,
    handwriting: Boolean,
    store: DocStore,
    close: () -> Unit,
    refresh: () -> Unit,
    snack: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recognized Text") },
                navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            if (handwriting) {
                Text(
                    "Handwriting recognition can make mistakes. Please review and correct the text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("text", text))
                    scope.launch { snack.showSnackbar("Copied") }
                }) { Text("Copy") }
                OutlinedButton({
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            null
                        )
                    )
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