package dev.southstar21.volum.ui.session

import androidx.compose.foundation.background
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.volum.model.Exercise
import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.ui.components.BareField
import dev.southstar21.volum.ui.components.BottomSheet
import dev.southstar21.volum.ui.components.CircleButton
import dev.southstar21.volum.ui.components.DangerButton
import dev.southstar21.volum.ui.components.Divider
import dev.southstar21.volum.ui.components.GlassButton
import dev.southstar21.volum.ui.components.Mute
import dev.southstar21.volum.ui.components.Pill
import dev.southstar21.volum.ui.components.PrimaryButton
import dev.southstar21.volum.ui.components.Segmented
import dev.southstar21.volum.ui.components.Txt
import dev.southstar21.volum.ui.components.accentGel
import dev.southstar21.volum.ui.components.bleedHorizontally
import dev.southstar21.volum.ui.components.glassCard
import dev.southstar21.volum.ui.components.liquidGlass
import dev.southstar21.volum.ui.components.milky
import dev.southstar21.volum.ui.components.rememberLast
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.southstar21.volum.ui.theme.Bold
import dev.southstar21.volum.ui.theme.ExtraBold
import dev.southstar21.volum.ui.theme.Icon
import dev.southstar21.volum.ui.theme.Icons
import dev.southstar21.volum.ui.theme.Semibold
import dev.southstar21.volum.ui.theme.Volum
import dev.southstar21.volum.ui.theme.tappable
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.formatWeightValue
import dev.southstar21.volum.util.initials

private fun parseNumber(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

/**
 * What a weight field accepts as you type: digits, one decimal separator (either `.` or `,`) and two
 * decimals after it. Anything else is dropped, so the text always parses (or is empty).
 */
internal fun filterDecimal(input: String): String {
    val out = StringBuilder()
    var separator = false
    var decimals = 0
    for (ch in input) {
        when {
            ch.isDigit() && !separator -> out.append(ch)
            ch.isDigit() && decimals < 2 -> { out.append(ch); decimals++ }
            (ch == '.' || ch == ',') && !separator -> { out.append(ch); separator = true }
        }
    }
    return out.toString()
}

/** Edit one set exactly: weight (typed in your unit, with the other shown), reps or hold, its tag, or remove it. */
@Composable
fun SetEditSheet(
    target: Pair<WorkoutEntry, SetEntry>?,
    preferred: WeightUnit,
    onSave: (WorkoutEntry, SetEntry) -> Unit,
    onRemove: (WorkoutEntry, SetEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Volum.colors
    var weightText by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<SetTag?>(null) }
    LaunchedEffect(target) {
        target?.let { (_, set) ->
            weightText = formatWeightValue(set.weightIn(preferred)).let { if (it == "0") "" else it }
            repsText = if (target.first.isTimed) (set.seconds ?: 0).toString() else set.reps.toString()
            tag = set.tag
        }
    }
    // The target as it was when the sheet opened, still shown while it slides away after Save.
    val shown = rememberLast(target)
    BottomSheet(open = target != null, onDismiss = onDismiss, title = shown?.first?.name) {
        val (entry, set) = shown ?: return@BottomSheet
        val index = entry.sets.indexOfFirst { it.id == set.id } + 1
        Txt(entry.name, Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Mute("Set $index", Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.3f).milky(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                val weightLabel = "Weight (${preferred.label})"
                Mute(weightLabel, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
                BareField(weightText, { weightText = filterDecimal(it) }, Modifier.fillMaxWidth(), placeholder = "0", size = T.Entry, weight = ExtraBold, keyboardType = KeyboardType.Decimal, label = weightLabel)
                val other = parseNumber(weightText)?.let { convert(it, preferred, preferred.other) } ?: 0.0
                Mute("= ${formatWeightValue(other)} ${preferred.other.label}", size = T.Caption)
            }
            Column(Modifier.weight(1f).milky(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                val countLabel = if (entry.isTimed) "Seconds" else "Reps"
                Mute(countLabel, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
                BareField(repsText, { repsText = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), placeholder = "0", size = T.Entry, weight = ExtraBold, keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, label = countLabel)
                Mute(" ", size = T.Caption)
            }
        }
        Spacer(Modifier.height(14.dp))
        Segmented(listOf("Working", "Warm-up", "Drop"), when (tag) { null -> 0; SetTag.Warmup -> 1; SetTag.Drop -> 2 }, { tag = when (it) { 1 -> SetTag.Warmup; 2 -> SetTag.Drop; else -> null } }, Modifier.fillMaxWidth(), fill = true)
        Spacer(Modifier.height(16.dp))
        // Empty means no weight; anything typed always parses (see filterDecimal).
        val weightValid = weightText.isBlank() || parseNumber(weightText) != null
        PrimaryButton("Save", enabled = weightValid) {
            val w = if (weightText.isBlank()) 0.0 else parseNumber(weightText) ?: return@PrimaryButton
            val n = repsText.toIntOrNull() ?: 0
            val updated = if (entry.isTimed) set.copy(weight = w, unit = preferred, seconds = n, reps = 0, tag = tag)
            else set.copy(weight = w, unit = preferred, reps = n, tag = tag)
            onSave(entry, updated)
            onDismiss()
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp).tappable { onRemove(entry, set); onDismiss() }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Trash, c.danger, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Remove set", size = T.Body, weight = Bold, color = c.danger)
        }
    }
}

/** Search the library, pick a lift, or create one by name. */
@Composable
fun AddExerciseSheet(open: Boolean, exercises: List<Exercise>, recent: List<Exercise>, lastLabels: Map<String, String>, onPick: (Exercise) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Volum.colors
    var query by remember { mutableStateOf("") }
    LaunchedEffect(open) { if (open) query = "" }
    BottomSheet(open = open, onDismiss = onDismiss, fill = true, title = "Add exercise") {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Add exercise", Modifier.weight(1f), size = T.SheetTitle, weight = ExtraBold)
            CircleButton(Icons.X, "Close", onClick = onDismiss)
        }
        Row(Modifier.fillMaxWidth().height(50.dp).liquidGlass(RoundedCornerShape(25.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Search, c.mute, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            val q0 = query.trim()
            BareField(query, { query = it }, Modifier.weight(1f), placeholder = "Search or create", size = T.BodyLarge, imeAction = ImeAction.Done, label = "Search or create an exercise", onDone = { if (query.isNotBlank()) { onCreate(query.trim().replaceFirstChar { it.uppercaseChar() }); onDismiss() } })
            if (q0.isNotEmpty() && exercises.any { it.name.contains(q0, ignoreCase = true) }) Mute("${exercises.count { it.name.contains(q0, ignoreCase = true) }} found", size = T.Small)
        }
        val q = query.trim()
        val matches = exercises.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        val exact = matches.any { it.name.equals(q, ignoreCase = true) }
        Column(Modifier.weight(1f, fill = false).bleedHorizontally(18.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            if (recent.isNotEmpty()) {
                Mute("Recent this week", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), weight = Bold)
                Row(Modifier.bleedHorizontally(18.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { ex -> Pill(height = 36.dp, onClick = { onPick(ex); onDismiss() }) { Txt(ex.name, size = T.Small, weight = Semibold) } }
                }
            }
            if (matches.isNotEmpty()) {
                Mute(if (q.isEmpty()) "All exercises" else "Matches", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), weight = Bold)
                Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
                    matches.take(80).forEachIndexed { i, ex ->
                        if (i > 0) Divider(inset = 66.dp, end = 14.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).tappable { onPick(ex); onDismiss() }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(40.dp).liquidGlass(CircleShape), contentAlignment = Alignment.Center) { Txt(initials(ex.name), size = T.Small, weight = ExtraBold, color = c.accent) }
                            Column(Modifier.weight(1f)) {
                                Txt(ex.name, size = T.Body, weight = Bold, maxLines = 1)
                                Mute(lastLabels[ex.id]?.let { "Last $it" } ?: if (ex.isTimed) "Timed" else "Not done yet", size = T.Caption, maxLines = 1)
                            }
                            Icon(Icons.Plus, c.accent, size = 20.dp)
                        }
                    }
                }
            }
            if (q.isNotEmpty() && !exact) {
                val newName = q.replaceFirstChar { it.uppercaseChar() }
                Row(
                    Modifier.padding(top = if (matches.isEmpty()) 14.dp else 12.dp).fillMaxWidth().tappable { onCreate(newName); onDismiss() }.liquidGlass(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(40.dp).accentGel(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Plus, c.onAccent, size = 20.dp) }
                    Column {
                        Txt("Create “$newName”", size = T.Body, weight = Bold)
                        Mute("New exercise in your library", size = T.Caption)
                    }
                }
            }
            Mute("An added exercise copies the sets from last time you did it. A new one starts with 3 sets of 8.", Modifier.padding(8.dp, 14.dp), size = T.Caption)
        }
    }
}

/** Rename an exercise in this workout, or swap it for another lift from the library. */
@Composable
fun RenameExerciseSheet(
    entry: WorkoutEntry?,
    exercises: List<Exercise>,
    canUpdateRoutine: Boolean,
    onSave: (newName: String, linkTo: Exercise?, updateRoutine: Boolean, updateLibrary: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Volum.colors
    var name by remember { mutableStateOf("") }
    var updateRoutine by remember { mutableStateOf(true) }
    var updateLibrary by remember { mutableStateOf(false) }
    LaunchedEffect(entry) { entry?.let { name = it.name; updateRoutine = true; updateLibrary = false } }
    val shown = rememberLast(entry)
    BottomSheet(open = entry != null, onDismiss = onDismiss, title = "Rename exercise") {
        val e = shown ?: return@BottomSheet
        Txt("Rename exercise", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Exercise name", imeAction = ImeAction.Done, label = "Exercise name")
        }
        val q = name.trim()
        val link = exercises.firstOrNull { it.name.equals(q, ignoreCase = true) && it.id != e.exerciseId }
        val suggestions = if (q.length >= 2) exercises.filter { it.name.contains(q, ignoreCase = true) && it.id != e.exerciseId && !it.name.equals(q, true) }.take(4) else emptyList()
        if (suggestions.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { ex -> Pill(height = 34.dp, onClick = { name = ex.name }) { Txt(ex.name, size = T.Small, weight = Semibold) } }
            }
        }
        if (link != null) Mute("Links this to ${link.name} in your library.", Modifier.padding(8.dp, 10.dp), size = T.Small)
        Spacer(Modifier.height(8.dp))
        if (canUpdateRoutine) Check("Also update in routine", updateRoutine) { updateRoutine = it }
        if (link == null && e.exerciseId.isNotEmpty()) Check("Also update in exercise library", updateLibrary) { updateLibrary = it }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = q.isNotEmpty()) {
            onSave(q, link, canUpdateRoutine && updateRoutine, link == null && updateLibrary)
            onDismiss()
        }
    }
}

@Composable
fun Check(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val c = Volum.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp)
            .tappable(role = Role.Checkbox, highlight = false) { onChange(!checked) }
            .semantics { toggleableState = ToggleableState(checked) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).then(if (checked) Modifier.accentGel(RoundedCornerShape(8.dp)) else Modifier.clip(RoundedCornerShape(8.dp)).background(c.line)), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Check, c.onAccent, size = 14.dp)
        }
        Txt(label, size = T.Label, color = c.mute)
    }
}

/** Delete an exercise from this workout, and optionally from its routine. */
@Composable
fun DeleteExerciseSheet(entry: WorkoutEntry?, routineName: String?, onDelete: (fromRoutine: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Volum.colors
    val shown = rememberLast(entry)
    BottomSheet(open = entry != null, onDismiss = onDismiss, title = shown?.let { "Delete ${it.name}?" }) {
        val e = shown ?: return@BottomSheet
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.dangerSoft), contentAlignment = Alignment.Center) { Icon(Icons.Trash, c.danger, size = 24.dp) }
            Spacer(Modifier.height(12.dp))
            Txt("Delete ${e.name}?", size = T.SheetTitle, weight = ExtraBold, align = TextAlign.Center)
            Mute("Your logged sets for ${e.name} in this workout will be removed.", Modifier.padding(top = 4.dp), size = T.Label, align = TextAlign.Center)
        }
        if (routineName != null) {
            DangerButton("Delete from this workout only", subtitle = "$routineName keeps ${e.name} next time") { onDismiss(); onDelete(false) }
            Spacer(Modifier.height(8.dp))
            DangerButton("Delete from workout and routine", subtitle = "$routineName won't include ${e.name} any more") { onDismiss(); onDelete(true) }
        } else {
            DangerButton("Delete from this workout") { onDismiss(); onDelete(false) }
        }
        Spacer(Modifier.height(12.dp))
        GlassButton("Cancel", Modifier.fillMaxWidth(), height = 56.dp, onClick = onDismiss)
    }
}

/** Rename the workout (and optionally its routine). */
@Composable
fun RenameWorkoutSheet(open: Boolean, current: String, hasRoutine: Boolean, onSave: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var alsoRoutine by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) { name = current; alsoRoutine = false } }
    BottomSheet(open = open, onDismiss = onDismiss, title = "Rename workout") {
        Txt("Rename workout", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Workout name", imeAction = ImeAction.Done, label = "Workout name")
        }
        if (hasRoutine) { Spacer(Modifier.height(8.dp)); Check("Also update routine name", alsoRoutine) { alsoRoutine = it } }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = name.isNotBlank()) { onSave(name.trim(), alsoRoutine); onDismiss() }
    }
}
