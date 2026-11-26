package dev.southstar21.volum.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS has the native tab bar (see the shell in App.kt), so there is no Compose dock. */
@Composable
internal actual fun AndroidDock(visible: Boolean, selected: Tab, onSelect: (Tab) -> Unit, onAction: () -> Unit, modifier: Modifier) = Unit
