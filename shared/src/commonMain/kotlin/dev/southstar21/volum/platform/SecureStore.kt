package dev.southstar21.volum.platform

/**
 * Small secrets, such as the sign-in token. iOS keeps them in the Keychain, on this device only, so
 * they never go into iCloud or computer backups. Android encrypts them with an Android Keystore key
 * into an app-private file (`secure/<key>.bin` in filesDir), which the backup and device-transfer
 * rules also leave out.
 */
expect object SecureStore {
    fun read(key: String): String?

    /** False if the value couldn't be saved; it then lasts only until the app quits. */
    fun write(key: String, value: String): Boolean

    fun delete(key: String)
}
