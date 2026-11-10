package com.chen3feng.beautycam

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Offscreen OpenGL ES 3.0 beauty pipeline for the captured photo.
 *
 * It covers the three full-resolution, per-pixel stages:
 *
 *   1. two-scale guided filter (fine scale at half res, coarse scale at
 *      quarter res, detail-preserving full-res combine)
 *   2. YCbCr tone curve (shadow lift / whitening / less red / warm)
 *   3. 20% soft focus inside the skin mask
 *
 * All three run back to back in one upload -> one readback. The RGB image
 * lives in RGBA8 working textures between stages, which also matches the
 * CPU pipeline's 8-bit `store()` between stages. The guide and mask stay in
 * R32F/R8; the guided filter itself works in float at half resolution.
 *
 * If the device or driver lacks float render targets / float linear
 * filtering, [init] returns false and [BeautyProcessor] keeps using the
 * CPU path.
 */
internal class GpuGuidedFilter {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var fbo = 0
    private var quadVbo = 0

    private var progBlur = 0
    private var progCoeff = 0
    private var progFinal = 0
    private var progCombine = 0
    private var progExtract = 0
    private var progGuide = 0
    private var progTone = 0
    private var progSoftH = 0
    private var progSoftV = 0

    private var uBlurA = -1
    private var uBlurB = -1
    private var uBlurStep = -1
    private var uBlurRadius = -1
    private var uBlurMode = -1

    private var uCoeffMeanI = -1
    private var uCoeffMeanP = -1
    private var uCoeffMeanII = -1
    private var uCoeffMeanIP = -1
    private var uCoeffEps = -1

    private var uFinalA2 = -1
    private var uFinalB2 = -1
    private var uFinalGuide = -1

    private var uCombLow1 = -1
    private var uCombLow2 = -1
    private var uCombCh = -1
    private var uCombMask = -1
    private var uCombSmooth = -1

    private var uExtractImg = -1
    private var uExtractChannel = -1

    private var uGuideImg = -1

    private var uToneImg = -1
    private var uToneMask = -1
    private var uToneWhiten = -1
    private var uToneWarm = -1

    private var uSoftHImg = -1
    private var uSoftHStep = -1
    private var uSoftHRadius = -1

    private var uSoftVBlur = -1
    private var uSoftVOrig = -1
    private var uSoftVMask = -1
    private var uSoftVStep = -1
    private var uSoftVRadius = -1
    private var uSoftVWeight = -1

    private var texGuide = 0
    private var texCh = 0
    private var texMask = 0
    private var texLow1 = 0
    private var texLow2 = 0
    private var texImgA = 0
    private var texImgB = 0
    private var texImgC = 0
    private val texP = IntArray(6)
    // The coarse scale only needs a heavily smoothed image, so it runs at a
    // quarter of the resolution; these are its scratch targets.
    private val texQ = IntArray(6)
    // Guide statistics are channel-independent: mean(guide) / mean(guide^2)
    // for each scale, computed once per frame instead of once per channel.
    private val texStatH = IntArray(2)
    private val texStatQ = IntArray(2)

    private var width = 0
    private var height = 0
    private var maskW = 0
    private var maskH = 0
    private var halfW = 0
    private var halfH = 0
    private var quarterW = 0
    private var quarterH = 0
    private var pixelByteBuf: ByteBuffer? = null
    private var pixelIntBuf: java.nio.IntBuffer? = null
    private var maskBuf: ByteBuffer? = null

    private val drawBufs1 = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
    private val drawBufs2 = intArrayOf(
        GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1
    )

    var available = false
        private set

    fun init(): Boolean {
        if (available) return true
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false

            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) ||
                num[0] == 0
            ) {
                return false
            }
            val config = configs[0] ?: return false

            val ctxAttrs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false

            val surfAttrs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(display, config, surfAttrs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            if (!makeCurrent()) return false

            if (!hasExtension("GL_EXT_color_buffer_float")) {
                Log.w(TAG, "no GL_EXT_color_buffer_float; CPU path")
                return false
            }
            if (!hasExtension("GL_OES_texture_float_linear")) {
                Log.w(TAG, "no GL_OES_texture_float_linear; CPU path")
                return false
            }

            progBlur = build(VERTEX_SHADER, BLUR_SHADER)
            progCoeff = build(VERTEX_SHADER, COEFF_SHADER)
            progFinal = build(VERTEX_SHADER, FINAL_SHADER)
            progCombine = build(VERTEX_SHADER, COMBINE_SHADER)
            progExtract = build(VERTEX_SHADER, EXTRACT_SHADER)
            progGuide = build(VERTEX_SHADER, GUIDE_SHADER)
            progTone = build(VERTEX_SHADER, TONE_SHADER)
            progSoftH = build(VERTEX_SHADER, SOFT_H_SHADER)
            progSoftV = build(VERTEX_SHADER, SOFT_V_SHADER)
            if (progBlur == 0 || progCoeff == 0 || progFinal == 0 || progCombine == 0 ||
                progExtract == 0 || progGuide == 0 || progTone == 0 ||
                progSoftH == 0 || progSoftV == 0
            ) {
                return false
            }

            uBlurA = GLES30.glGetUniformLocation(progBlur, "uA")
            uBlurB = GLES30.glGetUniformLocation(progBlur, "uB")
            uBlurStep = GLES30.glGetUniformLocation(progBlur, "uStep")
            uBlurRadius = GLES30.glGetUniformLocation(progBlur, "uRadius")
            uBlurMode = GLES30.glGetUniformLocation(progBlur, "uMode")

            uCoeffMeanI = GLES30.glGetUniformLocation(progCoeff, "uMeanI")
            uCoeffMeanP = GLES30.glGetUniformLocation(progCoeff, "uMeanP")
            uCoeffMeanII = GLES30.glGetUniformLocation(progCoeff, "uMeanII")
            uCoeffMeanIP = GLES30.glGetUniformLocation(progCoeff, "uMeanIP")
            uCoeffEps = GLES30.glGetUniformLocation(progCoeff, "uEps")

            uFinalA2 = GLES30.glGetUniformLocation(progFinal, "uA2")
            uFinalB2 = GLES30.glGetUniformLocation(progFinal, "uB2")
            uFinalGuide = GLES30.glGetUniformLocation(progFinal, "uGuide")

            uCombLow1 = GLES30.glGetUniformLocation(progCombine, "uLow1")
            uCombLow2 = GLES30.glGetUniformLocation(progCombine, "uLow2")
            uCombCh = GLES30.glGetUniformLocation(progCombine, "uCh")
            uCombMask = GLES30.glGetUniformLocation(progCombine, "uMask")
            uCombSmooth = GLES30.glGetUniformLocation(progCombine, "uSmooth")

            uExtractImg = GLES30.glGetUniformLocation(progExtract, "uImg")
            uExtractChannel = GLES30.glGetUniformLocation(progExtract, "uChannel")

            uGuideImg = GLES30.glGetUniformLocation(progGuide, "uImg")

            uToneImg = GLES30.glGetUniformLocation(progTone, "uImg")
            uToneMask = GLES30.glGetUniformLocation(progTone, "uMask")
            uToneWhiten = GLES30.glGetUniformLocation(progTone, "uWhiten")
            uToneWarm = GLES30.glGetUniformLocation(progTone, "uWarm")

            uSoftHImg = GLES30.glGetUniformLocation(progSoftH, "uImg")
            uSoftHStep = GLES30.glGetUniformLocation(progSoftH, "uStep")
            uSoftHRadius = GLES30.glGetUniformLocation(progSoftH, "uRadius")

            uSoftVBlur = GLES30.glGetUniformLocation(progSoftV, "uBlur")
            uSoftVOrig = GLES30.glGetUniformLocation(progSoftV, "uOrig")
            uSoftVMask = GLES30.glGetUniformLocation(progSoftV, "uMask")
            uSoftVStep = GLES30.glGetUniformLocation(progSoftV, "uStep")
            uSoftVRadius = GLES30.glGetUniformLocation(progSoftV, "uRadius")
            uSoftVWeight = GLES30.glGetUniformLocation(progSoftV, "uWeight")

            val vbo = IntArray(1)
            GLES30.glGenBuffers(1, vbo, 0)
            quadVbo = vbo[0]
            val quad = floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
            )
            val qb = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            qb.put(quad).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            fbo = f[0]

            available = checkGl()
            Log.i(TAG, "GPU beauty pipeline ready: ${available}")
            return available
        } catch (e: Throwable) {
            Log.w(TAG, "GPU init failed", e)
            release()
            return false
        }
    }

    /** Allocate targets for this frame and upload the mask. */
    fun prepare(
        w: Int, h: Int, mw: Int, mh: Int, mask: FloatArray
    ): Boolean {
        if (!available) return false
        if (!makeCurrent()) return false
        if (w != width || h != height || mw != maskW || mh != maskH) {
            releaseTextures()
            width = w
            height = h
            maskW = mw
            maskH = mh
            halfW = (w + 1) / 2
            halfH = (h + 1) / 2
            quarterW = (w + 3) / 4
            quarterH = (h + 3) / 4
            texGuide = newFloatTex(w, h)
            texCh = newFloatTex(w, h)
            texMask = newMaskTex(mw, mh)
            texLow1 = newFloatTex(halfW, halfH)
            texLow2 = newFloatTex(quarterW, quarterH)
            for (i in texP.indices) texP[i] = newFloatTex(halfW, halfH)
            for (i in texQ.indices) texQ[i] = newFloatTex(quarterW, quarterH)
            for (i in texStatH.indices) texStatH[i] = newFloatTex(halfW, halfH)
            for (i in texStatQ.indices) texStatQ[i] = newFloatTex(quarterW, quarterH)
            texImgA = newRgbaTex(w, h)
            texImgB = newRgbaTex(w, h)
            texImgC = newRgbaTex(w, h)
            pixelByteBuf = ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder())
            pixelIntBuf = pixelByteBuf!!.asIntBuffer()
            maskBuf = ByteBuffer.allocateDirect(mw * mh).order(ByteOrder.nativeOrder())
            // fail fast if the driver cannot render to float targets
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texLow1, 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "float FBO incomplete: $status")
                available = false
                return false
            }
        }
        uploadMask(texMask, mw, mh, mask)
        return checkGl()
    }

    /**
     * Runs guided filter -> tone -> soft focus on the warped pixels and
     * writes the final RGB back into [pixels]. Returns false without
     * touching [pixels] if any GL stage fails, so the caller can run the
     * CPU pipeline on the original data.
     */
    fun beautify(
        pixels: IntArray,
        doSmooth: Boolean, smooth: Float,
        r1: Int, r2: Int, eps1: Float, eps2: Float,
        softRadius: Int,
        doTone: Boolean, whiten: Float, warm: Float
    ): Boolean {
        if (!available) return false
        if (!makeCurrent()) return false

        uploadPixels(pixels)
        if (!checkGl()) return false

        if (doSmooth) {
            guidePass()
            if (!checkGl()) return false
            val r1h = max(1, r1 / 2)
            val r2q = max(1, r2 / 4)
            // Guide statistics do not depend on the channel, so compute them
            // once per scale instead of once per channel (3x less blur work).
            blurPair(texGuide, texGuide, 2, r1h, true, texP[0], texP[1], halfW, halfH)
            blurPair(texP[0], texP[1], 0, r1h, false, texStatH[0], texStatH[1], halfW, halfH)
            blurPair(texGuide, texGuide, 2, r2q, true, texQ[0], texQ[1], quarterW, quarterH)
            blurPair(texQ[0], texQ[1], 0, r2q, false, texStatQ[0], texStatQ[1], quarterW, quarterH)
            for (c in 0 until 3) {
                extractChannel(c)
                guided(
                    texGuide, texCh, r1h, eps1, texLow1,
                    texStatH[0], texStatH[1], halfW, halfH, texP
                )
                // The coarse scale is a heavily smoothed image, so running it
                // at a quarter of the resolution (with the radius scaled to
                // match, same physical window) is visually identical and 4x
                // cheaper.
                guided(
                    texGuide, texLow1, r2q, eps2, texLow2,
                    texStatQ[0], texStatQ[1], quarterW, quarterH, texQ
                )
                combine(texLow1, texLow2, texCh, texMask, smooth, texImgA, c)
                if (!checkGl()) return false
            }
        }

        // Where the current image lives after the optional stages.
        var current = texImgA
        if (doTone) {
            tone(texImgA, texMask, whiten, warm)      // -> texImgB
            if (!checkGl()) return false
            current = texImgB
        }

        if (doSmooth) {
            softH(current, softRadius)                // -> texImgC
            if (!checkGl()) return false
            // soft focus is part of the smoothing stage; write to whichever
            // full-res target is not being sampled
            val out = if (doTone) texImgA else texImgB
            softV(texImgC, current, texMask, softRadius, 0.20f, out)
            if (!checkGl()) return false
            current = out
        }

        readback(current, pixels)
        return checkGl()
    }

    /** Luma of the uploaded image, computed on the GPU instead of the CPU. */
    private fun guidePass() {
        GLES30.glUseProgram(progGuide)
        bindTex(0, texImgA)
        GLES30.glUniform1i(uGuideImg, 0)
        draw(intArrayOf(texGuide), width, height, 1)
    }

    private fun extractChannel(channel: Int) {
        GLES30.glUseProgram(progExtract)
        bindTex(0, texImgA)
        GLES30.glUniform1i(uExtractImg, 0)
        GLES30.glUniform1i(uExtractChannel, channel)
        draw(intArrayOf(texCh), width, height, 1)
    }

    private fun guided(
        guide: Int, src: Int, r: Int, eps: Float, out: Int,
        statI: Int, statII: Int, w: Int, h: Int, p: IntArray
    ) {
        // mean(src) and mean(guide*src); the guide's own means are shared
        blurPair(guide, src, 3, r, true, p[0], p[1], w, h)
        blurPair(p[0], p[1], 0, r, false, p[2], p[3], w, h)
        // a, b
        coeff(statI, p[2], statII, p[3], eps, p[0], p[1], w, h)
        // blur a and b
        blurPair(p[0], p[1], 0, r, true, p[2], p[3], w, h)
        blurPair(p[2], p[3], 0, r, false, p[4], p[5], w, h)
        // out = a * guide + b
        finalHalf(p[4], p[5], guide, out, w, h)
    }

    private fun blurPair(
        a: Int, b: Int, mode: Int, radius: Int, horizontal: Boolean,
        outA: Int, outB: Int, w: Int, h: Int
    ) {
        GLES30.glUseProgram(progBlur)
        bindTex(0, a)
        bindTex(1, b)
        GLES30.glUniform1i(uBlurA, 0)
        GLES30.glUniform1i(uBlurB, 1)
        GLES30.glUniform2f(
            uBlurStep,
            if (horizontal) 1f / w else 0f,
            if (horizontal) 0f else 1f / h
        )
        GLES30.glUniform1i(uBlurRadius, radius)
        GLES30.glUniform1i(uBlurMode, mode)
        draw(intArrayOf(outA, outB), w, h, 2)
    }

    private fun coeff(
        meanI: Int, meanP: Int, meanII: Int, meanIP: Int, eps: Float,
        outA: Int, outB: Int, w: Int, h: Int
    ) {
        GLES30.glUseProgram(progCoeff)
        bindTex(0, meanI)
        bindTex(1, meanP)
        bindTex(2, meanII)
        bindTex(3, meanIP)
        GLES30.glUniform1i(uCoeffMeanI, 0)
        GLES30.glUniform1i(uCoeffMeanP, 1)
        GLES30.glUniform1i(uCoeffMeanII, 2)
        GLES30.glUniform1i(uCoeffMeanIP, 3)
        GLES30.glUniform1f(uCoeffEps, eps)
        draw(intArrayOf(outA, outB), w, h, 2)
    }

    private fun finalHalf(a2: Int, b2: Int, guide: Int, out: Int, w: Int, h: Int) {
        GLES30.glUseProgram(progFinal)
        bindTex(0, a2)
        bindTex(1, b2)
        bindTex(2, guide)
        GLES30.glUniform1i(uFinalA2, 0)
        GLES30.glUniform1i(uFinalB2, 1)
        GLES30.glUniform1i(uFinalGuide, 2)
        draw(intArrayOf(out), w, h, 1)
    }

    private fun combine(
        low1: Int, low2: Int, ch: Int, mask: Int, smooth: Float,
        target: Int, channel: Int
    ) {
        GLES30.glUseProgram(progCombine)
        bindTex(0, low1)
        bindTex(1, low2)
        bindTex(2, ch)
        bindTex(3, mask)
        GLES30.glUniform1i(uCombLow1, 0)
        GLES30.glUniform1i(uCombLow2, 1)
        GLES30.glUniform1i(uCombCh, 2)
        GLES30.glUniform1i(uCombMask, 3)
        GLES30.glUniform1f(uCombSmooth, smooth)
        // ARGB ints uploaded as GL_RGBA land in the texture as B,G,R,A,
        // so channel 0 (red) is texture .b and channel 2 (blue) is .r.
        GLES30.glColorMask(channel == 2, channel == 1, channel == 0, false)
        draw(intArrayOf(target), width, height, 1)
        GLES30.glColorMask(true, true, true, true)
    }

    private fun tone(img: Int, mask: Int, whiten: Float, warm: Float) {
        GLES30.glUseProgram(progTone)
        bindTex(0, img)
        bindTex(1, mask)
        GLES30.glUniform1i(uToneImg, 0)
        GLES30.glUniform1i(uToneMask, 1)
        GLES30.glUniform1f(uToneWhiten, whiten)
        GLES30.glUniform1f(uToneWarm, warm)
        draw(intArrayOf(texImgB), width, height, 1)
    }

    private fun softH(img: Int, radius: Int) {
        GLES30.glUseProgram(progSoftH)
        bindTex(0, img)
        GLES30.glUniform1i(uSoftHImg, 0)
        GLES30.glUniform2f(uSoftHStep, 1f / width, 0f)
        GLES30.glUniform1i(uSoftHRadius, radius)
        draw(intArrayOf(texImgC), width, height, 1)
    }

    private fun softV(
        blur: Int, orig: Int, mask: Int, radius: Int, weight: Float, out: Int
    ) {
        GLES30.glUseProgram(progSoftV)
        bindTex(0, blur)
        bindTex(1, orig)
        bindTex(2, mask)
        GLES30.glUniform1i(uSoftVBlur, 0)
        GLES30.glUniform1i(uSoftVOrig, 1)
        GLES30.glUniform1i(uSoftVMask, 2)
        GLES30.glUniform2f(uSoftVStep, 0f, 1f / height)
        GLES30.glUniform1i(uSoftVRadius, radius)
        GLES30.glUniform1f(uSoftVWeight, weight)
        draw(intArrayOf(out), width, height, 1)
    }

    private fun draw(targets: IntArray, w: Int, h: Int, count: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, targets[0], 0
        )
        if (count > 1) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_TEXTURE_2D, targets[1], 0
            )
            GLES30.glDrawBuffers(2, drawBufs2, 0)
        } else {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_TEXTURE_2D, 0, 0
            )
            GLES30.glDrawBuffers(1, drawBufs1, 0)
        }
        GLES30.glViewport(0, 0, w, h)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun bindTex(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun newFloatTex(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, w, h, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, null
        )
        setClampLinear()
        return t[0]
    }

    private fun newMaskTex(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null
        )
        setClampLinear()
        return t[0]
    }

    private fun newRgbaTex(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        setClampLinear()
        return t[0]
    }

    private fun setClampLinear() {
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
    }

    private fun uploadMask(tex: Int, w: Int, h: Int, mask: FloatArray) {
        val bytes = maskBuf ?: return
        bytes.clear()
        for (i in 0 until w * h) {
            bytes.put((mask[i] * 255f).toInt().coerceIn(0, 255).toByte())
        }
        bytes.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        // One byte per pixel: the default 4-byte unpack alignment would make
        // the driver skip padding after every row whose width is not a
        // multiple of 4, shearing the mask into diagonal stripes (visible as
        // a dark diagonal band across the face on any odd image width).
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun uploadPixels(pixels: IntArray) {
        val ints = pixelIntBuf ?: return
        val bytes = pixelByteBuf ?: return
        ints.clear()
        ints.put(pixels)
        bytes.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texImgA)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes
        )
    }

    private fun readback(tex: Int, pixels: IntArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex, 0
        )
        GLES30.glDrawBuffers(1, drawBufs1, 0)
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        val bytes = pixelByteBuf ?: return
        bytes.clear()
        GLES30.glReadPixels(
            0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes
        )
        if (!checkGl()) return
        bytes.position(0)
        bytes.asIntBuffer().get(pixels)
    }

    private fun hasExtension(name: String): Boolean {
        val all = GLES30.glGetString(GLES30.GL_EXTENSIONS)
        if (all != null) return all.split(' ').contains(name)
        val count = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
        for (i in 0 until count[0]) {
            if (GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i) == name) return true
        }
        return false
    }

    private fun makeCurrent(): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT ||
            eglSurface == EGL14.EGL_NO_SURFACE
        ) {
            return false
        }
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    private fun checkGl(): Boolean {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "gl error 0x${Integer.toHexString(e)}; disabling GPU path")
            available = false
            return false
        }
        return true
    }

    private fun build(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "link failed: ${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun releaseTextures() {
        val all = intArrayOf(
            texGuide, texCh, texMask, texLow1, texLow2, texImgA, texImgB, texImgC,
            texP[0], texP[1], texP[2], texP[3], texP[4], texP[5],
            texQ[0], texQ[1], texQ[2], texQ[3], texQ[4], texQ[5],
            texStatH[0], texStatH[1], texStatQ[0], texStatQ[1]
        )
        for (t in all) {
            if (t != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(t), 0)
            }
        }
        texGuide = 0; texCh = 0; texMask = 0
        texLow1 = 0; texLow2 = 0
        texImgA = 0; texImgB = 0; texImgC = 0
        for (i in texP.indices) texP[i] = 0
        for (i in texQ.indices) texQ[i] = 0
        for (i in texStatH.indices) texStatH[i] = 0
        for (i in texStatQ.indices) texStatQ[i] = 0
        width = 0
        height = 0
        maskW = 0
        maskH = 0
    }

    fun release() {
        try {
            if (fbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                fbo = 0
            }
            if (quadVbo != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
                quadVbo = 0
            }
            for (p in intArrayOf(
                progBlur, progCoeff, progFinal, progCombine,
                progExtract, progGuide, progTone, progSoftH, progSoftV
            )) {
                if (p != 0) GLES30.glDeleteProgram(p)
            }
            progBlur = 0; progCoeff = 0; progFinal = 0; progCombine = 0
            progExtract = 0; progGuide = 0; progTone = 0; progSoftH = 0; progSoftV = 0
            releaseTextures()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Throwable) {
            Log.w(TAG, "release failed", e)
        }
        available = false
    }

    companion object {
        private const val TAG = "GpuGuidedFilter"
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private const val VERTEX_SHADER = """#version 300 es
            in vec2 aPos;
            out vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val BLUR_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uA;
            uniform sampler2D uB;
            uniform vec2 uStep;
            uniform int uRadius;
            uniform int uMode;
            layout(location = 0) out float outA;
            layout(location = 1) out float outB;
            void main() {
                float sa = 0.0;
                float sb = 0.0;
                for (int i = -uRadius; i <= uRadius; ++i) {
                    vec2 uv = vUv + uStep * float(i);
                    float a = texture(uA, uv).r;
                    float b = texture(uB, uv).r;
                    if (uMode == 1) {
                        // (mean guide^2, mean guide*src)
                        b = a * b;
                        a = a * a;
                    } else if (uMode == 2) {
                        // guide vs itself: (mean guide, mean guide^2)
                        b = a * a;
                    } else if (uMode == 3) {
                        // (mean src, mean guide*src)
                        float g = a;
                        a = b;
                        b = g * b;
                    }
                    sa += a;
                    sb += b;
                }
                float inv = 1.0 / float(2 * uRadius + 1);
                outA = sa * inv;
                outB = sb * inv;
            }
        """

        private const val COEFF_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uMeanI;
            uniform sampler2D uMeanP;
            uniform sampler2D uMeanII;
            uniform sampler2D uMeanIP;
            uniform float uEps;
            layout(location = 0) out float outA;
            layout(location = 1) out float outB;
            void main() {
                float mi = texture(uMeanI, vUv).r;
                float mp = texture(uMeanP, vUv).r;
                float mii = texture(uMeanII, vUv).r;
                float mip = texture(uMeanIP, vUv).r;
                float a = (mip - mi * mp) / (mii - mi * mi + uEps);
                outA = a;
                outB = mp - a * mi;
            }
        """

        private const val FINAL_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uA2;
            uniform sampler2D uB2;
            uniform sampler2D uGuide;
            out float outColor;
            void main() {
                float a = texture(uA2, vUv).r;
                float b = texture(uB2, vUv).r;
                float g = texture(uGuide, vUv).r;
                outColor = a * g + b;
            }
        """

        private const val COMBINE_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uLow1;
            uniform sampler2D uLow2;
            uniform sampler2D uCh;
            uniform sampler2D uMask;
            uniform float uSmooth;
            out vec4 outColor;
            void main() {
                float l1 = texture(uLow1, vUv).r;
                float l2 = texture(uLow2, vUv).r;
                float c = texture(uCh, vUv).r;
                float m = texture(uMask, vUv).r;
                float k1 = 1.0 - uSmooth * m;
                float k2 = 1.0 - uSmooth * 0.8 * m;
                float v = l2 + (l1 - l2) * k2 + (c - l1) * k1;
                // RGBA8 target: normalize to the same 8-bit domain the CPU
                // pipeline stores between stages. The same value goes to all
                // three components; glColorMask picks the channel to keep.
                outColor = vec4(vec3(clamp(v, 0.0, 255.0) / 255.0), 1.0);
            }
        """

        private const val EXTRACT_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uImg;
            uniform int uChannel;
            out float outColor;
            void main() {
                // texture is B,G,R,A because ARGB ints are uploaded as RGBA
                vec4 t = texture(uImg, vUv);
                float v = uChannel == 0 ? t.b : (uChannel == 1 ? t.g : t.r);
                outColor = v * 255.0;
            }
        """

        private const val GUIDE_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uImg;
            out float outColor;
            void main() {
                // texture is B,G,R,A because ARGB ints are uploaded as RGBA
                vec4 t = texture(uImg, vUv);
                outColor = (0.299 * t.b + 0.587 * t.g + 0.114 * t.r) * 255.0;
            }
        """

        private const val TONE_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uImg;
            uniform sampler2D uMask;
            uniform float uWhiten;
            uniform float uWarm;
            out vec4 outColor;
            void main() {
                // texture stores B,G,R; swap back to RGB for YCbCr
                vec3 c = texture(uImg, vUv).bgr * 255.0;
                float r = c.r;
                float g = c.g;
                float b = c.b;
                float y = 0.299 * r + 0.587 * g + 0.114 * b;
                float cb = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0;
                float cr = 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0;

                float m = texture(uMask, vUv).r;
                if (m > 0.001) {
                    y += (3.5 * (1.0 - y / 255.0) + 0.4) * m * uWhiten;
                    cr -= 3.0 * m * uWarm;
                    cb -= 0.2 * m * uWarm;
                    cb = 128.0 + (cb - 128.0) * (1.0 - 0.08 * m);
                    cr = 128.0 + (cr - 128.0) * (1.0 - 0.08 * m);
                }

                float d = (y - 135.0) / 70.0;
                y += 30.0 * exp(-d * d) * (1.0 - 0.87 * m);
                cb -= 0.7 * uWarm;
                cr += 0.3 * uWarm;

                vec3 rgb = vec3(
                    y + 1.402 * (cr - 128.0),
                    y - 0.344136 * (cb - 128.0) - 0.714136 * (cr - 128.0),
                    y + 1.772 * (cb - 128.0)
                );
                // keep the B,G,R storage order used by the upload/readback
                outColor = vec4(clamp(rgb, 0.0, 255.0).bgr / 255.0, 1.0);
            }
        """

        private const val SOFT_H_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uImg;
            uniform vec2 uStep;
            uniform int uRadius;
            out vec4 outColor;
            void main() {
                vec3 sum = vec3(0.0);
                for (int i = -uRadius; i <= uRadius; ++i) {
                    sum += texture(uImg, vUv + uStep * float(i)).rgb;
                }
                outColor = vec4(sum / float(2 * uRadius + 1), 1.0);
            }
        """

        private const val SOFT_V_SHADER = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uBlur;
            uniform sampler2D uOrig;
            uniform sampler2D uMask;
            uniform vec2 uStep;
            uniform int uRadius;
            uniform float uWeight;
            out vec4 outColor;
            void main() {
                vec3 sum = vec3(0.0);
                for (int i = -uRadius; i <= uRadius; ++i) {
                    sum += texture(uBlur, vUv + uStep * float(i)).rgb;
                }
                vec3 blurred = sum / float(2 * uRadius + 1);
                vec3 orig = texture(uOrig, vUv).rgb;
                float m = uWeight * texture(uMask, vUv).r;
                outColor = vec4(mix(orig, blurred, m), 1.0);
            }
        """
    }
}
