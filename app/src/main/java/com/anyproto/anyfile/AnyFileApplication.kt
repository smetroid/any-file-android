package com.anyproto.anyfile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AnyFileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialization will be handled by Hilt modules
    }
}
