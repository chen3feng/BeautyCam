package com.chen3feng.beautycam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * CameraX preview surface that renders the camera frame through a beauty
 * shader. This is the real-time path: the captured photo still goes through
 * the CPU pipeline in [BeautyProcessor].
 *
 * The shader uses a YCbCr skin mask instead of ML Kit landmarks. That is much
 * cheaper (no per-frame face detection) and works for face, neck and hands;
 * eyes/brows/hair fall outside the mask because they are dark or saturated.
 */
class BeautyGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), Preview.SurfaceProvider {

    @Volatile private var settings = BeautySettings()
    private val renderer = BeautyRenderer()

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setSettings(s: BeautySettings) {
        settings = s
        requestRender()
    }

    /**
     * Grabs the frame currently on screen so the shutter can show it right
     * away, the way the stock camera does: the photo appears instantly and
     * then quietly sharpens when the real capture finishes.
     *
     * The callback runs on the UI thread with the letterbox cropped away, or
     * null if no frame has been drawn yet.
     */
    fun snapshot(callback: (Bitmap?) -> Unit) {
        renderer.requestSnapshot(callback)
        requestRender()
    }

    /** Keep the letterboxed frame centered between the overlaid bars. */
    fun setContentInsets(top: Int, bottom: Int) {
        renderer.setContentInsets(top, bottom)
        requestRender()
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        renderer.attachRequest(request)
        requestRender()
    }

    private inner class BeautyRenderer : Renderer {
        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private var uTexture = 0
        private var uTextureMatrix = 0
        private var uQuadScale = 0
        private var uQuadOffset = 0
        private var uTexelSize = 0
        private var uSmooth = 0
        private var uWhiten = 0
        private var uWarm = 0
        private var uEnabled = 0

        private var textureId = 0
        @Volatile private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        @Volatile private var pending: SurfaceRequest? = null

        @Volatile private var rotationDegrees = 0
        @Volatile private var mirroring = false
        @Volatile private var bufferW = 0
        @Volatile private var bufferH = 0
        private var viewW = 1
        private var viewH = 1
        @Volatile private var frameAvailable = false
        private var hasFrame = false
        private var loggedFrame = false
        @Volatile private var snapshotCallback: ((Bitmap?) -> Unit)? = null
        @Volatile private var snapshotRequested = false

        private val texMatrix = FloatArray(16)
        private val quadScale = FloatArray(2)
        private val quadOffset = FloatArray(2)
        @Volatile private var insetTop = 0
        @Volatile private var insetBottom = 0

        private val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        private val quadBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(quad); position(0)
                }

        fun setContentInsets(top: Int, bottom: Int) {
            insetTop = top
            insetBottom = bottom
        }

        fun requestSnapshot(cb: (Bitmap?) -> Unit) {
            snapshotCallback = cb
            snapshotRequested = true
        }

        fun attachRequest(request: SurfaceRequest) {
            pending = request
            bufferW = request.resolution.width
            bufferH = request.resolution.height
            request.setTransformationInfoListener(
                androidx.core.content.ContextCompat.getMainExecutor(context)
            ) { info ->
                rotationDegrees = info.rotationDegrees
                mirroring = info.isMirroring
                Log.d(TAG, "request res=${request.resolution} rot=${info.rotationDegrees} " +
                        "mirror=${info.isMirroring} crop=${info.cropRect}")
                requestRender()
            }
            provideIfReady()
        }

        private fun provideIfReady() {
            val request = pending ?: return
            val st = surfaceTexture ?: return
            st.setDefaultBufferSize(bufferW, bufferH)
            val s = Surface(st)
            surface = s
            request.provideSurface(s, GL_EXECUTOR) { result ->
                Log.d(TAG, "preview surface result: ${result.resultCode}")
                s.release()
            }
            pending = null
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            // a lost/recreated EGL context invalidates the old texture
            surfaceTexture?.release()
            surfaceTexture = null
            hasFrame = false
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uTextureMatrix = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            uQuadScale = GLES20.glGetUniformLocation(program, "uQuadScale")
            uQuadOffset = GLES20.glGetUniformLocation(program, "uQuadOffset")
            uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
            uSmooth = GLES20.glGetUniformLocation(program, "uSmooth")
            uWhiten = GLES20.glGetUniformLocation(program, "uWhiten")
            uWarm = GLES20.glGetUniformLocation(program, "uWarm")
            uEnabled = GLES20.glGetUniformLocation(program, "uEnabled")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )

            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener {
                frameAvailable = true
                requestRender()
            }
            surfaceTexture = st
            provideIfReady()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            if (program == 0 || bufferW == 0 || bufferH == 0) return

            if (frameAvailable) {
                frameAvailable = false
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                hasFrame = true
                if (!loggedFrame) {
                    loggedFrame = true
                    Log.d(TAG, "view=${viewW}x${viewH} buffer=${bufferW}x${bufferH} " +
                            "rot=$rotationDegrees mirror=$mirroring " +
                            "tex=${texMatrix.joinToString(",")}")
                }
            }
            // A slider change calls requestRender() without a new camera
            // frame. Redraw the last frame with the new settings instead of
            // clearing to black and returning, which made the preview flash
            // on every slider tick.
            if (!hasFrame) return
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            updateQuadScale()

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uTextureMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2fv(uQuadScale, 1, quadScale, 0)
            GLES20.glUniform2fv(uQuadOffset, 1, quadOffset, 0)
            GLES20.glUniform2f(
                uTexelSize,
                1f / bufferW.coerceAtLeast(1),
                1f / bufferH.coerceAtLeast(1)
            )
            val s = settings
            GLES20.glUniform1f(uSmooth, if (s.enabled) s.smooth else 0f)
            GLES20.glUniform1f(uWhiten, s.whiten)
            GLES20.glUniform1f(uWarm, s.warm)
            GLES20.glUniform1f(uEnabled, if (s.enabled) 1f else 0f)

            quadBuffer.position(0)
            GLES20.glVertexAttribPointer(
                aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer
            )
            GLES20.glEnableVertexAttribArray(aPosition)
            quadBuffer.position(2)
            GLES20.glVertexAttribPointer(
                aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer
            )
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)

            if (snapshotRequested) {
                snapshotRequested = false
                val cb = snapshotCallback
                snapshotCallback = null
                val bmp = grabFrame()
                if (cb != null) post { cb(bmp) } else bmp?.recycle()
            }
        }

        /**
         * Reads the just-drawn frame out of the default framebuffer and crops
         * away the letterbox. Must be called inside onDrawFrame, before the
         * buffer swap.
         */
        private fun grabFrame(): Bitmap? {
            val w = viewW
            val h = viewH
            if (!hasFrame || w <= 1 || h <= 1) return null
            val buf = ByteBuffer.allocateDirect(w * h * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "snapshot read failed 0x${Integer.toHexString(err)}")
                return null
            }
            buf.position(0)
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            // GL's origin is bottom-left; flip to screen orientation
            val flip = Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
            val flipped = Bitmap.createBitmap(full, 0, 0, w, h, flip, false)
            full.recycle()

            // quadScale/quadOffset are in NDC; convert to pixels and crop
            val left = ((quadOffset[0] - quadScale[0] + 1f) * 0.5f * w)
                .toInt().coerceIn(0, w - 1)
            val right = ((quadOffset[0] + quadScale[0] + 1f) * 0.5f * w)
                .toInt().coerceIn(left + 1, w)
            val bottom = ((quadOffset[1] - quadScale[1] + 1f) * 0.5f * h)
                .toInt().coerceIn(0, h - 1)
            val top = ((quadOffset[1] + quadScale[1] + 1f) * 0.5f * h)
                .toInt().coerceIn(bottom + 1, h)
            val out = Bitmap.createBitmap(
                flipped, left, h - top, right - left, top - bottom
            )
            if (out !== flipped) flipped.recycle()
            return out
        }

        private fun updateQuadScale() {
            // Letterbox the full camera frame instead of center-cropping it,
            // so the preview shows the same framing as the saved 3:4 photo.
            // Fit it inside the area not covered by the top bar and controls,
            // then center that area.
            val rotated = rotationDegrees % 180 == 90
            val bw = if (rotated) bufferH else bufferW
            val bh = if (rotated) bufferW else bufferH
            val visibleH = (viewH - insetTop - insetBottom).coerceAtLeast(1)
            val availAspect = viewW.toFloat() / visibleH
            val bufAspect = bw.toFloat() / bh.coerceAtLeast(1)
            if (bufAspect > availAspect) {
                quadScale[0] = 1f
                quadScale[1] = (viewW.toFloat() / bufAspect) / viewH
            } else {
                quadScale[1] = visibleH.toFloat() / viewH
                quadScale[0] = (visibleH * bufAspect) / viewW
            }
            quadOffset[0] = 0f
            quadOffset[1] = (insetBottom - insetTop).toFloat() / viewH
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "compile failed: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    companion object {
        private const val TAG = "BeautyGLView"
        private val GL_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor()

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform vec2 uQuadScale;
            uniform vec2 uQuadOffset;
            varying vec2 vTexCoord;
            void main() {
                // uTextureMatrix maps display coords -> buffer coords and
                // already contains the sensor rotation. The quad is scaled
                // down to letterbox the full frame; no extra mirror is applied
                // because the front-camera stream is already mirrored by the
                // HAL, which is why the preview matches the saved photo.
                vTexCoord = (uTextureMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                gl_Position = vec4(aPosition.xy * uQuadScale + uQuadOffset, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uSmooth;
            uniform float uWhiten;
            uniform float uWarm;
            uniform float uEnabled;

            vec3 toYcbcr(vec3 c) {
                float y = dot(c, vec3(0.299, 0.587, 0.114));
                float cb = dot(c, vec3(-0.168736, -0.331264, 0.5)) + 0.5;
                float cr = dot(c, vec3(0.5, -0.418688, -0.081312)) + 0.5;
                return vec3(y, cb, cr) * 255.0;
            }

            vec3 toRgb(vec3 ycc) {
                float y = ycc.x / 255.0;
                float cb = ycc.y / 255.0 - 0.5;
                float cr = ycc.z / 255.0 - 0.5;
                return vec3(
                    y + 1.402 * cr,
                    y - 0.344136 * cb - 0.714136 * cr,
                    y + 1.772 * cb
                );
            }

            float skinMask(vec3 c) {
                vec3 ycc = toYcbcr(c);
                float y = ycc.x, cb = ycc.y, cr = ycc.z;
                float m = smoothstep(133.0, 143.0, cr) *
                          (1.0 - smoothstep(174.0, 186.0, cr));
                m *= smoothstep(68.0, 80.0, cb) *
                     (1.0 - smoothstep(120.0, 132.0, cb));
                m *= smoothstep(30.0, 55.0, y);
                m *= smoothstep(0.035, 0.09, c.r - c.b);
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float sat = mx > 0.001 ? (mx - mn) / mx : 0.0;
                m *= smoothstep(0.10, 0.20, sat);
                m *= 1.0 - smoothstep(0.48, 0.64, sat);
                return m;
            }

            void tap(vec2 off, float wt, vec3 c, inout vec3 sum, inout float wsum) {
                vec3 s = texture2D(uTexture, vTexCoord + off).rgb;
                float w = wt * exp(-dot(s - c, s - c) * 60.0);
                sum += s * w;
                wsum += w;
            }

            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;
                if (uEnabled < 0.5 || (uSmooth < 0.001 && uWhiten < 0.001)) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                float m = skinMask(c);
                vec3 outc = c;
                if (uSmooth > 0.001) {
                    vec2 o1 = uTexelSize * 2.0;
                    vec2 o2 = uTexelSize * 5.0;
                    vec3 sum = c;
                    float wsum = 1.0;
                    tap(vec2(o1.x, 0.0), 1.0, c, sum, wsum);
                    tap(vec2(-o1.x, 0.0), 1.0, c, sum, wsum);
                    tap(vec2(0.0, o1.y), 1.0, c, sum, wsum);
                    tap(vec2(0.0, -o1.y), 1.0, c, sum, wsum);
                    tap(vec2(o1.x, o1.y), 0.7, c, sum, wsum);
                    tap(vec2(-o1.x, -o1.y), 0.7, c, sum, wsum);
                    tap(vec2(o1.x, -o1.y), 0.7, c, sum, wsum);
                    tap(vec2(-o1.x, o1.y), 0.7, c, sum, wsum);
                    tap(vec2(o2.x, 0.0), 0.5, c, sum, wsum);
                    tap(vec2(-o2.x, 0.0), 0.5, c, sum, wsum);
                    tap(vec2(0.0, o2.y), 0.5, c, sum, wsum);
                    tap(vec2(0.0, -o2.y), 0.5, c, sum, wsum);
                    vec3 blurred = sum / wsum;
                    outc = mix(c, blurred, uSmooth * m);
                }

                // whiten 0 means no tone stage at all (warm included), same
                // as the captured-photo pipeline
                if (uWhiten < 0.001) {
                    gl_FragColor = vec4(outc, 1.0);
                    return;
                }

                vec3 ycc = toYcbcr(outc);
                float y = ycc.x, cb = ycc.y, cr = ycc.z;
                y += (3.5 * (1.0 - y / 255.0) + 0.4) * m * uWhiten;
                cr -= 3.0 * m * uWarm;
                cb -= 0.2 * m * uWarm;
                cb = 128.0 + (cb - 128.0) * (1.0 - 0.08 * m);
                cr = 128.0 + (cr - 128.0) * (1.0 - 0.08 * m);

                float d = (y - 135.0) / 70.0;
                y += 30.0 * exp(-d * d) * (1.0 - 0.87 * m);
                cb -= 0.7 * uWarm;
                cr += 0.3 * uWarm;

                gl_FragColor = vec4(toRgb(vec3(y, cb, cr)), 1.0);
            }
        """
    }
}
