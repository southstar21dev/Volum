package dev.southstar21.volum.platform

/** Everything the platform tab bar needs to draw itself. The round + (new routine) always shows beside it. */
data class SystemNavbarState(
    val selectedIndex: Int,
    /** True while a sheet is open or a screen is pushed. */
    val hidden: Boolean,
    /** Accent colour as 0xAARRGGBB. */
    val accentArgb: Int,
)

/** A tab bar drawn by the platform instead of the Compose dock (iOS: the system UITabBarController). */
interface SystemNavbar {
    fun update(state: SystemNavbarState)
    fun detach()
    var onSelect: ((Int) -> Unit)?
    var onAction: (() -> Unit)?
}

/** The platform tab bar, or null where Compose draws the liquid dock (Android). */
expect val platformNavbar: SystemNavbar?
