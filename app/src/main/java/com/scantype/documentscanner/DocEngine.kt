package com.scantype.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DocAlign { LEFT, CENTER, RIGHT, JUSTIFY }
enum class DocTemplate { PLAIN, LETTER }
enum class PageSize(val label: String, val wPt: Int, val hPt: Int, val wTw: Int, val hTw: Int) {
    A4("A4", 595, 842, 11906, 16838), LETTER("Letter", 612, 792, 12240, 15840)
}

data class DocSpec(
    val text: String,
    val template: DocTemplate = DocTemplate.PLAIN,
    val align: DocAlign = DocAlign.JUSTIFY,
    val fontSize: Int = 13,
    val lineSpacing: Float = 1.3f,
    val page: PageSize = PageSize.A4,
    val joinLines: Boolean = true,
    val margin: Int = 56)

/** One paragraph of the document. Sizes are in points. */
class Block(val text: String, val bold: Boolean = false, val align: DocAlign = DocAlign.LEFT,
            val first: Float = 0f, val left: Float = 0f, val before: Float = 0f, val after: Float = 6f, val scale: Float = 1f)

/** Turns typed or recognised text into formatted paragraphs. Never changes the words. */
object DocFormatter {
    private val END = Regex("[।.!?:;”\")\\]]$")
    private val LIST = Regex("^\\s*(\\d{1,3}[.)]|[-•*])\\s+")
    private val TO = listOf("सेवा में", "सेवा मे", "To,", "To ")
    private val REF = listOf("पत्रांक", "पत्र संख्या", "संख्या", "Ref")
    private val DATE = listOf("दिनांक", "दिनाँक", "Date")
    private val SUBJ = listOf("विषय", "Subject", "Sub:", "Sub-", "Sub.")
    private val SAL = listOf("महोदय", "महोदया", "आदरणीय", "माननीय", "प्रिय", "Dear", "Respected")
    private val ENC = listOf("संलग्नक", "संलग्न", "Enclosure", "Encl")
    private val CLOSE = listOf("भवदीय", "प्रार्थी", "निवेदक", "Yours", "Sincerely", "आपका")

    private fun starts(t: String, keys: List<String>) = keys.any { t.startsWith(it, ignoreCase = true) }
    private fun lines(s: String) = s.replace("\r", "").split("\n").map { it.trim() }

    fun blocks(spec: DocSpec): List<Block> = if (spec.template == DocTemplate.LETTER) letter(spec) else plain(spec)

    private fun letter(spec: DocSpec): List<Block> {
        val out = ArrayList<Block>()
        var mode = 0; var first = true
        var para = StringBuilder(); var subj = StringBuilder(); var item = StringBuilder()
        fun flushPara() { if (para.isNotEmpty()) { out.add(Block(para.toString(), align = spec.align, first = 36f, after = 8f)); para = StringBuilder() } }
        fun flushSubj() { if (subj.isNotEmpty()) { out.add(Block(subj.toString(), bold = true, left = 44f, first = -44f, before = 8f, after = 10f)); subj = StringBuilder() } }
        fun flushItem() { if (item.isNotEmpty()) { out.add(Block(item.toString(), left = 36f, first = -18f, after = 3f)); item = StringBuilder() } }
        fun flushAll() { flushPara(); flushSubj(); flushItem() }
        for (t in lines(spec.text)) {
            if (t.isEmpty()) { flushAll(); if (mode in 1..3) mode = 0; continue }
            if (first && starts(t, TO)) { first = false; out.add(Block(t, after = 2f)); mode = 1; continue }
            first = false
            val key = when {
                starts(t, REF) && t.length <= 80 -> "REF"
                starts(t, DATE) && t.length <= 60 -> "DATE"
                starts(t, SUBJ) -> "SUBJ"
                starts(t, SAL) && t.length <= 40 -> "SAL"
                starts(t, ENC) && t.length <= 60 -> "ENC"
                starts(t, CLOSE) && t.length <= 40 -> "CLOSE"
                else -> ""
            }
            when (key) {
                "REF" -> { flushAll(); out.add(Block(t, bold = true, before = 8f, after = 4f)); mode = 0 }
                "DATE" -> { flushAll(); out.add(Block(t, align = DocAlign.RIGHT, after = 4f)); mode = 0 }
                "SUBJ" -> { flushAll(); subj.append(t); mode = 2 }
                "SAL" -> { flushAll(); out.add(Block(t, before = 8f, after = 6f)); mode = 0 }
                "ENC" -> { flushAll(); out.add(Block(t, bold = true, before = 18f, after = 4f)); mode = 3 }
                "CLOSE" -> { flushAll(); out.add(Block(t, align = DocAlign.RIGHT, before = 22f, after = 2f)); mode = 4 }
                else -> when (mode) {
                    1 -> out.add(Block(t, left = 36f, after = 2f))
                    2 -> subj.append(" ").append(t)
                    3 -> if (LIST.containsMatchIn(t) || item.isEmpty() || END.containsMatchIn(item)) { flushItem(); item.append(t) } else item.append(" ").append(t)
                    4 -> out.add(Block(t, align = DocAlign.RIGHT, after = 2f))
                    else -> if (para.isEmpty()) para.append(t)
                    else if (spec.joinLines && !END.containsMatchIn(para)) para.append(" ").append(t)
                    else if (spec.joinLines) { flushPara(); para.append(t) }
                    else para.append("\n").append(t)
                }
            }
        }
        flushAll()
        return out
    }

    private fun plain(spec: DocSpec): List<Block> {
        val ls = lines(spec.text)
        val out = ArrayList<Block>()
        var para = StringBuilder(); var item = StringBuilder()
        fun flushPara() { if (para.isNotEmpty()) { out.add(Block(para.toString(), align = spec.align, after = 8f)); para = StringBuilder() } }
        fun flushItem() { if (item.isNotEmpty()) { out.add(Block(item.toString(), left = 26f, first = -20f, after = 3f)); item = StringBuilder() } }
        val k = ls.indexOfFirst { it.isNotEmpty() }
        var skip = -1
        if (k >= 0) {
            val t = ls[k]
            val alone = k + 1 >= ls.size || ls[k + 1].isEmpty()
            if (t.length <= 60 && !END.containsMatchIn(t) && !LIST.containsMatchIn(t) && !t.startsWith("#") && alone && ls.count { it.isNotEmpty() } > 1) {
                out.add(Block(t, bold = true, align = DocAlign.CENTER, scale = 1.4f, after = 10f)); skip = k
            }
        }
        for ((i, t) in ls.withIndex()) {
            if (i == skip) continue
            if (t.isEmpty()) { flushPara(); flushItem(); continue }
            when {
                t.startsWith("## ") -> { flushPara(); flushItem(); out.add(Block(t.removePrefix("## "), bold = true, scale = 1.2f, before = 10f, after = 6f)) }
                t.startsWith("# ") -> { flushPara(); flushItem(); out.add(Block(t.removePrefix("# "), bold = true, scale = 1.5f, before = 12f, after = 8f)) }
                LIST.containsMatchIn(t) -> { flushPara(); flushItem(); item.append(t) }
                item.isNotEmpty() && spec.joinLines && !END.containsMatchIn(item) -> item.append(" ").append(t)
                item.isNotEmpty() -> { flushItem(); para.append(t) }
                para.isEmpty() -> para.append(t)
                spec.joinLines && !END.containsMatchIn(para) -> para.append(" ").append(t)
                spec.joinLines -> { flushPara(); para.append(t) }
                else -> para.append("\n").append(t)
            }
        }
        flushPara(); flushItem()
        return out
    }
}

/** Real-text PDF writer. Fonts come from the phone, so Hindi (Devanagari) renders without boxes. */
object DocPdf {
    fun render(spec: DocSpec, out: File) {
        val blocks = DocFormatter.blocks(spec)
        val pw = spec.page.wPt; val ph = spec.page.hPt; val m = spec.margin
        val cw = pw - 2 * m; val chh = (ph - 2 * m).toFloat()
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            p.canvas.drawColor(Color.WHITE)
            page = p; canvas = p.canvas; y = 0f
        }
        fun draw(layout: StaticLayout, from: Int, to: Int) {
            val top = layout.getLineTop(from).toFloat(); val bottom = layout.getLineBottom(to - 1).toFloat()
            val c = canvas!!
            c.save(); c.translate(m.toFloat(), m + y - top); c.clipRect(0f, top, cw.toFloat(), bottom); layout.draw(c); c.restore()
            y += bottom - top
        }
        try {
            newPage()
            for (b in blocks) {
                if (b.text.isBlank()) continue
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = spec.fontSize * b.scale
                    typeface = if (b.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                }
                val sp = SpannableString(b.text)
                sp.setSpan(LeadingMarginSpan.Standard((b.left + b.first).toInt().coerceAtLeast(0), b.left.toInt()),
                    0, sp.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                val lb = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, cw)
                    .setAlignment(when (b.align) {
                        DocAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                        DocAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    })
                    .setLineSpacing(0f, spec.lineSpacing)
                if (Build.VERSION.SDK_INT >= 26 && b.align == DocAlign.JUSTIFY) lb.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                val layout = lb.build()
                if (y > 0f) y += b.before
                val h = layout.height.toFloat()
                when {
                    y + h <= chh -> { draw(layout, 0, layout.lineCount); y += b.after }
                    h <= chh -> { newPage(); draw(layout, 0, layout.lineCount); y += b.after }
                    else -> {
                        var line = 0
                        while (line < layout.lineCount) {
                            val top = layout.getLineTop(line)
                            var end = line
                            while (end < layout.lineCount && y + (layout.getLineBottom(end) - top) <= chh) end++
                            if (end == line) { if (y > 0f) { newPage(); continue } else end = line + 1 }
                            draw(layout, line, end); line = end
                            if (line < layout.lineCount) newPage()
                        }
                        y += b.after
                    }
                }
            }
            page?.let { doc.finishPage(it) }
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    fun rasterize(f: File, width: Int, maxPages: Int): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            for (i in 0 until minOf(r.pageCount, maxPages)) r.openPage(i).use { p ->
                val h = (width * p.height.toFloat() / p.width).toInt()
                val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.add(b)
            }
        }
        return out
    }
}

/** Real .docx (a zip of Word XML) that opens and edits in Word, Google Docs and WPS. */
object DocxWriter {
    private const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun para(b: Block, spec: DocSpec): String {
        val sz = (spec.fontSize * b.scale * 2).toInt()
        val rpr = "<w:rPr>" + (if (b.bold) "<w:b/><w:bCs/>" else "") + "<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr>"
        val runs = b.text.split("\n").joinToString("<w:r><w:br/></w:r>") {
            "<w:r>$rpr<w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r>"
        }
        var ind = "<w:ind w:left=\"${(b.left * 20).toInt()}\""
        if (b.first < 0) ind += " w:hanging=\"${(-b.first * 20).toInt()}\"" else if (b.first > 0) ind += " w:firstLine=\"${(b.first * 20).toInt()}\""
        ind += "/>"
        val jc = when (b.align) { DocAlign.LEFT -> "left"; DocAlign.CENTER -> "center"; DocAlign.RIGHT -> "right"; DocAlign.JUSTIFY -> "both" }
        val line = (240 * spec.lineSpacing).toInt()
        return "<w:p><w:pPr><w:keepLines/><w:spacing w:before=\"${(b.before * 20).toInt()}\" w:after=\"${(b.after * 20).toInt()}\" " +
            "w:line=\"$line\" w:lineRule=\"auto\"/>$ind<w:jc w:val=\"$jc\"/></w:pPr>$runs</w:p>"
    }

    fun write(spec: DocSpec, out: File) {
        val body = DocFormatter.blocks(spec).filter { it.text.isNotBlank() }.joinToString("") { para(it, spec) }
        val mg = spec.margin * 20
        val doc = "$HEAD<w:document $NS><w:body>$body<w:sectPr><w:pgSz w:w=\"${spec.page.wTw}\" w:h=\"${spec.page.hTw}\"/>" +
            "<w:pgMar w:top=\"$mg\" w:right=\"$mg\" w:bottom=\"$mg\" w:left=\"$mg\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val styles = "$HEAD<w:styles $NS><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Mangal\"/>" +
            "<w:sz w:val=\"${spec.fontSize * 2}\"/><w:szCs w:val=\"${spec.fontSize * 2}\"/><w:lang w:val=\"en-US\" w:bidi=\"hi-IN\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>"
        val ct = "$HEAD<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"
        val rels = "$HEAD<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val docRels = "$HEAD<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", ct)
            put("_rels/.rels", rels)
            put("word/document.xml", doc)
            put("word/styles.xml", styles)
            put("word/_rels/document.xml.rels", docRels)
        }
    }
}
