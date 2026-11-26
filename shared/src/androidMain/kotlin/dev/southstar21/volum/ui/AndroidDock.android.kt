package dev.southstar21.volum.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.platform.hapticTick
import dev.southstar21.volum.ui.components.LocalBackdrop
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.rememberLiquidPane
import dev.southstar21.volum.ui.liquid.LiquidTabs
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Easings
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.LocalReduceMotion
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable

/** A Kyant liquid-glass dock with the four tabs, plus a separate round + for new routines. */
@Composable
internal actual fun AndroidDock(visible: Boolean, selected: Tab, onSelect: (Tab) -> Unit, onAction: () -> Unit, modifier: Modifier) {
    val c = Volum.colors
    val reduce = LocalReduceMotion.current
    val pane = rememberLiquidPane()
    val alpha = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(if (visible) 1f else 0f, if (visible) tween(Durations.Enter, easing = Easings.Enter) else tween(Durations.Exit, easing = Easings.Exit))
    }
    // Read through derivedStateOf: the fade itself runs in the graphics layer below, so the dock
    // (and its liquid tabs) recompose only when it fully hides or shows, not on every fade frame.
    val gone by remember { androidx.compose.runtime.derivedStateOf { alpha.value == 0f } }
    if (gone && !visible) return
    // The iOS bar's filled symbols, on every tab.
    val tabIcons = remember { listOf(Icons.TabHome, Icons.TabHistory, Icons.TabLifts, Icons.TabProfile) }
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .graphicsLayer { this.alpha = alpha.value; translationY = if (reduce) 0f else (1f - alpha.value) * 40.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiquidTabs(
            count = Tab.entries.size,
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(Tab.entries[it]) },
            backdrop = LocalBackdrop.current,
            accent = c.accent,
            // What the selection drop shows the pane as: a solid stand-in for the glass below.
            container = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.66f),
            // The mockups' `.lg` glass: a white gradient (not a dark tint) with its rim and sheen.
            surface = pane,
            // iOS's selected-tab platter: the bar's glass a shade darker, tinted by what's behind it
            // (sage here), over the near-white stand-in the selection draws on.
            selection = androidx.compose.ui.graphics.Color(0xFF5F7F45).copy(alpha = 0.24f),
            modifier = Modifier.weight(1f),
            height = 62.dp,
            onCross = { hapticTick() },
        ) { index, tinted ->
            // As on iOS, only the colour (and the icon's fill) changes: the label keeps its weight.
            val ink = if (tinted) c.accent else c.ink
            Icon(tabIcons[index], ink, size = 26.dp)
            Txt(Tab.entries[index].label, size = T.Tiny, weight = Bold, color = ink, maxLines = 1)
        }
        Box(
            Modifier.size(62.dp).tappable(haptic = true, onClick = onAction).liquidGlass(CircleShape).semantics { contentDescription = "New routine" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Plus, c.ink, size = 24.dp) }
    }
}

