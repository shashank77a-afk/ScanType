package com.scantype.documentscanner

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

/** App-private PDF storage. Nothing leaves the device unless the user shares it. */
class DocStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "docs").apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.extension == "pdf" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun newFile(prefix: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${prefix}_$ts.pdf")
    }

    fun importPdf(uri: Uri, prefix: String) {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> newFile(prefix).outputStream().use { i.copyTo(it) } }
    }

    fun rename(f: File, name: String) {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isNotEmpty()) f.renameTo(File(dir, "$clean.pdf"))
    }

    fun duplicate(f: File) { f.copyTo(File(dir, "${f.nameWithoutExtension}_copy.pdf"), overwrite = true) }
    fun uri(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun pageCount(f: File): Int = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
    }.getOrDefault(0)

    fun thumb(f: File): Bitmap? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { p ->
                val bmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bmp
            }
        }
    }.getOrNull()

    private fun decode(uri: Uri): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > 2000) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }!!
        val deg = ctx.contentResolver.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }

    fun imagesToPdf(uris: List<Uri>): File {
        val doc = PdfDocument()
        uris.forEachIndexed { i, u ->
            val bmp = decode(u)
            val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            p.canvas.drawColor(Color.WHITE)
            val s = min(595f / bmp.width, 842f / bmp.height)
            val w = bmp.width * s; val h = bmp.height * s
            p.canvas.drawBitmap(bmp, null, RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2), null)
            doc.finishPage(p); bmp.recycle()
        }
        return newFile("ImagePDF").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    /** Typed PDF. Uses the system Devanagari font, so Hindi/English/mixed text renders correctly. */
    fun textToPdf(text: String): File {
        val m = 48; val cw = 595 - 2 * m; val ch = 842 - 2 * m
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, cw)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        val doc = PdfDocument(); var line = 0; var n = 1
        do {
            val top = if (layout.lineCount > 0) layout.getLineTop(line) else 0
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= ch) end++
            if (end == line && line < layout.lineCount) end = line + 1
            val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n++).create())
            p.canvas.drawColor(Color.WHITE)
            if (end > line) {
                p.canvas.save()
                p.canvas.translate(m.toFloat(), (m - top).toFloat())
                p.canvas.clipRect(0f, top.toFloat(), cw.toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(p.canvas); p.canvas.restore()
            }
            doc.finishPage(p); line = end
        } while (line < layout.lineCount)
        return newFile("Typed").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }
}
