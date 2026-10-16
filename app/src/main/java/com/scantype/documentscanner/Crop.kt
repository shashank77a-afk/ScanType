package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

private fun nearest(pts: List<Offset>, o: Offset, sz: IntSize): Int {
    var best = -1; var bd = 120f
    pts.forEachIndexed { i, p ->
        val d = hypot(p.x * sz.width - o.x, p.y * sz.height - o.y)
        if (d < bd) { bd = d; best = i }
    }
    return best
}

/** Drag the four corners onto the document edges; "Apply" straightens it (perspective correction). */
@Composable
fun CropScreen(uri: Uri, rot: Int, title: String, onDone: (Uri) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadRetry by remember { mutableIntStateOf(0) }
    var cropRotation by remember(uri, rot) { mutableIntStateOf(rot) }
    val cropState by produceState<Result<Bitmap>?>(null, uri, cropRotation, loadRetry) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching { ImageFilters.rotate(ImageFilters.load(ctx, uri, 1200), cropRotation) }
        }
    }
    val bmp = cropState?.getOrNull()
    DisposableEffect(bmp) {
        onDispose { bmp?.let { image -> if (!image.isRecycled) runCatching { image.recycle() } } }
    }
    val pts = remember { mutableStateListOf(Offset(0.06f, 0.06f), Offset(0.94f, 0.06f), Offset(0.94f, 0.94f), Offset(0.06f, 0.94f)) }
    var working by remember { mutableStateOf(false) }
    fun auto(manual: Boolean) {
        val b = bmp ?: return
        scope.launch {
            val q = withContext(Dispatchers.Default) { EdgeDetect.detectBitmap(b) }
            if (q != null) { for (i in 0 until 4) pts[i] = Offset(q[i * 2], q[i * 2 + 1]) }
            else if (manual) Toast.makeText(ctx, "Edges not found. Please drag the corners.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(bmp) { if (bmp != null) auto(false) }
    Scaffold(containerColor = Navy) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            Text("Drag the 4 corners to fit the edges of the document.", color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) {
                    if (cropState == null) CircularProgressIndicator(color = Cyan)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Could not load this page.", color = Color.White)
                        TextButton(onClick = { loadRetry++ }) { Text("Retry", color = Cyan) }
                        TextButton(onCancel) { Text("Cancel", color = Cyan) }
                    }
                } else {
                    val ar = b.width.toFloat() / b.height
                    val w = if (maxWidth / maxHeight > ar) maxHeight * ar else maxWidth
                    val h = w / ar
                    Box(Modifier.size(w, h)) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                            var active = -1
                            detectDragGestures(
                                onDragStart = { o -> active = nearest(pts, o, size) },
                                onDrag = { change, d ->
                                    change.consume()
                                    if (active >= 0) {
                                        val p = pts[active]
                                        pts[active] = Offset((p.x + d.x / size.width).coerceIn(0f, 1f), (p.y + d.y / size.height).coerceIn(0f, 1f))
                                    }
                                },
                                onDragEnd = { active = -1 }, onDragCancel = { active = -1 })
                        }) {
                            val q = pts.map { Offset(it.x * size.width, it.y * size.height) }
                            for (i in 0 until 4) drawLine(Cyan, q[i], q[(i + 1) % 4], strokeWidth = 4f)
                            q.forEach { drawCircle(Cyan, 22f, it); drawCircle(Color.White, 9f, it) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { cropRotation = (cropRotation + 270) % 360 }) { Text("Left", color = Cyan) }
                TextButton(onClick = { cropRotation = (cropRotation + 90) % 360 }) { Text("Right", color = Cyan) }
                TextButton(onClick = { auto(true) }) { Text("Auto Crop", color = Cyan) }
                TextButton(onClick = {
                    pts[0] = Offset(0.01f, 0.01f); pts[1] = Offset(0.99f, 0.01f)
                    pts[2] = Offset(0.99f, 0.99f); pts[3] = Offset(0.01f, 0.99f)
                }) { Text("All", color = Cyan) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onCancel, Modifier.weight(1f)) { Text("Cancel", color = Color.White) }
                Button({
                    val src = bmp
                    if (src != null) scope.launch {
                        working = true
                        val xy = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
                        val out = withContext(Dispatchers.Default) {
                            runCatching {
                                val w = ImageFilters.warp(src, xy)
                                val f = File(ctx.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                                try {
                                    f.outputStream().use { stream ->
                                        check(w.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "Could not save cropped page" }
                                    }
                                    Uri.fromFile(f)
                                } finally {
                                    if (!w.isRecycled) w.recycle()
                                }
                            }.getOrNull()
                        }
                        working = false
                        if (out != null) onDone(out)
                    }
                }, Modifier.weight(1f), enabled = bmp != null && !working,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)) {
                    Text(if (working) "Working…" else "Apply")
                }
            }
        }
    }
}
