package com.scantype.documentscanner

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The seven independent document looks exposed by Scanner.ai. */
enum class PageFilter(val label: String) {
    ORIGINAL("Original"),
    ENHANCE("Enhance"),
    MAGIC_COLOR("Magic Color"),
    LIGHTEN("Lighten"),
    BW("B&W"),
    GREY("Grey"),
    SHARPEN("Sharpen")
}

object ImageFilters {
    fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw IllegalArgumentException("Cannot open image: $uri")
        require(o.outWidth > 0 && o.outHeight > 0) { "Invalid image dimensions" }
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: throw IllegalArgumentException("Cannot decode image: $uri")
        val deg = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (deg != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val sc = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (sc >= 1f) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * sc).toInt()), max(1, (bmp.height * sc).toInt()), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    fun rotate(src: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }

    /**
     * Applies one filter to the supplied bitmap. A newly-created output owns its pixels;
     * the input is recycled when the operation returns a different bitmap.
     */
    fun apply(src: Bitmap, f: PageFilter): Bitmap {
        val out = when (f) {
            PageFilter.ORIGINAL -> return src
            PageFilter.ENHANCE -> enhance(src)
            PageFilter.MAGIC_COLOR -> normalizePaper(src, preserveColor = true, strength = 0.78f)
            PageFilter.LIGHTEN -> lighten(src)
            PageFilter.BW -> blackAndWhite(src)
            PageFilter.GREY -> grayscale(src, normalizePaper = true)
            PageFilter.SHARPEN -> sharpen(src)
        }
        if (out !== src && !src.isRecycled) src.recycle()
        return out
    }

    private fun colorFilter(src: Bitmap, contrast: Float, brightness: Float, saturation: Float = 1f): Bitmap {
        val cm = ColorMatrix().apply { setSaturation(saturation) }
        val shift = (0.5f - 0.5f * contrast) * 255f + brightness
        cm.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, shift,
            0f, contrast, 0f, 0f, shift,
            0f, 0f, contrast, 0f, shift,
            0f, 0f, 0f, 1f, 0f)))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    /** Paper correction first, with moderate global contrast to avoid crushing thin Devanagari strokes. */
    private fun enhance(src: Bitmap): Bitmap {
        val normalized = normalizePaper(src, preserveColor = true, strength = 0.60f)
        return try { colorFilter(normalized, 1.13f, 3f, 1.0f) } finally { normalized.recycle() }
    }

    /** Brightens low-light pages with a gentle gamma curve rather than a large uniform white shift. */
    private fun lighten(src: Bitmap): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        val lut = IntArray(256) { i -> (255.0 * (i / 255.0).pow(0.82)).toInt().coerceIn(0, 255) }
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = lut[(c ushr 16) and 255]
            val g = lut[(c ushr 8) and 255]
            val b = lut[c and 255]
            pixels[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        }
    }

    /**
     * Estimates uneven paper illumination from a reduced image and gently normalizes RGB.
     * strength controls background removal without flattening stamps, photos or colored ink.
     */
    private fun normalizePaper(src: Bitmap, preserveColor: Boolean, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / 24), max(1, h / 24), true)
        val background = Bitmap.createScaledBitmap(small, w, h, true)
        val pixels = IntArray(w * h)
        val bgPixels = IntArray(w * h)
        try {
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            background.getPixels(bgPixels, 0, w, 0, 0, w, h)
        } finally {
            if (!small.isRecycled) small.recycle()
            if (background !== small && !background.isRecycled) background.recycle()
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val bgc = bgPixels[i]
            val r = (c ushr 16) and 255
            val g = (c ushr 8) and 255
            val b = c and 255
            val br = max(32, (bgc ushr 16) and 255)
            val bg = max(32, (bgc ushr 8) and 255)
            val bb = max(32, bgc and 255)
            fun corrected(v: Int, back: Int): Int {
                // Divide out part of the local lighting gradient, then ease toward the original.
                val normalized = (v * 232f / back).coerceIn(0f, 255f)
                val softened = (normalized * normalized / 255f).coerceIn(0f, 255f)
                return (v * (1f - strength) + softened * strength).toInt().coerceIn(0, 255)
            }
            var nr = corrected(r, br)
            var ng = corrected(g, bg)
            var nb = corrected(b, bb)
            if (!preserveColor) {
                val lum = (nr * 30 + ng * 59 + nb * 11) / 100
                nr = lum; ng = lum; nb = lum
            }
            pixels[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Adaptive local threshold: compensates for shadows while retaining thin and pale writing. */
    private fun blackAndWhite(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / 24), max(1, h / 24), true)
        val bgBmp = Bitmap.createScaledBitmap(small, w, h, true)
        val p = IntArray(w * h); val b = IntArray(w * h)
        try { src.getPixels(p, 0, w, 0, 0, w, h); bgBmp.getPixels(b, 0, w, 0, 0, w, h) }
        finally {
            if (!small.isRecycled) small.recycle()
            if (bgBmp !== small && !bgBmp.isRecycled) bgBmp.recycle()
        }
        for (i in p.indices) {
            val c = p[i]; val g = b[i]
            val lum = (((c ushr 16) and 255) * 30 + ((c ushr 8) and 255) * 59 + (c and 255) * 11) / 100
            val bgLum = max(32, (((g ushr 16) and 255) * 30 + ((g ushr 8) and 255) * 59 + (g and 255) * 11) / 100)
            // A small absolute floor protects faint pencil marks on already-dark pages.
            val threshold = (bgLum * 0.78f).toInt().coerceIn(42, 218)
            p[i] = if (lum < threshold) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(p, 0, w, 0, 0, w, h) }
    }

    private fun grayscale(src: Bitmap, normalize: Boolean): Bitmap {
        val base = if (normalize) normalizePaper(src, preserveColor = false, strength = 0.40f) else src.copy(Bitmap.Config.ARGB_8888, false)
        val p = IntArray(base.width * base.height)
        base.getPixels(p, 0, base.width, 0, 0, base.width, base.height)
        for (i in p.indices) {
            val c = p[i]
            val y = (((c ushr 16) and 255) * 30 + ((c ushr 8) and 255) * 59 + (c and 255) * 11) / 100
            p[i] = (c and -0x1000000) or (y shl 16) or (y shl 8) or y
        }
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        out.setPixels(p, 0, base.width, 0, 0, base.width, base.height)
        if (base !== src && !base.isRecycled) base.recycle()
        return out
    }

    /** Conservative unsharp mask using neighboring pixels; avoids harsh halos on page edges. */
    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w < 3 || h < 3) return src.copy(Bitmap.Config.ARGB_8888, false)
        val input = IntArray(w * h)
        src.getPixels(input, 0, w, 0, 0, w, h)
        val outPixels = input.copyOf()
        val amount = 0.28f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val c = input[i]
            fun channel(shift: Int): Int {
                val center = (c ushr shift) and 255
                val blur = (((input[i - 1] ushr shift) and 255) + ((input[i + 1] ushr shift) and 255) +
                    ((input[i - w] ushr shift) and 255) + ((input[i + w] ushr shift) and 255)) / 4f
                return (center + (center - blur) * amount).toInt().coerceIn(0, 255)
            }
            outPixels[i] = (c and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(outPixels, 0, w, 0, 0, w, h) }
    }

    /** Straightens a document: [xy] holds 4 corners (TL, TR, BR, BL) as 0..1 fractions of the image. */
    fun warp(src: Bitmap, xy: FloatArray): Bitmap {
        require(xy.size >= 8) { "Four corners are required" }
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val s = FloatArray(8) { if (it % 2 == 0) xy[it] * (w - 1f) else xy[it] * (h - 1f) }
        fun d(a: Int, b: Int) = Math.hypot((s[a * 2] - s[b * 2]).toDouble(), (s[a * 2 + 1] - s[b * 2 + 1]).toDouble()).toFloat()
        val dw = max(d(0, 1), d(3, 2)).toInt().coerceAtLeast(50)
        val dh = max(d(0, 3), d(1, 2)).toInt().coerceAtLeast(50)
        val dst = floatArrayOf(0f, 0f, dw.toFloat(), 0f, dw.toFloat(), dh.toFloat(), 0f, dh.toFloat())
        val m = Matrix()
        val ok = m.setPolyToPoly(s, 0, dst, 0, 4)
        if (!ok) return src.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}

object IdCard {
    /** Puts the ID sides on one A4 page at about real size, ready to print. */
    fun sheet(bitmaps: List<Bitmap>): Bitmap {
        val pw = 1240; val ph = 1754
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(page); c.drawColor(Color.WHITE)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.LTGRAY }
        var y = 220f
        for (b in bitmaps) {
            val tw = 520f; val th = tw * b.height / b.width
            val left = (pw - tw) / 2
            c.drawBitmap(b, null, RectF(left, y, left + tw, y + th), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(left, y, left + tw, y + th, border)
            y += th + 120f
            b.recycle()
        }
        return page
    }
}
