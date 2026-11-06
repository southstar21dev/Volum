package dev.southstar21.volum.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/** Set once from the Application before anything else touches the platform layer. */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    lateinit var context: Context
        private set
    var baseUrl: String = "https://apivolum.southstar21.dev"
    var version: String = "1.0.0"

    fun init(context: Context, baseUrl: String, version: String) {
        this.context = context.applicationContext
        this.baseUrl = baseUrl
        this.version = version
    }
}

actual fun appDataDirectory(): String = AndroidPlatform.context.filesDir.absolutePath

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.SECONDS)
        }
    }
    expectSuccess = false
}

actual val isIos: Boolean = false

actual val pocketBaseUrl: String get() = AndroidPlatform.baseUrl

actual val appVersion: String get() = AndroidPlatform.version

// Looked up once: every tap with haptics (and each tab crossed while dragging the dock) ticks.
private val vibrator: Vibrator by lazy {
    val context = AndroidPlatform.context
    if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}

/**
 * Every haptic is one short click, never a pattern: the system's own predefined effects (the ones the
 * keyboard and system buttons use, tuned per phone for a crisp, damped motor stop) played as touch
 * feedback. Bigger moments are a heavier click, not a longer one.
 */
actual fun haptic(kind: Haptic) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val effect = VibrationEffect.createPredefined(
                when (kind) {
                    Haptic.Tick, Haptic.Undo -> VibrationEffect.EFFECT_TICK
                    Haptic.Tap, Haptic.SetDone, Haptic.Milestone -> VibrationEffect.EFFECT_CLICK
                    Haptic.ExerciseDone, Haptic.WorkoutDone -> VibrationEffect.EFFECT_HEAVY_CLICK
                },
            )
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(effect)
            }
        } else {
            val (ms, amp) = when (kind) {
                Haptic.Tick, Haptic.Undo -> 8L to 90
                Haptic.Tap, Haptic.SetDone, Haptic.Milestone -> 12L to 180
                Haptic.ExerciseDone, Haptic.WorkoutDone -> 16L to 255
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, amp))
        }
    }
}

actual fun isReduceMotionEnabled(): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(
        AndroidPlatform.context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)
