package dev.southstar21.volum.platform

import platform.Foundation.NSLog

actual fun logWarn(tag: String, message: String, error: Throwable?) {
    val detail = if (error != null) " (${error::class.simpleName}: ${error.message})" else ""
    // The text is the format itself, with % escaped: passing a Kotlin String through NSLog's
    // varargs ("%@") crashes Kotlin/Native.
    NSLog("[$tag] $message$detail".replace("%", "%%"))
}
