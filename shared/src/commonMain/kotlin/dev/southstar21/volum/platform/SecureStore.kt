package dev.southstar21.volum.platform

/**
 * Small secrets, such as the sign-in token. iOS keeps them in the Keychain, on this device only, so
 * they never go into iCloud or computer backups. Android keeps them in an app-private file
 * (`<key>.json` in filesDir), which is already excluded from backups (allowBackup="false").
 */
expect object SecureStore {
    fun read(key: String): String?

    /** False if the value couldn't be saved; it then lasts only until the app quits. */
    fun write(key: String, value: String): Boolean

    fun delete(key: String)
}
