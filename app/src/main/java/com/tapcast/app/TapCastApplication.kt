package com.tapcast.app

import android.app.Application
import android.util.Log
import com.ffalcon.mercury.android.sdk.MercurySDK

/** Initializes RayNeo's gesture/display bridge before the first activity. */
class TapCastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { MercurySDK.init(this) }
            .onSuccess { Log.i(TAG, "Mercury SDK initialized") }
            .onFailure { Log.w(TAG, "Mercury SDK initialization failed", it) }
    }

    private companion object {
        const val TAG = "TapCastMercury"
    }
}
