package com.chen3feng.beautycam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.chen3feng.beautycam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BeautyCam"
        private const val PREFS_NAME = "beauty_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SMOOTH = "smooth"
        private const val KEY_WHITEN = "whiten"
        private const val KEY_SLIM = "slim"
        private const val KEY_EYES = "eyes"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var beautyProcessor: BeautyProcessor
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var placeholderBitmap: Bitmap? = null
    private var lastSavedUri: Uri? = null
    private var busy = false
    // Guards against a late preview snapshot overwriting a newer result.
    private var captureToken = 0

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    // The debug import path overrides the sliders; don't let that clobber
    // the user's saved settings.
    private var persistEnabled = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        beautyProcessor = BeautyProcessor(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setupControls()

        // Center the letterboxed preview between the overlaid bars. The panel
        // is laid out after the top bar, so both heights are known here.
        binding.controlPanel.doOnLayout {
            binding.previewView.setContentInsets(
                binding.topBar.height,
                binding.controlPanel.height
            )
        }

        // Debug/import path: adb shell am start ... --es image_path /path/to.jpg
        val importPath = intent?.getStringExtra("image_path")
        if (importPath != null) {
            // Debug: override sliders from the intent, e.g.
            // --ei smooth 0 --ei whiten 0
            persistEnabled = false
            intent.getIntExtra("smooth", -1).let {
                if (it >= 0) binding.smoothSeek.progress = it
            }
            intent.getIntExtra("whiten", -1).let {
                if (it >= 0) binding.whitenSeek.progress = it
            }
            intent.getIntExtra("slim", -1).let {
                if (it >= 0) binding.slimSeek.progress = it
            }
            intent.getIntExtra("eyes", -1).let {
                if (it >= 0) binding.eyesSeek.progress = it
            }
            intent.getIntExtra("enabled", -1).let {
                if (it >= 0) binding.beautySwitch.isChecked = it != 0
            }
            binding.cameraLabel.text = getString(R.string.camera_import)
            binding.progressOverlay.visibility = View.VISIBLE
            processFile(importPath)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.previewView.onResume()
    }

    override fun onPause() {
        binding.previewView.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility") // hold-to-compare, not a click
    private fun setupControls() {
        val saved = loadSettings()
        binding.smoothSeek.progress = (saved.smooth * 100).roundToInt()
        binding.whitenSeek.progress = (saved.whiten * 100).roundToInt()
        binding.slimSeek.progress = (saved.slim * 100).roundToInt()
        binding.eyesSeek.progress = (saved.eyes * 100).roundToInt()
        binding.beautySwitch.isChecked = saved.enabled

        bindSeekBar(binding.smoothSeek, binding.smoothValue)
        bindSeekBar(binding.whitenSeek, binding.whitenValue)
        bindSeekBar(binding.slimSeek, binding.slimValue)
        bindSeekBar(binding.eyesSeek, binding.eyesValue)

        binding.beautySwitch.setOnCheckedChangeListener { _, checked ->
            applyEnabledState(checked)
            updateSummary()
            binding.previewView.setSettings(currentSettings())
            persistSettings()
        }
        applyEnabledState(saved.enabled)

        binding.shutterButton.setOnClickListener { capture() }
        binding.flipButton.setOnClickListener { switchCamera() }
        binding.retakeButton.setOnClickListener { retake() }
        binding.saveButton.setOnClickListener { save() }
        binding.galleryButton.setOnClickListener { openGallery() }

        binding.resultImage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originalBitmap?.let { binding.resultImage.setImageBitmap(it) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    processedBitmap?.let { binding.resultImage.setImageBitmap(it) }
                    true
                }
                else -> false
            }
        }

        updateSummary()
        binding.previewView.setSettings(currentSettings())
    }

    private fun applyEnabledState(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.35f
        binding.smoothSeek.alpha = alpha
        binding.whitenSeek.alpha = alpha
        binding.slimSeek.alpha = alpha
        binding.eyesSeek.alpha = alpha
        binding.smoothSeek.isEnabled = enabled
        binding.whitenSeek.isEnabled = enabled
        binding.slimSeek.isEnabled = enabled
        binding.eyesSeek.isEnabled = enabled
    }

    private fun bindSeekBar(bar: SeekBar, value: android.widget.TextView) {
        value.text = String.format(Locale.getDefault(), "%d", bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = String.format(Locale.getDefault(), "%d", progress)
                updateSummary()
                binding.previewView.setSettings(currentSettings())
                persistSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // ------------------------------------------------------------ persistence

    private fun loadSettings(): BeautySettings {
        val d = BeautySettings()
        return BeautySettings(
            enabled = prefs.getBoolean(KEY_ENABLED, d.enabled),
            smooth = prefs.getFloat(KEY_SMOOTH, d.smooth),
            whiten = prefs.getFloat(KEY_WHITEN, d.whiten),
            slim = prefs.getFloat(KEY_SLIM, d.slim),
            eyes = prefs.getFloat(KEY_EYES, d.eyes)
        )
    }

    private fun persistSettings() {
        if (!persistEnabled) return
        val s = currentSettings()
        prefs.edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putFloat(KEY_SMOOTH, s.smooth)
            .putFloat(KEY_WHITEN, s.whiten)
            .putFloat(KEY_SLIM, s.slim)
            .putFloat(KEY_EYES, s.eyes)
            .apply()
    }

    private fun updateSummary() {
        if (!binding.beautySwitch.isChecked) {
            binding.beautySummary.text = getString(R.string.beauty_off)
            return
        }
        binding.beautySummary.text = getString(
            R.string.beauty_summary,
            binding.smoothSeek.progress,
            binding.whitenSeek.progress,
            binding.slimSeek.progress,
            binding.eyesSeek.progress
        )
    }

    private fun currentSettings() = BeautySettings(
        enabled = binding.beautySwitch.isChecked,
        smooth = binding.smoothSeek.progress / 100f,
        whiten = binding.whitenSeek.progress / 100f,
        slim = binding.slimSeek.progress / 100f,
        eyes = binding.eyesSeek.progress / 100f
    )

    // --------------------------------------------------------------- camera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView)
                }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                // PAL region: ask AE to avoid mains-flicker bands.
                Camera2Interop.Extender(captureBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    )
                val capture = captureBuilder.build()
                imageCapture = capture

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) {
                Log.e(TAG, "startCamera failed", e)
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        if (busy) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        binding.cameraLabel.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            getString(R.string.camera_front)
        } else {
            getString(R.string.camera_back)
        }
        startCamera()
    }

    private fun capture() {
        if (busy) return
        val capture = imageCapture ?: return
        busy = true
        binding.shutterButton.isEnabled = false
        binding.progressOverlay.visibility = View.VISIBLE

        // Show the frame that is already on screen, the way the stock camera
        // does: the photo appears the instant you press the shutter and is
        // replaced by the processed capture a moment later. Otherwise the
        // ~1 s the HAL needs for the still would be fully visible.
        val token = ++captureToken
        binding.previewView.snapshot { bmp ->
            if (bmp == null) return@snapshot
            if (token != captureToken || !busy || processedBitmap != null) {
                bmp.recycle()
                return@snapshot
            }
            placeholderBitmap?.recycle()
            placeholderBitmap = bmp
            binding.resultImage.setImageBitmap(bmp)
            binding.resultOverlay.visibility = View.VISIBLE
            binding.progressOverlay.visibility = View.GONE
        }

        val t0 = System.currentTimeMillis()
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.i(
                        TAG,
                        "shutter->callback ${System.currentTimeMillis() - t0} ms, " +
                                "${image.width}x${image.height}"
                    )
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = image.imageInfo.rotationDegrees
                    val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
                    image.close()
                    processAsync(bytes, rotation, mirror)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    busy = false
                    binding.shutterButton.isEnabled = true
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.capture_failed, exception.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun processAsync(jpeg: ByteArray, rotation: Int, mirror: Boolean) {
        processingExecutor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw IllegalStateException("decode failed")
                val t1 = System.currentTimeMillis()
                val upright = orient(decoded, rotation, mirror)
                val t2 = System.currentTimeMillis()
                val settings = currentSettings()
                val result = beautyProcessor.process(upright, settings)
                val t3 = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "capture ${decoded.width}x${decoded.height} decode=${t1 - t0} " +
                            "orient=${t2 - t1} beauty=${t3 - t2}"
                )
                runOnUiThread {
                    originalBitmap = upright
                    processedBitmap = result
                    binding.resultImage.setImageBitmap(result)
                    clearPlaceholder()
                    binding.progressOverlay.visibility = View.GONE
                    binding.resultOverlay.visibility = View.VISIBLE
                    busy = false
                    binding.shutterButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "processing failed", e)
                runOnUiThread {
                    binding.progressOverlay.visibility = View.GONE
                    binding.resultOverlay.visibility = View.GONE
                    binding.resultImage.setImageDrawable(null)
                    clearPlaceholder()
                    binding.shutterButton.isEnabled = true
                    busy = false
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.process_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun processFile(path: String) {
        processingExecutor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val decoded = BitmapFactory.decodeFile(path)
                    ?: throw IllegalStateException("decode failed")
                val t1 = System.currentTimeMillis()
                val upright = orientExif(decoded, path)
                val t2 = System.currentTimeMillis()
                val result = beautyProcessor.process(upright, currentSettings())
                val t3 = System.currentTimeMillis()
                // Debug: repeat to see the JIT-warmed cost of the pipeline.
                val repeat = intent.getIntExtra("repeat", 1)
                if (repeat > 1) {
                    for (r in 2..repeat) {
                        beautyProcessor.process(upright, currentSettings())
                    }
                }
                Log.i(
                    TAG,
                    "import ${decoded.width}x${decoded.height} decode=${t1 - t0} " +
                            "orient=${t2 - t1} beauty=${t3 - t2}"
                )
                try {
                    java.io.FileOutputStream(
                        java.io.File(filesDir, "debug_out.jpg")
                    ).use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "debug out save failed", e)
                }
                runOnUiThread {
                    originalBitmap = upright
                    processedBitmap = result
                    binding.resultImage.setImageBitmap(result)
                    binding.progressOverlay.visibility = View.GONE
                    binding.resultOverlay.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "import processing failed", e)
                runOnUiThread {
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.process_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun orientExif(src: Bitmap, path: String): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
        }
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    private fun orient(src: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return src
        // One transform for rotate + mirror. The pivot has to be expressed in
        // the rotated space: for 90/270 the width and height swap.
        val swap = rotation % 180 != 0
        val pw = if (swap) src.height else src.width
        val ph = if (swap) src.width else src.height
        val m = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f, pw / 2f, ph / 2f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    // ---------------------------------------------------------------- result

    private fun retake() {
        captureToken++
        binding.resultImage.setImageDrawable(null)
        binding.resultOverlay.visibility = View.GONE
        clearPlaceholder()
        originalBitmap?.recycle()
        processedBitmap?.recycle()
        originalBitmap = null
        processedBitmap = null
    }

    private fun clearPlaceholder() {
        placeholderBitmap?.recycle()
        placeholderBitmap = null
    }

    private fun save() {
        val bmp = processedBitmap ?: return
        try {
            val name = "BEAUTY_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BeautyCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            resolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            lastSavedUri = uri

            val thumb = Bitmap.createScaledBitmap(bmp, 120, 120, true)
            binding.galleryButton.setImageBitmap(thumb)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Toast.makeText(this, getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = lastSavedUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_gallery_app), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The offscreen EGL context is current on the processing thread, so
        // release it there before shutting the executor down.
        processingExecutor.execute { beautyProcessor.releaseGpu() }
        processingExecutor.shutdown()
        beautyProcessor.close()
        originalBitmap?.recycle()
        processedBitmap?.recycle()
        placeholderBitmap?.recycle()
        cameraProvider?.unbindAll()
    }
}
