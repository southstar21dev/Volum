package dev.southstar21.volum.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each value is AES-GCM encrypted with a key that is created in, and never leaves, the Android
 * Keystore, and kept in `secure/<key>.bin` under filesDir, so a copy of the file alone (a rooted
 * phone, a backup) can't be read. Earlier builds kept the value in a plain `<key>.json`; [read]
 * moves that over once, so the upgrade signs nobody out.
 */
actual object SecureStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dev.southstar21.volum.securestore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val lock = Any()
    private var cachedKey: SecretKey? = null

    private fun file(key: String) = File(File(appDataDirectory(), "secure"), "$key.bin")

    private fun legacyFile(key: String) = File(appDataDirectory(), "$key.json")

    actual fun read(key: String): String? = synchronized(lock) {
        val target = file(key)
        if (target.exists()) {
            // A file that can't be read this time may be readable next time: keep it.
            val bytes = runCatching { target.readBytes() }.getOrNull() ?: return@synchronized null
            return@synchronized try {
                decrypt(bytes)
            } catch (e: Exception) {
                // Undecryptable (the Keystore key was lost or broken, the file damaged) is the same as
                // signed out, for good: drop the file so it isn't tried on every launch, and a key
                // that is itself the problem, so the next sign-in is saved with a working one.
                if (e is GeneralSecurityException || e is ProviderException || e is IllegalArgumentException) {
                    if (isBrokenKey(e)) resetKey()
                    runCatching { target.delete() }
                }
                null
            }
        }
        val legacy = legacyFile(key)
        val plain = runCatching { legacy.takeIf { it.exists() }?.readText() }.getOrNull() ?: return@synchronized null
        // If the Keystore can't take it, keep the old file rather than lose the sign-in.
        if (writeLocked(key, plain)) runCatching { legacy.delete() }
        plain
    }

    actual fun write(key: String, value: String): Boolean = synchronized(lock) {
        val written = writeLocked(key, value)
        if (written) runCatching { legacyFile(key).delete() }
        written
    }

    actual fun delete(key: String) {
        synchronized(lock) {
            runCatching { file(key).delete() }
            runCatching { legacyFile(key).delete() }
        }
    }

    /**
     * Encrypts and writes atomically. A Keystore key that exists but can't be used any more (after an
     * OS upgrade or a Keystore migration: "invalid key blob", an unrecoverable key) is replaced once,
     * so the sign-in is saved rather than lost on every launch.
     */
    private fun writeLocked(key: String, value: String): Boolean {
        val sealed = try {
            encrypt(value)
        } catch (e: Exception) {
            if (e !is GeneralSecurityException && e !is ProviderException) return false
            resetKey()
            runCatching { encrypt(value) }.getOrNull() ?: return false
        }
        return runCatching {
            val target = file(key)
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(sealed)
            check(tmp.renameTo(target))
        }.isSuccess
    }

    /** A failure that says the key itself is unusable, not the data it was given. */
    private fun isBrokenKey(e: Exception): Boolean =
        e is InvalidKeyException || e is UnrecoverableKeyException || e is KeyStoreException || e is ProviderException

    /** Forgets the key and deletes it from the Keystore; the next use creates a new one. */
    private fun resetKey() {
        cachedKey = null
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    /** The IV's length, the IV, then the ciphertext with its GCM tag. */
    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(value.encodeToByteArray())
        return byteArrayOf(iv.size.toByte()) + iv + sealed
    }

    private fun decrypt(bytes: ByteArray): String {
        val ivSize = bytes[0].toInt()
        require(ivSize in 1..<bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 1, ivSize))
        return cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize).decodeToString()
    }

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // An entry that is there but can't be loaded is replaced, not reported forever.
        val stored = try {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (_: GeneralSecurityException) {
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            null
        }
        val key = stored ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        cachedKey = key
        return key
    }
}
