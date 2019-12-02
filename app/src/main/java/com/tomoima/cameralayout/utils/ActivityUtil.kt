package com.tomoima.cameralayout.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

fun getActivity(context: Context): Activity? {
    var ctx = context
    do {
        if (ctx is Activity) {
            return ctx
        }
        ctx = (context as ContextWrapper).baseContext
    } while (ctx is ContextWrapper)
    return null
}