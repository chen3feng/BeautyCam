package com.chen3feng.beautycam

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin maths shared by the CPU beauty pipeline. Kept free of Android
 * types so it can be unit-tested on the JVM.
 */
internal object BeautyMath {

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Loose YCbCr skin test, used only to keep the neck/forehead extension
     *  off clothing, hair and jewellery. */
    fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val yv = 0.299f * r + 0.587f * g + 0.114f * b
        val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
        val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val sat = if (mx == 0) 0 else (mx - mn) * 255 / mx
        return yv > 45f && r - b > 6 && cr in 132f..185f &&
                cb in 72f..135f && sat < 170
    }

    /** O(n) box blur using a running prefix sum per row / column. */
    fun boxBlur(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val pref = FloatArray(max(w, h) + 1)
        for (y in 0 until h) {
            var sum = 0f
            pref[0] = 0f
            val row = y * w
            for (x in 0 until w) {
                sum += src[row + x]
                pref[x + 1] = sum
            }
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                tmp[row + x] = (pref[x1 + 1] - pref[x0]) / (x1 - x0 + 1)
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            pref[0] = 0f
            for (y in 0 until h) {
                sum += tmp[y * w + x]
                pref[y + 1] = sum
            }
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                dst[y * w + x] = (pref[y1 + 1] - pref[y0]) / (y1 - y0 + 1)
            }
        }
    }

    /** Bilinear upsample of the low-res mask for the CPU fallback. */
    fun upsampleMask(mask: FloatArray, mw: Int, mh: Int, w: Int, h: Int): FloatArray {
        if (mw == w && mh == h) return mask
        val out = FloatArray(w * h)
        val sx = mw.toFloat() / w
        val sy = mh.toFloat() / h
        for (y in 0 until h) {
            val fy = (y + 0.5f) * sy - 0.5f
            val y0 = fy.toInt().coerceIn(0, mh - 1)
            val y1 = (y0 + 1).coerceAtMost(mh - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * mw
            val row1 = y1 * mw
            val row = y * w
            for (x in 0 until w) {
                val fx = (x + 0.5f) * sx - 0.5f
                val x0 = fx.toInt().coerceIn(0, mw - 1)
                val x1 = (x0 + 1).coerceAtMost(mw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val a = mask[row0 + x0] + (mask[row0 + x1] - mask[row0 + x0]) * tx
                val b = mask[row1 + x0] + (mask[row1 + x1] - mask[row1 + x0]) * tx
                out[row + x] = a + (b - a) * ty
            }
        }
        return out
    }

    fun bilinear(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt()
        val y0 = yc.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = xc - x0
        val fy = yc - y0
        val p00 = src[y0 * w + x0]
        val p10 = src[y0 * w + x1]
        val p01 = src[y1 * w + x0]
        val p11 = src[y1 * w + x1]
        fun ch(p: Int, s: Int) = ((p ushr s) and 0xFF).toFloat()
        val r = lerp(lerp(ch(p00, 16), ch(p10, 16), fx), lerp(ch(p01, 16), ch(p11, 16), fx), fy)
        val g = lerp(lerp(ch(p00, 8), ch(p10, 8), fx), lerp(ch(p01, 8), ch(p11, 8), fx), fy)
        val b = lerp(lerp(ch(p00, 0), ch(p10, 0), fx), lerp(ch(p01, 0), ch(p11, 0), fx), fy)
        return argb(r, g, b)
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = ((a ushr 16) and 0xFF).toFloat()
        val ag = ((a ushr 8) and 0xFF).toFloat()
        val ab = (a and 0xFF).toFloat()
        val br = ((b ushr 16) and 0xFF).toFloat()
        val bg = ((b ushr 8) and 0xFF).toFloat()
        val bb = (b and 0xFF).toFloat()
        return argb(ar + (br - ar) * tt, ag + (bg - ag) * tt, ab + (bb - ab) * tt)
    }

    fun extract(pixels: IntArray, ch: FloatArray, shift: Int) {
        for (i in pixels.indices) ch[i] = ((pixels[i] ushr shift) and 0xFF).toFloat()
    }

    fun store(pixels: IntArray, ch: FloatArray, shift: Int) {
        val keep = when (shift) {
            16 -> 0xFF00FFFF.toInt()
            8 -> 0xFFFF00FF.toInt()
            else -> 0xFFFFFF00.toInt()
        }
        for (i in pixels.indices) {
            val v = ch[i].coerceIn(0f, 255f).toInt()
            pixels[i] = (pixels[i] and keep) or (v shl shift)
        }
    }

    fun luma(p: Int): Float =
        0.299f * ((p ushr 16) and 0xFF) + 0.587f * ((p ushr 8) and 0xFF) + 0.114f * (p and 0xFF)

    fun argb(r: Float, g: Float, b: Float): Int {
        val rr = r.coerceIn(0f, 255f).toInt()
        val gg = g.coerceIn(0f, 255f).toInt()
        val bb = b.coerceIn(0f, 255f).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- separable weights of the slim-face displacement field ----
    // exp(-(nx^2/1.1 + ny^2/1.4)) factors into the product of these two.

    fun slimColumnWeight(nx: Float) = exp(-(nx * nx) / 1.1f) * abs(nx)

    fun slimRowWeight(ny: Float) = exp(-(ny * ny) / 1.4f)

    /** (1 - sqrt(d))^2, the eye-enlarge falloff at squared distance d. */
    fun eyeFalloff(d2: Float): Float {
        val f = 1f - sqrt(d2.coerceIn(0f, 1f))
        return f * f
    }

    /** Downscale so the image fits [maxPixels]; null when no scaling is needed. */
    fun capSize(w: Int, h: Int, maxPixels: Long): Pair<Int, Int>? {
        val px = w.toLong() * h
        if (px <= maxPixels) return null
        val scale = sqrt(maxPixels.toDouble() / px.toDouble())
        return (w * scale).toInt() to (h * scale).toInt()
    }
}
