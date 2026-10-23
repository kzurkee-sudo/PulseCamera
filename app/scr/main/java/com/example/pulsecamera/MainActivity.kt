package com.example.pulsecamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var bpmText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var measuring = false
    @Volatile private var camera: Camera? = null
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastBpm = 0
    private var lastPeakTime = 0L
    private var smoothed = 0.0
    private var previous = 0.0
    private var rising = false
    private var sampleCount = 0
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera()
            else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
        stopButton.setOnClickListener { stopCamera("Measurement stopped") }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 30, 28, 30)
            setBackgroundColor(Color.rgb(250, 250, 250))
        }
        val title = TextView(this).apply {
            text = "PULSE CAMERA"
            textSize = 22f
            setTextColor(Color.rgb(35, 35, 35))
            gravity = Gravity.CENTER
        }
        bpmText = TextView(this).apply {
            text = "—"
            textSize = 76f
            setTextColor(Color.rgb(190, 35, 55))
            gravity = Gravity.CENTER
        }
        val bpmLabel = TextView(this).apply {
            text = "beats per minute"
            textSize = 18f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Place your fingertip gently over the rear camera and flash."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 24)
        }
        startButton = Button(this).apply { text = "Start measurement" }
        stopButton = Button(this).apply { text = "Stop"; isEnabled = false }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(bpmText, LinearLayout.LayoutParams(-1, -2))
        root.addView(bpmLabel, LinearLayout.LayoutParams(-1, -2))
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        root.addView(startButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(stopButton, LinearLayout.LayoutParams(-1, -2))
        val note = TextView(this).apply {
            text = "For wellness use only. Not an ECG or a medical diagnostic device."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(8, 26, 8, 0)
        }
        root.addView(note, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else statusText.text = "Camera permission is required to measure pulse."
    }

    private fun startCamera() {
        if (measuring) return
        statusText.text = "Starting camera… cover the rear lens and flash with your fingertip."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                resetSignal()
                measuring = true
                analysis.setAnalyzer(executor) { image ->
                    try { analyzeFrame(image) } finally { image.close() }
                }
                provider.unbindAll()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                camera = provider.bindToLifecycle(this, selector, analysis)
                camera?.cameraControl?.enableTorch(true)
                runOnUiThread {
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    statusText.text = "Measuring… keep your finger still and use gentle pressure."
                }
            } catch (e: Exception) {
                measuring = false
                runOnUiThread { statusText.text = "Could not start camera: ${e.localizedMessage ?: "unsupported camera"}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!measuring) return
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val left = width / 3
        val right = width * 2 / 3
        val top = height / 3
        val bottom = height * 2 / 3
        var sum = 0L
        var count = 0
        for (y in top until bottom step 3) {
            for (x in left until right step 3) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xff)
                    count++
                }
            }
        }
        if (count == 0) return
        val value = sum.toDouble() / count
        val now = SystemClock.elapsedRealtime()
        synchronized(samples) {
            samples.addLast(now to value)
            while (samples.isNotEmpty() && now - samples.first().first > 12_000) samples.removeFirst()
            sampleCount++
            // Slowly track baseline; pulse peaks are detected in the residual signal.
            smoothed = if (sampleCount == 1) value else smoothed * 0.88 + value * 0.12
            val residual = value - smoothed
            val threshold = 0.35
            if (residual > threshold && previous <= threshold) rising = true
            if (residual < -threshold && rising) {
                val peak = now
                val interval = if (lastPeakTime > 0) peak - lastPeakTime else 0
                if (interval in 300..1500) {
                    val bpm = (60_000.0 / interval).toInt()
                    if (bpm in 40..200) {
                        lastBpm = if (lastBpm == 0) bpm else ((lastBpm * 0.65) + (bpm * 0.35)).toInt()
                        runOnUiThread {
                            bpmText.text = lastBpm.toString()
                            statusText.text = if (now - startedAt < 5000) "Detecting pulse… keep your finger still." else "Pulse detected. Keep still for a steadier result."
                        }
                    }
                }
                lastPeakTime = peak
                rising = false
            }
            previous = residual
            if (startedAt == 0L) startedAt = now
            if (now - startedAt > 8000 && lastBpm == 0 && sampleCount % 20 == 0) {
                runOnUiThread { statusText.text = "No clear pulse yet. Adjust your finger gently and keep still." }
            }
        }
    }

    private fun resetSignal() {
        synchronized(samples) { samples.clear() }
        lastBpm = 0
        lastPeakTime = 0L
        smoothed = 0.0
        previous = 0.0
        rising = false
        sampleCount = 0
        startedAt = 0L
        bpmText.text = "—"
    }

    private fun stopCamera(message: String) {
        measuring = false
        camera?.cameraControl?.enableTorch(false)
        camera = null
        val future = ProcessCameraProvider.getInstance(this)
        if (future.isDone) {
            try { future.get().unbindAll() } catch (_: Exception) {}
        }
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = message
    }

    override fun onDestroy() {
        stopCamera("App closed")
        executor.shutdown()
        super.onDestroy()
    }
}
