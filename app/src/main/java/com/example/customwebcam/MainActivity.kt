package com.example.customwebcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isConnected = false

    // State untuk fitur baru
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentResState = 1 // 0=480p, 1=720p, 2=1080p
    private var targetResolution = Size(1280, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupButtons()

        val sharedPrefs = getSharedPreferences("WebcamPrefs", MODE_PRIVATE)
        val savedIp = sharedPrefs.getString("pc_ip", "127.0.0.1") ?: "127.0.0.1"
        findViewById<EditText>(R.id.editIpAddress).setText(savedIp)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            connectSocket(savedIp)
        }
    }

    private fun setupButtons() {
        val btnSwitch = findViewById<Button>(R.id.btnSwitchCamera)
        val btnRes = findViewById<Button>(R.id.btnResolution)

        btnSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                btnSwitch.text = "Kamera Belakang"
                CameraSelector.LENS_FACING_FRONT
            } else {
                btnSwitch.text = "Kamera Depan"
                CameraSelector.LENS_FACING_BACK
            }
            startCamera() // Restart kamera dengan arah baru
        }

        btnRes.setOnClickListener {
            currentResState = (currentResState + 1) % 3
            when (currentResState) {
                0 -> {
                    targetResolution = Size(640, 480)
                    btnRes.text = "Res: 480p SD"
                }
                1 -> {
                    targetResolution = Size(1280, 720)
                    btnRes.text = "Res: 720p HD"
                }
                2 -> {
                    targetResolution = Size(1920, 1080)
                    btnRes.text = "Res: 1080p FHD"
                }
            }
            startCamera() // Restart kamera dengan resolusi baru
        }

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val editIpAddress = findViewById<EditText>(R.id.editIpAddress)
        btnConnect.setOnClickListener {
            val ip = editIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                getSharedPreferences("WebcamPrefs", MODE_PRIVATE).edit().putString("pc_ip", ip).apply()
                connectSocket(ip)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val editIp = findViewById<EditText>(R.id.editIpAddress)
            connectSocket(editIp.text.toString())
        }
    }

    private fun connectSocket(ip: String) {
        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = "Konek ke $ip..."
        
        scope.launch {
            try {
                isConnected = false
                socket?.close()
                
                socket = Socket(ip, 5000)
                outputStream = socket?.getOutputStream()
                isConnected = true
                
                withContext(Dispatchers.Main) {
                    statusText.text = "[LIVE] Terhubung ke $ip"
                    startCamera()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "[Offline] Gagal hubung ke $ip"
                    // Walaupun offline, kita tetap bisa preview kamera lokal
                    startCamera()
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            // Menerapkan strategi resolusi pilihan pengguna
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(targetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        processImage(image)
                    }
                }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch(exc: Exception) {
                Log.e("Webcam", "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(image: ImageProxy) {
        if (!isConnected) {
            image.close()
            return
        }
        
        try {
            val bitmap = image.toBitmap()
            val stream = ByteArrayOutputStream()
            // Mengatur kompresi dinamis berdasarkan resolusi agar FPS terjaga
            val quality = if (currentResState == 2) 65 else 80 
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val jpegData = stream.toByteArray()
            
            val sizeBuffer = ByteBuffer.allocate(4)
            sizeBuffer.putInt(jpegData.size)
            
            outputStream?.write(sizeBuffer.array())
            outputStream?.write(jpegData)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e("Webcam", "Error sending frame", e)
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        isConnected = false
        try {
            socket?.close()
        } catch (e: Exception) {}
    }
}
