package dev.southstar21.volum.platform

import platform.Foundation.NSLog

actual fun logWarn(tag: String, message: String, error: Throwable?) {
    val detail = if (error != null) " (${error::class.simpleName}: ${error.message})" else ""
    NSLog("%@", "[$tag] $message$detail")
}
