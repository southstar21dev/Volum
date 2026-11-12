package dev.southstar21.volum.ui.session

import dev.southstar21.volum.data.MAX_ROUTINE_NAME
import dev.southstar21.volum.data.MAX_EXERCISE_NAME
import androidx.compose.foundation.background
import dev.southstar21.volum.ui.theme.T
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
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
import kotlin.math.roundToLong

/**
 * A weight field's number. A trailing separator ("62.") is a number still being typed, a leading one
 * (".5") means 0.5, and a lone separator is no weight, like an empty field.
 */
internal fun parseNumber(text: String): Double? {
    val t = text.replace(',', '.').trim().removeSuffix(".")
    if (t.isEmpty()) return 0.0
    return (if (t.startsWith('.')) "0$t" else t).toDoubleOrNull()
}

private fun Char.isAsciiDigit() = this in '0'..'9'

/**
 * What a weight field accepts as you type: ASCII digits (other scripts' digits don't parse), one
 * decimal separator (either `.` or `,`) and two decimals after it. Anything else is dropped, so the
 * text always parses (see [parseNumber]). Nothing is inserted: the field keeps the cursor where the
 * edit left it, so an added character would put the next digit in the wrong place.
 */
internal fun filterDecimal(input: String): String {
    val out = StringBuilder()
    var separator = false
    var decimals = 0
    for (ch in input) {
        when {
            ch.isAsciiDigit() && !separator -> out.append(ch)
            ch.isAsciiDigit() && decimals < 2 -> { out.append(ch); decimals++ }
            (ch == '.' || ch == ',') && !separator -> { out.append(ch); separator = true }
        }
    }
    return out.toString()
}

/** [weight] as the field shows it: the two decimals the field accepts, so a stored 61.25 isn't shown as 61.3. */
internal fun editableWeight(weight: Double): String {
    if (weight <= 0.0) return ""
    val cents = (weight * 100).roundToLong()
    val whole = cents / 100
    val frac = cents % 100
    return when {
        frac == 0L -> "$whole"
        frac % 10 == 0L -> "$whole.${frac / 10}"
        else -> "$whole.${frac.toString().padStart(2, '0')}"
    }
}

/**
 * Lets a sheet act once per opening. A sheet stays on screen, and tappable, while it slides away, so a
 * quick second tap would otherwise add, save or delete a second time.
 */
private class Once {
    var used = false
    inline fun act(action: () -> Unit) {
        if (used) return
        used = true
        action()
    }
}

@Composable
private fun rememberOnce(open: Boolean): Once {
    val once = remember { Once() }
    LaunchedEffect(open) { if (open) once.used = false }
    return once
}

/**
 * The unit [SetEditSheet] types [set]'s weight in. A partner's set always keeps its own, even at no
 * weight, so clearing it and typing a new one never moves it into the viewer's unit; yours (even one
 * logged before a unit switch) is edited in [preferred].
 */
internal fun sheetUnit(set: SetEntry, preferred: WeightUnit, keepSetUnit: Boolean): WeightUnit =
    if (keepSetUnit) set.unit else preferred

/**
 * What [SetEditSheet] saves: [set] with the typed [count] (reps, or seconds when [timed]) and [tag].
 * A weight left as it opened ([openedWeight]) keeps the stored value and unit exactly, never
 * re-rounded; a changed one is saved in [unit]. Null when the weight doesn't parse.
 */
internal fun editedSet(set: SetEntry, timed: Boolean, weightText: String, openedWeight: String, unit: WeightUnit, count: Int, tag: SetTag?): SetEntry? {
    val weighed = if (weightText == openedWeight) set else set.copy(weight = parseNumber(weightText) ?: return null, unit = unit)
    return if (timed) weighed.copy(seconds = count, reps = 0, tag = tag) else weighed.copy(reps = count, tag = tag)
}

/**
 * The lift renaming an entry to [name] links it to, if any: [owner]'s own lift by that name first,
 * so the entry stays in their history, then anyone's (the library is shared by every account).
 */
internal fun renameLinkTarget(exercises: List<Exercise>, name: String, exceptId: String, owner: String?): Exercise? {
    val wanted = name.trim()
    if (wanted.isEmpty()) return null
    val matches = exercises.filter { it.id != exceptId && !it.deleted && it.name.trim().equals(wanted, ignoreCase = true) }
    return matches.firstOrNull { it.owner == owner } ?: matches.firstOrNull()
}

/** A [Pill] [height] tall in a touch target of at least 48 dp, so a short chip is still easy to hit. */
@Composable
private fun TargetPill(height: Dp, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).tappable(onClick = onClick), contentAlignment = Alignment.Center) {
        Pill(height = height, content = content)
    }
}

/**
 * Edit one set exactly: weight (typed in [preferred], with the other unit shown), reps or hold, its tag,
 * or remove it. With [keepSetUnit] (a partner's set) the weight is typed in the unit the set was
 * logged in instead, so editing never converts someone else's set.
 */
@Composable
fun SetEditSheet(
    target: Pair<WorkoutEntry, SetEntry>?,
    preferred: WeightUnit,
    onSave: (WorkoutEntry, SetEntry) -> Unit,
    onRemove: (WorkoutEntry, SetEntry) -> Unit,
    onDismiss: () -> Unit,
    keepSetUnit: Boolean = false,
) {
    val c = Volum.colors
    var weightText by remember { mutableStateOf("") }
    // The weight as the sheet opened: saved untouched, the stored value is kept exactly, never re-rounded.
    var openedWeight by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<SetTag?>(null) }
    val once = rememberOnce(target != null)
    LaunchedEffect(target) {
        target?.let { (_, set) ->
            val unit = sheetUnit(set, preferred, keepSetUnit)
            weightText = editableWeight(convert(set.weight, set.unit, unit))
            openedWeight = weightText
            repsText = if (target.first.isTimed) (set.seconds ?: 0).toString() else set.reps.toString()
            tag = set.tag
        }
    }
    // The target as it was when the sheet opened, still shown while it slides away after Save.
    val shown = rememberLast(target)
    BottomSheet(open = target != null, onDismiss = onDismiss, title = shown?.first?.name) {
        val (entry, set) = shown ?: return@BottomSheet
        val index = entry.sets.indexOfFirst { it.id == set.id } + 1
        // A partner's set keeps its own unit; yours (even one logged before a unit switch) is edited in
        // your unit. A weight left as it opened is saved untouched either way (see openedWeight).
        val unit = sheetUnit(set, preferred, keepSetUnit)
        Txt(entry.name, Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Mute("Set $index", Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.3f).milky(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                val weightLabel = "Weight (${unit.label})"
                Mute(weightLabel, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
                BareField(weightText, { weightText = filterDecimal(it) }, Modifier.fillMaxWidth(), placeholder = "0", size = T.Entry, weight = ExtraBold, keyboardType = KeyboardType.Decimal, label = weightLabel)
                val other = parseNumber(weightText)?.let { convert(it, unit, unit.other) } ?: 0.0
                Mute("= ${formatWeightValue(other)} ${unit.other.label}", size = T.Caption)
            }
            Column(Modifier.weight(1f).milky(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                val countLabel = if (entry.isTimed) "Seconds" else "Reps"
                Mute(countLabel, Modifier.clearAndSetSemantics {}, size = T.Caption, weight = Semibold)
                BareField(repsText, { repsText = it.filter { ch -> ch.isAsciiDigit() }.take(4) }, Modifier.fillMaxWidth(), placeholder = "0", size = T.Entry, weight = ExtraBold, keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, label = countLabel)
                Mute(" ", size = T.Caption)
            }
        }
        Spacer(Modifier.height(14.dp))
        Segmented(listOf("Working", "Warm-up", "Drop"), when (tag) { null -> 0; SetTag.Warmup -> 1; SetTag.Drop -> 2 }, { tag = when (it) { 1 -> SetTag.Warmup; 2 -> SetTag.Drop; else -> null } }, Modifier.fillMaxWidth(), fill = true)
        Spacer(Modifier.height(16.dp))
        // Empty means no weight; anything typed always parses (see filterDecimal).
        val weightValid = weightText.isBlank() || parseNumber(weightText) != null
        PrimaryButton("Save", enabled = weightValid) {
            val updated = editedSet(set, entry.isTimed, weightText, openedWeight, unit, repsText.toIntOrNull() ?: 0, tag) ?: return@PrimaryButton
            once.act { onSave(entry, updated); onDismiss() }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp).tappable { once.act { onRemove(entry, set); onDismiss() } }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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
    val once = rememberOnce(open)
    fun pick(ex: Exercise) = once.act { onPick(ex); onDismiss() }
    fun create(name: String) = once.act { onCreate(name); onDismiss() }
    BottomSheet(open = open, onDismiss = onDismiss, fill = true, title = "Add exercise") {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Add exercise", Modifier.weight(1f), size = T.SheetTitle, weight = ExtraBold)
            CircleButton(Icons.X, "Close", onClick = onDismiss)
        }
        Row(Modifier.fillMaxWidth().height(50.dp).liquidGlass(RoundedCornerShape(25.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Search, c.mute, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            val q0 = query.trim()
            BareField(query, { query = it.take(MAX_EXERCISE_NAME) }, Modifier.weight(1f), placeholder = "Search or create", size = T.BodyLarge, imeAction = ImeAction.Done, label = "Search or create an exercise", onDone = { if (query.isNotBlank()) create(query.trim().replaceFirstChar { it.uppercaseChar() }) })
            if (q0.isNotEmpty() && exercises.any { it.name.contains(q0, ignoreCase = true) }) Mute("${exercises.count { it.name.contains(q0, ignoreCase = true) }} found", size = T.Small)
        }
        val q = query.trim()
        val matches = exercises.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        val exact = matches.any { it.name.equals(q, ignoreCase = true) }
        Column(Modifier.weight(1f, fill = false).bleedHorizontally(18.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            if (recent.isNotEmpty()) {
                // The pills' touch targets add 6 dp above them, taken off the gap under the label.
                Mute("Recent this week", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 2.dp), weight = Bold)
                Row(Modifier.bleedHorizontally(18.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { ex -> TargetPill(height = 36.dp, onClick = { pick(ex) }) { Txt(ex.name, size = T.Small, weight = Semibold) } }
                }
            }
            if (matches.isNotEmpty()) {
                Mute(if (q.isEmpty()) "All exercises" else "Matches", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), weight = Bold)
                Column(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
                    matches.take(80).forEachIndexed { i, ex ->
                        if (i > 0) Divider(inset = 66.dp, end = 14.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).tappable { pick(ex) }.padding(horizontal = 14.dp, vertical = 8.dp),
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
                    Modifier.padding(top = if (matches.isEmpty()) 14.dp else 12.dp).fillMaxWidth().tappable { create(newName) }.liquidGlass(RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
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

/**
 * Rename an exercise in this workout, or swap it for another lift from the library. The library copy
 * can be renamed too only when it belongs to [ownerId] (the signed-in user); null never offers it.
 */
@Composable
fun RenameExerciseSheet(
    entry: WorkoutEntry?,
    exercises: List<Exercise>,
    canUpdateRoutine: Boolean,
    ownerId: String?,
    onSave: (newName: String, linkTo: Exercise?, updateRoutine: Boolean, updateLibrary: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Whose lift a name links to first when several share it: the workout owner's. */
    linkOwner: String? = ownerId,
) {
    val c = Volum.colors
    var name by remember { mutableStateOf("") }
    var updateRoutine by remember { mutableStateOf(true) }
    var updateLibrary by remember { mutableStateOf(false) }
    LaunchedEffect(entry) { entry?.let { name = it.name; updateRoutine = true; updateLibrary = false } }
    val once = rememberOnce(entry != null)
    val shown = rememberLast(entry)
    BottomSheet(open = entry != null, onDismiss = onDismiss, title = "Rename exercise") {
        val e = shown ?: return@BottomSheet
        Txt("Rename exercise", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(name, { name = it.take(MAX_EXERCISE_NAME) }, Modifier.fillMaxWidth(), placeholder = "Exercise name", imeAction = ImeAction.Done, label = "Exercise name")
        }
        val q = name.trim()
        val link = renameLinkTarget(exercises, q, e.exerciseId, linkOwner)
        val suggestions = if (q.length >= 2) exercises.filter { it.name.contains(q, ignoreCase = true) && it.id != e.exerciseId && !it.name.equals(q, true) }.take(4) else emptyList()
        if (suggestions.isNotEmpty()) {
            // 3 dp, plus the 7 dp the pills' touch targets add above them.
            Row(Modifier.padding(top = 3.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { ex -> TargetPill(height = 34.dp, onClick = { name = ex.name }) { Txt(ex.name, size = T.Small, weight = Semibold) } }
            }
        }
        if (link != null) Mute("Links this to ${link.name} in your library.", Modifier.padding(8.dp, 10.dp), size = T.Small)
        Spacer(Modifier.height(8.dp))
        if (canUpdateRoutine) Check("Also update in routine", updateRoutine) { updateRoutine = it }
        // A partner's exercise (or one shared from elsewhere) isn't yours to rename in the library.
        val ownExercise = ownerId != null && e.exerciseId.isNotEmpty() && exercises.any { it.id == e.exerciseId && it.owner == ownerId }
        val canUpdateLibrary = link == null && ownExercise
        if (canUpdateLibrary) Check("Also update in exercise library", updateLibrary) { updateLibrary = it }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = q.isNotEmpty()) {
            once.act {
                onSave(q, link, canUpdateRoutine && updateRoutine, canUpdateLibrary && updateLibrary)
                onDismiss()
            }
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

/**
 * Delete an exercise from this workout, and optionally from its routine. [onDelete] gets the entry the
 * sheet shows: the caller's own state is cleared by [onDismiss], which runs after it.
 */
@Composable
fun DeleteExerciseSheet(
    entry: WorkoutEntry?,
    routineName: String?,
    onDelete: (entry: WorkoutEntry, fromRoutine: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The routine has other exercises too; the server refuses a routine with none, so it can't lose its last. */
    routineKeepsOthers: Boolean = true,
    /**
     * Set when [entry] is the workout's only exercise: the server refuses a workout with none, so the
     * sheet offers to discard the workout instead.
     */
    onDiscardWorkout: (() -> Unit)? = null,
) {
    val c = Volum.colors
    val once = rememberOnce(entry != null)
    val shown = rememberLast(entry)
    BottomSheet(open = entry != null, onDismiss = onDismiss, title = shown?.let { "Delete ${it.name}?" }) {
        val e = shown ?: return@BottomSheet
        fun delete(fromRoutine: Boolean) = once.act { onDelete(e, fromRoutine); onDismiss() }
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.dangerSoft), contentAlignment = Alignment.Center) { Icon(Icons.Trash, c.danger, size = 24.dp) }
            Spacer(Modifier.height(12.dp))
            Txt("Delete ${e.name}?", size = T.SheetTitle, weight = ExtraBold, align = TextAlign.Center)
            val message = if (onDiscardWorkout != null) "It's the only exercise in this workout, and a workout needs one. Discard the whole workout instead?"
            else "Your logged sets for ${e.name} in this workout will be removed."
            Mute(message, Modifier.padding(top = 4.dp), size = T.Label, align = TextAlign.Center)
        }
        if (onDiscardWorkout != null) {
            DangerButton("Discard workout", subtitle = "Its sets won't be saved to your history") { once.act { onDiscardWorkout(); onDismiss() } }
        } else if (routineName != null && routineKeepsOthers) {
            DangerButton("Delete from this workout only", subtitle = "$routineName keeps ${e.name} next time") { delete(false) }
            Spacer(Modifier.height(8.dp))
            DangerButton("Delete from workout and routine", subtitle = "$routineName won't include ${e.name} any more") { delete(true) }
        } else {
            DangerButton("Delete from this workout") { delete(false) }
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
    val once = rememberOnce(open)
    BottomSheet(open = open, onDismiss = onDismiss, title = "Rename workout") {
        Txt("Rename workout", Modifier.padding(horizontal = 6.dp), size = T.SheetTitle, weight = ExtraBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().milky(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BareField(name, { name = it.take(MAX_ROUTINE_NAME) }, Modifier.fillMaxWidth(), placeholder = "Workout name", imeAction = ImeAction.Done, label = "Workout name")
        }
        if (hasRoutine) { Spacer(Modifier.height(8.dp)); Check("Also update routine name", alsoRoutine) { alsoRoutine = it } }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Save", enabled = name.isNotBlank()) { once.act { onSave(name.trim(), alsoRoutine); onDismiss() } }
    }
}
