package com.tomoima.cameralayout.camera


import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.OrientationEventListener
import android.view.Surface
import com.tomoima.cameralayout.utils.getCaptureSize
import com.tomoima.cameralayout.utils.isAutoExposureSupported
import com.tomoima.cameralayout.utils.isAutoWhiteBalanceSupported
import com.tomoima.cameralayout.utils.isContinuousAutoFocusSupported
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs


private const val TAG = "CAMERA"

private enum class State {
    PREVIEW,
    WAITING_LOCK,
    WAITING_PRECAPTURE,
    WAITING_NON_PRECAPTURE,
    TAKEN
}

enum class ScreenSizeMode {
    WIDTH_MATCH, FULL_SCREEN
}

val ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}

private const val MAX_PREVIEW_WIDTH = 1920
private const val MAX_PREVIEW_HEIGHT = 1080

interface ImageHandler {
    fun handleImage(image: Image)
}

interface OnFocusListener {
    fun onFocusStateChanged(focusState: Int)
}

private class ComparableByArea : Comparator<Size> {
    override fun compare(o1: Size, o2: Size): Int {
        return (o1.height * o1.width) - (o2.height * o2.width)
    }
}

/**
 * Compare by the order of the closest ratio
 */
private class ComparableByRatio(private val ratio: Double) : Comparator<Size> {
    override fun compare(o1: Size, o2: Size): Int {
        val r = abs((o2.width.toDouble() / o2.height) - ratio) - abs((o1.width.toDouble() / o1.height) - ratio)
        return r.compareTo(0)
    }
}

/**
 * Controller class that operates Non-UI Camera activity
 */
class Camera constructor(private val cameraManager: CameraManager, private val cameraId: String) {
    private val characteristics: CameraCharacteristics =
        cameraManager.getCameraCharacteristics(cameraId)


    private val openLock = Semaphore(1)

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var focusListener: OnFocusListener? = null
    private var state = State.PREVIEW
    private var aeMode = CaptureRequest.CONTROL_AE_MODE_ON
    private var preAfState: Int? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var surface: Surface? = null
    private var isClosed = true
    var deviceRotation: Int = 0 // Device rotation is defined by Screen Rotation
    var sensorRotation: Int = 0 // Sensor rotation is defined by sensor manager

    var screenSizeMode: ScreenSizeMode = ScreenSizeMode.WIDTH_MATCH
        private set
    private var screenRatio: Double = 1.0


    companion object {
        // Make thread-safe Singleton
        @Volatile var instance: Camera? = null
            private set

        fun initInstance(cameraManager: CameraManager, cameraId: String): Camera {
            val i = instance
            if (i != null) {
                return i
            }
            return synchronized(this) {
                val created = Camera(cameraManager, cameraId)
                instance = created
                created
            }
        }
    }

    // Callbacks
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            cameraDevice = camera
            openLock.release()
            isClosed = false
        }

        override fun onClosed(camera: CameraDevice?) {
            isClosed = true
        }

        override fun onDisconnected(camera: CameraDevice?) {
            openLock.release()
            camera?.close()
            cameraDevice = null
            isClosed = true
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            openLock.release()
            camera?.close()
            cameraDevice = null
            isClosed = true
        }
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            //TODO: handle error
        }

        override fun onConfigured(session: CameraCaptureSession) {
            // if camera is closed
            if(isClosed) return
            captureSession = session
            startPreview()
        }

    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                State.PREVIEW -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                    if (afState == preAfState) {
                        return
                    }
                    preAfState = afState
                    focusListener?.onFocusStateChanged(afState)
                }

                State.WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    // Auto Focus state is not ready in the first place
                    if (afState == null) {
                        runPreCapture()
                    } else if (CaptureResult.CONTROL_AF_STATE_INACTIVE == afState ||
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            captureStillPicture()
                        } else {
                            runPreCapture()
                        }
                    } else {
                        captureStillPicture()
                    }
                }

                State.WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                        || aeState == CaptureRequest.CONTROL_AE_STATE_PRECAPTURE
                        || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                        || aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED) {
                        state = State.WAITING_NON_PRECAPTURE
                    }
                }

                State.WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureRequest.CONTROL_AE_STATE_PRECAPTURE) {
                        captureStillPicture()
                    }
                }
                else -> { }
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }

    }

    // Camera interfaces
    /**
     * Open camera and setup background handler
     */
    fun open() {

        try {
            if(!openLock.tryAcquire(3L, TimeUnit.SECONDS)) {
                throw IllegalStateException("Camera launch failed")
            }

            if(cameraDevice != null) {
                openLock.release()
                return
            }

            startBackgroundHandler()

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e : SecurityException) {

        }
    }

    /**
     * Start camera. Should be called after open() is successful
     */
    fun start(surface: Surface) {
        this.surface = surface
        val comparable = getComparable()
        // setup camera session
        val size = characteristics.getCaptureSize(comparable)
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        cameraDevice?.createCaptureSession(
            listOf(surface, imageReader?.surface),
            captureStateCallback,
            backgroundHandler
        )
    }

    fun takePicture(handler : ImageHandler) {
        checkNotNull(cameraDevice) { "Camera device not ready" }

        if(isClosed) return

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            handler.handleImage(image = image)
        }, backgroundHandler)

        lockFocus()
    }

    fun close() {
        try {
            if(openLock.tryAcquire(3, TimeUnit.SECONDS))
                isClosed = true
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            surface?.release()
            surface = null

            imageReader?.close()
            imageReader = null
            stopBackgroundHandler()
        } catch (e: InterruptedException) {
        } finally {
            openLock.release()
        }
    }

    fun changeScreenMode(screenSizeMode: ScreenSizeMode, screenSize: Point) {
        this.screenSizeMode = screenSizeMode
        screenRatio = screenSize.let { it.y.toDouble().div(it.x) }
    }

    // internal methods

    private fun startBackgroundHandler() {
        if (backgroundThread != null) return

        backgroundThread = HandlerThread("Camera-$cameraId").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundHandler() {
        backgroundThread?.quitSafely()
        try {
            // TODO: investigate why thread does not end when join is called
            // backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
        }
    }

    private fun startPreview() {
        try {
            if(!openLock.tryAcquire(1L, TimeUnit.SECONDS)) return
            if(isClosed) return
            state = State.PREVIEW
            val builder = createPreviewRequestBuilder()

            captureSession?.setRepeatingRequest(
                builder?.build(), captureCallback, backgroundHandler)

        } catch (e1: IllegalStateException) {

            println("===== startPreview e1 $e1")
        } catch (e2: CameraAccessException) {

            println("===== startPreview e2 $e2")
        } catch (e3: InterruptedException) {
            println("===== startPreview e3 $e3")

        } finally {
            println( "===== startPreview lock released")
            openLock.release()
        }
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewRequestBuilder(): CaptureRequest.Builder? {
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder?.addTarget(surface)
        enableDefaultModes(builder)
        return builder
    }

    private fun enableDefaultModes(builder: CaptureRequest.Builder?) {
        if(builder == null) return

        // Auto focus should be continuous for camera preview.
        // Use the same AE and AF modes as the preview.
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        if (characteristics.isContinuousAutoFocusSupported()) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            )
        }

        if (characteristics.isAutoExposureSupported(aeMode)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        if (characteristics.isAutoWhiteBalanceSupported()) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            state = State.WAITING_LOCK

            val builder = createPreviewRequestBuilder()

            if(!characteristics.isContinuousAutoFocusSupported()) {
                // If continuous AF is not supported , start AF here
                builder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
            }
            captureSession?.capture(builder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            println("lockFocus $e")
        }
    }

    private fun runPreCapture() {
        try {
            state = State.WAITING_PRECAPTURE
            val builder = createPreviewRequestBuilder()
            builder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            captureSession?.capture(builder?.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            println("runPreCapture $e")
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        state = State.TAKEN
        try {
            // This is the CaptureRequest.Builder that we use to take a picture.
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            enableDefaultModes(builder)
            builder?.addTarget(imageReader?.surface)
            builder?.addTarget(surface)
            captureSession?.stopRepeating()
            captureSession?.capture(
                builder?.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult) {
                        /** do nothing **/
                    }
                },
                backgroundHandler)

        } catch (e: CameraAccessException) {
            println("captureStillPicture $e")
        }
    }

    private fun getComparable() = when (screenSizeMode) {
        ScreenSizeMode.WIDTH_MATCH -> ComparableByArea()
        ScreenSizeMode.FULL_SCREEN -> ComparableByRatio(screenRatio)
    }
    /**
     * Retrieves the image orientation from the specified screen rotation.
     */
    fun getImageOrientation(): Int {
        if (deviceRotation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(deviceRotation) + sensorOrientation + 270) % 360
    }

    fun getCaptureSize(): Size {
        val comparable = getComparable()
        return characteristics.getCaptureSize(comparable)
    }

    /**
     * Get sensor orientation.
     * 0, 90, 180, 270.
     */
    fun getSensorOrientation() = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /**
     * Given `choices` of `Size`s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */
    fun chooseOptimalSize(textureViewWidth: Int,
                          textureViewHeight: Int,
                          maxWidth: Int,
                          maxHeight: Int,
                          aspectRatio: Size): Size {
        var _maxWidth = maxWidth
        var _maxHeight = maxHeight

        if (_maxWidth > MAX_PREVIEW_WIDTH) {
            _maxWidth = MAX_PREVIEW_WIDTH
        }

        if (_maxHeight > MAX_PREVIEW_HEIGHT) {
            _maxHeight = MAX_PREVIEW_HEIGHT
        }

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(0, 0)

        val choices = map.getOutputSizes(SurfaceTexture::class.java)

        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<Size>()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        for (option in choices) {
            if (option.width <= _maxWidth && option.height <= _maxHeight &&
                option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }
        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        return when {
            bigEnough.size > 0 -> Collections.min(bigEnough, ComparableByArea())
            notBigEnough.size > 0 -> Collections.max(notBigEnough, ComparableByArea())
            else -> choices[0]
        }
    }
}