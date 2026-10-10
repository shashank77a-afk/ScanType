package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pages being edited, each with its own filter and rotation. */
class ReviewState(initial: List<Uri>) {
    val pages = mutableStateListOf<Uri>().apply { addAll(initial) }
    val filters = mutableStateListOf<PageFilter>().apply { repeat(initial.size) { add(PageFilter.ENHANCE) } }
    val rots = mutableStateListOf<Int>().apply { repeat(initial.size) { add(0) } }
    var title by mutableStateOf("Scan_" + stamp())
    fun add(u: List<Uri>) {
        u.forEach { pages.add(it); filters.add(filters.lastOrNull() ?: PageFilter.ENHANCE); rots.add(0) }
    }
    fun remove(i: Int) { pages.removeAt(i); filters.removeAt(i); rots.removeAt(i) }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun ScanReviewScreen(r: ReviewState, store: DocStore, snack: SnackbarHostState, scope: CoroutineScope,
                     onBack: () -> Unit, onSaved: () -> Unit, onExtract: () -> Unit, onAdd: () -> Unit, onRetake: (Int) -> Unit) {
    val ctx = LocalContext.current
    if (r.pages.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    var idx by remember { mutableIntStateOf(0) }
    var applyAll by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val cur = idx.coerceIn(0, r.pages.size - 1)
    val page = r.pages[cur]; val flt = r.filters[cur]; val rot = r.rots[cur]

    val preview by produceState<Bitmap?>(null, page, flt, rot) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching { ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, page, 1200), rot), flt) }.getOrNull()
        }
    }
    val thumbs by produceState<Map<PageFilter, Bitmap>>(emptyMap(), page, rot) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = ImageFilters.rotate(ImageFilters.load(ctx, page, 220), rot)
                val m = PageFilter.values().associateWith { f -> ImageFilters.apply(base.copy(Bitmap.Config.ARGB_8888, false)!!, f) }
                base.recycle(); m
            }.getOrDefault(emptyMap())
        }
    }

    fun save() {
        val pages = r.pages.toList(); val fl = r.filters.toList(); val ro = r.rots.toList(); val name = r.title
        scope.launch {
            saving = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    store.bitmapsToPdf(pages.size, name) { i ->
                        ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, pages[i], 1500), ro[i]), fl[i])
                    }
                }.isSuccess
            }
            saving = false
            if (ok) onSaved() else snack.showSnackbar("PDF creation failed. Please try again.")
        }
    }

    Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
                BasicTextField(r.title, { r.title = it }, Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp), singleLine = true, cursorBrush = SolidColor(Cyan))
                Spacer(Modifier.width(8.dp))
                Button({ save() }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)) {
                    Text(if (saving) "Saving…" else "Done")
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
                IconButton({ if (cur > 0) idx = cur - 1 }) { Icon(Icons.Filled.ArrowBack, "Previous page", tint = Color.White) }
                Text("${cur + 1} / ${r.pages.size}", color = Color.White)
                IconButton({ if (cur < r.pages.size - 1) idx = cur + 1 }) { Icon(Icons.Filled.ArrowForward, "Next page", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("Apply to all pages", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(applyAll, { applyAll = it })
            }
            LazyRow(Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(PageFilter.values().toList()) { f ->
                    val sel = f == flt
                    Column(Modifier.clickable {
                        if (applyAll) { for (i in r.filters.indices) r.filters[i] = f } else r.filters[cur] = f
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(80.dp, 104.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2B55))
                            .then(if (sel) Modifier.border(3.dp, Cyan, RoundedCornerShape(10.dp)) else Modifier)) {
                            thumbs[f]?.let { Image(it.asImageBitmap(), f.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(f.label, color = if (sel) Cyan else Color.White, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarButton(Icons.Filled.CameraAlt, "Retake") { onRetake(cur) }
                BarButton(Icons.Filled.Add, "Add") { onAdd() }
                BarButton(Icons.Filled.RotateLeft, "Left") { r.rots[cur] = (rot + 270) % 360 }
                BarButton(Icons.Filled.TextFields, "Extract Text") { onExtract() }
            }
        }
    }
}
