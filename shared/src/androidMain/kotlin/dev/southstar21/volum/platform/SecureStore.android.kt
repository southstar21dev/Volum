package dev.southstar21.volum.platform

import java.io.File

actual object SecureStore {
    private fun file(key: String) = File(appDataDirectory(), "$key.json")

    actual fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()

    actual fun write(key: String, value: String): Boolean = runCatching {
        val target = file(key)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(value)
        check(tmp.renameTo(target))
    }.isSuccess

    actual fun delete(key: String) {
        runCatching { file(key).delete() }
    }
}
