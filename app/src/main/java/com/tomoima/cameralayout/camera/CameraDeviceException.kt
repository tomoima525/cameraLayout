package com.tomoima.cameralayout.camera

import android.util.AndroidException

class CameraDeviceException: Exception("Camera Device not ready", AndroidException())