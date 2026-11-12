package dev.southstar21.volum.platform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIAction
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISegmentedControl
import platform.UIKit.UIView
import platform.UIKit.UIViewAnimationOptionBeginFromCurrentState
import platform.UIKit.UIViewAnimationOptionCurveEaseOut

/** iOS's own switcher, UISegmentedControl. Only the tab bar besides it is the system's; the rest is Compose. */
actual val nativeSegmented: NativeSegmentedControl? = { options, selected, onSelect, modifier, fill ->
    SystemSegmented(options, selected, onSelect, modifier, fill)
}

/**
 * The real control, always: its glass takes its colour from what is behind it, so no picture or
 * drawn copy can stand in for it. It floats above Compose and follows its page as it slides, but it
 * can't fade or shrink with it or sit under a sheet, so while things move it fades out with the page
 * and fades back in once the page is still.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SystemSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, fill: Boolean) {
    val pick by rememberUpdatedState(onSelect)
    val settled = LocalNativeViewsSettled.current
    key(options) {
        val control = remember {
            UISegmentedControl(items = options).apply {
                alpha = if (settled) 1.0 else 0.0
                addAction(UIAction.actionWithHandler { _ -> pick(selectedSegmentIndex.toInt()) }, forControlEvents = UIControlEventValueChanged)
            }
        }
        // The control's own size: its height, and its width when it doesn't span the row (points are dp).
        val (intrinsicWidth, intrinsicHeight) = remember { control.intrinsicContentSize.useContents { width to height } }
        LaunchedEffect(settled) {
            val target = if (settled) 1.0 else 0.0
            if (control.alpha == target) return@LaunchedEffect
            UIView.animateWithDuration(
                duration = if (settled) FADE_IN_S else FADE_OUT_S,
                delay = 0.0,
                options = UIViewAnimationOptionBeginFromCurrentState or UIViewAnimationOptionCurveEaseOut,
                animations = { control.alpha = target },
                completion = null,
            )
        }
        UIKitView(
            factory = { control },
            update = {
                // Not tappable while it is fading out or gone, so a tap reaches the page instead.
                it.userInteractionEnabled = settled
                if (it.selectedSegmentIndex.toInt() != selected) it.selectedSegmentIndex = selected.toLong()
            },
            modifier = modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier.width(intrinsicWidth.dp))
                .height(intrinsicHeight.dp),
            properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true, placedAsOverlay = true),
        )
    }
}

/** About as quick as a page starts to fade when it leaves, so the control goes with it. */
private const val FADE_OUT_S = 0.12

/** A soft return once the page is still. */
private const val FADE_IN_S = 0.2
