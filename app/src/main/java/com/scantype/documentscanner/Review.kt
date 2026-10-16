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
import androidx.compose.material.icons.filled.Check
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
/** Snapshot of reversible page edits. The original input URI is retained so filters never stack. */
private data class PageEditSnapshot(
    val page: Uri,
    val original: Uri,
    val filter: PageFilter,
    val rotation: Int,
    val text: String,
    val watermark: String,
    val signature: List<List<Pair<Float, Float>>>,
    val highlights: List<List<Pair<Float, Float>>>,
    val brushes: List<List<Pair<Float, Float>>>,
    val erasures: List<List<Pair<Float, Float>>>
)

class ReviewState(initial: List<Uri>, initialFilter: PageFilter = PageFilter.ENHANCE) {
    val pages = mutableStateListOf<Uri>().apply { addAll(initial) }
    val origs = mutableStateListOf<Uri>().apply { addAll(initial) }
    val filters = mutableStateListOf<PageFilter>().apply { repeat(initial.size) { add(initialFilter) } }
    val rots = mutableStateListOf<Int>().apply { repeat(initial.size) { add(0) } }
    val textOverlays = mutableStateListOf<String>().apply { repeat(initial.size) { add("") } }
    val watermarks = mutableStateListOf<String>().apply { repeat(initial.size) { add("") } }
    val signatures = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    val highlights = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    val brushes = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    val erasures = mutableStateListOf<List<List<Pair<Float, Float>>>>().apply { repeat(initial.size) { add(emptyList()) } }
    private val undoStacks = MutableList(initial.size) { mutableListOf<PageEditSnapshot>() }
    private val redoStacks = MutableList(initial.size) { mutableListOf<PageEditSnapshot>() }
    var title by mutableStateOf("ScannerAi " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date()))
    fun add(u: List<Uri>, initialFilter: PageFilter = filters.lastOrNull() ?: PageFilter.ENHANCE) {
        u.forEach {
            pages.add(it); origs.add(it); filters.add(initialFilter); rots.add(0)
            textOverlays.add(""); watermarks.add(""); signatures.add(emptyList()); highlights.add(emptyList()); brushes.add(emptyList()); erasures.add(emptyList())
            undoStacks.add(mutableListOf()); redoStacks.add(mutableListOf())
        }
    }
    private fun current(i: Int) = PageEditSnapshot(
        pages[i], origs[i], filters[i], rots[i], textOverlays[i], watermarks[i],
        signatures[i].map { it.toList() }, highlights[i].map { it.toList() }, brushes[i].map { it.toList() }, erasures[i].map { it.toList() }
    )
    private fun restore(i: Int, p: PageEditSnapshot) {
        pages[i] = p.page; origs[i] = p.original; filters[i] = p.filter; rots[i] = p.rotation
        textOverlays[i] = p.text; watermarks[i] = p.watermark; signatures[i] = p.signature
        highlights[i] = p.highlights; brushes[i] = p.brushes; erasures[i] = p.erasures
    }
    fun pushUndo(i: Int) {
        if (i !in pages.indices) return
        undoStacks[i].add(current(i))
        if (undoStacks[i].size > 30) undoStacks[i].removeAt(0)
        redoStacks[i].clear()
    }
    fun canUndo(i: Int) = undoStacks.getOrNull(i)?.isNotEmpty() == true
    fun canRedo(i: Int) = redoStacks.getOrNull(i)?.isNotEmpty() == true
    fun undo(i: Int) {
        if (!canUndo(i)) return
        redoStacks[i].add(current(i))
        restore(i, undoStacks[i].removeAt(undoStacks[i].lastIndex))
    }
    fun redo(i: Int) {
        if (!canRedo(i)) return
        undoStacks[i].add(current(i))
        restore(i, redoStacks[i].removeAt(redoStacks[i].lastIndex))
    }
    fun replace(i: Int, u: Uri, filter: PageFilter = filters[i]) { pushUndo(i); pages[i] = u; origs[i] = u; filters[i] = filter; rots[i] = 0 }
    fun setCroppedPage(i: Int, u: Uri) { pushUndo(i); pages[i] = u; rots[i] = 0 }
    fun remove(i: Int) {
        pages.removeAt(i); origs.removeAt(i); filters.removeAt(i); rots.removeAt(i)
        textOverlays.removeAt(i); watermarks.removeAt(i); signatures.removeAt(i); highlights.removeAt(i); brushes.removeAt(i); erasures.removeAt(i)
        undoStacks.removeAt(i); redoStacks.removeAt(i)
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
        val er = erasures[i]; erasures[i] = erasures[j]; erasures[j] = er
        val un = undoStacks[i]; undoStacks[i] = undoStacks[j]; undoStacks[j] = un
        val re = redoStacks[i]; redoStacks[i] = redoStacks[j]; redoStacks[j] = re
    }
}

/** Composites annotations into the page bitmap so the same content reaches previews and exports. */
private fun compositeMarkup(
    bitmap: Bitmap, text: String, watermark: String,
    signature: List<List<Pair<Float, Float>>> = emptyList(),
    highlights: List<List<Pair<Float, Float>>> = emptyList(),
    brushes: List<List<Pair<Float, Float>>> = emptyList(),
    erasures: List<List<Pair<Float, Float>>> = emptyList()
): Bitmap {
    if (text.isBlank() && watermark.isBlank() && signature.isEmpty() && highlights.isEmpty() && brushes.isEmpty() && erasures.isEmpty()) return bitmap
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
    // Manual whiteout strokes: works offline and is included in previews and export.
    erasures.forEach { stroke ->
        if (stroke.size > 1) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
                val x = point.first.coerceIn(0f, 1f) * w
                val y = point.second.coerceIn(0f, 1f) * h
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val erasePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (w * 0.035f).coerceIn(24f, 68f)
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            canvas.drawPath(path, erasePaint)
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
    signature.forEach { stroke ->
        if (stroke.size > 1) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { index, point ->
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
    // Do not manually recycle a bitmap owned by produceState; Compose may still be drawing it.
    Box(Modifier.size(52.dp, 68.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier).clickable { onClick() }) {
        bmp?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp))
    }
}


@Composable
private fun FilterEditPanel(
    page: Uri,
    rotation: Int,
    selected: PageFilter,
    compareOriginal: Boolean,
    applyAll: Boolean,
    onApplyAllChanged: (Boolean) -> Unit,
    onCompare: () -> Unit,
    onSelect: (PageFilter) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val ctx = LocalContext.current
    val previewState by produceState<Result<Bitmap>?>(null, page, rotation, selected, compareOriginal) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                ImageFilters.apply(
                    ImageFilters.rotate(ImageFilters.load(ctx, page, 1000), rotation),
                    if (compareOriginal) PageFilter.ORIGINAL else selected
                )
            }
        }
    }
    val preview = previewState?.getOrNull()
    val thumbnails by produceState<Map<PageFilter, Bitmap>>(emptyMap(), page, rotation) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                PageFilter.values().associateWith { filter ->
                    ImageFilters.apply(
                        ImageFilters.rotate(ImageFilters.load(ctx, page, 140), rotation),
                        filter
                    )
                }
            }.getOrDefault(emptyMap())
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onCancel) { Icon(Icons.Filled.ArrowBack, "Cancel filter") }
                Text("Filter", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onCompare) { Text(if (compareOriginal) "Show Filter" else "Compare") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    preview != null -> Image(preview.asImageBitmap(), "Filtered document preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    previewState == null -> CircularProgressIndicator()
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Preview failed to load.")
                        TextButton(onClick = onCancel) { Text("Close") }
                    }
                }
                TextButton(onClick = onCompare, Modifier.align(Alignment.BottomEnd)) { Text("Compare") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Same filter for all pages", Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                Switch(checked = applyAll, onCheckedChange = onApplyAllChanged)
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(PageFilter.values().toList()) { filter ->
                    val active = filter == selected
                    Column(
                        Modifier.width(84.dp).clickable { onSelect(filter) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(80.dp, 100.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(if (active) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                        ) {
                            thumbnails[filter]?.let {
                                Image(it.asImageBitmap(), filter.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Text(filter.label, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onCancel) { Icon(Icons.Filled.ArrowBack, "Cancel filter") }
                Spacer(Modifier.weight(1f))
                Text("Filter", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onApply) { Icon(Icons.Filled.Check, "Apply filter", tint = MaterialTheme.colorScheme.primary) }
            }
        }
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
    var showFilterPanel by remember { mutableStateOf(false) }
    var filterPanelOriginals by remember { mutableStateOf<Map<Int, PageFilter>>(emptyMap()) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signatureDraft by remember { mutableStateOf(emptyList<List<Pair<Float, Float>>>()) }
    var activeSignature by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var showEraseDialog by remember { mutableStateOf(false) }
    var drawingMode by remember { mutableStateOf("Highlight") }
    var highlightDraft by remember { mutableStateOf(emptyList<List<Pair<Float, Float>>>()) }
    var activeHighlight by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var textDraft by remember { mutableStateOf("") }
    var watermarkDraft by remember { mutableStateOf("") }
    val liveSignatureDraft by rememberUpdatedState(signatureDraft)
    val liveActiveSignature by rememberUpdatedState(activeSignature)
    val liveHighlightDraft by rememberUpdatedState(highlightDraft)
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

    var previewRetry by remember { mutableIntStateOf(0) }
    val previewState by produceState<Result<Bitmap>?>(null, page, flt, rot, compareOriginal, r.textOverlays[cur], r.watermarks[cur], r.signatures[cur], r.highlights[cur], r.brushes[cur], previewRetry) {
        // Keep loading and failure separate so a failed filter never leaves an endless spinner.
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                compositeMarkup(
                    ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, page, 900), rot), if (compareOriginal) PageFilter.ORIGINAL else flt),
                    if (compareOriginal) "" else r.textOverlays[cur],
                    if (compareOriginal) "" else r.watermarks[cur],
                    if (compareOriginal) emptyList() else r.signatures[cur],
                    if (compareOriginal) emptyList() else r.highlights[cur],
                    if (compareOriginal) emptyList() else r.brushes[cur], r.erasures[cur]
                )
            }
        }
    }
    val preview = previewState?.getOrNull()
    val thumbs by produceState<Map<PageFilter, Bitmap>>(emptyMap(), page, rot, r.textOverlays[cur], r.watermarks[cur], r.signatures[cur], r.highlights[cur], r.brushes[cur]) {
        value = emptyMap()
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = ImageFilters.rotate(ImageFilters.load(ctx, page, 220), rot)
                try {
                    PageFilter.values().associateWith { f ->
                        compositeMarkup(
                            ImageFilters.apply(base.copy(Bitmap.Config.ARGB_8888, false)!!, f),
                            r.textOverlays[cur], r.watermarks[cur], r.signatures[cur], r.highlights[cur], r.brushes[cur], r.erasures[cur]
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
    // These bitmaps are owned by Compose state and may still be in use by a draw pass
    // when the state changes. Do not recycle them manually during recomposition/disposal.

    fun finish() {
        val pages = r.pages.toList(); val fl = r.filters.toList(); val ro = r.rots.toList(); val name = r.title; val mode = fMode
        scope.launch {
            saving = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    fun bmp(i: Int) = compositeMarkup(
                        ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, pages[i], 1500), ro[i]), fl[i]),
                        r.textOverlays[i], r.watermarks[i], r.signatures[i], r.highlights[i], r.brushes[i], r.erasures[i]
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
                            r.textOverlays[i], r.watermarks[i], r.signatures[i], r.highlights[i], r.brushes[i], r.erasures[i]
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

    if (showFilterPanel) {
        FilterEditPanel(
            page = page,
            rotation = rot,
            selected = flt,
            compareOriginal = compareOriginal,
            applyAll = applyAll,
            onApplyAllChanged = { applyAll = it },
            onCompare = { compareOriginal = !compareOriginal },            onSelect = { f -> if (applyAll) r.filters.indices.forEach { r.filters[it] = f } else r.filters[cur] = f },
            onCancel = {
                filterPanelOriginals.forEach { (i, old) -> if (i in r.filters.indices) r.filters[i] = old }
                filterPanelOriginals.keys.forEach { i -> if (r.canUndo(i)) r.undo(i) }
                compareOriginal = false
                showFilterPanel = false
            },
            onApply = { compareOriginal = false; showFilterPanel = false }
        )
        return
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
                when {
                    p != null -> Image(p.asImageBitmap(), "Page ${cur + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    previewState == null -> CircularProgressIndicator(color = Cyan)
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preview could not be loaded. Try again or choose another filter.", color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { previewRetry++ }) { Text("Retry") }
                    }
                }
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
                        BarButton(Icons.Filled.Image, "CS PDF") { fMode = 0; fDialog = true }
                        Box {
                            BarButton(Icons.Filled.Add, "Add") { addMenu = true }
                            DropdownMenu(addMenu, { addMenu = false }) {
                                DropdownMenuItem({ Text("Camera") }, { addMenu = false; onAddCamera() })
                                DropdownMenuItem({ Text("Gallery") }, { addMenu = false; pickG.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem({ Text("Files") }, { addMenu = false; pickF.launch(arrayOf("image/*")) })
                            }
                        }
                        BarButton(Icons.Filled.Crop, "Crop") { onCrop(cur) }
                        BarButton(Icons.Filled.Image, "Filter") {
                            val targets = if (applyAll) r.filters.indices.toList() else listOf(cur)
                            filterPanelOriginals = targets.associateWith { r.filters[it] }
                            targets.forEach { r.pushUndo(it) }
                            compareOriginal = false
                            showFilterPanel = true
                        }
                        BarButton(Icons.Filled.RotateLeft, "Rotate") { r.pushUndo(cur); r.rots[cur] = (rot + 270) % 360 }
                        BarButton(Icons.Filled.Image, "Reset") {
                            r.pushUndo(cur); r.filters[cur] = PageFilter.ORIGINAL
                            r.rots[cur] = 0; compareOriginal = false
                        }
                        BarButton(Icons.Filled.Edit, "Edit Text") {
                            textDraft = r.textOverlays[cur]; showTextDialog = true
                        }
                        BarButton(Icons.Filled.Delete, "Smart Erase") {
                            activeHighlight = emptyList()
                            highlightDraft = r.erasures[cur]
                            showEraseDialog = true
                        }
                    }
                    "Markup" -> {
                        BarButton(Icons.Filled.TextFields, "Edit Text") {
                            textDraft = r.textOverlays[cur]; showTextDialog = true
                        }
                        BarButton(Icons.Filled.Edit, "Signature") {
                            signatureDraft = r.signatures[cur]; activeSignature = emptyList(); showSignatureDialog = true
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
    if (fDialog) AlertDialog(
        onDismissRequest = { fDialog = false },
        title = { Text("Finish document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose the PDF format for your scanned document.")
                listOf(
                    "Standard PDF",
                    "Searchable PDF",
                    "Original + Typed PDF",
                    "Original + Typed PDF (typed pages at end)"
                ).forEachIndexed { index, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable { fMode = index }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = fMode == index, onClick = { fMode = index })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    fDialog = false
                    // Mode 2 puts typed text after each scan; mode 3 places it at the end.
                    fMode = when (fMode) {
                        3 -> 3
                        else -> fMode
                    }
                    finish()
                }
            ) { Text("Save PDF") }
        },
        dismissButton = { TextButton(onClick = { fDialog = false }) { Text("Cancel") } }
    )
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
            r.pushUndo(cur); r.textOverlays[cur] = textDraft.take(500)
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
            r.pushUndo(cur); r.watermarks[cur] = watermarkDraft.take(80)
            showWatermarkDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showWatermarkDialog = false }) { Text("Cancel") } }
    )
    if (showEraseDialog) AlertDialog(
        onDismissRequest = { showEraseDialog = false },
        title = { Text("Smart Erase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Draw over content to cover it with white. This works offline; it hides marks rather than reconstructing the background.")
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(220.dp)
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
                                onDragEnd = {
                                    val stroke = liveActiveHighlight
                                    if (stroke.size > 1) highlightDraft = liveHighlightDraft + listOf(stroke)
                                    activeHighlight = emptyList()
                                }
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
                            drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                    }
                }
                TextButton(onClick = { highlightDraft = emptyList(); activeHighlight = emptyList() }) { Text("Clear") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                r.pushUndo(cur)
                r.erasures[cur] = highlightDraft + listOf(activeHighlight).filter { it.size > 1 }
                activeHighlight = emptyList()
                showEraseDialog = false
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = { showEraseDialog = false; activeHighlight = emptyList() }) { Text("Cancel") } }
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
            r.pushUndo(cur)
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
                Text("Draw with your finger. Lift your finger between strokes. The signature is included in exports.")
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    activeSignature = listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = change.position
                                    activeSignature = liveActiveSignature + listOf(
                                        (p.x / size.width).coerceIn(0f, 1f) to (p.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDragEnd = {
                                    val stroke = liveActiveSignature
                                    if (stroke.size > 1) signatureDraft = liveSignatureDraft + listOf(stroke)
                                    activeSignature = emptyList()
                                }
                            )
                        }
                ) {
                    (signatureDraft + listOf(activeSignature)).forEach { stroke ->
                        if (stroke.size > 1) {
                            val path = androidx.compose.ui.graphics.Path()
                            stroke.forEachIndexed { i, p ->
                                val x = p.first * size.width; val y = p.second * size.height
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, Color(0xFF142D7D), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { signatureDraft = emptyList(); activeSignature = emptyList() }) { Text("Clear") }
                    TextButton(onClick = {
                        val stroke = liveActiveSignature
                        if (stroke.size > 1) signatureDraft = liveSignatureDraft + listOf(stroke)
                        activeSignature = emptyList()
                    }) { Text("Keep stroke") }
                }
            }
        },
        confirmButton = { TextButton({
            val stroke = liveActiveSignature
            r.pushUndo(cur)
            r.signatures[cur] = if (stroke.size > 1) liveSignatureDraft + listOf(stroke) else liveSignatureDraft
            showSignatureDialog = false
        }) { Text("Apply") } },
        dismissButton = { TextButton({ showSignatureDialog = false }) { Text("Cancel") } }
    )
    if (progress.isNotEmpty()) AlertDialog({}, confirmButton = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(16.dp)); Text(progress) }
    })
}
