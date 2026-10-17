package com.scantype.documentscanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Document edge detection, image-quality check and book splitting. Pure Kotlin, fully offline. */
object EdgeDetect {
    private class Comp {
        var area = 0; var touches = false
        val p = FloatArray(8)
        var sMin = Int.MAX_VALUE; var sMax = Int.MIN_VALUE; var dMin = Int.MAX_VALUE; var dMax = Int.MIN_VALUE
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(src.size); val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in -1..1) { val xx = x + k; if (xx in 0 until w) { s += src[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in -1..1) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            out[y * w + x] = s / n
        }
        return out
    }

    private fun components(mask: BooleanArray, w: Int, h: Int): List<Comp> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Comp>()
        for (s in 0 until w * h) {
            if (!mask[s] || seen[s]) continue
            val c = Comp()
            var sp = 0; stack[sp++] = s; seen[s] = true
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                c.area++
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) c.touches = true
                val sum = x + y; val dif = x - y
                if (sum < c.sMin) { c.sMin = sum; c.p[0] = x.toFloat(); c.p[1] = y.toFloat() }
                if (dif > c.dMax) { c.dMax = dif; c.p[2] = x.toFloat(); c.p[3] = y.toFloat() }
                if (sum > c.sMax) { c.sMax = sum; c.p[4] = x.toFloat(); c.p[5] = y.toFloat() }
                if (dif < c.dMin) { c.dMin = dif; c.p[6] = x.toFloat(); c.p[7] = y.toFloat() }
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            out.add(c)
        }
        return out
    }

    /** Corner order: TL, TR, BR, BL as 0..1 fractions, plus the covered area as the 9th value. */
    private fun toQuad(c: Comp, w: Int, h: Int): FloatArray? {
        val total = (w * h).toFloat()
        val frac = c.area / total
        if (frac < 0.12f || frac > 0.97f) return null
        val p = c.p
        var a = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
        val quad = abs(a) / 2f
        if (quad <= 0f || c.area / quad < 0.72f) return null
        val out = FloatArray(9)
        for (i in 0 until 4) { out[i * 2] = p[i * 2] / (w - 1); out[i * 2 + 1] = p[i * 2 + 1] / (h - 1) }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (abs(out[i * 2] - out[j * 2]) + abs(out[i * 2 + 1] - out[j * 2 + 1]) < 0.15f) return null
        }
        // All four turns must have the same orientation. This rejects crossed, folded,
        // or duplicate-corner quads before they can cause a bad perspective warp.
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val abx = out[j * 2] - out[i * 2]
            val aby = out[j * 2 + 1] - out[i * 2 + 1]
            val bcx = out[k * 2] - out[j * 2]
            val bcy = out[k * 2 + 1] - out[j * 2 + 1]
            val cross = abx * bcy - aby * bcx
            if (abs(cross) < 0.0005f) return null
            val currentSign = if (cross > 0f) 1 else -1
            if (sign != 0 && currentSign != sign) return null
            sign = currentSign
        }
        // Refuse nearly collapsed quadrilaterals even if the connected component was large.
        if (quad / total < 0.12f || quad / total > 0.97f) return null
        out[8] = quad / total
        return out
    }

    private fun bestQuad(cs: List<Comp>, w: Int, h: Int): FloatArray? =
        cs.sortedByDescending { it.area }.take(4).mapNotNull { toQuad(it, w, h) }.maxByOrNull { it[8] }

    private fun bright(img: IntArray, w: Int, h: Int): FloatArray? {
        val hist = IntArray(256)
        for (v in img) hist[v.coerceIn(0, 255)]++
        val total = img.size
        var sumAll = 0L
        for (i in 0 until 256) sumAll += i.toLong() * hist[i]
        var wB = 0; var sumB = 0L; var best = 0.0; var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB; val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        val mask = BooleanArray(img.size) { img[it] > thr }
        return bestQuad(components(mask, w, h), w, h)
    }

    private fun edges(img: IntArray, w: Int, h: Int): FloatArray? {
        val mag = IntArray(img.size)
        var sum = 0L; var cnt = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -img[i - w - 1] - 2 * img[i - 1] - img[i + w - 1] + img[i - w + 1] + 2 * img[i + 1] + img[i + w + 1]
            val gy = -img[i - w - 1] - 2 * img[i - w] - img[i - w + 1] + img[i + w - 1] + 2 * img[i + w] + img[i + w + 1]
            val m = abs(gx) + abs(gy); mag[i] = m; sum += m; cnt++
        }
        val mean = sum.toDouble() / max(1, cnt)
        var v = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) { val d = mag[y * w + x] - mean; v += d * d }
        val thr = max(60.0, mean + sqrt(v / max(1, cnt)))
        val edge = BooleanArray(img.size) { mag[it] > thr }
        val dil = edge.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (edge[i]) { dil[i - 1] = true; dil[i + 1] = true; dil[i - w] = true; dil[i + w] = true }
        }
        val mask = BooleanArray(img.size) { !dil[it] }
        return bestQuad(components(mask, w, h).filter { !it.touches }, w, h)
    }

    /** Finds the document corners (TL, TR, BR, BL) as 8 values in 0..1, or null when nothing reliable is found. */
    fun detect(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 40 || h < 40 || gray.size < w * h) return null
        return CustomDocumentDetector.detect(gray, w, h)
    }

    fun area(q: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1] }
        return abs(a) / 2f
    }

    fun rotate(g: IntArray, w: Int, h: Int, deg: Int): Triple<IntArray, Int, Int> = when ((deg % 360 + 360) % 360) {
        90 -> { val o = IntArray(g.size); for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = g[y * w + x]; Triple(o, h, w) }
        180 -> { val o = IntArray(g.size); for (i in g.indices) o[g.size - 1 - i] = g[i]; Triple(o, w, h) }
        270 -> { val o = IntArray(g.size); for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = g[y * w + x]; Triple(o, h, w) }
        else -> Triple(g, w, h)
    }

    private fun gray(b: Bitmap, maxSide: Int): Triple<IntArray, Int, Int> {
        val s = maxSide.toFloat() / max(b.width, b.height)
        val sm = if (s < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true) else b
        val px = IntArray(sm.width * sm.height)
        sm.getPixels(px, 0, sm.width, 0, 0, sm.width, sm.height)
        val g = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100 }
        val r = Triple(g, sm.width, sm.height)
        if (sm !== b) sm.recycle()
        return r
    }

    fun detectBitmap(b: Bitmap): FloatArray? { val (g, w, h) = gray(b, 320); return detect(g, w, h) }

    private fun save(ctx: Context, b: Bitmap, prefix: String): Uri {
        val f = File(ctx.cacheDir, "${prefix}_${System.nanoTime()}.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        b.recycle()
        return Uri.fromFile(f)
    }

    /** Crops and straightens the page when its edges are found. Returns the original uri otherwise. */
    fun autoCrop(ctx: Context, uri: Uri): Uri {
        val bmp = runCatching { ImageFilters.load(ctx, uri, 2000) }.getOrNull() ?: return uri
        val q = detectBitmap(bmp)
        if (q == null) { bmp.recycle(); return uri }
        val out = ImageFilters.warp(bmp, q)
        bmp.recycle()
        return save(ctx, out, "auto")
    }

    /** A soft hint (never blocks the user) when the picture is dark, overexposed or blurry. */
    fun quality(b: Bitmap): String? {
        val (g, w, h) = gray(b, 400)
        var sum = 0L; var over = 0
        for (v in g) { sum += v; if (v >= 250) over++ }
        val mean = sum.toDouble() / g.size
        if (mean < 50) return "This image is very dark. Retake it in better light."
        if (over.toFloat() / g.size > 0.45f) return "This image is overexposed. Retake it without glare."
        var s = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            s += l; sq += l * l; n++
        }
        val variance = sq / max(1, n) - (s / max(1, n)) * (s / max(1, n))
        var m2 = 0.0
        for (v in g) m2 += (v - mean) * (v - mean)
        val std = sqrt(m2 / g.size)
        if (std > 20 && variance < 25) return "This image looks blurry. Hold steady and retake it."
        return null
    }

    /** Splits a photo of an open book at the dark gutter into two pages. Portrait photos are left alone. */
    fun splitBook(ctx: Context, uri: Uri): List<Uri> {
        val b = ImageFilters.load(ctx, uri, 2000)
        if (b.width < b.height * 1.1f) { b.recycle(); return listOf(uri) }
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val lo = (w * 0.4f).toInt(); val hi = (w * 0.6f).toInt()
        val cols = FloatArray(w)
        for (x in lo until hi) {
            var s = 0L; var y = 0
            while (y < h) { val c = px[y * w + x]; s += ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100; y += 4 }
            cols[x] = s.toFloat() / (h / 4)
        }
        var best = (lo + hi) / 2; var bv = Float.MAX_VALUE
        for (x in lo + 3 until hi - 3) {
            var s = 0f
            for (k in -3..3) s += cols[x + k]
            if (s < bv) { bv = s; best = x }
        }
        val l = Bitmap.createBitmap(b, 0, 0, best, h)
        val r = Bitmap.createBitmap(b, best, 0, w - best, h)
        b.recycle()
        return listOf(save(ctx, l, "book_l"), save(ctx, r, "book_r"))
    }
}
