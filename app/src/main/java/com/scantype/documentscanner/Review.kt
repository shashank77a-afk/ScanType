package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pages of one document, each with its original image, filter and rotation. */
class ReviewState(initial: List<Uri>) {
    val pages = mutableStateListOf<Uri>().apply { addAll(initial) }
    val origs = mutableStateListOf<Uri>().apply { addAll(initial) }
    val filters = mutableStateListOf<PageFilter>().apply { repeat(initial.size) { add(PageFilter.ENHANCE) } }
    val rots = mutableStateListOf<Int>().apply { repeat(initial.size) { add(0) } }
    val textOverlays = mutableStateListOf<String>().apply { repeat(initial.size) { add("") } }
    val watermarks = mutableStateListOf<String>().apply { repeat(initial.size) { add("") } }
    val signatures = mutableStateListOf<List<Pair<Float, Float>>>().apply { repeat(initial.size) { add(emptyList()) } }
    val highlights = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    val brushes = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    var title by mutableStateOf("ScannerAi " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date()))
    fun add(u: List<Uri>) {
        u.forEach {
            pages.add(it); origs.add(it); filters.add(filters.lastOrNull() ?: PageFilter.ENHANCE); rots.add(0)
            textOverlays.add(""); watermarks.add(""); signatures.add(emptyList()); highlights.add(emptyList()); brushes.add(emptyList())
        }
    }
    fun replace(i: Int, u: Uri) { pages[i] = u; origs[i] = u; rots[i] = 0 }
    fun remove(i: Int) {
        pages.removeAt(i); origs.removeAt(i); filters.removeAt(i); rots.removeAt(i)
        textOverlays.removeAt(i); watermarks.removeAt(i); signatures.removeAt(i); highlights.removeAt(i); brushes.removeAt(i)
    }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= pages.size) return
        val p = pages[i]; pages[i] = pages[j]; pages[j] = p
        val o = origs[i]; origs[i] = origs[j]; origs[j] = o
        val f = filters[i]; filters[i] = filters[j]; filters[j] = f
        val r = rots[i]; rots[i] = rots[j]; rots[j] = r
        val t = textOverlays[i]; textOverlays[i] = textOverlays[j]; textOverlays[j] = t
        val wm = watermarks[i]; watermarks[i] = watermarks[j]; watermarks[j] = wm
        val sig = signatures[i]; signatures[i] = signatures[j]; signatures[j] = sig
        val hi = highlights[i]; highlights[i] = highlights[j]; highlights[j] = hi
        val br = brushes[i]; brushes[i] = brushes[j]; brushes[j] = br
    }
}

/** Composites annotations into the page bitmap so the same content reaches previews and exports. */
private fun compositeMarkup(
    bitmap: Bitmap, text: String, watermark: String,
    signature: List<Pair<Float, Float>> = emptyList(),
    highlights: List<List<Pair<Float, Float>>> = emptyList(),
    brushes: List<List<Pair<Float, Float>>> = emptyList()
): Bitmap {
    if (text.isBlank() && watermark.isBlank() && signature.isEmpty() && highlights.isEmpty() && brushes.isEmpty()) return bitmap
    val canvas = android.graphics.Canvas(bitmap)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    highlights.forEach { stroke ->
        if (stroke.size > 1) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
                val x = point.first.coerceIn(0f, 1f) * w
                val y = point.second.coerceIn(0f, 1f) * h
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val highlightPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(92, 255, 218, 0)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (w * 0.028f).coerceIn(14f, 42f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            canvas.drawPath(path, highlightPaint)
        }
    }
    brushes.forEach { stroke ->
        if (stroke.size > 1) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
                val x = point.first.coerceIn(0f, 1f) * w
                val y = point.second.coerceIn(0f, 1f) * h
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val brushPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(25, 75, 160)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (w * 0.008f).coerceIn(5f, 16f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            canvas.drawPath(path, brushPaint)
        }
    }
    if (watermark.isNotBlank()) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(105, 90, 90, 90)
            textSize = (w * 0.065f).coerceIn(22f, 100f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.save()
        canvas.rotate(-24f, w / 2f, h / 2f)
        canvas.drawText(watermark.take(80), w / 2f, h / 2f, paint)
        canvas.restore()
    }
    if (signature.size > 1) {
        val path = android.graphics.Path()
        signature.forEachIndexed { index, point ->
            val x = point.first.coerceIn(0f, 1f) * w
            val y = point.second.coerceIn(0f, 1f) * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val signaturePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(20, 45, 125)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (w * 0.006f).coerceIn(4f, 14f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        canvas.drawPath(path, signaturePaint)
    }
    if (text.isNotBlank()) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = (w * 0.032f).coerceIn(18f, 48f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        }
        val safeText = text.take(500)
        val lines = safeText.split("\n").take(8)
        val lineHeight = paint.textSize * 1.25f
        val maxWidth = w * 0.82f
        val y0 = h - (lines.size * lineHeight) - h * 0.045f
        val rectPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(220, 255, 255, 255)
        }
        canvas.drawRoundRect(
            w * 0.045f, y0 - paint.textSize * 0.5f, w * 0.955f, h - h * 0.025f,
            w * 0.01f, w * 0.01f, rectPaint
        )
        lines.forEachIndexed { index, line ->
            var clipped = line
            while (paint.measureText(clipped) > maxWidth && clipped.isNotEmpty()) clipped = clipped.dropLast(1)
            canvas.drawText(clipped, w * 0.06f, y0 + index * lineHeight, paint)
        }
    }
    return bitmap
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onBackground)
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp)
    }
}

@Composable
private fun PageThumb(uri: Uri, rot: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, rot) {
        value = withContext(Dispatchers.Default) { runCatching { ImageFilters.rotate(ImageFilters.load(ctx, uri, 160), rot) }.getOrNull() }
    }
    Box(Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier).clickable { onClick() }) {
        bmp?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp))
    }
}

@Composable
fun ScanReviewScreen(r: ReviewState, store: DocStore, prefs: Prefs, snack: SnackbarHostState, scope: CoroutineScope,
                     onBack: () -> Unit, onSaved: () -> Unit, onExtract: () -> Unit, onAddCamera: () -> Unit,
                     onRetake: (Int) -> Unit, onCrop: (Int) -> Unit) {
    val ctx = LocalContext.current
    if (r.pages.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    var idx by remember { mutableIntStateOf(0) }
    var applyAll by remember { mutableStateOf(true) }
    var compareOriginal by remember { mutableStateOf(false) }
    var editorTab by remember { mutableStateOf("Images") }
    var showTextDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signatureDraft by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var drawingMode by remember { mutableStateOf("Highlight") }
    var highlightDraft by remember { mutableStateOf(emptyList<List<Pair<Float, Float>>>()) }
    var activeHighlight by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var textDraft by remember { mutableStateOf("") }
    var watermarkDraft by remember { mutableStateOf("") }
    val liveSignatureDraft by rememberUpdatedState(signatureDraft)
    val liveActiveHighlight by rememberUpdatedState(activeHighlight)
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var gDialog by remember { mutableStateOf(false) }
    var gPng by remember { mutableStateOf(false) }
    var gAll by remember { mutableStateOf(true) }
    var fDialog by remember { mutableStateOf(false) }
    var fMode by remember { mutableIntStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    val gate = rememberWriteGate { scope.launch { snack.showSnackbar("Storage permission is needed to save images on this Android version.") } }
    val cur = idx.coerceIn(0, r.pages.size - 1)
    val page = r.pages[cur]; val flt = r.filters[cur]; val rot = r.rots[cur]

    fun addProcessed(us: List<Uri>) {
        scope.launch {
            saving = true
            val p = withContext(Dispatchers.IO) { us.map { if (prefs.autoCrop) runCatching { EdgeDetect.autoCrop(ctx, it) }.getOrDefault(it) else it } }
            r.add(p); idx = r.pages.size - 1; saving = false
        }
    }
    val pickG = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us -> if (us.isNotEmpty()) addProcessed(us) }
    val pickF = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> if (us.isNotEmpty()) addProcessed(us) }

    val preview by produceState<Bitmap?>(null, page, flt, rot, compareOriginal) {
        // Dispose of the previous preview before creating a new native bitmap.
        value?.let { old -> runCatching { old.recycle() } }
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                compositeMarkup(
                    ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, page, 1200), rot), if (compareOriginal) PageFilter.ORIGINAL else flt),
                    if (compareOriginal) "" else r.textOverlays[cur],
                    if (compareOriginal) "" else r.watermarks[cur],
                    if (compareOriginal) emptyList() else r.signatures[cur],
                    if (compareOriginal) emptyList() else r.highlights[cur],
                    if (compareOriginal) emptyList() else r.brushes[cur]
                )
            }.getOrNull()
        }
    }
    val thumbs by produceState<Map<PageFilter, Bitmap>>(emptyMap(), page, rot) {
        value.values.forEach { old -> runCatching { old.recycle() } }
        value = emptyMap()
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = ImageFilters.rotate(ImageFilters.load(ctx, page, 220), rot)
                try {
                    PageFilter.values().associateWith { f ->
                        compositeMarkup(
                            ImageFilters.apply(base.copy(Bitmap.Config.ARGB_8888, false)!!, f),
                            r.textOverlays[cur], r.watermarks[cur], r.signatures[cur], r.highlights[cur], r.brushes[cur]
                        )
                    }
                } finally {
                    base.recycle()
                }
            }.getOrDefault(emptyMap())
        }
    }
    val warn by produceState<String?>(null, page) {
        value = withContext(Dispatchers.Default) {
            runCatching { val b = ImageFilters.load(ctx, page, 500); val m = EdgeDetect.quality(b); b.recycle(); m }.getOrNull()
        }
    }

    fun finish() {
        val pages = r.pages.toList(); val fl = r.filters.toList(); val ro = r.rots.toList(); val name = r.title; val mode = fMode
        scope.launch {
            saving = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    fun bmp(i: Int) = compositeMarkup(
                        ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, pages[i], 1500), ro[i]), fl[i]),
                        r.textOverlays[i], r.watermarks[i], r.signatures[i], r.highlights[i], r.brushes[i]
                    )
                    val saved = when (mode) {
                        0 -> store.bitmapsToPdf(pages.size, name) { bmp(it) }
                        1 -> store.newFileExt(name, "pdf").also { PdfBuilders.searchable(it, pages.size) { i -> bmp(i) } }
                        else -> store.newFileExt(name, "pdf").also { out ->
                            PdfBuilders.originalPlusTyped(ctx, out, pages.size, pickEngine(prefs, false),
                                mode == 2, { i -> bmp(i) }) { p -> progress = "Reading page $p of ${pages.size}…" }
                        }
                    }
                    val exported = Export.saveToDownloads(ctx, saved, saved.name, "application/pdf")
                    saved to exported
                }.getOrNull()
            }
            saving = false; progress = ""
            if (result != null) {
                if (result.second) snack.showSnackbar("PDF saved to Download/ScannerAi.")
                else snack.showSnackbar("PDF saved in Scanner.ai, but could not copy it to Download/ScannerAi.")
                onSaved()
            } else snack.showSnackbar("PDF creation failed. Please try again.")
        }
    }

    fun saveToGallery() {
        val pages = r.pages.toList(); val fl = r.filters.toList(); val ro = r.rots.toList()
        val name = r.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Scan" }
        val which = if (gAll) pages.indices.toList() else listOf(cur)
        val png = gPng
        scope.launch {
            saving = true
            val n = withContext(Dispatchers.IO) {
                var count = 0
                for (i in which) {
                    val ok = runCatching {
                        val b = compositeMarkup(
                            ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, pages[i], 2000), ro[i]), fl[i]),
                            r.textOverlays[i], r.watermarks[i], r.signatures[i], r.highlights[i], r.brushes[i]
                        )
                        val res = Export.saveImage(ctx, b, "${name}_${i + 1}", png)
                        b.recycle(); res
                    }.getOrDefault(false)
                    if (ok) count++
                }
                count
            }
            saving = false
            snack.showSnackbar(if (n > 0) "Saved $n image(s) to Gallery (Pictures/ScannerAi)" else "Could not save to Gallery. Please try again.")
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground) }
                BasicTextField(r.title, { r.title = it }, Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp), singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
                Button({ fDialog = true }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Text(if (saving) "Working…" else "Finish")
                }
            }
            warn?.let { w ->
                Row(Modifier.fillMaxWidth().background(Color(0x33FFC107)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton({ onRetake(cur) }) { Text("Retake", color = Cyan) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) CircularProgressIndicator(color = Cyan)
                else Image(p.asImageBitmap(), "Page ${cur + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton({ r.remove(cur); idx = 0 }, Modifier.align(Alignment.TopStart).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.Filled.Delete, "Delete page", tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (cur > 0) { r.move(cur, -1); idx = cur - 1 } }) { Text("◀ Move", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.weight(1f))
                Text("Page ${cur + 1} of ${r.pages.size}", color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                TextButton({ if (cur < r.pages.size - 1) { r.move(cur, 1); idx = cur + 1 } }) { Text("Move ▶", color = MaterialTheme.colorScheme.primary) }
            }
            LazyRow(Modifier.padding(vertical = 4.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(r.pages.toList()) { i, u -> PageThumb(u, r.rots[i], i == cur, "${i + 1}") { idx = i } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Same filter for all pages", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(applyAll, { applyAll = it })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { compareOriginal = !compareOriginal }) {
                    Text(if (compareOriginal) "Showing Original · Return to Filter" else "Compare with Original",
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    r.filters[cur] = PageFilter.ORIGINAL
                    r.rots[cur] = 0
                    compareOriginal = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.primary) }
            }
            LazyRow(Modifier.padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(PageFilter.values().toList()) { f ->
                    val sel = f == flt
                    Column(Modifier.clickable {
                        if (applyAll) { for (i in r.filters.indices) r.filters[i] = f } else r.filters[cur] = f
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF164532))
                            .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)) {
                            thumbs[f]?.let { Image(it.asImageBitmap(), f.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(f.label, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Images", "Markup", "Page").forEach { tabName ->
                    FilterChip(
                        selected = editorTab == tabName,
                        onClick = { editorTab = tabName },
                        label = { Text(tabName) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (editorTab) {
                    "Images" -> {
                        BarButton(Icons.Filled.CameraAlt, "Retake") { onRetake(cur) }
                        Box {
                            BarButton(Icons.Filled.Add, "Add") { addMenu = true }
                            DropdownMenu(addMenu, { addMenu = false }) {
                                DropdownMenuItem({ Text("Camera") }, { addMenu = false; onAddCamera() })
                                DropdownMenuItem({ Text("Gallery") }, { addMenu = false; pickG.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem({ Text("Files") }, { addMenu = false; pickF.launch(arrayOf("image/*")) })
                            }
                        }
                        BarButton(Icons.Filled.Crop, "Crop") { onCrop(cur) }
                        BarButton(Icons.Filled.RotateLeft, "Rotate") { r.rots[cur] = (rot + 270) % 360 }
                        BarButton(Icons.Filled.Image, "Reset") {
                            r.pages[cur] = r.origs[cur]; r.filters[cur] = PageFilter.ORIGINAL
                            r.rots[cur] = 0; compareOriginal = false
                        }
                    }
                    "Markup" -> {
                        BarButton(Icons.Filled.TextFields, "Add Text") {
                            textDraft = r.textOverlays[cur]; showTextDialog = true
                        }
                        BarButton(Icons.Filled.Edit, "Signature") {
                            signatureDraft = r.signatures[cur]; showSignatureDialog = true
                        }
                        BarButton(Icons.Filled.Edit, "Highlight") {
                            drawingMode = "Highlight"; highlightDraft = r.highlights[cur]
                            activeHighlight = emptyList(); showHighlightDialog = true
                        }
                        BarButton(Icons.Filled.Edit, "Brush") {
                            drawingMode = "Brush"; highlightDraft = r.brushes[cur]
                            activeHighlight = emptyList(); showHighlightDialog = true
                        }
                        BarButton(Icons.Filled.Image, "Watermark") {
                            watermarkDraft = r.watermarks[cur]; showWatermarkDialog = true
                        }
                        BarButton(Icons.Filled.TextFields, "Read Text") { onExtract() }
                        BarButton(Icons.Filled.Image, "Gallery") { gDialog = true }
                    }
                    else -> {
                        BarButton(Icons.Filled.Add, "Add Page") { addMenu = true }
                        BarButton(Icons.Filled.Delete, "Delete") { r.remove(cur); idx = (cur - 1).coerceAtLeast(0) }
                        BarButton(Icons.Filled.ArrowBack, "Move Left") { if (cur > 0) { r.move(cur, -1); idx = cur - 1 } }
                        BarButton(Icons.Filled.ArrowForward, "Move Right") { if (cur < r.pages.size - 1) { r.move(cur, 1); idx = cur + 1 } }
                    }
                }
            }
        }
    }
    if (showTextDialog) AlertDialog(
        onDismissRequest = { showTextDialog = false },
        title = { Text("Add text to page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This text will be added to the current page and included when you export it.")
                androidx.compose.material3.OutlinedTextField(
                    value = textDraft, onValueChange = { textDraft = it },
                    label = { Text("Text (up to 500 characters)") }, minLines = 2, maxLines = 5
                )
            }
        },
        confirmButton = { TextButton({
            r.textOverlays[cur] = textDraft.take(500)
            showTextDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showTextDialog = false }) { Text("Cancel") } }
    )
    if (showWatermarkDialog) AlertDialog(
        onDismissRequest = { showWatermarkDialog = false },
        title = { Text("Page watermark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The watermark is composited into the current page and included in exports.")
                androidx.compose.material3.OutlinedTextField(
                    value = watermarkDraft, onValueChange = { watermarkDraft = it },
                    label = { Text("Watermark (up to 80 characters)") }, singleLine = true
                )
            }
        },
        confirmButton = { TextButton({
            r.watermarks[cur] = watermarkDraft.take(80)
            showWatermarkDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showWatermarkDialog = false }) { Text("Cancel") } }
    )
    if (showHighlightDialog) AlertDialog(
        onDismissRequest = { showHighlightDialog = false },
        title = { Text(if (drawingMode == "Brush") "Draw on page" else "Highlight page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (drawingMode == "Brush") "Draw a mark on the page. You can add more than one stroke." else "Draw over the area to highlight. You can add more than one stroke.")
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    activeHighlight = listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = change.position
                                    activeHighlight = liveActiveHighlight + listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDragEnd = { val stroke = liveActiveHighlight; if (stroke.size > 1) highlightDraft = liveHighlightDraft + listOf(stroke); activeHighlight = emptyList() }
                            )
                        }
                ) {
                    (highlightDraft + listOf(activeHighlight)).forEach { stroke ->
                        if (stroke.size > 1) {
                            val path = androidx.compose.ui.graphics.Path()
                            stroke.forEachIndexed { i, p ->
                                val x = p.first * size.width; val y = p.second * size.height
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, if (drawingMode == "Brush") Color(0xFF194BA0) else Color(0xCCFFD600), style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (drawingMode == "Brush") 4.dp.toPx() else 14.dp.toPx()))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { highlightDraft = emptyList(); activeHighlight = emptyList() }) { Text("Clear") }
                    TextButton(onClick = { val stroke = liveActiveHighlight; if (stroke.size > 1) highlightDraft = liveHighlightDraft + listOf(stroke); activeHighlight = emptyList() }) { Text("Keep stroke") }
                }
            }
        },
        confirmButton = { TextButton({
            val stroke = liveActiveHighlight
            val all = if (stroke.size > 1) liveHighlightDraft + listOf(stroke) else liveHighlightDraft
            if (drawingMode == "Brush") r.brushes[cur] = all else r.highlights[cur] = all
            showHighlightDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showHighlightDialog = false }) { Text("Cancel") } }
    )
    if (showSignatureDialog) AlertDialog(
        onDismissRequest = { showSignatureDialog = false },
        title = { Text("Draw signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Draw with your finger. The signature will be placed on the current page and included in exports.")
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    signatureDraft = liveSignatureDraft + listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = change.position
                                    signatureDraft = signatureDraft + listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                }
                            )
                        }
                ) {
                    if (signatureDraft.size > 1) {
                        val path = androidx.compose.ui.graphics.Path()
                        signatureDraft.forEachIndexed { i, p ->
                            val x = p.first * size.width
                            val y = p.second * size.height
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, Color(0xFF142D7D), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                    }
                }
                TextButton(onClick = { signatureDraft = emptyList() }) { Text("Clear signature") }
            }
        },
        confirmButton = { TextButton({
            r.signatures[cur] = signatureDraft
            showSignatureDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showSignatureDialog = false }) { Text("Cancel") } }
    )
    if (gDialog) AlertDialog({ gDialog = false }, title = { Text("Save to Gallery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saves the edited image(s) to your phone's Gallery, in the folder Pictures/ScannerAi.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!gPng, { gPng = false }, { Text("JPG") })
                    FilterChip(gPng, { gPng = true }, { Text("PNG") })
                }
                if (r.pages.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(gAll, { gAll = true }, { Text("All ${r.pages.size} pages") })
                    FilterChip(!gAll, { gAll = false }, { Text("This page") })
                }
            }
        },
        confirmButton = { TextButton({ gDialog = false; gate { saveToGallery() } }) { Text("Save") } },
        dismissButton = { TextButton({ gDialog = false }) { Text("Cancel") } })
    if (fDialog) AlertDialog({ fDialog = false }, title = { Text("Finish document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "PDF of the scanned pages",
                    "Searchable PDF (hidden text layer, offline)",
                    "Original + typed text, page by page",
                    "Original pages, then typed text section"
                ).forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clickable { fMode = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(fMode == i, { fMode = i }); Text(l)
                    }
                }
                if (fMode >= 2) Text("The text is read from each page now, using " + (if (prefs.aiReady) "AI recognition." else "on-device recognition (weak on handwriting)."),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ fDialog = false; finish() }) { Text("Save PDF") } },
        dismissButton = { TextButton({ fDialog = false }) { Text("Cancel") } })
    if (progress.isNotEmpty()) AlertDialog({}, confirmButton = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(16.dp)); Text(progress) }
    })
}
