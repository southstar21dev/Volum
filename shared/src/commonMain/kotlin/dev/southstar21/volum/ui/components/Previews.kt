package dev.southstar21.volum.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.VolumTheme

/** The colour field and theme every preview sits on, as the app's screens do. */
@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    VolumTheme {
        GlassRoot(solid = false) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Preview(widthDp = 380, heightDp = 420)
@Composable
private fun ButtonsPreview() = PreviewSurface {
    PrimaryButton("Start workout", icon = Icons.ArrowRight) {}
    PrimaryButton("Saving", busy = true) {}
    GlassButton("Cancel", Modifier.fillMaxWidth()) {}
    DangerButton("Delete routine", subtitle = "Your workout history stays") {}
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircleButton(Icons.ChevronLeft, "Back") {}
        CircleButton(Icons.Plus, "Add", accent = true) {}
        CircleButton(Icons.More, "More", enabled = false) {}
        TextButton("Add set") {}
    }
}

@Preview(widthDp = 380, heightDp = 300)
@Composable
private fun PillsAndSwitchesPreview() = PreviewSurface {
    var selected by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(selected = true, onClick = {}) { Txt("Bench") }
        Pill(onClick = {}) { Txt("Squat") }
        Tag("PR")
    }
    Segmented(listOf("Working", "Warm-up", "Drop"), selected, { selected = it }, Modifier.fillMaxWidth(), fill = true)
    NumberUnit("62.5", "kg")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar("Alex")
        Avatar("Sam", warm = true)
    }
}

@Preview(widthDp = 380, heightDp = 420)
@Composable
private fun CardAndFieldsPreview() = PreviewSurface {
    var name by remember { mutableStateOf("Push A") }
    GlassCard(Modifier.fillMaxWidth()) {
        Txt("Weekly volume", weight = dev.southstar21.volum.ui.theme.Bold)
        Mute("3 workouts this month")
    }
    Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
        SheetActionRow(SheetAction("Rename", Icons.Pencil, subtitle = "Or swap for another exercise") {}) {}
        Divider(inset = 16.dp)
        SheetActionRow(SheetAction("Delete…", Icons.Trash, destructive = true) {}) {}
    }
    Column(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp))) {
        LabeledField("Routine name", name, { name = it })
    }
    ErrorText("Give the routine a name.", Modifier.width(300.dp))
}
