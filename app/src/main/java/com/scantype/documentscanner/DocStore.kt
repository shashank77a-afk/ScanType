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
        dir.walkTopDown().maxDepth(2).filter { it.isFile && it.extension == "pdf" }
            .sortedByDescending { it.lastModified() }.toList()

    private fun newFile(prefix: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${prefix}_$ts.pdf")
    }
    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    private fun unique(base: String): File {
        val c = clean(base).ifEmpty { "Scan" }
        var f = File(dir, "$c.pdf"); var n = 1
        while (f.exists()) f = File(dir, "${c}_${n++}.pdf")
        return f
    }
    fun importPdf(uri: Uri, base: String) {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> unique(base).outputStream().use { i.copyTo(it) } }
    }
    fun newFileExt(base: String, ext: String): File {
        val c = clean(base).ifEmpty { "Document" }
        var f = File(dir, "$c.$ext"); var n = 1
        while (f.exists()) f = File(dir, "${c}_${n++}.$ext")
        return f
    }
    fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".txt")
    fun searchText(f: File): String =
        runCatching { sidecar(f).takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")
    fun rename(f: File, name: String) {
        val c = clean(name); if (c.isEmpty()) return
        val t = File(f.parentFile, "$c.pdf")
        if (t.exists()) return
        val s = sidecar(f)
        if (f.renameTo(t) && s.exists()) s.renameTo(File(f.parentFile, "$c.txt"))
    }
    fun duplicate(f: File) { f.copyTo(File(f.parentFile, "${f.nameWithoutExtension}_copy.pdf"), overwrite = true) }
    fun delete(f: File) { f.delete(); sidecar(f).delete() }
    fun folders(): List<String> =
        (listOf("School", "Personal", "Office", "Notes", "Certificates", "Other") +
            (dir.listFiles { x -> x.isDirectory }?.map { it.name } ?: emptyList())).distinct()
    fun createFolder(name: String) { val c = clean(name); if (c.isNotEmpty()) File(dir, c).mkdirs() }
    fun folderOf(f: File): String = if (f.parentFile == dir) "" else f.parentFile!!.name
    fun move(f: File, folder: String) {
        val t = if (folder.isEmpty()) dir else File(dir, folder).apply { mkdirs() }
        if (t == f.parentFile) return
        val dest = File(t, f.name); if (dest.exists()) return
        val s = sidecar(f)
        if (f.renameTo(dest) && s.exists()) s.renameTo(File(t, s.name))
    }

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

    /** Builds a PDF from bitmaps supplied one at a time (each is recycled after use). */
    fun bitmapsToPdf(n: Int, name: String, get: (Int) -> Bitmap) {
        val doc = PdfDocument()
        try {
            for (i in 0 until n) {
                val bmp = get(i)
                val ph = (595f * bmp.height / bmp.width).toInt()
                val p = doc.startPage(PdfDocument.PageInfo.Builder(595, ph, i + 1).create())
                p.canvas.drawColor(Color.WHITE)
                p.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, ph), null)
                doc.finishPage(p); bmp.recycle()
            }
            unique(name).outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
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
        return newFile("Typed").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close(); runCatching { sidecar(f).writeText(text) } }
    }

    /** Rebuilds a PDF from (file, pageIndex) pairs. Pages are re-rendered as images at [width] px. */
    fun rebuild(pages: List<Pair<File, Int>>, width: Int, prefix: String): File {
        val doc = PdfDocument()
        val rs = HashMap<File, PdfRenderer>()
        try {
            pages.forEachIndexed { n, (f, i) ->
                val r = rs.getOrPut(f) { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) }
                r.openPage(i).use { p ->
                    val h = (width * p.height.toFloat() / p.width).toInt()
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val ph = (595f * h / width).toInt()
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, ph, n + 1).create())
                    page.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, ph), null)
                    doc.finishPage(page); bmp.recycle()
                }
            }
            val out = newFile(prefix)
            out.outputStream().use { doc.writeTo(it) }
            return out
        } finally { rs.values.forEach { it.close() }; doc.close() }
    }
}
