package dev.southstar21.volum.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.southstar21.volum.platform.appVersion
import dev.southstar21.volum.platform.isIos
import dev.southstar21.volum.platform.updateTarget
import dev.southstar21.volum.ui.auth.AppIcon
import dev.southstar21.volum.ui.auth.Wordmark
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.TopInset
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.T
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.popIn
import dev.southstar21.volum.ui.theme.riseIn

/**
 * Shown instead of the whole app once the server refuses this build as too old. Laid out like the
 * log in screen: brand on top, a glass sheet at the foot. There is no way past it but updating;
 * what's saved on the device stays and syncs from the new build.
 */
@Composable
fun UpdateRequiredScreen(minVersion: String?) {
    val c = Volum.colors
    val target = remember { updateTarget() }
    val uriHandler = LocalUriHandler.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
            TopInset(64.dp)
            Column(Modifier.fillMaxWidth().riseIn(), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(Modifier.popIn(120)) { AppIcon(84.dp) }
                Spacer(Modifier.height(20.dp))
                Wordmark()
                Spacer(Modifier.height(10.dp))
                Mute("Your strength training log", size = T.Subhead, weight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .riseIn(48f)
                    .fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(42.dp), tint = c.sheet)
                    .padding(16.dp),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp)) {
                    Txt("Update Volum to continue", size = T.Entry, weight = ExtraBold, tracking = (-0.02).em)
                    Spacer(Modifier.height(8.dp))
                    Mute("This version can no longer sync. Install the latest one to keep logging.", size = T.Body, weight = FontWeight.Medium)
                }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(vertical = 4.dp)) {
                    VersionRow("Installed", appVersion)
                    if (minVersion != null) {
                        Divider(inset = 16.dp)
                        VersionRow("Needed", "$minVersion or newer")
                    }
                }
                Mute(
                    "Workouts saved on this ${if (isIos) "iPhone" else "phone"} stay here and sync once you've updated.",
                    Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp),
                    size = T.Small,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(target.label, height = 58.dp, icon = Icons.ArrowUpRight) {
                    // No handler for the link (TestFlight removed, no browser) leaves the screen as it is.
                    runCatching { uriHandler.openUri(target.url) }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mute(label, size = T.Body, weight = FontWeight.Medium)
        Txt(value, size = T.Body, weight = Bold, tabular = true)
    }
}
