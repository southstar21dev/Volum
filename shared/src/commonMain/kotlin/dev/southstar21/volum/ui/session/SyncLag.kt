package dev.southstar21.volum.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.components.StatusBanner
import dev.southstar21.volum.ui.rememberNow
import dev.southstar21.volum.ui.theme.Durations
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Springs
import kotlinx.coroutines.delay

/** How long an edit can wait to reach the server before the connection counts as slow. */
const val SLOW_SEND_MS = 6_000L

/** Once shown, the banner stays at least this long, so a connection on the edge doesn't make it flicker. */
private const val MIN_SHOWN_MS = 2_000L

/**
 * Whether a shared workout is lagging at [now] (epoch ms): an edit has been waiting since [pendingSince]
 * for [slowAfterMs] or more, or nothing has come back from the server since [lastHeardAt] for longer
 * than [staleAfterMs] (a few of the screen's polls missed in a row).
 */
fun syncLagging(now: Long, pendingSince: Long?, lastHeardAt: Long, staleAfterMs: Long, slowAfterMs: Long = SLOW_SEND_MS): Boolean =
    (pendingSince != null && now - pendingSince >= slowAfterMs) || now - lastHeardAt > staleAfterMs

/**
 * A quiet strip under a shared workout's heading while changes are slow to pass between you and
 * [partnerName]. It checks once a second on its own, so the rest of the screen doesn't recompose.
 */
@Composable
fun SyncLagBanner(partnerName: String, offline: Boolean, pendingSince: Long?, lastHeardAt: Long, staleAfterMs: Long) {
    val now = rememberNow()
    val lagging = syncLagging(now, pendingSince, lastHeardAt, staleAfterMs)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(lagging) {
        if (lagging) shown = true
        else if (shown) { delay(MIN_SHOWN_MS); shown = false }
    }
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(Durations.Fade)) + expandVertically(Springs.settle(), expandFrom = Alignment.Top),
        exit = fadeOut(tween(Durations.Exit)) + shrinkVertically(Springs.settle(), shrinkTowards = Alignment.Top),
    ) {
        val modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        if (offline) StatusBanner(Icons.CloudOff, "Offline", "Changes reach $partnerName when you're back", modifier)
        else StatusBanner(Icons.CloudSync, "Slow connection", "Syncing with $partnerName…", modifier, spinning = true)
    }
}
