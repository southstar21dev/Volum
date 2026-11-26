package dev.southstar21.volum.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.southstar21.volum.ui.VolumApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Volum is light-only: dark bar icons on transparent bars, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        applyOrientationPolicy()
        val container = (application as VolumApplication).container
        splash.setKeepOnScreenCondition { !container.ready.value }
        setContent { VolumApp(container) }
    }

    // The manifest declares configChanges for screen size, so folding or unfolding lands here
    // instead of recreating the activity.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    // Phones stay in portrait; tablets and foldables (smallest width of 600dp or more) rotate freely.
    // Android 16+ ignores orientation locks on those screens anyway, so this matches what it does.
    private fun applyOrientationPolicy() {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private companion object {
        const val LARGE_SCREEN_DP = 600
    }
}
