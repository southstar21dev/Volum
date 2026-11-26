package dev.southstar21.volum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** What the Android dock's glass blurs and refracts: the colour field behind every screen. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/** Records the field (the modifier handed to [content]) as [LocalBackdrop], for the dock. */
@Composable
internal actual fun GlassBackdropScope(content: @Composable (recorded: Modifier) -> Unit) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalBackdrop provides backdrop) { content(Modifier.layerBackdrop(backdrop)) }
}
