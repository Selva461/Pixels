package com.pixels.enhancer.core.logging

import android.util.Log

/** Structured events go to Logcat in debug builds; errors are always logged (metadata only). */
class LogcatLogger(private val verbose: Boolean) : EnhancerLogger {

    override fun event(name: String, fields: Map<String, Any?>) {
        if (verbose) Log.i(TAG, LogFormat.format(name, fields))
    }

    override fun error(name: String, fields: Map<String, Any?>, throwable: Throwable?) {
        Log.e(TAG, LogFormat.format(name, fields), throwable)
    }

    private companion object {
        const val TAG = "Enhancer"
    }
}
