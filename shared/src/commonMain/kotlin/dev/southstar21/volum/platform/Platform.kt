package dev.southstar21.volum.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** Where this app keeps its local database files (one folder per signed-in user underneath). */
expect fun appDataDirectory(): String

/** A Ktor client on the platform's engine (OkHttp on Android, NSURLSession on iOS). */
expect fun createHttpClient(): HttpClient

/**
 * Bounds every call, so a request stalled on a dead connection fails (and sync retries) instead of
 * holding the sync lock until the app restarts. The socket timeout catches a stall; the total is only
 * a backstop for a connection that trickles, long enough for a full 500-record pull page to arrive
 * over a weak gym connection. The realtime stream lifts these for itself only.
 */
internal fun HttpClientConfig<*>.installTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 5 * 60_000
    }
}

expect val isIos: Boolean

/** The PocketBase server, from gradle.properties' volum.pocketbaseUrl. */
expect val pocketBaseUrl: String

expect val appVersion: String

/** The smallest detent: a tab or chip crossed, a reorder step. Same as [haptic] with [Haptic.Tick]. */
fun hapticTick() = haptic(Haptic.Tick)

/**
 * The app's haptics. Each is a single short click, like a mechanical key, never a pattern or a buzz;
 * the bigger the moment, the firmer the click.
 */
enum class Haptic {
    /** The lightest tick: selection changes, a running hold's ten-second marks. */
    Tick,
    /** A light click: nudges, Add set, Start timer. */
    Tap,
    /** A set logged: a firm click. */
    SetDone,
    /** A set un-ticked: a light tick. */
    Undo,
    /** A hold passing last time's: a firm click. */
    Milestone,
    /** An exercise's last set logged: a heavy click. */
    ExerciseDone,
    /** The workout's last set logged, or the workout finished: a heavy click. */
    WorkoutDone,
}

expect fun haptic(kind: Haptic)

/**
 * The system asks for less motion (iOS: Reduce Motion; Android: animations removed or turned off).
 * Entrances, celebrations and counters then appear in place instead of animating.
 */
expect fun isReduceMotionEnabled(): Boolean
