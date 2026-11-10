package com.chen3feng.beautycam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A Xiaomi-TrueSight-like beauty pipeline that runs entirely on-device.
 *
 * Stages (mirrors what MiuiCamera's beauty node does):
 *   1. ML Kit face + 5 landmarks + contours
 *   2. skin mask = face contour minus eyes / brows / lips, feathered
 *   3. optional landmark-driven warp (slim face, enlarge eyes)
 *   4. two-scale guided-filter frequency separation (pores + wrinkles)
 *   5. skin tone: shadow lift, whitening, less red, warmer b, desaturation
 *   6. soft-focus blend inside skin
 *   7. eye brightening, lip warmth
 *   8. global midtone lift / warm white balance / highlight roll-off
 */
class BeautyProcessor(context: Context) {

    companion object {
        private const val TAG = "BeautyProcessor"
        // 6 MP keeps the float working set around 250 MB even with largeHeap.
        private const val MAX_PIXELS = 6_000_000
        // The skin mask is smooth and feathered, so it is built at a quarter
        // of the resolution and upsampled by the shaders' bilinear sampling.
        private const val MASK_SCALE = 0.25f
        // Detect on a small copy: ML Kit's cost scales with input pixels, and
        // 512 px is enough for contours. Coordinates are scaled back up.
        private const val DETECT_MAX_SIDE = 512
    }

    private val gpuFilter = GpuGuidedFilter()
    // 0 = not tried, 1 = usable, -1 = unsupported / failed (stick to CPU)
    private var gpuState = 0

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
    )

    fun close() = detector.close()

    /** Must run on the same thread that calls [process] (the EGL context is
     *  current there). */
    fun releaseGpu() = gpuFilter.release()

    fun process(src: Bitmap, s: BeautySettings): Bitmap {
        val t0 = System.currentTimeMillis()
        val bitmap = capResolution(src)
        if (!s.enabled) {
            Log.i(
                TAG,
                "beauty disabled, ${bitmap.width}x${bitmap.height} " +
                        "in ${System.currentTimeMillis() - t0} ms"
            )
            return bitmap
        }

        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val stages = StringBuilder()
        var mark = System.currentTimeMillis()
        fun lap(name: String) {
            val now = System.currentTimeMillis()
            if (stages.isNotEmpty()) stages.append(' ')
            stages.append(name).append('=').append(now - mark)
            mark = now
        }
        lap("cap")

        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        lap("get")

        val face = detectFace(bitmap)
        lap("detect")
        if (face == null) {
            if (s.whiten > 0.01f) globalGradeOnly(pixels, s)
            lap("grade")
            Log.i(TAG, "beautify ${w}x$h in ${System.currentTimeMillis() - t0} ms [$stages]")
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        // The mask is a smooth, heavily feathered weight, so build it at a
        // quarter of the resolution and let the shaders' bilinear sampling
        // upsample it. That turns the most expensive CPU stage (full-res
        // Bitmap + Canvas + getPixels + FloatArray + box blur) into a small
        // one; the physical feather radius is unchanged.
        val mw = (w * MASK_SCALE).toInt().coerceAtLeast(1)
        val mh = (h * MASK_SCALE).toInt().coerceAtLeast(1)
        val maskSource = Bitmap.createScaledBitmap(bitmap, mw, mh, true)
        val mask = buildSkinMask(maskSource, face.scaled(MASK_SCALE))
        if (maskSource !== bitmap) maskSource.recycle()
        val faceW = face.boundingBox.width().toFloat().coerceAtLeast(64f)
        lap("mask")

        if (s.slim > 0.01f || s.eyes > 0.01f) {
            warp(pixels, w, h, face, mask, mw, mh, s)
        }
        lap("warp")

        // A zero slider means the whole stage is skipped, not just made a
        // no-op: no guided filter, no soft focus, no tone pass.
        val doSmooth = s.smooth > 0.01f
        val doTone = s.whiten > 0.01f
        val doGpu = doSmooth || doTone

        // ---- two-scale guided-filter smoothing, one channel at a time ----
        val r1 = max(3, (faceW * 0.018f).toInt())
        val r2 = max(8, (faceW * 0.060f).toInt())
        val eps1 = (0.07f * 255f).pow(2)
        val eps2 = (0.18f * 255f).pow(2)

        // GPU first: guided filter + tone + soft focus in one transaction.
        // If it is unavailable or fails, fall back to the CPU stages.
        var gpuOk = false
        if (doGpu) {
            gpuOk = prepareGpu(w, h, mw, mh, mask) &&
                    gpuFilter.beautify(
                        pixels, doSmooth, s.smooth, r1, r2, eps1, eps2,
                        max(1, (faceW * 0.006f).toInt()),
                        doTone, s.whiten, s.warm
                    )
            if (!gpuOk) gpuState = -1
        }
        if (!gpuOk && doGpu) {
            val mask = BeautyMath.upsampleMask(mask, mw, mh, w, h)
            val guide = if (doSmooth) FloatArray(n) { BeautyMath.luma(pixels[it]) } else FloatArray(0)
            val ch = FloatArray(n)
            if (doSmooth) {
                val buf = Buffers(n)
                for (shift in intArrayOf(16, 8, 0)) {
                    BeautyMath.extract(pixels, ch, shift)
                    guided(guide, ch, buf.low1, w, h, r1, eps1, buf)
                    guided(guide, buf.low1, buf.low2, w, h, r2, eps2, buf)
                    for (i in 0 until n) {
                        val m = mask[i]
                        val k1 = 1f - s.smooth * m
                        val k2 = 1f - s.smooth * 0.8f * m
                        ch[i] = buf.low2[i] + (buf.low1[i] - buf.low2[i]) * k2 +
                                (ch[i] - buf.low1[i]) * k1
                    }
                    BeautyMath.store(pixels, ch, shift)
                }
            }
            if (doTone) tone(pixels, mask, s)
            if (doSmooth) softFocus(pixels, mask, faceW, ch, w, h)
        }
        lap("smooth+tone+soft")
        enhanceEyes(pixels, w, h, face, s.eyes)
        lap("eyes")
        enhanceLips(pixels, w, h, face, s.lips)
        lap("lips")

        val out = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        lap("encode")
        Log.i(TAG, "beautify ${w}x$h in ${System.currentTimeMillis() - t0} ms [$stages]")
        return out
    }

    // ---------------------------------------------------------------- detection

    private fun detectFace(bitmap: Bitmap): ScaledFace? = try {
        val maxSide = max(bitmap.width, bitmap.height)
        val scale = min(1f, DETECT_MAX_SIDE.toFloat() / maxSide)
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val faces = Tasks.await(
            detector.process(InputImage.fromBitmap(input, 0)),
            12, TimeUnit.SECONDS
        )
        if (input !== bitmap) input.recycle()
        faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?.let { ScaledFace(it, 1f / scale) }
    } catch (e: Exception) {
        Log.w(TAG, "face detection failed", e)
        null
    }

    /**
     * ML Kit result from the downscaled detection bitmap, with the bounding
     * box and contours mapped back to full-resolution pixel coordinates.
     */
    private class ScaledFace(private val src: Face, private val k: Float) {
        val boundingBox: RectF = src.boundingBox.let {
            RectF(it.left * k, it.top * k, it.right * k, it.bottom * k)
        }

        /** Same detection, mapped into another resolution. */
        fun scaled(factor: Float) = ScaledFace(src, k * factor)

        fun contour(type: Int): List<PointF>? =
            src.getContour(type)?.points?.map { PointF(it.x * k, it.y * k) }
    }

    private fun capResolution(src: Bitmap): Bitmap {
        val (w, h) = BeautyMath.capSize(src.width, src.height, MAX_PIXELS.toLong())
            ?: return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // ------------------------------------------------------------------- mask

    private fun buildSkinMask(source: Bitmap, face: ScaledFace): FloatArray {
        val w = source.width
        val h = source.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        val facePath = Path()
        val faceContour = face.contour(FaceContour.FACE)
        if (faceContour != null && faceContour.size > 2) {
            facePath.moveTo(faceContour[0].x, faceContour[0].y)
            for (i in 1 until faceContour.size) {
                facePath.lineTo(faceContour[i].x, faceContour[i].y)
            }
            facePath.close()
        } else {
            val b = RectF(face.boundingBox)
            b.inset(-b.width() * 0.04f, -b.height() * 0.04f)
            facePath.addOval(b, Path.Direction.CW)
        }
        canvas.drawPath(facePath, white)

        // holes: eyes stay sharp; brows/lips only partially reduced so the
        // smoothing and tone changes fade into them instead of drawing a ring
        for (type in intArrayOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE)) {
            contourBounds(face, type)?.let {
                val e = RectF(it)
                e.inset(-it.width() * 0.08f, -it.height() * 0.22f)
                hole.alpha = 255
                canvas.drawOval(e, hole)
            }
        }
        for (side in intArrayOf(0, 1)) {
            val top = if (side == 0) FaceContour.LEFT_EYEBROW_TOP
            else FaceContour.RIGHT_EYEBROW_TOP
            val bottom = if (side == 0) FaceContour.LEFT_EYEBROW_BOTTOM
            else FaceContour.RIGHT_EYEBROW_BOTTOM
            val a = contourBounds(face, top)
            val b = contourBounds(face, bottom)
            val u = when {
                a != null && b != null -> RectF(a).apply { union(b) }
                a != null -> RectF(a)
                b != null -> RectF(b)
                else -> null
            }
            u?.let {
                it.inset(-it.width() * 0.03f, -it.height() * 0.10f)
                hole.alpha = 120          // keep ~53% smoothing on the brow
                canvas.drawOval(it, hole)
            }
        }
        lipBounds(face)?.let {
            val e = RectF(it)
            e.inset(-it.width() * 0.02f, -it.height() * 0.03f)
            hole.alpha = 150              // keep ~41% smoothing on the lips
            canvas.drawOval(e, hole)
        }

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val mask = FloatArray(w * h) { ((px[it] ushr 24) and 0xFF) / 255f }

        // ---- forehead: the FACE contour often stops below the hairline ----
        // Extend upward a little, gated by skin colour, so the top of the
        // forehead doesn't stay redder than the rest of the face.
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val fb = face.boundingBox
        val faceW = fb.width().toFloat().coerceAtLeast(64f)
        val faceH = fb.height().toFloat().coerceAtLeast(64f)
        val topY = face.contour(FaceContour.FACE)
            ?.minOfOrNull { it.y } ?: fb.top.toFloat()
        val fhTop = (topY - faceH * 0.12f).toInt().coerceIn(0, h - 1)
        val fhBottom = (topY + faceH * 0.02f).toInt().coerceIn(0, h - 1)
        val fhSpan = (fhBottom - fhTop).toFloat().coerceAtLeast(1f)
        val fhHalfW = faceW * 0.42f
        val fhCx = fb.centerX()
        for (y in fhTop..fhBottom) {
            val ty = ((y - fhTop) / fhSpan).coerceIn(0f, 1f)
            val vfade = BeautyMath.smoothstep(0f, 0.40f, ty)
            if (vfade <= 0.001f) continue
            val x0 = (fhCx - fhHalfW).toInt().coerceIn(0, w - 1)
            val x1 = (fhCx + fhHalfW).toInt().coerceIn(0, w - 1)
            val row = y * w
            for (x in x0..x1) {
                val nx = (x - fhCx) / fhHalfW
                val hfade = 1f - BeautyMath.smoothstep(0.65f, 1f, abs(nx))
                if (hfade <= 0.001f) continue
                val p = srcPixels[row + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (!BeautyMath.isSkin(r, g, b)) continue
                val i = row + x
                val wgt = 0.85f * vfade * hfade
                if (wgt > mask[i]) mask[i] = wgt
            }
        }

        // ---- neck: continue tone/smoothing below the jaw -----------------
        // Without this the face gets whiter/cooler while the neck keeps the
        // original red, which reads as a hard face/neck split.
        val chinY = face.contour(FaceContour.FACE)
            ?.maxOfOrNull { it.y } ?: fb.bottom.toFloat()
        val neckTop = (chinY - faceH * 0.02f).toInt().coerceIn(0, h - 1)
        val neckBottom = (chinY + faceH * 0.45f).toInt().coerceIn(0, h - 1)
        val span = (neckBottom - chinY).coerceAtLeast(1f)
        val cx = fb.centerX()
        for (y in neckTop..neckBottom) {
            val ty = ((y - chinY) / span).coerceIn(0f, 1f)
            val vfade = 1f - BeautyMath.smoothstep(0.62f, 1f, ty)
            if (vfade <= 0.001f) continue
            val halfW = faceW * (0.30f + 0.06f * ty)
            val x0 = (cx - halfW).toInt().coerceIn(0, w - 1)
            val x1 = (cx + halfW).toInt().coerceIn(0, w - 1)
            val row = y * w
            for (x in x0..x1) {
                val nx = (x - cx) / halfW
                val hfade = 1f - BeautyMath.smoothstep(0.62f, 1f, abs(nx))
                if (hfade <= 0.001f) continue
                val p = srcPixels[row + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (!BeautyMath.isSkin(r, g, b)) continue
                val i = row + x
                val wgt = 0.85f * vfade * hfade
                if (wgt > mask[i]) mask[i] = wgt
            }
        }

        // feather so smoothing fades out instead of drawing a hard edge
        val tmp = FloatArray(w * h)
        BeautyMath.boxBlur(mask, mask, w, h, max(3, (face.boundingBox.width() * 0.035f).toInt()), tmp)
        return mask
    }

    private fun contourBounds(face: ScaledFace, type: Int): RectF? {
        val pts = face.contour(type) ?: return null
        if (pts.isEmpty()) return null
        return boundsOf(pts)
    }

    private fun lipBounds(face: ScaledFace): RectF? {
        val all = ArrayList<PointF>()
        for (type in intArrayOf(
            FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM
        )) {
            face.contour(type)?.let { all.addAll(it) }
        }
        return if (all.isEmpty()) null else boundsOf(all)
    }

    private fun boundsOf(pts: List<PointF>): RectF {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in pts) {
            l = min(l, p.x); t = min(t, p.y)
            r = max(r, p.x); b = max(b, p.y)
        }
        return RectF(l, t, r, b)
    }

    /** Loose YCbCr skin test, used only to keep the neck extension off
     *  clothing, hair and jewellery. */
    // ------------------------------------------------------------------ guided

    private fun prepareGpu(
        w: Int, h: Int, maskW: Int, maskH: Int, mask: FloatArray
    ): Boolean {
        if (gpuState < 0) return false
        if (gpuState == 0) {
            gpuState = if (gpuFilter.init()) 1 else -1
            if (gpuState < 0) {
                Log.i(TAG, "GPU beauty pipeline unavailable; using CPU")
                return false
            }
        }
        val ok = gpuFilter.prepare(w, h, maskW, maskH, mask)
        if (!ok) {
            gpuState = -1
            Log.w(TAG, "GPU prepare failed; using CPU")
        }
        return ok
    }

    /** Bilinear upsample of the quarter-res mask for the CPU fallback. */
    private class Buffers(n: Int) {
        val meanI = FloatArray(n)
        val varI = FloatArray(n)
        val meanP = FloatArray(n)
        val a = FloatArray(n)
        val b = FloatArray(n)
        val tmp = FloatArray(n)
        val low1 = FloatArray(n)
        val low2 = FloatArray(n)
    }

    private fun guided(
        guide: FloatArray, src: FloatArray, dst: FloatArray,
        w: Int, h: Int, r: Int, eps: Float, buf: Buffers
    ) {
        BeautyMath.boxBlur(guide, buf.meanI, w, h, r, buf.tmp)
        for (i in guide.indices) buf.a[i] = guide[i] * guide[i]
        BeautyMath.boxBlur(buf.a, buf.a, w, h, r, buf.tmp)
        for (i in guide.indices) buf.varI[i] = buf.a[i] - buf.meanI[i] * buf.meanI[i]

        BeautyMath.boxBlur(src, buf.meanP, w, h, r, buf.tmp)
        for (i in guide.indices) buf.a[i] = guide[i] * src[i]
        BeautyMath.boxBlur(buf.a, buf.a, w, h, r, buf.tmp)
        for (i in guide.indices) {
            val cov = buf.a[i] - buf.meanI[i] * buf.meanP[i]
            val ai = cov / (buf.varI[i] + eps)
            buf.a[i] = ai
            buf.b[i] = buf.meanP[i] - ai * buf.meanI[i]
        }
        BeautyMath.boxBlur(buf.a, buf.a, w, h, r, buf.tmp)
        BeautyMath.boxBlur(buf.b, buf.b, w, h, r, buf.tmp)
        for (i in guide.indices) dst[i] = buf.a[i] * guide[i] + buf.b[i]
    }

    /** O(n) box blur using a running prefix sum per row / column. */
    // -------------------------------------------------------------------- tone

    private fun tone(pixels: IntArray, mask: FloatArray, s: BeautySettings) {
        val whiten = s.whiten
        val warm = s.warm
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xFF).toFloat()
            val g = ((p ushr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()

            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            var cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f

            val m = mask[i]
            if (m > 0.001f) {
                // gentle shadow-weighted lift; the global bell below already
                // raises midtones, so keep this small or skin goes pale
                y += (3.5f * (1f - y / 255f) + 0.4f) * m * whiten
                // Xiaomi skin measures a touch less red and warmer in b
                cr -= 3.0f * m * warm
                cb -= 0.2f * m * warm
                // clean look: only a slight desaturation (Xiaomi keeps ~same sat)
                cb = 128f + (cb - 128f) * (1f - 0.08f * m)
                cr = 128f + (cr - 128f) * (1f - 0.08f * m)
            }

            // global midtone lift (bell curve) — matches Xiaomi's L25/L50/L95.
            // Skin gets a reduced share, otherwise the face ends up much
            // brighter than the neck/background.
            val d = (y - 135f) / 70f
            y += 30f * exp(-d * d) * (1f - 0.87f * m)
            cb -= 0.7f * warm
            cr += 0.3f * warm

            val rr = y + 1.402f * (cr - 128f)
            val gg = y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)
            val bb = y + 1.772f * (cb - 128f)
            pixels[i] = BeautyMath.argb(rr, gg, bb)
        }
    }

    private fun globalGradeOnly(pixels: IntArray, s: BeautySettings) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xFF).toFloat()
            val g = ((p ushr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            var y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            var cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
            val d = (y - 135f) / 70f
            y += 24f * exp(-d * d)
            cb -= 0.7f * s.warm
            cr += 0.3f * s.warm
            pixels[i] = BeautyMath.argb(
                y + 1.402f * (cr - 128f),
                y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f),
                y + 1.772f * (cb - 128f)
            )
        }
    }

    private fun softFocus(
        pixels: IntArray, mask: FloatArray, faceW: Float,
        ch: FloatArray, w: Int, h: Int
    ) {
        val r = max(1, (faceW * 0.006f).toInt())
        val low = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        for (shift in intArrayOf(16, 8, 0)) {
            BeautyMath.extract(pixels, ch, shift)
            BeautyMath.boxBlur(ch, low, w, h, r, tmp)
            for (i in pixels.indices) {
                val m = 0.20f * mask[i]
                ch[i] = ch[i] * (1f - m) + low[i] * m
            }
            BeautyMath.store(pixels, ch, shift)
        }
    }

    // ------------------------------------------------------------------- eyes

    private fun enhanceEyes(pixels: IntArray, w: Int, h: Int, face: ScaledFace, amount: Float) {
        if (amount <= 0.01f) return
        for (type in intArrayOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE)) {
            val pts = face.contour(type) ?: continue
            if (pts.isEmpty()) continue
            val b = boundsOf(pts)
            val cx = b.centerX()
            val cy = b.centerY()
            val rx = max(4f, b.width() * 0.62f)
            val ry = max(4f, b.height() * 0.85f)
            val x0 = max(0, (cx - rx).toInt())
            val x1 = min(w - 1, (cx + rx).toInt())
            val y0 = max(0, (cy - ry).toInt())
            val y1 = min(h - 1, (cy + ry).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val nx = (x - cx) / rx
                    val ny = (y - cy) / ry
                    val d = nx * nx + ny * ny
                    if (d >= 1f) continue
                    val wgt = (1f - d) * (1f - d) * amount
                    val i = y * w + x
                    val p = pixels[i]
                    val r = ((p ushr 16) and 0xFF).toFloat()
                    val g = ((p ushr 8) and 0xFF).toFloat()
                    val bb = (p and 0xFF).toFloat()
                    var yv = 0.299f * r + 0.587f * g + 0.114f * bb
                    var cb = -0.168736f * r - 0.331264f * g + 0.5f * bb + 128f
                    var cr = 0.5f * r - 0.418688f * g - 0.081312f * bb + 128f
                    yv += 7f * wgt
                    yv = 128f + (yv - 128f) * (1f + 0.10f * wgt)
                    cb = 128f + (cb - 128f) * (1f - 0.10f * wgt)
                    cr = 128f + (cr - 128f) * (1f - 0.10f * wgt)
                    pixels[i] = BeautyMath.argb(
                        yv + 1.402f * (cr - 128f),
                        yv - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f),
                        yv + 1.772f * (cb - 128f)
                    )
                }
            }
            // No artificial catchlight: the real specular highlight is already
            // in the photo, and painting a second white dot on the iris reads
            // as a fake "white spot" (and does not exist in the preview).
        }
    }

    private fun enhanceLips(pixels: IntArray, w: Int, h: Int, face: ScaledFace, amount: Float) {
        if (amount <= 0.01f) return
        val b = lipBounds(face) ?: return
        val cx = b.centerX()
        val cy = b.centerY()
        val rx = max(4f, b.width() * 0.48f)
        val ry = max(3f, b.height() * 0.52f)
        val x0 = max(0, (cx - rx).toInt())
        val x1 = min(w - 1, (cx + rx).toInt())
        val y0 = max(0, (cy - ry).toInt())
        val y1 = min(h - 1, (cy + ry).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                val d = nx * nx + ny * ny
                if (d >= 1f) continue
                val wgt = (1f - d) * (1f - d) * (1f - d) * amount
                val i = y * w + x
                val p = pixels[i]
                val r = ((p ushr 16) and 0xFF).toFloat()
                val g = ((p ushr 8) and 0xFF).toFloat()
                val bb = (p and 0xFF).toFloat()
                val yv = 0.299f * r + 0.587f * g + 0.114f * bb
                var cb = -0.168736f * r - 0.331264f * g + 0.5f * bb + 128f
                var cr = 0.5f * r - 0.418688f * g - 0.081312f * bb + 128f
                cr += 2.0f * wgt
                cb = 128f + (cb - 128f) * (1f + 0.04f * wgt)
                cr = 128f + (cr - 128f) * (1f + 0.04f * wgt)
                pixels[i] = BeautyMath.argb(
                    yv + 1.402f * (cr - 128f),
                    yv - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f),
                    yv + 1.772f * (cb - 128f)
                )
            }
        }
    }

    // ------------------------------------------------------------------- warp

    private fun warp(
        pixels: IntArray, w: Int, h: Int, face: ScaledFace,
        mask: FloatArray, mw: Int, mh: Int, s: BeautySettings
    ) {
        val src = pixels.copyOf()
        val bb = face.boundingBox
        val cx = bb.centerX()
        val cy = bb.centerY()
        val fw = max(1f, bb.width().toFloat())
        val fh = max(1f, bb.height().toFloat())

        if (s.slim > 0.01f) {
            val x0 = max(0, (bb.left - fw * 0.08f).toInt())
            val x1 = min(w - 1, (bb.right + fw * 0.08f).toInt())
            val y0 = max(0, (bb.top - fh * 0.08f).toInt())
            val y1 = min(h - 1, (bb.bottom + fh * 0.08f).toInt())
            // exp(-(nx^2/1.1 + ny^2/1.4)) is separable, so the per-pixel exp()
            // becomes one 1-D table lookup per axis. Same values, ~1.3M fewer
            // exp() calls per photo.
            val colW = FloatArray(x1 - x0 + 1)
            for (x in x0..x1) {
                val nx = (x - cx) / (fw * 0.5f)
                colW[x - x0] = BeautyMath.slimColumnWeight(nx)
            }
            for (y in y0..y1) {
                val ny = (y - cy) / (fh * 0.55f)
                val rowW = BeautyMath.slimRowWeight(ny)
                if (rowW < 0.001f) continue
                for (x in x0..x1) {
                    val wgt = colW[x - x0] * rowW
                    if (wgt < 0.001f) continue
                    val i = y * w + x
                    // The mask is zero on hair/background, where blending
                    // would be a no-op; skipping those pixels avoids the
                    // whole bilinear + blend cost for them.
                    val m = mask[(y * mh / h) * mw + (x * mw / w)]
                    if (m < 0.01f) continue
                    val sx = x + (x - cx) * s.slim * 0.10f * wgt
                    val sy = y + (y - cy) * s.slim * 0.015f * wgt
                    val sample = BeautyMath.bilinear(src, w, h, sx, sy)
                    pixels[i] = BeautyMath.blend(pixels[i], sample, m)
                }
            }
        }

        if (s.eyes > 0.01f) {
            // (1 - d)^2 for d in [0,1]; turns the per-pixel sqrt into a lookup
            val falloffLut = FloatArray(1024) {
                BeautyMath.eyeFalloff(it / 1023f)
            }
            for (type in intArrayOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE)) {
                val pts = face.contour(type) ?: continue
                if (pts.isEmpty()) continue
                val b = boundsOf(pts)
                val ex = b.centerX()
                val ey = b.centerY()
                val rad = max(6f, b.width() * 1.6f)
                val invRad2 = 1f / (rad * rad)
                val x0 = max(0, (ex - rad).toInt())
                val x1 = min(w - 1, (ex + rad).toInt())
                val y0 = max(0, (ey - rad).toInt())
                val y1 = min(h - 1, (ey + rad).toInt())
                val colDx2 = FloatArray(x1 - x0 + 1)
                for (x in x0..x1) {
                    val dx = x - ex
                    colDx2[x - x0] = dx * dx
                }
                for (y in y0..y1) {
                    val dy = y - ey
                    val dy2 = dy * dy
                    for (x in x0..x1) {
                        val d2 = (colDx2[x - x0] + dy2) * invRad2
                        if (d2 >= 1f) continue
                        val falloff = falloffLut[(d2 * 1023f).toInt().coerceIn(0, 1023)]
                        val scale = 1f - s.eyes * 0.12f * falloff
                        val sample = BeautyMath.bilinear(
                            src, w, h, ex + (x - ex) * scale, ey + dy * scale
                        )
                        val i = y * w + x
                        pixels[i] = BeautyMath.blend(pixels[i], sample, falloff * s.eyes)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

}
