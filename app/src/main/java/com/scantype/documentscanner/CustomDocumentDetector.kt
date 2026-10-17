package com.scantype.documentscanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline document detector optimized for live low-resolution camera frames.
 *
 * It combines global threshold candidates with multi-threshold edge-enclosed regions,
 * then ranks quads using boundary contrast, area and geometric validity rather than
 * blindly selecting the largest connected component.
 */
internal object CustomDocumentDetector {
    internal data class Detection(val corners: FloatArray, val confidence: Float, val edgeSupport: Float)
    private data class Quad(val points: FloatArray, val area: Float, val support: Float, val score: Float)

    private class Component {
        var count = 0
        var touchesBorder = false
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var maxDiff = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        val corners = FloatArray(8)
    }

    fun detect(gray: IntArray, width: Int, height: Int): FloatArray? =
        detectDetailed(gray, width, height)?.corners

    fun detectDetailed(gray: IntArray, width: Int, height: Int): Detection? {
        if (width < 40 || height < 40 || gray.size < width * height) return null
        val smooth = blur3(gray, width, height)
        val gradients = gradientMagnitude(smooth, width, height)
        val candidates = ArrayList<Quad>()

        val threshold = otsu(smooth)
        collectComponents(BooleanArray(smooth.size) { smooth[it] > threshold }, width, height, smooth, gradients, candidates)
        collectComponents(BooleanArray(smooth.size) { smooth[it] <= threshold }, width, height, smooth, gradients, candidates)

        // A small set of gradient thresholds helps preserve faint page boundaries under shadows
        // without letting text texture at one threshold dominate the result.
        val histogram = IntArray(1021)
        var nonZero = 0
        for (v in gradients) if (v > 0) { histogram[v.coerceAtMost(1020)]++; nonZero++ }
        val p60 = percentile(histogram, nonZero, 0.60)
        val p75 = percentile(histogram, nonZero, 0.75)
        val p88 = percentile(histogram, nonZero, 0.88)
        val thresholds = intArrayOf(max(28, p60), max(42, p75), max(58, p88)).distinct()
        for (thresholdValue in thresholds) {
            val edges = BooleanArray(gradients.size) { gradients[it] >= thresholdValue }
            val closed = dilate(edges, width, height, 2)
            val interior = BooleanArray(closed.size) { !closed[it] }
            collectComponents(interior, width, height, smooth, gradients, candidates, ignoreBorder = true)
        }

        if (candidates.isEmpty()) return null

        // Detections from different threshold paths often describe the same page.
        // Cluster near-identical candidates and prefer support across all four sides.
        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val near = ranked.filter { it !== best && overlapCorners(it.points, best.points) < 0.08f }
            .take(3)
        val selected = if (near.isEmpty()) best else {
            // A near duplicate may be marginally more accurate on a different side;
            // use the score-weighted consensus only when all corners agree closely.
            val pool = (listOf(best) + near).filter { overlapCorners(it.points, best.points) < 0.035f }
            if (pool.size < 2) best else {
                val weights = pool.map { max(0.01f, it.score) }
                val total = weights.sum()
                val q = FloatArray(8) { i -> pool.indices.sumOf { j -> (pool[j].points[i] * weights[j]).toDouble() }.toFloat() / total }
                val area = polygonArea(q)
                val support = boundarySupport(q, smooth, gradients, width, height)
                Quad(q, area, support, qualityScore(area, support))
            }
        }
        return Detection(selected.points.copyOf(), selected.score.coerceIn(0f, 1f), selected.support.coerceIn(0f, 1f))
    }

    private fun collectComponents(
        mask: BooleanArray,
        w: Int,
        h: Int,
        gray: IntArray,
        gradients: IntArray,
        out: MutableList<Quad>,
        ignoreBorder: Boolean = false
    ) {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val total = mask.size.toFloat()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val c = Component()
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                c.count++
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) c.touchesBorder = true
                val sum = x + y
                val diff = x - y
                if (sum < c.minSum) { c.minSum = sum; c.corners[0] = x.toFloat(); c.corners[1] = y.toFloat() }
                if (diff > c.maxDiff) { c.maxDiff = diff; c.corners[2] = x.toFloat(); c.corners[3] = y.toFloat() }
                if (sum > c.maxSum) { c.maxSum = sum; c.corners[4] = x.toFloat(); c.corners[5] = y.toFloat() }
                if (diff < c.minDiff) { c.minDiff = diff; c.corners[6] = x.toFloat(); c.corners[7] = y.toFloat() }
                if (x > 0 && mask[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; stack[sp++] = idx - 1 }
                if (x + 1 < w && mask[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; stack[sp++] = idx + 1 }
                if (y > 0 && mask[idx - w] && !seen[idx - w]) { seen[idx - w] = true; stack[sp++] = idx - w }
                if (y + 1 < h && mask[idx + w] && !seen[idx + w]) { seen[idx + w] = true; stack[sp++] = idx + w }
            }

            val fraction = c.count / total
            if (fraction < 0.05f || fraction > 0.985f || c.touchesBorder) continue
            val q = FloatArray(8) { i ->
                val axis = if (i % 2 == 0) w - 1 else h - 1
                c.corners[i] / max(1, axis).toFloat()
            }
            if (!validQuad(q)) continue
            val area = polygonArea(q)
            if (area < 0.06f || area > 0.985f) continue
            // Reject components whose filled region is implausibly different from its four-corner polygon.
            val polygonPixels = max(1f, area * total)
            val fillRatio = c.count / polygonPixels
            if (fillRatio < 0.45f || fillRatio > 2.0f) continue
            val support = boundarySupport(q, gray, gradients, w, h)
            val score = qualityScore(area, support)
            out += Quad(q, area, support, score)
        }
    }

    private fun validQuad(q: FloatArray): Boolean {
        if (q.size < 8 || q.any { !it.isFinite() || it < -0.03f || it > 1.03f }) return false
        var sign = 0
        var twiceArea = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val ax = q[j * 2] - q[i * 2]
            val ay = q[j * 2 + 1] - q[i * 2 + 1]
            val bx = q[k * 2] - q[j * 2]
            val by = q[k * 2 + 1] - q[j * 2 + 1]
            val cross = ax * by - ay * bx
            if (abs(cross) < 0.0003f) return false
            val s = if (cross > 0f) 1 else -1
            if (sign != 0 && sign != s) return false
            sign = s
            twiceArea += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
            if (abs(ax) + abs(ay) < 0.08f) return false
        }
        return abs(twiceArea) * 0.5f > 0.10f
    }

    private fun boundarySupport(q: FloatArray, gray: IntArray, gradient: IntArray, w: Int, h: Int): Float {
        var supported = 0
        var samples = 0
        for (side in 0..3) {
            val next = (side + 1) % 4
            val x1 = q[side * 2] * (w - 1)
            val y1 = q[side * 2 + 1] * (h - 1)
            val x2 = q[next * 2] * (w - 1)
            val y2 = q[next * 2 + 1] * (h - 1)
            val length = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
            val n = max(8, (length / 2.5f).toInt()).coerceAtMost(48)
            for (s in 2 until n - 2) {
                val t = s.toFloat() / n
                val x = (x1 + (x2 - x1) * t).toInt().coerceIn(1, w - 2)
                val y = (y1 + (y2 - y1) * t).toInt().coerceIn(1, h - 2)
                val idx = y * w + x
                val gx = x2 - x1
                val gy = y2 - y1
                val norm = max(1f, sqrt(gx * gx + gy * gy))
                val nx = (-gy / norm * 2.2f).toInt()
                val ny = (gx / norm * 2.2f).toInt()
                val outside = gray[(y + ny).coerceIn(0, h - 1) * w + (x + nx).coerceIn(0, w - 1)]
                val inside = gray[(y - ny).coerceIn(0, h - 1) * w + (x - nx).coerceIn(0, w - 1)]
                val contrast = abs(outside - inside)
                if (gradient[idx] >= 38 || contrast >= 12) supported++
                samples++
            }
        }
        return if (samples == 0) 0f else supported.toFloat() / samples
    }

    private fun qualityScore(area: Float, support: Float): Float {
        val areaScore = sqrt(area.coerceIn(0f, 1f))
        val edgeScore = support.coerceIn(0f, 1f)
        val framePenalty = when {
            area > 0.92f -> 0.18f
            area < 0.14f -> 0.12f
            else -> 0f
        }
        return (0.68f * edgeScore + 0.32f * areaScore - framePenalty).coerceAtLeast(0f)
    }

    private fun overlapCorners(a: FloatArray, b: FloatArray): Float =
        (0 until 8).map { abs(a[it] - b[it]) }.average().toFloat()

    private fun polygonArea(q: FloatArray): Float {
        var a = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(a) / 2f
    }

    private fun blur3(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                sum += src[yy * w + xx].coerceIn(0, 255)
                n++
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    private fun gradientMagnitude(gray: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(gray.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
            val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
            out[i] = (abs(gx) + abs(gy)) / 4
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, iterations: Int): BooleanArray {
        var current = src
        repeat(iterations) {
            val next = current.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (current[i] || current[i - 1] || current[i + 1] || current[i - w] || current[i + w] ||
                    current[i - w - 1] || current[i - w + 1] || current[i + w - 1] || current[i + w + 1]) next[i] = true
            }
            current = next
        }
        return current
    }

    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size
        var sumAll = 0L
        for (i in hist.indices) sumAll += i.toLong() * hist[i]
        var weightBack = 0
        var sumBack = 0L
        var bestVariance = -1.0
        var threshold = 128
        for (t in hist.indices) {
            weightBack += hist[t]
            if (weightBack == 0) continue
            val weightFront = total - weightBack
            if (weightFront == 0) break
            sumBack += t.toLong() * hist[t]
            val meanBack = sumBack.toDouble() / weightBack
            val meanFront = (sumAll - sumBack).toDouble() / weightFront
            val delta = meanBack - meanFront
            val between = weightBack.toDouble() * weightFront * delta * delta
            if (between > bestVariance) { bestVariance = between; threshold = t }
        }
        return threshold
    }

    private fun percentile(histogram: IntArray, count: Int, percentile: Double): Int {
        if (count <= 0) return 48
        val target = (count * percentile).toInt().coerceAtLeast(1)
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i
        }
        return 48
    }
}
