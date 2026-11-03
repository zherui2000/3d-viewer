package com.inspect3d.viewer

import android.app.Application
import android.util.Log
import com.google.android.filament.Filament

class Inspect3dApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Loads Filament's native library. Must run before any Engine is created, and
        // doing it here keeps the first model load free of that cost.
        //
        // A failure here (no native library for this device's ABI, for example) must not
        // take the process down on launch: the UI can still start and report why the
        // renderer is unavailable, which is far more useful than disappearing.
        runCatching { Filament.init() }
            .onFailure { Log.e(TAG, "Filament native library failed to load", it) }
    }

    private companion object {
        const val TAG = "Inspect3D"
    }
}
