package dev.southstar21.volum.platform

import android.util.Log

actual fun logWarn(tag: String, message: String, error: Throwable?) {
    // Plain JVM unit tests have no Log implementation: logging must never be what fails.
    try {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    } catch (_: Throwable) {
    }
}
