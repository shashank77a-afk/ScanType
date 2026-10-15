package com.scantype.documentscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One text block of the letter. Position and size are fractions (0..1) of the page, so they survive any page size. */
data class LBlock(
    val id: Int, val page: Int, val text: String, val role: String, val align: DocAlign,
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val bold: Boolean = false, val fontSize: Float = 0f)   // fontSize 0 = automatic

data class LetterOpts(
    val page: PageSize = PageSize.A4, val margin: Int = 36, val baseFont: Float = 13f,
    val lineSpacing: Float = 1.25f, val clean: Boolean = false, val align: DocAlign = DocAlign.JUSTIFY)

class LetterState {
    val images = mutableStateListOf<Uri>()
    val origs = mutableStateListOf<Uri>()
    val blocks = mutableStateListOf<LBlock>()
    var original by mutableStateOf<List<LBlock>>(emptyList())
    var source by mutableStateOf("")
    var note by mutableStateOf("")
    var needLogin by mutableStateOf(false)
    fun addImages(us: List<Uri>) { images.addAll(us); origs.addAll(us) }
}

class LetterResult(val blocks: List<LBlock>, val source: String, val note: String, val needLogin: Boolean)
class LayoutReport(val notes: List<String>, val pages: Int)

private const val LAYOUT_PROMPT =
    "You are a document-layout extraction engine. Inspect the provided photo of a handwritten or printed Hindi, English, or mixed-language official letter. Return one complete JSON object only.\n" +
    "Schema: {\"language\":\"hi|en|mixed\",\"pageAspect\":0.707,\"blocks\":[{\"text\":\"exact visible text\",\"role\":\"recipient|reference|date|subject|salutation|body|enclosure_heading|enclosure_item|closing|signature|other\",\"align\":\"left|center|right|justify\",\"box\":[x0,y0,x1,y1],\"bold\":false}]}\n" +
    "Coordinates are integers from 0 to 1000 relative to the full page, origin at top-left; box is [left,top,right,bottom]. pageAspect is page width divided by page height.\n" +
    "Make one block per paragraph or logical group. Preserve headings, recipient, reference, date, subject, salutation, body paragraphs, enclosure list, closing, signature and designation. Keep reading order top-to-bottom. Transcribe faithfully; do not translate, correct spelling, summarize, or invent text. For unreadable words, use [?].\n" +
    "Every block must have non-empty text. Use sensible positive boxes that do not exceed 1000. Do not include markdown fences, comments, or extra fields outside the JSON object. Keep the response concise enough to fit in one complete response.";


object LetterAnalyzer {
    private fun alignOf(s: String) = when (s) { "center" -> DocAlign.CENTER; "right" -> DocAlign.RIGHT; "justify" -> DocAlign.JUSTIFY; else -> DocAlign.LEFT }

    fun parse(layout: JSONObject, page: Int, startId: Int): List<LBlock> {
        val arr = layout.optJSONArray("blocks")
            ?: layout.optJSONArray("elements")
            ?: layout.optJSONArray("paragraphs")
            ?: layout.optJSONArray("items")
            ?: layout.optJSONArray("lines")
        val out = ArrayList<LBlock>()
        var cursor = 0.08f

        fun readText(b: JSONObject): String {
            val candidates = listOf("text", "content", "value", "recognizedText", "description")
            return candidates.asSequence()
                .map { b.optString(it, "").trim() }
                .firstOrNull { it.isNotEmpty() && it != "null" } ?: ""
        }

        fun readBox(b: JSONObject): JSONArray? {
            val direct = b.optJSONArray("box") ?: b.optJSONArray("bbox") ?: b.optJSONArray("bounds")
            if (direct != null && direct.length() == 4) return direct
            val x = b.optDouble("x", Double.NaN)
            val y = b.optDouble("y", Double.NaN)
            val w = b.optDouble("width", Double.NaN)
            val h = b.optDouble("height", Double.NaN)
            if (listOf(x, y, w, h).all { it.isFinite() } && w > 0 && h > 0) {
                return JSONArray().put(x).put(y).put(x + w).put(y + h)
            }
            return null
        }

        val candidates = if (arr != null) {
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } else if (readText(layout).isNotEmpty()) {
            listOf(layout)
        } else emptyList()

        for (b in candidates) {
            val t = readText(b)
            if (t.isBlank()) continue
            val box = readBox(b)
            var x0 = 0.10f
            var y0 = cursor
            var x1 = 0.90f
            var y1 = (cursor + 0.06f).coerceAtMost(0.96f)

            if (box != null) {
                val raw = (0..3).map { box.optDouble(it, Double.NaN) }
                if (raw.all { it.isFinite() }) {
                    // Worker output is documented in 0..1000 page coordinates. Some model outputs
                    // return 0..1 normalized coordinates; accept those too.
                    val divisor = if (raw.maxOrNull()!! <= 1.0) 1.0 else 1000.0
                    val bx0 = (raw[0] / divisor).toFloat()
                    val by0 = (raw[1] / divisor).toFloat()
                    val bx1 = (raw[2] / divisor).toFloat()
                    val by1 = (raw[3] / divisor).toFloat()
                    if (bx0 >= 0f && by0 >= 0f && bx1 <= 1f && by1 <= 1f && bx1 > bx0 && by1 > by0) {
                        x0 = bx0
                        y0 = by0
                        x1 = bx1
                        y1 = by1
                        cursor = (y1 + 0.02f).coerceAtMost(0.96f)
                    } else {
                        // Ignore malformed geometry and stack this text in reading order.
                        cursor = (y1 + 0.02f).coerceAtMost(0.96f)
                    }
                } else {
                    cursor = (y1 + 0.02f).coerceAtMost(0.96f)
                }
            } else {
                cursor = (y1 + 0.02f).coerceAtMost(0.96f)
            }

            val role = b.optString("role", "body").takeIf { it.isNotBlank() } ?: "body"
            val align = alignOf(b.optString("align", "left"))
            out.add(
                LBlock(
                    id = startId + out.size,
                    page = page,
                    text = t,
                    role = role,
                    align = align,
                    x0 = x0,
                    y0 = y0,
                    x1 = x1,
                    y1 = y1,
                    bold = b.optBoolean("bold", false),
                    fontSize = b.optDouble("fontSize", 0.0).toFloat().coerceIn(0f, 64f)
                )
            )
        }
        return out
    }

    private fun roleOf(t: String): String = when {
        t.startsWith("सेवा में") || t.startsWith("To,") -> "recipient"
        t.startsWith("पत्रांक") || t.startsWith("Ref") -> "reference"
        t.startsWith("दिनांक") || t.startsWith("Date") -> "date"
        t.startsWith("विषय") || t.startsWith("Subject") -> "subject"
        t.startsWith("महोदय") || t.startsWith("Dear") -> "salutation"
        t.startsWith("संलग्नक") -> "enclosure_heading"
        t.startsWith("भवदीय") || t.startsWith("Yours") -> "closing"
        else -> "body"
    }

    /**
     * On-device fallback uses Latin and Devanagari OCR together. Duplicate overlapping
     * blocks are filtered so English, Hindi and mixed-script letters have a better fallback.
     * Handwritten recognition may still be limited without AI recognition.
     */
    private suspend fun offline(ctx: Context, uri: Uri, page: Int, startId: Int): List<LBlock> {
        val bmp = ImageFilters.load(ctx, uri, 1600)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val image = InputImage.fromBitmap(bmp, 0)
        val latin = runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image).await().textBlocks
        }.getOrDefault(emptyList())
        val devanagari = runCatching {
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()).process(image).await().textBlocks
        }.getOrDefault(emptyList())
        bmp.recycle()
        data class Candidate(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
        val candidates = ArrayList<Candidate>()
        for (blocks in listOf(latin, devanagari)) {
            for (block in blocks) {
                val box = block.boundingBox ?: continue
                val text = block.lines.joinToString("\n") { it.text }.trim()
                if (text.isBlank()) continue
                val duplicate = candidates.any { c ->
                    val ix = maxOf(0, minOf(c.right, box.right) - maxOf(c.left, box.left))
                    val iy = maxOf(0, minOf(c.bottom, box.bottom) - maxOf(c.top, box.top))
                    val intersection = ix.toFloat() * iy
                    val areaA = maxOf(1, c.right - c.left).toFloat() * maxOf(1, c.bottom - c.top)
                    val areaB = maxOf(1, box.width()).toFloat() * maxOf(1, box.height())
                    intersection / (areaA + areaB - intersection).coerceAtLeast(1f) > 0.55f
                }
                if (!duplicate) candidates.add(Candidate(text, box.left, box.top, box.right, box.bottom))
            }
        }
        return candidates.sortedWith(compareBy<Candidate>({ it.top }, { it.left })).mapIndexed { index, item ->
            val x0 = (item.left / w).coerceIn(0f, 0.95f)
            val x1 = (item.right / w).coerceIn(x0 + 0.05f, 1f)
            val y0 = (item.top / h).coerceIn(0f, 0.98f)
            val y1 = (item.bottom / h).coerceIn(y0 + 0.01f, 1f)
            val center = (x0 + x1) / 2f
            val role = roleOf(item.text)
            val alignment = when {
                role == "closing" || role == "date" || x0 > 0.55f -> DocAlign.RIGHT
                kotlin.math.abs(center - 0.5f) < 0.05f && x1 - x0 < 0.6f -> DocAlign.CENTER
                role == "body" && x1 - x0 > 0.55f -> DocAlign.JUSTIFY
                else -> DocAlign.LEFT
            }
            LBlock(startId + index, page, item.text, role, alignment, x0, y0, x1, y1, role == "subject")
        }
    }

    /**
     * Hybrid path: local OCR and local rule-based layout run first. Gemini is only an
     * optional rescue path when local OCR returns no usable text or a very small result.
     * PDF rendering never calls AI.
     */
    private suspend fun aiFallback(ctx: Context, prefs: Prefs, uri: Uri, page: Int, startId: Int): List<LBlock> {
        val jpeg = jpegBytes(ctx, uri)
        val text = if (prefs.aiViaBackend) {
            Backend.ocr(prefs, jpeg, prefs.lang)
        } else {
            geminiDirect(prefs, "Transcribe the readable Hindi and English text from this document exactly. Preserve paragraph breaks. Do not summarize or invent words.", jpeg, false)
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { index, line ->
            val y0 = (0.06f + index * 0.045f).coerceAtMost(0.94f)
            LBlock(startId + index, page, line, roleOf(line), DocAlign.LEFT, 0.10f, y0, 0.90f, (y0 + 0.035f).coerceAtMost(0.99f))
        }
    }

    private fun isWeakLocalResult(blocks: List<LBlock>, uri: Uri): Boolean {
        val chars = blocks.sumOf { it.text.count(Char::isLetterOrDigit) }
        // Use conservative signals only. Weak local text prompts optional AI; it doesn't silently upload.
        return blocks.isEmpty() || chars < 24
    }

    suspend fun analyze(ctx: Context, prefs: Prefs, uris: List<Uri>, allowAiFallback: Boolean = false): LetterResult {
        val all = ArrayList<LBlock>()
        var source = "On-device OCR"
        var note = ""
        for ((i, uri) in uris.withIndex()) {
            val local = runCatching { offline(ctx, uri, i, all.size) }.getOrDefault(emptyList())
            val weak = isWeakLocalResult(local, uri)
            val chosen = if (weak && allowAiFallback && prefs.aiReady && prefs.signedIn) {
                runCatching { aiFallback(ctx, prefs, uri, i, all.size) }.getOrElse { e ->
                    note = "Local OCR was used because AI fallback failed: ${aiMessage(e)}"
                    local
                }.also { if (it.isNotEmpty()) source = "AI fallback" }
            } else {
                if (weak) note = "Local OCR may have missed handwritten words. Review the text, or choose AI fallback if you want."
                local
            }
            if (chosen.isEmpty()) {
                throw AiException("ocr_no_text", "No text was detected locally. Improve the crop/lighting and retry, or enable optional AI fallback.")
            }
            all.addAll(chosen)
        }
        return LetterResult(all, source, note, false)
    }


/** Rebuilds the letter as a real-text PDF with every block placed where it was on the original page. */
object LayoutPdf {
    private fun mk(b: LBlock, size: Float, width: Int, o: LetterOpts): StaticLayout {
        val runs = Inline.runs(b.text)
        val plain = runs.joinToString("") { it.text }.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = size
            typeface = if (b.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        val sp = SpannableString(plain)
        var at = 0
        for (r in runs) {
            val st = if (r.bold && r.italic) Typeface.BOLD_ITALIC else if (r.bold) Typeface.BOLD else if (r.italic) Typeface.ITALIC else -1
            if (st >= 0 && r.text.isNotEmpty()) sp.setSpan(StyleSpan(st), at, at + r.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            at += r.text.length
        }
        val lb = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, width)
            .setAlignment(when (b.align) {
                DocAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                DocAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }).setLineSpacing(0f, o.lineSpacing)
        if (Build.VERSION.SDK_INT >= 26 && b.align == DocAlign.JUSTIFY) lb.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return lb.build()
    }

    fun render(blocks: List<LBlock>, o: LetterOpts, out: File): LayoutReport {
        val notes = ArrayList<String>()
        val pw = o.page.wPt; val ph = o.page.hPt; val m = o.margin.toFloat()
        val pdf = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        val placed = ArrayList<RectF>()
        fun newPage() {
            page?.let { pdf.finishPage(it) }
            pageNo++
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            p.canvas.drawColor(Color.WHITE)
            page = p; canvas = p.canvas; placed.clear()
        }
        fun draw(layout: StaticLayout, left: Float, top: Float, from: Int, to: Int) {
            val t0 = layout.getLineTop(from).toFloat(); val t1 = layout.getLineBottom(to - 1).toFloat()
            val c = canvas!!
            c.save(); c.translate(left, top - t0); c.clipRect(0f, t0, layout.width.toFloat(), t1); layout.draw(c); c.restore()
        }
        try {
            val last = blocks.maxOfOrNull { it.page } ?: 0
            for (pg in 0..last) {
                val list = blocks.filter { it.page == pg }.sortedWith(compareBy({ it.y0 }, { it.x0 }))
                if (list.isEmpty() && pg > 0) continue
                newPage()
                for ((n, b) in list.withIndex()) {
                    if (Inline.plain(b.text).isBlank()) continue
                    val left = (b.x0 * pw).coerceIn(m, pw - m - 40f)
                    val right = (b.x1 * pw).coerceIn(left + 40f, pw - m)
                    val width = (right - left).toInt()
                    val boxH = (b.y1 - b.y0) * ph
                    val auto = b.fontSize <= 0f
                    var size = if (auto) o.baseFont else b.fontSize
                    var layout = mk(b, size, width, o)
                    if (auto) {
                        while (size > 8f && layout.height > boxH * 1.2f + size) { size -= 0.5f; layout = mk(b, size, width, o) }
                        if (size < o.baseFont) notes.add("Block ${n + 1}: font reduced from ${o.baseFont.toInt()} to ${size.toInt()} to fit its area.")
                    }
                    var top = (b.y0 * ph).coerceAtLeast(m)
                    var moved = false
                    var guard = 0
                    while (guard++ < 12) {
                        val r = RectF(left, top, right, top + layout.height)
                        val hit = placed.filter { RectF.intersects(it, r) }.maxByOrNull { it.bottom } ?: break
                        top = hit.bottom + 4f; moved = true
                    }
                    if (moved) notes.add("Block ${n + 1} moved down to avoid overlapping another block.")
                    if (top + layout.height > ph - m) {
                        if (auto) { while (size > 9f && top + layout.height > ph - m) { size -= 0.5f; layout = mk(b, size, width, o) } }
                        if (top + layout.height > ph - m) {
                            if (top > m + 1) { newPage(); top = m; notes.add("Block ${n + 1} moved to the next page (it did not fit).") }
                        }
                    }
                    if (top + layout.height <= ph - m) {
                        draw(layout, left, top, 0, layout.lineCount)
                        placed.add(RectF(left, top, right, top + layout.height))
                    } else {
                        var line = 0
                        while (line < layout.lineCount) {
                            val t0 = layout.getLineTop(line)
                            var end = line
                            while (end < layout.lineCount && top + (layout.getLineBottom(end) - t0) <= ph - m) end++
                            if (end == line) { if (top > m + 1) { newPage(); top = m; continue } else end = line + 1 }
                            draw(layout, left, top, line, end)
                            placed.add(RectF(left, top, right, top + (layout.getLineBottom(end - 1) - t0)))
                            line = end
                            if (line < layout.lineCount) { newPage(); top = m }
                        }
                        notes.add("Block ${n + 1} is long and continues on the next page.")
                    }
                }
            }
            page?.let { pdf.finishPage(it) }
            out.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return LayoutReport(notes.distinct(), pageNo)
    }

    /** The same text as a clean, standard official letter (existing letter formatter). */
    fun clean(blocks: List<LBlock>, o: LetterOpts, out: File): LayoutReport {
        val text = blocks.sortedWith(compareBy({ it.page }, { it.y0 }, { it.x0 })).joinToString("\n\n") { it.text.trim() }
        DocPdf.render(DocSpec(text, DocTemplate.LETTER, o.align, o.baseFont.toInt(), o.lineSpacing, o.page, true, maxOf(o.margin, 48)), out)
        return LayoutReport(emptyList(), DocPdf.rasterize(out, 100, 50).size)
    }
}
