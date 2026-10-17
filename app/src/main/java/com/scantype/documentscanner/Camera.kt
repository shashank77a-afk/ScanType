package com.scantype.documentscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

private fun livePreviewMatrix(filter: PageFilter): ColorMatrix {
    // Live view is intentionally a fast visual approximation. The full-resolution
    // filter is run after capture and is authoritative for the saved document.
    val sat = when (filter) {
        PageFilter.BW, PageFilter.GREY -> 0f
        PageFilter.MAGIC_COLOR -> 1.12f
        else -> 1f
    }
    val (contrast, brightness) = when (filter) {
        PageFilter.ORIGINAL -> 1f to 0f
        PageFilter.ENHANCE -> 1.12f to 4f
        PageFilter.MAGIC_COLOR -> 1.06f to 6f
        PageFilter.LIGHTEN -> 1.00f to 17f
        PageFilter.BW -> 1.42f to 8f
        PageFilter.GREY -> 1.05f to 2f
        // A color matrix cannot sharpen edges; use a subtle clarity cue in preview.
        PageFilter.SHARPEN -> 1.05f to 0f
    }
    val matrix = ColorMatrix().apply { setSaturation(sat) }
    val shift = (0.5f - 0.5f * contrast) * 255f + brightness
    matrix.postConcat(ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, shift,
        0f, contrast, 0f, 0f, shift,
        0f, 0f, contrast, 0f, shift,
        0f, 0f, 0f, 1f, 0f
    )))
    return matrix
}

/** Live effect is a light preview approximation. The selected full-resolution filter is applied after capture too. */
private fun applyLivePreview(view: PreviewView, filter: PageFilter, enabled: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.setRenderEffect(
            if (enabled) RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(livePreviewMatrix(filter)))
            else null
        )
    }
}

enum class CamMode(val label: String) {
    DOCS("Docs"), ID("ID Card"), BOOK("Book"), QR("QR Code"), TEXT("To Text"), HAND("Handwriting")
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Cyan else Color(0x33FFFFFF))
        .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(text, color = if (selected) Navy else Color.White, fontSize = 14.sp)
    }
}

/** ScanType's own camera. Asks for the CAMERA permission itself and explains what to do if it is denied. */
@Composable
fun CameraScreen(mode: CamMode, onMode: (CamMode) -> Unit, batch: Boolean, onBatch: (Boolean) -> Unit, prefs: Prefs,
                 hint: String?, onDone: (List<Uri>, CamMode, PageFilter) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    fun has() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(has()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) denied = true }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) onDone(us, mode, PageFilter.ENHANCE)
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = has() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) CameraContent(mode, onMode, batch, onBatch, prefs, hint, onDone, onClose)
    else Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(if (denied) "Camera permission was denied. Allow it to scan with the camera, or choose images from your gallery."
        else "ScanType needs the camera only to photograph your documents. Photos stay on your device.")
        Spacer(Modifier.height(16.dp))
        Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }) { Text("Open app settings") }
        OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose from gallery") }
        TextButton(onClose) { Text("Close") }
    }
}

@Composable
private fun CameraContent(mode: CamMode, onMode: (CamMode) -> Unit, batch: Boolean, onBatch: (Boolean) -> Unit, prefs: Prefs,
                          hint: String?, onDone: (List<Uri>, CamMode, PageFilter) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    val shots = remember { mutableStateListOf<Uri>() }
    var flash by remember { mutableStateOf(false) }
    var liveFilter by remember { mutableStateOf(PageFilter.ENHANCE) }
    var batchFilter by remember { mutableStateOf(PageFilter.ENHANCE) }
    var busy by remember { mutableStateOf(false) }
    var autoOn by remember { mutableStateOf(prefs.autoCapture) }
    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var frameAr by remember { mutableFloatStateOf(0.75f) }
    var qr by remember { mutableStateOf<String?>(null) }
    var liveHint by remember { mutableStateOf<String?>(null) }
    var autoTrigger by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val analysis = remember { ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() }
    val modeNow by rememberUpdatedState(mode)
    val autoNow by rememberUpdatedState(autoOn)
    val filterNow by rememberUpdatedState(liveFilter)
    val busyNow by rememberUpdatedState(busy)
    val stable = remember { IntArray(1) }
    val cooldown = remember { LongArray(1) }
    val hintTick = remember { LongArray(1) }
    val hist = remember { arrayOfNulls<FloatArray>(1) }

    LaunchedEffect(flash) { capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
    LaunchedEffect(mode) { shots.clear(); quad = null; qr = null }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) onDone(us, mode, liveFilter)
    }

    fun shoot() {
        if (busy) return
        busy = true
        cooldown[0] = System.currentTimeMillis() + 2500
        val f = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    busy = false
                    val u = Uri.fromFile(f)
                    if (mode == CamMode.ID || mode == CamMode.BOOK) onDone(listOf(u), mode, filterNow)
                    else if (batch && (mode == CamMode.DOCS)) { if (shots.isEmpty()) batchFilter = filterNow; shots.add(u) }
                    else onDone(listOf(u), mode, filterNow)
                }
                override fun onError(e: ImageCaptureException) {
                    busy = false
                    Toast.makeText(ctx, "The photo could not be taken. Please try again.", Toast.LENGTH_SHORT).show()
                }
            })
    }
    LaunchedEffect(autoTrigger) { if (autoTrigger) { autoTrigger = false; shoot() } }

    DisposableEffect(analysis) {
        val ex = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        var last = 0L
        analysis.setAnalyzer(ex) { proxy ->
            val now = System.currentTimeMillis()
            if (now - last < 180) { proxy.close(); return@setAnalyzer }
            last = now
            val m = modeNow
            if (m == CamMode.QR) {
                val img = proxy.image
                if (img == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { l -> l.firstOrNull()?.rawValue?.let { if (qr == null) qr = it } }
                    .addOnCompleteListener { proxy.close() }
            } else if (m == CamMode.DOCS || m == CamMode.ID || m == CamMode.BOOK) {
                try {
                    val plane = proxy.planes[0]; val buf = plane.buffer; val rs = plane.rowStride
                    val w = proxy.width; val h = proxy.height
                    val step = max(1, max(w, h) / 240)
                    val sw = w / step; val sh = h / step
                    val g = IntArray(sw * sh)
                    var brightnessTotal = 0L
                    for (y in 0 until sh) {
                        val row = y * step * rs
                        for (x in 0 until sw) {
                            val v = buf.get(row + x * step).toInt() and 0xFF
                            g[y * sw + x] = v
                            brightnessTotal += v
                        }
                    }
                    // Update the warning occasionally, not on every camera frame, to avoid UI churn.
                    if (now - hintTick[0] > 1800L) {
                        hintTick[0] = now
                        val meanBrightness = brightnessTotal.toDouble() / g.size
                        liveHint = when {
                            meanBrightness < 48 -> "Low light — turn on a light or use flash."
                            meanBrightness > 244 -> "Too much glare — tilt the page or reduce the light."
                            else -> null
                        }
                    }
                    val (rg, rw, rh) = EdgeDetect.rotate(g, sw, sh, proxy.imageInfo.rotationDegrees)
                    frameAr = rw.toFloat() / rh
                    val detected = EdgeDetect.detect(rg, rw, rh)
                    val prev = hist[0]
                    // Reject abrupt jumps in the live overlay; only blend geometrically valid
                    // detections. The unsmoothed candidate still has to remain stable for capture.
                    val q = if (detected != null && prev != null &&
                        (0 until 8).all { abs(detected[it] - prev[it]) < 0.16f }) {
                        FloatArray(8) { i -> prev[i] * 0.62f + detected[i] * 0.38f }
                    } else detected
                    quad = q
                    if (detected != null && prev != null &&
                        (0 until 8).all { abs(detected[it] - prev[it]) < 0.025f }) stable[0]++ else stable[0] = 0
                    hist[0] = detected?.copyOf()
                    if (autoNow && detected != null && stable[0] >= 6 &&
                        EdgeDetect.area(detected) > 0.20f && !busyNow && now > cooldown[0]) {
                        stable[0] = 0; autoTrigger = true
                    }
                } finally { proxy.close() }
            } else { quad = null; proxy.close() }
        }
        onDispose { analysis.clearAnalyzer(); scanner.close(); ex.shutdown() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            PreviewView(c).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
                val fut = ProcessCameraProvider.getInstance(c)
                fut.addListener({
                    runCatching {
                        val provider = fut.get()
                        val pv = Preview.Builder().build()
                        pv.setSurfaceProvider(surfaceProvider)
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture, analysis)
                    }
                }, ContextCompat.getMainExecutor(c))
            }
        }, update = { view -> applyLivePreview(view, liveFilter, mode == CamMode.DOCS || mode == CamMode.BOOK) })
        val q = quad
        if (q != null) Canvas(Modifier.fillMaxSize()) {
            val bw = size.width; val bh = size.height
            val dw = if (bw / bh > frameAr) bh * frameAr else bw
            val dh = dw / frameAr
            val ox = (bw - dw) / 2; val oy = (bh - dh) / 2
            val pts = (0 until 4).map { Offset(ox + q[it * 2] * dw, oy + q[it * 2 + 1] * dh) }
            for (i in 0 until 4) drawLine(Cyan, pts[i], pts[(i + 1) % 4], strokeWidth = 6f)
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            TextButton({ autoOn = !autoOn; prefs.autoCapture = autoOn }) { Text(if (autoOn) "Auto: ON" else "Auto: OFF", color = if (autoOn) Cyan else Color.White) }
            IconButton({ flash = !flash }) {
                Icon(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000))
            .navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            liveHint?.let { Text(it, color = Color(0xFFFFD180), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            if (mode == CamMode.ID && hint != null) Text(hint, color = Color.White, fontSize = 15.sp)
            else if (mode == CamMode.QR) Text("Point the camera at a QR code", color = Color.White, fontSize = 15.sp)
            else if (mode == CamMode.BOOK) Text("Place the open book flat. Both pages are split automatically.", color = Color.White, fontSize = 14.sp)
            else if (mode == CamMode.DOCS) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Single", !batch) { onBatch(false) }
                Pill("Batch", batch) { onBatch(true) }
            }
            if (mode == CamMode.DOCS || mode == CamMode.BOOK) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageFilter.values().forEach { f ->
                        Pill(f.label, liveFilter == f) { if (shots.isEmpty() || f == batchFilter) liveFilter = f else Toast.makeText(ctx, "Finish this batch before changing its filter.", Toast.LENGTH_SHORT).show() }
                    }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Text("Live filter preview needs Android 12+; selected filter will still apply after capture.",
                        color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                CamMode.values().forEach { m ->
                    Text(m.label, color = if (m == mode) Cyan else Color.White,
                        fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            onMode(m)
                            if (m == CamMode.HAND && !prefs.aiReady)
                                Toast.makeText(ctx, "For accurate Hindi handwriting, turn on AI in Settings.", Toast.LENGTH_LONG).show()
                        }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(72.dp).clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Image, "Gallery", tint = Color.White)
                    Text("Gallery", color = Color.White, fontSize = 12.sp)
                }
                if (mode == CamMode.QR) Spacer(Modifier.size(84.dp))
                else Box(Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(CircleShape).background(Color.White).clickable(enabled = !busy) { shoot() })
                Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                    if (mode == CamMode.DOCS && batch && shots.isNotEmpty()) Button({ onDone(shots.toList(), mode, batchFilter) }) { Text("Done (${shots.size})") }
                }
            }
        }
    }
    qr?.let { v ->
        AlertDialog({ qr = null }, title = { Text("QR code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(v)
                    if (v.startsWith("http://", true) || v.startsWith("https://", true))
                        TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v))) } }) { Text("Open link") }
                }
            },
            confirmButton = {
                TextButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QR", v))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = { TextButton({ qr = null }) { Text("Close") } })
    }
}
