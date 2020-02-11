package com.tomoima.cameralayout.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.util.AttributeSet
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.View.OnTouchListener
import android.widget.LinearLayout
import android.widget.Toast
import com.tomoima.cameralayout.R
import com.tomoima.cameralayout.utils.getActivity
import kotlin.math.max


enum class Orientation(degree: Int) {
    PORTRAIT(0),
    LANDSCAPE(270),
    REVERSE_PORTRAIT(180),
    REVERSE_LANDSCAPE(90)
}

@SuppressLint("ViewConstructor")
class CameraView(
    context: Context,
    attributeSet: AttributeSet
) : LinearLayout(context, attributeSet), SurfaceTextureListener, OnTouchListener {
    private val TAG = CameraView::class.java.simpleName
    private lateinit var camera: Camera
    private var previewTextureView: AutoFitTextureView //TextureView for Camera
    private var previewSurface: SurfaceTexture? = null // Surface to render preview of camera

    init {
        View.inflate(context, R.layout.view_camera, this)
        previewTextureView = findViewById(R.id.camera_texture)
    }


    // Orientation Listener

    private val orientationEventListener = object: OrientationEventListener(context) {
        private var previous: Orientation? = null
        override fun onOrientationChanged(orientation: Int) {
            val newOrientation = when (orientation) {
                in 60..140 -> Orientation.REVERSE_LANDSCAPE
                in 140..220 -> Orientation.REVERSE_PORTRAIT
                in 220..300 -> Orientation.LANDSCAPE
                else -> Orientation.PORTRAIT
            }

            if (previous != newOrientation) {
                previous = newOrientation
                camera.sensorRotation = newOrientation.ordinal
            }
        }
    }

    // View related methods

    // Called at OnCreate
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // use back camera for now
        camera = Camera.initInstance(cameraManager, "0")

        previewTextureView.surfaceTextureListener = this

        orientationEventListener.enable()
    }

    // Called at OnDestroy
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        camera.close()
        previewTextureView.surfaceTextureListener = null
        orientationEventListener.disable()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        previewSurface = surface
        openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val isSwapped = isDimensionSwapped(camera)
        setupPreviewSize(camera, isSwapped).also {
            configureTransform(it, width, height)
        }
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true


    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        return false
    }

    fun resumePreview() {
        if(previewTextureView.isAvailable) {
            openCamera()
        }
    }

    fun pausePreview() {
        camera.close()
    }

    fun changeScreenMode(screenSizeMode: ScreenSizeMode) {
        camera.close()
        val point = Point()
        getActivity(context)?.windowManager?.defaultDisplay?.getRealSize(point)

        camera.changeScreenMode(screenSizeMode, point)
        openCamera()
    }

    private fun openCamera() {
        if(!previewTextureView.isAvailable) return

        val rotation = getActivity(context)?.windowManager?.defaultDisplay?.rotation ?: 0
        camera.deviceRotation = rotation

        val width = width
        val height = height
        check(!(width == 0 || height == 0)) { "preview is not properly set" }

        camera.let {
            val isDimensionSwapped = isDimensionSwapped(it)
            val previewSize = setupPreviewSize(it, isDimensionSwapped)
            updateAspectRatio(it.screenSizeMode , previewSize, isDimensionSwapped)
            configureTransform(previewSize, width, height)
            it.open()

            previewSurface?.setDefaultBufferSize(previewSize.width, previewSize.height)
            it.start(Surface(previewSurface))
        }
    }

    // Find out if we need to swap dimension to get the preview size relative to sensor
    // coordinate.
    private fun isDimensionSwapped(camera: Camera): Boolean {
        val activity = getActivity(context)
        val displayRotation = activity?.windowManager?.defaultDisplay?.rotation ?: 0
        val sensorOrientation = camera.getSensorOrientation()
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 ||
                    sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 ||
                    sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> Toast.makeText(context, "rotation error", Toast.LENGTH_LONG).show()
        }
        return swappedDimensions
    }

    /**
     * Sets up preview size
     *
     * @param camera The camera object use for capturing
     * @param isSwappedDimension If the screen dimension is swapped
     */
    private fun setupPreviewSize(camera: Camera, isSwappedDimension: Boolean) : Size {
        try {
            val activity = getActivity(context)
            val largest = camera.getCaptureSize()

            val displaySize = Point()
            activity?.windowManager?.defaultDisplay?.getRealSize(displaySize)

            return if (isSwappedDimension) {
                camera.getOptimalPreviewSize(
                    height,
                    width,
                    displaySize.y,
                    displaySize.x,
                    largest)
            } else {
                camera.getOptimalPreviewSize(
                    width,
                    height,
                    displaySize.x,
                    displaySize.y,
                    largest)
            }

        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Toast.makeText(context, "rotation error", Toast.LENGTH_LONG).show()
            return Size(0,0)
        }
    }

    /**
     * Setup the aspect ratio of preview Texture.
     * When Full screen mode, the aspect ratio should be determined by the display size
     * When Width match mode, the aspect ratio should be determined by the preview size. The preview
     * size has the closest aspect ratio to the screen capture size
     */
    private fun updateAspectRatio(screenSizeMode: ScreenSizeMode, previewSize:Size, isSwappedDimension: Boolean) {
        when(screenSizeMode) {
            ScreenSizeMode.FULL_SCREEN -> {
                val activity = getActivity(context)
                val displaySize = Point()
                activity?.windowManager?.defaultDisplay?.getSize(displaySize)

                if(isSwappedDimension) {
                    previewTextureView.setAspectRatio(displaySize.x, displaySize.y)
                } else {
                    previewTextureView.setAspectRatio(displaySize.y, displaySize.x)
                }
            }
            ScreenSizeMode.WIDTH_MATCH -> {
                if(isSwappedDimension) {
                    previewTextureView.setAspectRatio(previewSize.height, previewSize.width)
                } else {
                    previewTextureView.setAspectRatio(previewSize.width, previewSize.height)
                }
            }
        }
    }

    /**
     * Configures the necessary Matrix transformation to `previewTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `previewTextureView` is fixed.
     *
     * @param viewWidth  The width of `previewTextureView`
     * @param viewHeight The height of `previewTextureView`
     */
    private fun configureTransform(previewSize: Size, viewWidth: Int, viewHeight: Int) {
        if (previewSize.width == 0 || null == context) {
            return
        }

        val rotation = getActivity(context)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                val scale = max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
                matrix.apply {
                    setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
        previewTextureView.setTransform(matrix)
    }
}