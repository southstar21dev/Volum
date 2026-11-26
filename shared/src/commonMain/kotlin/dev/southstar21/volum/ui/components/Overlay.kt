package dev.southstar21.volum.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import dev.southstar21.volum.ui.theme.LocalEntrance
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

@Stable
class OverlayHost {
    internal val entries = mutableStateListOf<OverlayEntry>()
    val isShowing: Boolean get() = entries.isNotEmpty()
}

@Stable
internal class OverlayEntry(content: @Composable () -> Unit, locals: CompositionLocalContext) {
    var content by mutableStateOf(content)
    /** The CompositionLocals where the overlay was declared (the screen's session, navigator…). */
    var locals by mutableStateOf(locals)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("No OverlayHost: overlays need VolumApp's OverlayLayer") }

@Composable
fun OverlayLayer(host: OverlayHost) {
    host.entries.forEach { entry ->
        androidx.compose.runtime.key(entry) {
            // Drawn at the top of the app, but with the locals of the screen that declared it; sheets
            // don't take part in their screen's entrance.
            CompositionLocalProvider(entry.locals) {
                CompositionLocalProvider(LocalEntrance provides null) { entry.content() }
            }
        }
    }
}

/** Draws [content] on the app's top layer while this is composed. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val locals = currentCompositionLocalContext
    val entry = remember { OverlayEntry(content, locals) }
    SideEffect {
        entry.content = content
        entry.locals = locals
    }
    DisposableEffect(host) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val latest = rememberUpdatedState(onBack)
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { latest.value() })
}
