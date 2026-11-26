package dev.southstar21.volum.data

/** What people read when a failure has nothing more specific to say. */
const val GENERIC_ERROR = "Something went wrong"

/**
 * A failed action in words, for the screens to show: [offline] when there was no connection, the
 * server's own message for a rejected field (when [serverText]; PocketBase writes those for people),
 * otherwise [failed]. Anything that isn't a server exchange is [failed] too. Never raw response text.
 */
fun actionErrorMessage(e: Throwable, offline: String, failed: String, serverText: Boolean = true): String {
    if (e !is PbException) return failed
    if (e.status == 0) return offline
    return (if (serverText) e.fieldMessage else null) ?: failed
}

/** A sign-in or sign-up failure in words. */
fun authErrorMessage(e: Throwable, register: Boolean): String {
    if (e !is PbException) return "$GENERIC_ERROR. Try again."
    return when {
        e.status == 0 -> if (register) "You're offline. Connect to create your account." else "You're offline. Connect to sign in."
        !register && e.status == 400 -> "Wrong email or password."
        e.status == 429 -> "Too many attempts. Wait a minute and try again."
        e.status >= 500 -> "The server isn't responding. Try again soon."
        else -> e.fieldMessage ?: if (register) "Couldn't create your account. Check your email and try again." else "Couldn't sign in. Try again."
    }
}

/** A failed pairing or live-session call in words. */
fun partnerErrorMessage(e: Throwable): String {
    if (e !is PbException) return GENERIC_ERROR
    return when {
        e.status == 401 -> "Session expired. Sign in again"
        e.status == 404 -> "Session ended"
        (e.status == 400 || e.status == 403) && e.message.isNotBlank() -> e.message
        e.status == 0 -> "You're offline"
        else -> GENERIC_ERROR
    }
}
