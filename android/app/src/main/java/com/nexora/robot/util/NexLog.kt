package com.nexora.robot.util

import android.util.Log

/**
 * NEX's only logging entry point.
 *
 * Every message passes through [LogScrubber] before it reaches logcat, so user speech,
 * identifiers and credential-shaped strings cannot leak into a bug report.
 */
object NexLog {

    private const val TAG = "NEX"

    /** Turn off in release builds by calling [configure]. */
    @Volatile
    var enabled: Boolean = true
        private set

    fun configure(verbose: Boolean) {
        enabled = verbose
    }

    fun d(message: String) {
        if (enabled) Log.d(TAG, LogScrubber.scrub(message))
    }

    fun i(message: String) {
        if (enabled) Log.i(TAG, LogScrubber.scrub(message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, LogScrubber.scrub(message), throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, LogScrubber.scrub(message), throwable)
    }
}
