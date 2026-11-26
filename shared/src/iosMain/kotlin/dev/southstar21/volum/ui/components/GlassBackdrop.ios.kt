package dev.southstar21.volum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Nothing on iOS reads a recorded backdrop (the tab bar is native), so the field is not recorded. */
@Composable
internal actual fun GlassBackdropScope(content: @Composable (recorded: Modifier) -> Unit) = content(Modifier)
