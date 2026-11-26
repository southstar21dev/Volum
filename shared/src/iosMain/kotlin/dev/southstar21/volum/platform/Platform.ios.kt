package dev.southstar21.volum.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

// Only the path: AppContainer creates the folder on its background dispatcher, not on Main at launch.
actual fun appDataDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    return "$base/Volum"
}

/** The Caches folder: not backed up to iCloud, and iOS may clear it when storage runs low. */
actual fun cacheDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$base/Volum"
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

/**
 * A TestFlight build is signed by Apple and carries no provisioning profile; one installed through
 * AltStore or SideStore (or from Xcode) does, and updates from the AltStore source page instead.
 */
actual fun updateTarget(): UpdateTarget =
    if (platform.Foundation.NSBundle.mainBundle.pathForResource("embedded", ofType = "mobileprovision") == null) {
        UpdateTarget("Open TestFlight", "itms-beta://")
    } else {
        UpdateTarget("Get the update", "https://southstar21dev.github.io/Volum/")
    }
