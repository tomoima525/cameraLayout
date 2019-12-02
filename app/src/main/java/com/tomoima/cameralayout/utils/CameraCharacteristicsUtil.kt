package com.tomoima.cameralayout.utils

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Size

fun CameraCharacteristics.isSupported(
    modes: CameraCharacteristics.Key<IntArray>, mode: Int
): Boolean {
    val ints = this.get(modes) ?: return false
    for (value in ints) {
        if (value == mode) {
            return true
        }
    }
    return false
}

fun CameraCharacteristics.isAutoExposureSupported(mode: Int): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
        mode
    )


fun CameraCharacteristics.isContinuousAutoFocusSupported(): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)


fun CameraCharacteristics.isAutoWhiteBalanceSupported(): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO)

fun CameraCharacteristics.getCaptureSize(comparator: Comparator<Size>): Size {
    val map: StreamConfigurationMap =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    return map.getOutputSizes(ImageFormat.JPEG)
        .asList()
        .maxWith(comparator) ?: Size(0, 0)
}

