package com.scantype.documentscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun renderViewerPage(file: File, index: Int, targetWidth: Int): Bitmap? = runCatching {
    PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
        if (index !in 0 until renderer.pageCount) return@runCatching null
        renderer.openPage(index).use { page ->
            val w = targetWidth.coerceAtLeast(1)
            val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
}.getOrNull()

@Composable
private fun PdfRenderedPage(file: File, index: Int, width: Int, modifier: Modifier = Modifier,
                            contentScale: ContentScale = ContentScale.Fit, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(null, file.path, file.lastModified(), index, width) {
        value = withContext(Dispatchers.IO) { renderViewerPage(file, index, width) }
    }
    Box(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Local PDF preview. Page rendering is done on IO; no PDF is uploaded or modified. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    file: File,
    onBack: () -> Unit,
    onEditPages: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onSave: () -> Unit,
    onAddPages: () -> Unit,
    onRename: (String) -> Unit,
    onCheckErrors: () -> Unit
) {
    var page by remember(file.path, file.lastModified()) { mutableIntStateOf(0) }
    var nameDialog by remember(file.path) { mutableStateOf(false) }
    var newName by remember(file.path) { mutableStateOf(file.nameWithoutExtension) }
    var moreMenu by remember { mutableStateOf(false) }
    var tagsDialog by remember { mutableStateOf(false) }
    var tags by remember(file.path) { mutableStateOf("") }
    val count by produceState(0, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount } }
                .getOrDefault(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (count > 0) "Page ${(page + 1).coerceAtMost(count)} of $count" else "PDF preview",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nameDialog = true }) { Icon(Icons.Filled.Edit, "Rename document") }
                    Box {
                        IconButton(onClick = { tagsDialog = true }) { Icon(Icons.Filled.Label, "Tags") }
                    }
                    IconButton(onSave) { Icon(Icons.Filled.Save, "Save") }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { moreMenu = false; nameDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Tags") },
                                onClick = { moreMenu = false; tagsDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Print") },
                                onClick = { moreMenu = false; onPrint() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (count == 0) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("This PDF could not be previewed.")
                        TextButton(onOpenExternal) { Text("Open in another app") }
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    PdfRenderedPage(file, page.coerceIn(0, count - 1), 1200,
                        Modifier.fillMaxSize().padding(6.dp), ContentScale.Fit)
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items((0 until count).toList(), key = { it }) { index ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Card(
                                onClick = { page = index },
                                border = if (page == index) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                PdfRenderedPage(file, index, 140, Modifier.size(width = 64.dp, height = 84.dp).padding(3.dp))
                            }
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                                color = if (page == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (page > 0) page-- }, enabled = page > 0) {
                        Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Previous")
                    }
                    Text("${page + 1} / $count", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { if (page < count - 1) page++ }, enabled = page < count - 1) {
                        Text("Next"); Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Card(
                    onClick = onCheckErrors,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, "Document check", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Check for document errors", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.Send, "Check", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onAddPages, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(5.dp)); Text("Add", maxLines = 1)
                    }
                    Button(onEditPages, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(5.dp)); Text("Edit", maxLines = 1)
                    }
                    OutlinedButton(onShare, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(5.dp)); Text("Share", maxLines = 1)
                    }
                }
            }
        }

    if (nameDialog) AlertDialog(
        onDismissRequest = { nameDialog = false },
        title = { Text("Rename document") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text("Document name") }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val name = newName.trim()
                if (name.isNotBlank()) onRename(name)
                nameDialog = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Cancel") } }
    )
    if (tagsDialog) AlertDialog(
        onDismissRequest = { tagsDialog = false },
        title = { Text("Document tags") },
        text = {
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Tags (comma-separated)") },
                placeholder = { Text("School, Personal, Office") }
            )
        },
        confirmButton = {
            TextButton(onClick = { tagsDialog = false }) { Text("Done") }
        }
    )
    }
}
