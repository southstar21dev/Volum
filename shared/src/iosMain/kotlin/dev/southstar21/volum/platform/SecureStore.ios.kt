package dev.southstar21.volum.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Generic-password Keychain items under the service "dev.southstar21.volum", one per key. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object SecureStore {
    private const val SERVICE = "dev.southstar21.volum"

    actual fun read(key: String): String? = withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    actual fun write(key: String, value: String): Boolean {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        delete(key)
        val dataRef = CFBridgingRetain(data)
        try {
            return withQuery(
                key,
                kSecValueData to dataRef,
                // Readable after the first unlock (so a background refresh works), never restored
                // onto another device from a backup.
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) == errSecSuccess }
        } finally {
            CFRelease(dataRef)
        }
    }

    actual fun delete(key: String) {
        withQuery(key) { query -> SecItemDelete(query) }
    }

    private fun <T> withQuery(key: String, vararg extra: Pair<CFTypeRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(key)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            for ((name, value) in extra) CFDictionaryAddValue(query, name, value)
            return block(query)
        } finally {
            CFRelease(service)
            CFRelease(account)
            CFRelease(query)
        }
    }
}
