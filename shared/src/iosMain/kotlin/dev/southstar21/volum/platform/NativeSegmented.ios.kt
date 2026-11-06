package dev.southstar21.volum.platform

/**
 * iOS draws its switchers in Compose too, the same Kyant liquid glass as Android (see Segmented), so
 * they move with their pages and match across platforms. Only the tab bar is the system's own.
 */
actual val nativeSegmented: NativeSegmentedControl? = null
