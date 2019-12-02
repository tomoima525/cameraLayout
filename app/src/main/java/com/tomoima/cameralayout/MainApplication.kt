package com.tomoima.cameralayout

import android.app.Application
import timber.log.Timber.DebugTree
import timber.log.Timber


class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
    }
}