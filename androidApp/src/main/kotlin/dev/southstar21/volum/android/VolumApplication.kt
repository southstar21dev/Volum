package dev.southstar21.volum.android

import android.app.Application
import dev.southstar21.volum.data.AppContainer
import dev.southstar21.volum.platform.AndroidPlatform

class VolumApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(this, BuildConfig.POCKETBASE_URL, BuildConfig.VERSION_NAME)
    }
}
