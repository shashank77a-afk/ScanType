package com.scantype.documentscanner

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

enum class PageFilter(val label: String) {
    ORIGINAL("Original"), ENHANCE("Enhance"), NO_SHADOW("No Shadow"), GRAY("Grayscale"), BW("B&W")
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

    /** Returns a filtered bitmap. The source bitmap is recycled when a new one is produced. */
    fun apply(src: Bitmap, f: PageFilter): Bitmap {
        val out = when (f) {
            PageFilter.ORIGINAL -> return src
            PageFilter.ENHANCE -> colorFilter(src, 1.25f, 10f, 1.1f)
            PageFilter.GRAY -> colorFilter(src, 1.15f, 5f, 0f)
            PageFilter.NO_SHADOW -> normalize(src, false)
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

    /** Shadow removal: divides each pixel by the blurred local background (paper) brightness. */
    private fun normalize(src: Bitmap, bw: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, max(1, w / 12), max(1, h / 12), true)
        val bgBmp = Bitmap.createScaledBitmap(small, w, h, true)
        val p = IntArray(w * h); val b = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h); bgBmp.getPixels(b, 0, w, 0, 0, w, h)
        small.recycle(); if (bgBmp !== small) bgBmp.recycle()
        for (i in p.indices) {
            val c = p[i]; val g = b[i]
            val r = c shr 16 and 0xFF; val gr = c shr 8 and 0xFF; val bl = c and 0xFF
            val br = max(1, g shr 16 and 0xFF); val bg = max(1, g shr 8 and 0xFF); val bb = max(1, g and 0xFF)
            if (bw) {
                val lum = (r * 30 + gr * 59 + bl * 11) / 100
                val bgl = max(1, (br * 30 + bg * 59 + bb * 11) / 100)
                p[i] = if (lum * 100 < bgl * 82) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            } else {
                val nr = min(255, r * 255 / br); val ng = min(255, gr * 255 / bg); val nb = min(255, bl * 255 / bb)
                p[i] = 0xFF shl 24 or (nr * nr / 255 shl 16) or (ng * ng / 255 shl 8) or (nb * nb / 255)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(p, 0, w, 0, 0, w, h) }
    }
}
