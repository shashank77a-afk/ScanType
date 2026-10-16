package com.scantype.documentscanner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Saving to Gallery / Downloads uses MediaStore, which needs no permission on Android 10+.
 * Only Android 9 and older need WRITE_EXTERNAL_STORAGE; rememberWriteGate asks for it only then.
 */
@Composable
fun rememberWriteGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok) p?.invoke() else onDenied()
    }
    return { action ->
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
}

class PdfPrintAdapter(private val file: File, private val docName: String) : PrintDocumentAdapter() {
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
                          callback: LayoutResultCallback?, extras: Bundle?) {
        if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
        callback?.onLayoutFinished(PrintDocumentInfo.Builder(docName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
    }
    override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: CancellationSignal?,
                         callback: WriteResultCallback?) {
        try {
            FileInputStream(file).use { i -> FileOutputStream(destination!!.fileDescriptor).use { o -> i.copyTo(o) } }
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) { callback?.onWriteFailed(e.message) }
    }
}

object Export {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun share(ctx: Context, f: File, mime: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }

    fun print(ctx: Context, f: File, name: String) {
        runCatching {
            (ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(name, PdfPrintAdapter(f, name), null)
        }
    }

    /**
     * Generate a collision-free display name without overwriting previous exports.
     * Query failures must abort the save: treating an unknown result as "available"
     * could overwrite a user's existing document on providers that allow duplicate names.
     */
    private fun uniqueMediaName(ctx: Context, collection: Uri, relativePath: String, display: String, ext: String): String {
        val base = display.trim().ifBlank { "ScannerAi" }
        var candidate = "$base.$ext"
        var index = 1
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        while (true) {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val cursor = ctx.contentResolver.query(collection, projection, selection, arrayOf(candidate, relativePath), null)
                ?: error("Could not check whether Gallery filename already exists")
            val exists = cursor.use { it.moveToFirst() }
            if (!exists) return candidate
            candidate = "${base}_${index++}.$ext"
        }
    }

    fun saveImage(ctx: Context, bmp: Bitmap, display: String, png: Boolean): Boolean = runCatching {
        val mime = if (png) "image/png" else "image/jpeg"
        val ext = if (png) "png" else "jpg"
        val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relative = Environment.DIRECTORY_PICTURES + "/ScannerAi/"
            val name = uniqueMediaName(ctx, collection, relative, display, ext)
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(collection, v) ?: error("Could not create gallery item")
            try {
                cr.openOutputStream(uri)?.use { if (!bmp.compress(fmt, 95, it)) error("Image encoding failed") }
                    ?: error("Could not open gallery output")
                cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ScannerAi").apply { mkdirs() }
            var f = File(dir, "$display.$ext"); var n = 1
            while (f.exists()) f = File(dir, "${display}_${n++}.$ext")
            f.outputStream().use { if (!bmp.compress(fmt, 95, it)) error("Image encoding failed") }
            MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
        }
        true
    }.getOrDefault(false)

    /** Renders each PDF page to an image and saves it to the Gallery. Returns how many were saved. */
    fun savePdfToGallery(ctx: Context, f: File, png: Boolean): Int {
        var n = 0
        runCatching {
            PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                    val w = 1600; val h = (w * p.height.toFloat() / p.width).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    try {
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if (saveImage(ctx, b, "${f.nameWithoutExtension}_${i + 1}", png)) n++
                    } finally { b.recycle() }
                }
            }
        }
        return n
    }

    fun saveToDownloads(ctx: Context, f: File, name: String, mime: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relative = Environment.DIRECTORY_DOWNLOADS + "/ScannerAi/"
            val base = name.substringBeforeLast('.', name).ifBlank { "ScannerAi" }
            val ext = name.substringAfterLast('.', "")
            var candidate = name
            var index = 1
            while (true) {
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val cursor = cr.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), selection, arrayOf(candidate, relative), null)
                    ?: error("Could not check whether Downloads filename already exists")
                val exists = cursor.use { it.moveToFirst() }
                if (!exists) break
                candidate = if (ext.isBlank()) "${base}_${index++}" else "${base}_${index++}.$ext"
            }
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(collection, v) ?: error("Could not create download")
            try {
                cr.openOutputStream(uri)?.use { o -> FileInputStream(f).use { it.copyTo(o) } }
                    ?: error("Could not open download output")
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ScannerAi").apply { mkdirs() }
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var d = File(dir, name); var n = 1
            while (d.exists()) d = File(dir, if (ext.isBlank()) "${base}_${n++}" else "${base}_${n++}.$ext")
            FileInputStream(f).use { i -> d.outputStream().use { o -> i.copyTo(o) } }
            MediaScannerConnection.scanFile(ctx, arrayOf(d.path), arrayOf(mime), null)
        }
        true
    }.getOrDefault(false)
}
