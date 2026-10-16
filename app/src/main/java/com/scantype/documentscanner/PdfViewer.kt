package com.scantype.documentscanner

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(file: File, store: DocStore, onClose: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pageCount by remember(file.absolutePath, file.lastModified()) { mutableIntStateOf(0) }
    var pageIndex by remember(file.absolutePath) { mutableIntStateOf(0) }
    var loading by remember(file.absolutePath, file.lastModified()) { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        loading = true
        pageCount = withContext(Dispatchers.IO) { store.pageCount(file) }
        pageIndex = 0
        loading = false
    }

    val pageBitmap by produceState<Bitmap?>(null, file.absolutePath, pageIndex, pageCount) {
        value = if (pageCount > 0) withContext(Dispatchers.IO) { store.renderPage(file, pageIndex, 1200) } else null
    }

    fun launchExternal(action: String) {
        val uri = store.uri(file)
        val intent = Intent(action).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
            .onFailure { toast(if (action == Intent.ACTION_EDIT) "No compatible PDF editor was found." else "No app found to open this PDF.") }
    }

    fun share() {
        val uri = store.uri(file)
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(intent, "Share PDF")) }
            .onFailure { toast("Could not share this PDF.") }
    }

    fun saveCopy() {
        if (working) return
        scope.launch {
            working = true
            val saved = withContext(Dispatchers.IO) {
                Export.saveToDownloads(ctx, file, file.name, "application/pdf")
            }
            working = false
            toast(if (saved) "Copy saved in Download/ScannerAi." else "Could not save the PDF. Please try again.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (pageCount > 0) "$pageCount pages · PDF" else "PDF document",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClose) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton({ launchExternal(Intent.ACTION_VIEW) }) { Icon(Icons.Filled.OpenInNew, "Open in another app") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.PictureAsPdf, "PDF actions") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Open in another app") }, onClick = { menu = false; launchExternal(Intent.ACTION_VIEW) })
                            DropdownMenuItem(text = { Text("Edit in another app") }, onClick = { menu = false; launchExternal(Intent.ACTION_EDIT) })
                            DropdownMenuItem(text = { Text("Print") }, onClick = { menu = false; Export.print(ctx, file, file.nameWithoutExtension) })
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    if (pageCount > 1) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items((0 until pageCount).toList(), key = { it }) { index ->
                                PdfPageThumbnail(file, store, index, selected = index == pageIndex) {
                                    pageIndex = index
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ViewerAction("Open", Icons.Filled.OpenInNew, enabled = true) { launchExternal(Intent.ACTION_VIEW) }
                        ViewerAction("Edit", Icons.Filled.Edit, enabled = true) { launchExternal(Intent.ACTION_EDIT) }
                        ViewerAction("Share", Icons.Filled.Share, enabled = true) { share() }
                        ViewerAction(if (working) "Saving…" else "Save", Icons.Filled.Download, enabled = !working) { saveCopy() }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pageCount <= 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This PDF could not be read.", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) {
                        Icon(Icons.Filled.ChevronLeft, "Previous page")
                    }
                    Text("Page ${pageIndex + 1} of $pageCount", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { if (pageIndex < pageCount - 1) pageIndex++ }, enabled = pageIndex < pageCount - 1) {
                        Icon(Icons.Filled.ChevronRight, "Next page")
                    }
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = pageBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "PDF page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else CircularProgressIndicator()
                }
                if (pageCount > 1) Text("Tap a thumbnail or use the arrows to change pages.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun PdfPageThumbnail(file: File, store: DocStore, index: Int, selected: Boolean, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, file.absolutePath, file.lastModified(), index) {
        value = withContext(Dispatchers.IO) { store.renderPage(file, index, 140) }
    }
    Surface(
        modifier = Modifier.width(66.dp).height(88.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp)
            ) { Text("${index + 1}", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun ViewerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}
