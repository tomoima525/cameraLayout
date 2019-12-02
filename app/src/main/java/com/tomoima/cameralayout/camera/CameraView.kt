package com.tomoima.cameralayout.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraManager
import android.util.AttributeSet
import android.util.Size
import android.view.*
import android.view.TextureView.SurfaceTextureListener
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
    private var camera: Camera? = null
    private var previewTexture: AutoFitTextureView //TextureView for Camera
    private var previewSurface: SurfaceTexture? = null // Surface to render preview of camera
    private var previewSize: Size? = null

    init {
        View.inflate(context, R.layout.view_camera, this)
        previewTexture = findViewById(R.id.camera_texture)
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
                camera?.sensorRotation = newOrientation.ordinal
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

        previewTexture.surfaceTextureListener = this

        orientationEventListener.enable()
    }

    // Called at OnDestory
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        camera?.close()
        previewTexture.surfaceTextureListener = null
        orientationEventListener.disable()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        previewSurface = surface
        openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        configureTransform(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true


    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun resumePreview() {
        if(previewTexture.isAvailable) {
            openCamera()
        } else {
            println("====== resume failed")
        }
    }

    fun pausePreview() {
        camera?.close()
    }

    fun changeScreenMode(screenSizeMode: ScreenSizeMode) {
        camera?.close()
        val point = Point()
        getActivity(context)?.windowManager?.defaultDisplay?.getRealSize(point)

        camera?.changeScreenMode(screenSizeMode, point)
        openCamera()
    }

    private fun openCamera() {
        if(!previewTexture.isAvailable) return

        val rotation = getActivity(context)?.windowManager?.defaultDisplay?.rotation ?: 0
        camera?.deviceRotation = rotation

        val width = width
        val height = height
        if (width == 0 || height == 0) {
            throw IllegalStateException("preview is not properly set")
        }

        camera?.let {
            val isDimensionSwapped = isDimensionSwapped(it)
            setUpCameraOutputs(width, height, it, isDimensionSwapped)
            updateAspectRatio(it.screenSizeMode , isDimensionSwapped)
            configureTransform(width, height)
            it.open()

            previewSurface?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
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
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     * @param camera The camera object use for capturing
     */
    private fun setUpCameraOutputs(width: Int, height: Int, camera: Camera, isSwappedDimension: Boolean) {
        try {
            val activity = getActivity(context)
            val largest = camera.getCaptureSize()

            val displaySize = Point()
            activity?.windowManager?.defaultDisplay?.getRealSize(displaySize)
            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            if (isSwappedDimension) {
                previewSize = camera.chooseOptimalSize(
                    height,
                    width,
                    displaySize.y,
                    displaySize.x,
                    largest)
            } else {
                previewSize = camera.chooseOptimalSize(
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
        }
    }

    private fun updateAspectRatio(screenSizeMode: ScreenSizeMode, isSwappedDimension: Boolean) {
        when(screenSizeMode) {
            ScreenSizeMode.FULL_SCREEN -> {
                val activity = getActivity(context)
                val displaySize = Point()
                activity?.windowManager?.defaultDisplay?.getSize(displaySize)

                if(isSwappedDimension) {
                    println("====== $displaySize $height $width")
                    previewTexture.setAspectRatio(displaySize.x, displaySize.y)
                } else {
                    previewTexture.setAspectRatio(displaySize.y, displaySize.x)
                }
            }
            ScreenSizeMode.WIDTH_MATCH -> {
                if(isSwappedDimension) {
                    previewTexture.setAspectRatio(previewSize!!.height, previewSize!!.width)
                } else {
                    previewTexture.setAspectRatio(previewSize!!.width, previewSize!!.height)
                }
            }
        }
    }

    /**
     * Configures the necessary Matrix transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == previewSize || null == context) {
            return
        }

        val rotation = getActivity(context)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        previewSize?.apply {
            val bufferRect = RectF(0f, 0f, height.toFloat(), width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                val scale = max(
                    viewHeight.toFloat() / height,
                    viewWidth.toFloat() / width)
                matrix.apply {
                    setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
        }
        previewTexture.setTransform(matrix)
    }
}