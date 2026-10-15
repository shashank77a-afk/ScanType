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
    "Analyze this photo of a letter (Hindi or English, handwritten or printed) and return its text together with its page layout as JSON:\n" +
    "{\"language\":\"hi|en|mixed\",\"pageAspect\":<page width divided by page height>,\"blocks\":[{\"text\":\"...\",\"role\":\"recipient|reference|date|subject|salutation|body|enclosure_heading|enclosure_item|closing|signature|other\",\"align\":\"left|center|right|justify\",\"box\":[x0,y0,x1,y1],\"bold\":false}]}\n" +
    "box is the area of the block on the PAGE, from 0 to 1000, origin at the top-left corner of the page, x to the right, y downward.\n" +
    "Rules: one block per paragraph or logical group (recipient and address together, one block per enclosure item, closing words, name and designation under the signature). Keep reading order top to bottom. " +
    "Transcribe exactly as written: do not correct, translate or add any word, name, date, number or amount. Join lines that are only wrapped inside one paragraph; keep a line break only where the writer started a new line on purpose. " +
    "If a word is unclear, write your best reading followed by [?]. Never invent text. Output only the JSON."

object LetterAnalyzer {
    private fun alignOf(s: String) = when (s) { "center" -> DocAlign.CENTER; "right" -> DocAlign.RIGHT; "justify" -> DocAlign.JUSTIFY; else -> DocAlign.LEFT }

    /**
     * Parses model layout conservatively. Accept the documented schema plus common alternate
     * block containers used by older Worker versions, but reject invalid geometry instead of
     * silently creating squeezed/overlapping blocks.
     */
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

    /** On-device fallback: real text blocks and their positions from ML Kit. Accuracy is limited, especially for handwriting. */
    private suspend fun offline(ctx: Context, uri: Uri, page: Int, startId: Int): List<LBlock> {
        val bmp = ImageFilters.load(ctx, uri, 1600)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val res = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bmp, 0)).await()
        bmp.recycle()
        val out = ArrayList<LBlock>()
        for (b in res.textBlocks) {
            val box = b.boundingBox ?: continue
            val t = b.lines.joinToString("\n") { it.text }.trim()
            if (t.isEmpty()) continue
            val x0 = box.left / w; val x1 = box.right / w
            val center = (x0 + x1) / 2
            val role = roleOf(t)
            val align = when {
                role == "closing" || role == "date" || x0 > 0.55f -> DocAlign.RIGHT
                kotlin.math.abs(center - 0.5f) < 0.05f && x1 - x0 < 0.6f -> DocAlign.CENTER
                role == "body" && x1 - x0 > 0.55f -> DocAlign.JUSTIFY
                else -> DocAlign.LEFT
            }
            out.add(LBlock(startId + out.size, page, t, role, align, x0.coerceIn(0f, 0.95f), (box.top / h).coerceIn(0f, 0.98f),
                x1.coerceIn(x0 + 0.05f, 1f), (box.bottom / h).coerceIn(0.01f, 1f), role == "subject"))
        }
        return out.sortedWith(compareBy({ it.y0 }, { it.x0 }))
    }

    private suspend fun ai(ctx: Context, prefs: Prefs, uri: Uri, page: Int, startId: Int): List<LBlock> {
        val jpeg = jpegBytes(ctx, uri)
        val layout = if (prefs.aiViaBackend) Backend.letterLayout(prefs, jpeg, prefs.lang)
        else JSONObject(geminiDirect(prefs, LAYOUT_PROMPT + langNote(prefs.lang), jpeg, true))
        return parse(layout, page, startId)
    }

    suspend fun analyze(ctx: Context, prefs: Prefs, uris: List<Uri>): LetterResult {
        var useAi = prefs.aiReady
        var source = if (useAi) "AI" else "On-device"
        var note = ""; var login = false
        val all = ArrayList<LBlock>()
        for ((i, u) in uris.withIndex()) {
            var got: List<LBlock>? = null
            if (useAi) {
                try { got = ai(ctx, prefs, u, i, all.size) }
                catch (e: Exception) {
                    useAi = false; source = "On-device"
                    login = needsLogin(e)
                    note = aiMessage(e) + " On-device recognition was used instead, so text and layout accuracy are limited."
                }
            }
            val list = if (got.isNullOrEmpty()) offline(ctx, u, i, all.size) else got
            all.addAll(list)
        }
        if (!prefs.aiReady && note.isEmpty()) note = "AI is off, so on-device recognition was used. It is weak on handwriting: please check every block."
        return LetterResult(all, source, note, login)
    }
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
