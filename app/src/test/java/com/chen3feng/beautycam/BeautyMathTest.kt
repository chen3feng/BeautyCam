package com.chen3feng.beautycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class BeautyMathTest {

    // ------------------------------------------------------------- smoothstep

    @Test
    fun smoothstep_clampsOutsideRange() {
        assertEquals(0f, BeautyMath.smoothstep(0f, 1f, -3f), 0f)
        assertEquals(1f, BeautyMath.smoothstep(0f, 1f, 3f), 0f)
    }

    @Test
    fun smoothstep_isSymmetricAroundMidpoint() {
        assertEquals(0f, BeautyMath.smoothstep(0f, 1f, 0f), 1e-6f)
        assertEquals(1f, BeautyMath.smoothstep(0f, 1f, 1f), 1e-6f)
        assertEquals(0.5f, BeautyMath.smoothstep(0f, 1f, 0.5f), 1e-6f)
        val a = BeautyMath.smoothstep(0f, 1f, 0.25f)
        val b = BeautyMath.smoothstep(0f, 1f, 0.75f)
        assertEquals(1f, a + b, 1e-6f)
    }

    // ----------------------------------------------------------------- isSkin

    @Test
    fun isSkin_acceptsTypicalSkinTones() {
        assertTrue(BeautyMath.isSkin(200, 160, 130))
        assertTrue(BeautyMath.isSkin(150, 110, 85))
    }

    @Test
    fun isSkin_rejectsNonSkinColours() {
        assertFalse("white", BeautyMath.isSkin(255, 255, 255))
        assertFalse("black", BeautyMath.isSkin(10, 10, 10))
        assertFalse("blue", BeautyMath.isSkin(30, 60, 200))
        assertFalse("green", BeautyMath.isSkin(60, 140, 60))
        assertFalse("red cloth", BeautyMath.isSkin(220, 30, 40))
    }

    // ---------------------------------------------------------------- boxBlur

    @Test
    fun boxBlur_preservesConstantField() {
        val w = 9
        val h = 7
        val src = FloatArray(w * h) { 5f }
        val dst = FloatArray(w * h)
        BeautyMath.boxBlur(src, dst, w, h, 3, FloatArray(w * h))
        for (v in dst) assertEquals(5f, v, 1e-6f)
    }

    @Test
    fun boxBlur_matchesNaiveMeanIncludingBorders() {
        val w = 7
        val h = 5
        val r = 2
        val src = FloatArray(w * h) { ((it * 37) % 101).toFloat() }
        val dst = FloatArray(w * h)
        BeautyMath.boxBlur(src, dst, w, h, r, FloatArray(w * h))

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (yy in (y - r).coerceAtLeast(0)..(y + r).coerceAtMost(h - 1)) {
                    for (xx in (x - r).coerceAtLeast(0)..(x + r).coerceAtMost(w - 1)) {
                        sum += src[yy * w + xx]
                        count++
                    }
                }
                // Prefix sums are float, so allow a little cancellation error.
                assertEquals("at ($x,$y)", sum / count, dst[y * w + x], 1e-3f)
            }
        }
    }

    @Test
    fun boxBlur_spreadsAnImpulseEvenly() {
        val w = 11
        val h = 11
        val r = 2
        val src = FloatArray(w * h)
        src[5 * w + 5] = 1f
        val dst = FloatArray(w * h)
        BeautyMath.boxBlur(src, dst, w, h, r, FloatArray(w * h))
        assertEquals(1f / 25f, dst[5 * w + 5], 1e-6f)
        // Energy is conserved away from the borders.
        assertEquals(1f, dst.sum(), 1e-4f)
    }

    // ------------------------------------------------------------ upsampleMask

    @Test
    fun upsampleMask_sameSizeReturnsInput() {
        val mask = FloatArray(16) { it / 16f }
        assertSame(mask, BeautyMath.upsampleMask(mask, 4, 4, 4, 4))
    }

    @Test
    fun upsampleMask_keepsConstantFieldConstant() {
        val mask = FloatArray(4) { 0.7f }
        val out = BeautyMath.upsampleMask(mask, 2, 2, 8, 8)
        for (v in out) assertEquals(0.7f, v, 1e-6f)
    }

    @Test
    fun upsampleMask_interpolatesLinearly() {
        val out = BeautyMath.upsampleMask(floatArrayOf(0f, 1f), 2, 1, 4, 1)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.25f, out[1], 1e-6f)
        assertEquals(0.75f, out[2], 1e-6f)
        assertEquals(1f, out[3], 1e-6f)
    }

    // ---------------------------------------------------------------- bilinear

    @Test
    fun bilinear_hitsCornersExactly() {
        val px = intArrayOf(
            argb(255, 0, 0), argb(0, 255, 0),
            argb(0, 0, 255), argb(255, 255, 255)
        )
        assertEquals(px[0], BeautyMath.bilinear(px, 2, 2, 0f, 0f))
        assertEquals(px[1], BeautyMath.bilinear(px, 2, 2, 1f, 0f))
        assertEquals(px[2], BeautyMath.bilinear(px, 2, 2, 0f, 1f))
        assertEquals(px[3], BeautyMath.bilinear(px, 2, 2, 1f, 1f))
    }

    @Test
    fun bilinear_averagesTheFourNeighboursAtTheCentre() {
        val px = intArrayOf(
            argb(255, 0, 0), argb(0, 255, 0),
            argb(0, 0, 255), argb(255, 255, 255)
        )
        val c = BeautyMath.bilinear(px, 2, 2, 0.5f, 0.5f)
        assertEquals(127, red(c))
        assertEquals(127, green(c))
        assertEquals(127, blue(c))
    }

    @Test
    fun bilinear_clampsOutOfBoundsSamples() {
        val px = intArrayOf(argb(10, 20, 30), argb(40, 50, 60))
        assertEquals(px[0], BeautyMath.bilinear(px, 2, 1, -100f, -100f))
        assertEquals(px[1], BeautyMath.bilinear(px, 2, 1, 100f, 100f))
    }

    // ------------------------------------------------------------- blend/argb

    @Test
    fun blend_endpointsAndMidpoint() {
        val a = argb(0, 0, 0)
        val b = argb(200, 100, 50)
        assertEquals(a, BeautyMath.blend(a, b, 0f))
        assertEquals(b, BeautyMath.blend(a, b, 1f))
        val mid = BeautyMath.blend(a, b, 0.5f)
        assertEquals(100, red(mid))
        assertEquals(50, green(mid))
        assertEquals(25, blue(mid))
    }

    @Test
    fun blend_clampsWeight() {
        val a = argb(10, 20, 30)
        val b = argb(40, 50, 60)
        assertEquals(a, BeautyMath.blend(a, b, -1f))
        assertEquals(b, BeautyMath.blend(a, b, 2f))
    }

    @Test
    fun argb_clampsToByteRange() {
        assertEquals(0xFF000000.toInt(), BeautyMath.argb(-5f, -1f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), BeautyMath.argb(300f, 999f, 256f))
    }

    // --------------------------------------------------------- extract / store

    @Test
    fun extractStore_roundTripsEachChannel() {
        val original = intArrayOf(
            argb(1, 2, 3), argb(200, 100, 50), argb(255, 0, 128)
        )
        for (shift in intArrayOf(16, 8, 0)) {
            val pixels = original.copyOf()
            val ch = FloatArray(pixels.size)
            BeautyMath.extract(pixels, ch, shift)
            assertEquals((original[1] ushr shift and 0xFF).toFloat(), ch[1], 0f)
            BeautyMath.store(pixels, ch, shift)
            for (i in pixels.indices) assertEquals("shift $shift, index $i", original[i], pixels[i])
        }
    }

    @Test
    fun store_clampsChannelValues() {
        val pixels = intArrayOf(0xFF000000.toInt())
        val ch = floatArrayOf(999f)
        BeautyMath.store(pixels, ch, 8)
        assertEquals(0xFF00FF00.toInt(), pixels[0])
    }

    // ------------------------------------------------------------------- luma

    @Test
    fun luma_matchesRec601Weights() {
        assertEquals(0f, BeautyMath.luma(argb(0, 0, 0)), 1e-6f)
        assertEquals(255f, BeautyMath.luma(argb(255, 255, 255)), 1e-4f)
        assertEquals(0.587f * 255f, BeautyMath.luma(argb(0, 255, 0)), 1e-4f)
    }

    // ---------------------------------------------------- warp weight tables

    @Test
    fun slimWeights_factorIntoTheSeparableGaussian() {
        for (nx in floatArrayOf(0f, 0.3f, -0.7f, 1.4f)) {
            for (ny in floatArrayOf(0f, 0.2f, -0.9f, 1.7f)) {
                val expected = exp(-(nx * nx / 1.1f + ny * ny / 1.4f)) * abs(nx)
                val actual = BeautyMath.slimColumnWeight(nx) * BeautyMath.slimRowWeight(ny)
                assertEquals("nx=$nx ny=$ny", expected, actual, 1e-6f)
            }
        }
    }

    @Test
    fun slimRowWeight_peaksOnTheFaceCentreLine() {
        assertEquals(1f, BeautyMath.slimRowWeight(0f), 1e-6f)
        assertTrue(BeautyMath.slimRowWeight(0.5f) < BeautyMath.slimRowWeight(0.4f))
        assertEquals(0f, BeautyMath.slimColumnWeight(0f), 0f)
    }

    @Test
    fun eyeFalloff_isOneAtCentreAndZeroAtEdge() {
        assertEquals(1f, BeautyMath.eyeFalloff(0f), 1e-6f)
        assertEquals(0.25f, BeautyMath.eyeFalloff(0.25f), 1e-6f)
        assertEquals(0f, BeautyMath.eyeFalloff(1f), 1e-6f)
        assertEquals(0f, BeautyMath.eyeFalloff(2f), 0f)
    }

    // ----------------------------------------------------------------- capSize

    @Test
    fun capSize_leavesSmallImagesAlone() {
        assertNull(BeautyMath.capSize(1000, 1000, 6_000_000))
        assertNull(BeautyMath.capSize(3000, 2000, 6_000_000))
    }

    @Test
    fun capSize_scalesLargeImagesUnderThePixelBudget() {
        val (w, h) = BeautyMath.capSize(4000, 3000, 6_000_000)!!
        assertTrue(w.toLong() * h <= 6_000_000)
        assertEquals(4000.0 / 3000.0, w.toDouble() / h, 0.01)
    }

    // ---------------------------------------------------------------- settings

    @Test
    fun settings_defaultsMatchTheDocumentedSliders() {
        val s = BeautySettings()
        assertTrue(s.enabled)
        assertEquals(0.50f, s.smooth, 0f)
        assertEquals(1.00f, s.whiten, 0f)
        assertEquals(0.00f, s.slim, 0f)
        assertEquals(0.50f, s.eyes, 0f)
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p ushr 16) and 0xFF
    private fun green(p: Int) = (p ushr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
}
