package com.noahlangat.relay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for the Relay App
 */
@HiltAndroidApp
class RelayApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In production, you might want to use a different tree
            // that logs to file or crash reporting service
            Timber.plant(ReleaseTree())
        }
        
        Timber.i("RelayApplication created")
    }
    
    /**
     * Production logging tree that excludes verbose and debug logs
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.INFO) {
                // Log to crash reporting service or file
                // For now, we'll use Android's Log
                android.util.Log.println(priority, tag, message)
            }
        }
    }
}