package dev.southstar21.volum.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * False while the switcher's page or sheet is moving (a page transition, the iOS swipe back, a sheet
 * sliding) or covered by a sheet. A native control floats above Compose and can't follow its
 * animations or sit under its overlays, so meanwhile it steps aside.
 */
val LocalNativeViewsSettled = compositionLocalOf { true }

/** The platform's own segmented control: options, the selected index, the pick callback, a modifier, and whether it spans the width. */
typealias NativeSegmentedControl = @Composable (options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, fill: Boolean) -> Unit

/**
 * The platform's own segmented control: UISegmentedControl on iOS. Null on Android, which draws the
 * plain rounded switcher in Segmented.
 */
expect val nativeSegmented: NativeSegmentedControl?
