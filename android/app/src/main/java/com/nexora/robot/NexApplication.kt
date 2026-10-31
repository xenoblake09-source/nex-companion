package com.nexora.robot

import android.app.Application
import com.nexora.robot.util.NexLog

/**
 * Application entry point.
 *
 * Deliberately minimal: NEX starts no background work, no services, no workers and no analytics
 * SDK. The only thing it does at process start is set the log verbosity, because a release build
 * must never emit conversation content into logcat.
 */
class NexApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NexLog.configure(verbose = BuildConfig.DEBUG)
        NexLog.i(
            "application created verbose=${BuildConfig.DEBUG} version=${BuildConfig.VERSION_NAME}",
        )
    }
}
