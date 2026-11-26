package dev.southstar21.volum.platform

/** Writes a warning to the platform log (Logcat on Android, the unified log on iOS). Never throws. */
expect fun logWarn(tag: String, message: String, error: Throwable? = null)
