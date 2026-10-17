package com.scantype.documentscanner

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CustomDocumentDetectorTest {
    @Test
    fun detectsBrightPageOnUnevenDarkBackground() {
        val w = 240
        val h = 320
        val frame = syntheticFrame(w, h,
            background = { x, y -> (28 + x / 18 + y / 24).coerceIn(0, 70) },
            page = { x, y -> x in 35..204 && y in 42..276 },
            paper = { x, y -> (218 + ((x + y) % 9)).coerceAtMost(248) })

        val result = CustomDocumentDetector.detectDetailed(frame, w, h)
        assertNotNull("Bright paper should be detected", result)
        assertCornersNear(result!!.corners, w, h, 35, 42, 204, 276, 0.055f)
    }

    @Test
    fun detectsDarkPageOnBrightBackground() {
        val w = 240
        val h = 320
        val frame = syntheticFrame(w, h,
            background = { x, y -> (205 + x / 12 + y / 30).coerceAtMost(250) },
            page = { x, y -> x in 42..198 && y in 54..265 },
            paper = { x, y -> (65 + ((x + y) % 7)).coerceAtMost(78) })

        val result = CustomDocumentDetector.detectDetailed(frame, w, h)
        assertNotNull("Dark paper should be detected", result)
        assertCornersNear(result!!.corners, w, h, 42, 54, 198, 265, 0.06f)
    }

    @Test
    fun detectsPaperInDimUnevenLighting() {
        val w = 240
        val h = 320
        val frame = syntheticFrame(w, h,
            background = { x, y -> (12 + x / 18 + y / 24).coerceAtMost(35) },
            page = { x, y -> x in 38..201 && y in 48..270 },
            paper = { x, y -> (49 + ((x / 13 + y / 17) % 8)).coerceAtMost(61) })

        val result = CustomDocumentDetector.detectDetailed(frame, w, h)
        assertNotNull("Paper should still be detectable in dim lighting", result)
        assertCornersNear(result!!.corners, w, h, 38, 48, 201, 270, 0.065f)
    }

    @Test
    fun rejectsUniformSceneInsteadOfInventingPage() {
        val w = 240
        val h = 320
        val frame = IntArray(w * h) { 80 }
        assertNull("A uniform scene has no document boundary", CustomDocumentDetector.detectDetailed(frame, w, h))
    }

    @Test
    fun rejectsPageClippedByCameraFrame() {
        val w = 240
        val h = 320
        val frame = syntheticFrame(w, h,
            background = { x, y -> (35 + x / 10 + y / 16).coerceAtMost(95) },
            page = { x, y -> x in 0..150 && y in 35..285 },
            paper = { x, y -> (220 + ((x + y) % 7)).coerceAtMost(240) })

        assertNull("A page cut off by the camera frame must not be treated as a complete page",
            CustomDocumentDetector.detectDetailed(frame, w, h))
    }

    @Test
    fun returnedCornersAreFiniteAndConvex() {
        val w = 240
        val h = 320
        val frame = syntheticFrame(w, h,
            background = { x, y -> (35 + x / 10 + y / 16).coerceAtMost(95) },
            page = { x, y -> x in 28..211 && y in 35..285 },
            paper = { x, y -> (190 + ((x + y) % 10)).coerceAtMost(220) })

        val result = CustomDocumentDetector.detectDetailed(frame, w, h)
        assertNotNull(result)
        val q = result!!.corners
        assertTrue(q.all { it.isFinite() && it in 0f..1f })
        assertTrue("Confidence must be normalized", result.confidence in 0f..1f)
        assertTrue("Edge support must be normalized", result.edgeSupport in 0f..1f)
    }

    private fun syntheticFrame(
        w: Int,
        h: Int,
        background: (Int, Int) -> Int,
        page: (Int, Int) -> Boolean,
        paper: (Int, Int) -> Int
    ): IntArray = IntArray(w * h) { index ->
        val x = index % w
        val y = index / w
        (if (page(x, y)) paper(x, y) else background(x, y)).coerceIn(0, 255)
    }

    private fun assertCornersNear(
        q: FloatArray,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        tolerance: Float
    ) {
        val expected = floatArrayOf(
            left.toFloat() / (w - 1), top.toFloat() / (h - 1),
            right.toFloat() / (w - 1), top.toFloat() / (h - 1),
            right.toFloat() / (w - 1), bottom.toFloat() / (h - 1),
            left.toFloat() / (w - 1), bottom.toFloat() / (h - 1)
        )
        for (i in expected.indices) {
            assertTrue("Corner coordinate $i expected ${expected[i]} but got ${q[i]}",
                abs(expected[i] - q[i]) <= tolerance)
        }
    }
}
