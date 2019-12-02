package com.tomoima.cameralayout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tomoima.cameralayout.camera.CameraView
import com.tomoima.cameralayout.camera.ScreenSizeMode

class MainActivity : AppCompatActivity() {

    private val cameraView: CameraView by lazy { findViewById<CameraView>(R.id.camera_view) }
    companion object {
        private const val REQUEST_CAMERA = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!hasCameraPermission() || !hasStoragePermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CAMERA
            )
        }
        findViewById<View>(R.id.btn_full_size).setOnClickListener {
            cameraView.changeScreenMode(ScreenSizeMode.FULL_SCREEN)
        }
        findViewById<View>(R.id.btn_width_match).setOnClickListener {
            cameraView.changeScreenMode(ScreenSizeMode.WIDTH_MATCH)
        }
    }

    override fun onResume() {
        super.onResume()
        cameraView.resumePreview()
    }

    override fun onPause() {
        super.onPause()
        cameraView.pausePreview()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA && grantResults.size == 2
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && grantResults[1] == PackageManager.PERMISSION_GRANTED
        ) {
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
