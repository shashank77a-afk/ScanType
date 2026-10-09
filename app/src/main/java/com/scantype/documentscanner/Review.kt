package com.scantype.documentscanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReviewScreen(pages: List<Uri>, store: DocStore, close: () -> Unit, saved: () -> Unit,
                     extract: () -> Unit, snack: SnackbarHostState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(PageFilter.ENHANCE) }
    var naming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, idx, filter) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching { ImageFilters.apply(ImageFilters.load(ctx, pages[idx], 1200), filter) }.getOrNull()
        }
    }
    Scaffold(
        topBar = { TopAppBar({ Text("Edit scan") }, navigationIcon = { IconButton(close) { Icon(Icons.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) CircularProgressIndicator()
                else Image(p.asImageBitmap(), "Page ${idx + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (idx > 0) idx-- }) { Icon(Icons.Filled.ArrowBack, "Previous page") }
                Text("${idx + 1} / ${pages.size}")
                IconButton({ if (idx < pages.size - 1) idx++ }) { Icon(Icons.Filled.ArrowForward, "Next page") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PageFilter.values().toList()) { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
            }
            Text("The filter is applied to all pages.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(extract, Modifier.weight(1f)) { Text("Extract Text") }
                Button({ naming = true }, Modifier.weight(1f), enabled = !saving) { Text(if (saving) "Saving…" else "Save PDF") }
            }
        }
    }
    if (naming) SaveScanDialog(
        onSave = { name ->
            naming = false
            scope.launch {
                saving = true
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        store.bitmapsToPdf(pages.size, name) { i -> ImageFilters.apply(ImageFilters.load(ctx, pages[i], 1500), filter) }
                    }.isSuccess
                }
                saving = false
                if (ok) saved() else snack.showSnackbar("PDF creation failed. Please try again.")
            }
        },
        onCancel = { naming = false })
}
