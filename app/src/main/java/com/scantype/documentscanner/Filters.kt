package com.scantype.documentscanner

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class PageFilter(val label: String) {
    ORIGINAL("Original"), ENHANCE("Enhance"), LIGHTEN("Lighten"), NO_SHADOW("No Shadow"), OMNIFIX("OmniFix"), BW("B&W")
}

object ImageFilters {
    fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }!!
        val deg = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        if (deg != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            bmp.recycle(); bmp = rotated
        }
        val sc = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (sc >= 1f) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    fun rotate(src: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Returns a filtered bitmap. The source bitmap is recycled when a new one is produced. */
    fun apply(src: Bitmap, f: PageFilter): Bitmap {
        val out = when (f) {
            PageFilter.ORIGINAL -> return src
            PageFilter.ENHANCE -> colorFilter(src, 1.28f, 9f, 1.0f)
            PageFilter.LIGHTEN -> colorFilter(src, 1.08f, 24f, 1.0f)
            PageFilter.NO_SHADOW -> normalize(src, false)
            PageFilter.OMNIFIX -> omniFix(src)
            PageFilter.BW -> normalize(src, true)
        }
        if (out !== src) src.recycle()
        return out
    }

    private fun colorFilter(src: Bitmap, contrast: Float, brightness: Float, sat: Float): Bitmap {
        val cm = ColorMatrix().apply { setSaturation(sat) }
        val t = (-0.5f * contrast + 0.5f) * 255f + brightness
        cm.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f)))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** OmniFix combines local paper normalization with a separate conservative contrast pass. */
    private fun omniFix(src: Bitmap): Bitmap {
        val normalized = normalize(src, false)
        return try {
            colorFilter(normalized, 1.16f, 4f, 0.92f)
        } finally {
            normalized.recycle()
        }
    }

    /**
     * Local paper normalization. A downsampled background estimates uneven illumination.
     * The conservative threshold helps preserve thin writing and table lines.
     */
    private fun normalize(src: Bitmap, bw: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / 16), max(1, h / 16), true)
        val bgBmp = Bitmap.createScaledBitmap(small, w, h, true)
        val p = IntArray(w * h); val b = IntArray(w * h)
        try {
            src.getPixels(p, 0, w, 0, 0, w, h)
            bgBmp.getPixels(b, 0, w, 0, 0, w, h)
        } finally {
            small.recycle()
            if (bgBmp !== small) bgBmp.recycle()
        }
        for (i in p.indices) {
            val c = p[i]; val g = b[i]
            val r = c shr 16 and 0xFF; val gr = c shr 8 and 0xFF; val bl = c and 0xFF
            val br = max(24, g shr 16 and 0xFF); val bg = max(24, g shr 8 and 0xFF); val bb = max(24, g and 0xFF)
            val lum = (r * 30 + gr * 59 + bl * 11) / 100
            val bgl = max(24, (br * 30 + bg * 59 + bb * 11) / 100)
            if (bw) {
                // Compare ink with local paper brightness; keep pale paper white.
                val localCutoff = (bgl * 0.80f).toInt().coerceIn(48, 210)
                val cutoff = (localCutoff - if (bgl > 205) 0 else 8).coerceAtLeast(35)
                p[i] = if (lum < cutoff) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            } else {
                fun channel(v: Int, bgc: Int): Int {
                    val ratio = (v * 230f / bgc).coerceIn(0f, 255f)
                    return (ratio * ratio / 255f).toInt().coerceIn(0, 255)
                }
                val nr = channel(r, br); val ng = channel(gr, bg); val nb = channel(bl, bb)
                p[i] = 0xFF shl 24 or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(p, 0, w, 0, 0, w, h) }
    }

    /** Straightens a document: [xy] holds 4 corners (TL, TR, BR, BL) as 0..1 fractions of the image. */
    fun warp(src: Bitmap, xy: FloatArray): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val s = FloatArray(8) { if (it % 2 == 0) xy[it] * w else xy[it] * h }
        fun d(a: Int, b: Int) = Math.hypot((s[a * 2] - s[b * 2]).toDouble(), (s[a * 2 + 1] - s[b * 2 + 1]).toDouble()).toFloat()
        val dw = max(d(0, 1), d(3, 2)).toInt().coerceAtLeast(50)
        val dh = max(d(0, 3), d(1, 2)).toInt().coerceAtLeast(50)
        val dst = floatArrayOf(0f, 0f, dw.toFloat(), 0f, dw.toFloat(), dh.toFloat(), 0f, dh.toFloat())
        val m = Matrix()
        m.setPolyToPoly(s, 0, dst, 0, 4)
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
