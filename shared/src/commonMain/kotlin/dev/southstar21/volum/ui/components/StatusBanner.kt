package dev.southstar21.volum.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum

/**
 * A slim glass strip that tells you something about the connection without asking anything of you:
 * an icon, a bold [title] and a muted [message]. [spinning] turns the icon, for work under way.
 */
@Composable
fun StatusBanner(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val c = Volum.colors
    Row(
        modifier.fillMaxWidth().height(48.dp).liquidGlass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinning) {
            val spin by rememberInfiniteTransition(label = "banner").animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
            Icon(icon, c.ink, size = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = spin })
        } else {
            Icon(icon, c.ink, size = 20.dp)
        }
        Txt(title, size = T.Label, weight = Bold, maxLines = 1)
        Mute(message, size = T.Label, maxLines = 1)
    }
}
