package com.scantype.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

object PdfBuilders {
    private val rec by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private fun addImagePage(doc: PdfDocument, bmp: Bitmap, pageNo: Int): Int {
        val ph = (595f * bmp.height / bmp.width).toInt()
        val p = doc.startPage(PdfDocument.PageInfo.Builder(595, ph, pageNo + 1).create())
        p.canvas.drawColor(Color.WHITE)
        p.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, ph), null)
        doc.finishPage(p)
        bmp.recycle()
        return pageNo + 1
    }

    /**
     * Scanned page image + a nearly invisible text layer from on-device OCR (Hindi and English),
     * so the text can be searched and selected. English is the most reliable; Hindi depends on the PDF viewer.
     */
    suspend fun searchable(out: File, n: Int, get: (Int) -> Bitmap) {
        val doc = PdfDocument()
        try {
            for (i in 0 until n) {
                val bmp = get(i)
                val ph = (595f * bmp.height / bmp.width).toInt()
                val sc = 595f / bmp.width
                val lines = runCatching { rec.process(InputImage.fromBitmap(bmp, 0)).await().textBlocks.flatMap { it.lines } }
                    .getOrDefault(emptyList())
                val p = doc.startPage(PdfDocument.PageInfo.Builder(595, ph, i + 1).create())
                val c = p.canvas
                c.drawColor(Color.WHITE)
                c.drawBitmap(bmp, null, Rect(0, 0, 595, ph), null)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(3, 0, 0, 0) }
                for (ln in lines) {
                    val box = ln.boundingBox ?: continue
                    val t = ln.text
                    if (t.isBlank()) continue
                    paint.textSize = (box.height() * sc * 0.8f).coerceAtLeast(4f)
                    paint.textScaleX = 1f
                    val mw = paint.measureText(t)
                    if (mw > 0f) paint.textScaleX = (box.width() * sc / mw).coerceIn(0.3f, 3f)
                    c.drawText(t, box.left * sc, box.bottom * sc - box.height() * sc * 0.2f, paint)
                }
                doc.finishPage(p)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    /** Original scans plus typed text, either interleaved page by page or as a typed section at the end. */
    suspend fun originalPlusTyped(ctx: Context, out: File, n: Int, engine: OcrEngine, interleave: Boolean,
                                  get: (Int) -> Bitmap, progress: (Int) -> Unit) {
        val doc = PdfDocument()
        try {
            val texts = ArrayList<String>()
            var pageNo = 0
            fun spec(t: String) = DocSpec(t, DocTemplate.PLAIN, DocAlign.LEFT, 13, 1.3f, PageSize.A4, true, 56)
            for (i in 0 until n) {
                progress(i + 1)
                val bmp = get(i)
                val tmp = File(ctx.cacheDir, "ocr_${System.nanoTime()}.jpg")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                var txt = engine.recognize(ctx, Uri.fromFile(tmp)).getOrDefault("").trim()
                tmp.delete()
                if (txt == "UNREADABLE") txt = ""
                pageNo = addImagePage(doc, bmp, pageNo)
                if (interleave) pageNo += DocPdf.renderInto(doc, pageNo + 1, spec(txt.ifBlank { "(No text could be recognised on this page)" }))
                else texts.add(txt)
            }
            if (!interleave) pageNo += DocPdf.renderInto(doc, pageNo + 1, spec(texts.joinToString("\n\n")))
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }
}
