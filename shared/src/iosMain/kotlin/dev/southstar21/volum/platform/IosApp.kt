package dev.southstar21.volum.platform

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.southstar21.volum.data.AppContainer
import dev.southstar21.volum.ui.VolumApp
import platform.UIKit.UIViewController

/** The iOS counterpart of androidApp's VolumApplication. */
object IosApp {
    val container: AppContainer by lazy { AppContainer() }
}

/** The whole app, hosted by iosApp's SwiftUI scene. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController(
    // Screens make room for the keyboard themselves (imePadding); the default would also slide the
    // whole view up, moving the content twice.
    configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
) { VolumApp(IosApp.container) }
