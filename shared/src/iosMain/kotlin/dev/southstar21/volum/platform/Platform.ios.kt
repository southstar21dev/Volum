package dev.southstar21.volum.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

@OptIn(ExperimentalForeignApi::class)
actual fun appDataDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    val dir = "$base/Volum"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    installTimeouts()
    engine {
        // Each request's idle timeout comes from installTimeouts (the realtime stream's is unbounded).
        // The session-wide default is raised so it can't cut the stream off at 60 s regardless.
        configureSession { timeoutIntervalForRequest = 24 * 60 * 60.0 }
    }
    expectSuccess = false
}

actual val isIos: Boolean = true

actual val pocketBaseUrl: String get() = IosBuildConfig.POCKETBASE_URL

actual val appVersion: String get() = IosBuildConfig.VERSION_NAME

private val selection by lazy { UISelectionFeedbackGenerator() }
private val rigid by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid) }
private val heavy by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }

/**
 * Every haptic is one short, hard click (the rigid impact, like a key bottoming out), never a pattern:
 * no notification "success", which is itself a little triple tap. Bigger moments click harder.
 */
actual fun haptic(kind: Haptic) {
    when (kind) {
        Haptic.Tick -> selection.selectionChanged()
        Haptic.Undo -> rigid.impactOccurredWithIntensity(0.4)
        Haptic.Tap -> rigid.impactOccurredWithIntensity(0.6)
        Haptic.SetDone, Haptic.Milestone -> rigid.impactOccurredWithIntensity(1.0)
        Haptic.ExerciseDone, Haptic.WorkoutDone -> heavy.impactOccurredWithIntensity(1.0)
    }
}

actual fun isReduceMotionEnabled(): Boolean = platform.UIKit.UIAccessibilityIsReduceMotionEnabled()
