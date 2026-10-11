package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IdState {
    var front by mutableStateOf<Uri?>(null)
    var back by mutableStateOf<Uri?>(null)
    var fr by mutableIntStateOf(0)
    var br by mutableIntStateOf(0)
    var filter by mutableStateOf(PageFilter.ENHANCE)
    var title by mutableStateOf("ID_" + stamp())
}

@Composable
private fun SideCard(label: String, uri: Uri?, rot: Int, filter: PageFilter, onScan: () -> Unit, onGallery: () -> Unit,
                     onCrop: () -> Unit, onRotate: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, rot, filter) {
        value = if (uri == null) null else withContext(Dispatchers.Default) {
            runCatching { ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, uri, 900), rot), filter) }.getOrNull()
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(if (uri == null) "Not scanned yet" else "Loading…")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onScan, Modifier.weight(1f)) { Text(if (uri == null) "Scan" else "Rescan") }
                OutlinedButton(onGallery, Modifier.weight(1f)) { Text("Gallery") }
            }
            if (uri != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onCrop, Modifier.weight(1f)) { Text("Crop") }
                OutlinedButton(onRotate, Modifier.weight(1f)) { Text("Rotate") }
            }
        }
    }
}

/** Front and Back sides of an ID card. The back side is optional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardScreen(s: IdState, store: DocStore, snack: SnackbarHostState, scope: CoroutineScope,
                 onBack: () -> Unit, onScan: (Int) -> Unit, onGallery: (Int) -> Unit, onCrop: (Int) -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var working by remember { mutableStateOf(false) }
    val gate = rememberWriteGate { scope.launch { snack.showSnackbar("Storage permission is needed to save images on this Android version.") } }

    fun process(u: Uri, rot: Int, f: PageFilter): Bitmap = ImageFilters.apply(ImageFilters.rotate(ImageFilters.load(ctx, u, 1600), rot), f)

    fun export(kind: Int) {
        val list = listOfNotNull(s.front?.let { Triple(it, s.fr, "Front") }, s.back?.let { Triple(it, s.br, "Back") })
        if (list.isEmpty()) { scope.launch { snack.showSnackbar("Scan at least the front side first.") }; return }
        val f = s.filter; val name = s.title.ifBlank { "ID_Card" }
        scope.launch {
            working = true
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        0 -> {
                            val sheet = IdCard.sheet(list.map { process(it.first, it.second, f) })
                            store.bitmapsToPdf(1, name) { sheet }
                            "Saved"
                        }
                        1 -> {
                            store.bitmapsToPdf(list.size, name) { process(list[it].first, list[it].second, f) }
                            "Saved"
                        }
                        else -> {
                            var n = 0
                            for (t in list) {
                                val b = process(t.first, t.second, f)
                                if (Export.saveImage(ctx, b, "${name}_${t.third}", kind == 3)) n++
                                b.recycle()
                            }
                            if (n > 0) "Saved $n image(s) to Gallery (Pictures/ScanType)" else "Could not save to Gallery. Please try again."
                        }
                    }
                }.getOrElse { "Could not save. Please try again." }
            }
            working = false
            if (kind <= 1 && msg == "Saved") onSaved() else snack.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = { TopAppBar({ Text("ID Card") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SideCard("Front side", s.front, s.fr, s.filter, { onScan(0) }, { onGallery(0) }, { onCrop(0) }, { s.fr = (s.fr + 270) % 360 })
            SideCard("Back side (optional)", s.back, s.br, s.filter, { onScan(1) }, { onGallery(1) }, { onCrop(1) }, { s.br = (s.br + 270) % 360 })
            Text("Filter", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageFilter.values().forEach { f -> FilterChip(s.filter == f, { s.filter = f }, { Text(f.label) }) }
            }
            OutlinedTextField(s.title, { s.title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("File name") })
            Button({ export(0) }, Modifier.fillMaxWidth(), enabled = !working) { Text("A4 print-ready PDF (both sides on one page)") }
            OutlinedButton({ export(1) }, Modifier.fillMaxWidth(), enabled = !working) { Text("PDF with separate pages") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ gate { export(2) } }, Modifier.weight(1f), enabled = !working) { Text("Gallery (JPG)") }
                OutlinedButton({ gate { export(3) } }, Modifier.weight(1f), enabled = !working) { Text("Gallery (PNG)") }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
