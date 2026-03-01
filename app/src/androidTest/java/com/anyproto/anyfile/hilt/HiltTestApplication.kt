package com.anyproto.anyfile.hilt

import android.app.Application
import androidx.work.Configuration

/**
 * Custom test application for integration tests.
 *
 * This application class is used by Android instrumentation tests
 * that require Hilt dependency injection.
 *
 * Note: We don't use @HiltAndroidApp here because the tests use
 * @HiltAndroidTest annotation, which provides its own Hilt
 * test infrastructure. Using both would cause a compilation error:
 * "Cannot process test roots and app roots in the same compilation unit"
 *
 * The @HiltAndroidTest annotation on test classes handles all
 * Hilt initialization automatically.
 *
 * Implements Configuration.Provider to provide WorkManager configuration
 * for tests, since the main app disables WorkManager's auto-initialization.
 */
class HiltTestApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
