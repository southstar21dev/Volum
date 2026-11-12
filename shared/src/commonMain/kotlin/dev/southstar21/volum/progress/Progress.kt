package dev.southstar21.volum.progress

import dev.southstar21.volum.model.SetEntry
import dev.southstar21.volum.model.SetTag
import dev.southstar21.volum.model.WeightUnit
import dev.southstar21.volum.model.Workout
import dev.southstar21.volum.model.WorkoutEntry
import dev.southstar21.volum.model.WorkoutStatus
import dev.southstar21.volum.data.topSetOrder
import dev.southstar21.volum.util.convert
import dev.southstar21.volum.util.displayName
import dev.southstar21.volum.util.durationLabel
import dev.southstar21.volum.util.isoOf
import dev.southstar21.volum.util.localDateOf
import dev.southstar21.volum.util.localZone
import dev.southstar21.volum.util.monthName
import dev.southstar21.volum.util.monthShort
import dev.southstar21.volum.util.parseTime
import dev.southstar21.volum.util.plural
import dev.southstar21.volum.util.toKg
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus

// Ported from web/src/lib/progress (sets.ts, copy.ts, dashboard.ts) and web/src/lib/chart.ts.

const val MAX_COMPARE_REPS = 12

data class BestSet(val weight: Double, val unit: WeightUnit, val weightKg: Double, val reps: Int, val time: Long)

fun workoutTime(w: Workout): Long = parseTime(w.finishedAt ?: w.startedAt)

fun workingSets(entry: WorkoutEntry): List<SetEntry> =
    if (entry.isSkipped) emptyList() else entry.sets.filter { it.done && it.tag != SetTag.Warmup && it.reps >= 1 && it.weight > 0 }

fun epley(weightKg: Double, reps: Int): Double = weightKg * (1 + reps / 30.0)

fun bestSetIn(workouts: List<Workout>, exerciseId: String, maxReps: Int = MAX_COMPARE_REPS): BestSet? {
    var best: BestSet? = null
    for (w in workouts) {
        val time = workoutTime(w)
        for (entry in w.entries) {
            if (entry.exerciseId != exerciseId) continue
            for (set in workingSets(entry)) {
                if (set.reps > maxReps) continue
                val kg = toKg(set.weight, set.unit)
                val b = best
                if (b == null || kg > b.weightKg || (kg == b.weightKg && set.reps > b.reps)) best = BestSet(set.weight, set.unit, kg, set.reps, time)
            }
        }
    }
    return best
}

sealed interface Comparison {
    data class Weight(val amount: String, val gain: Double) : Comparison
    data class Reps(val amount: String, val at: String, val gain: Double) : Comparison
    data object Equal : Comparison
    data object Worse : Comparison
}

private fun trim2(v: Double): String {
    val r = (v * 100).roundToLong() / 100.0
    return if (r == floor(r)) r.toLong().toString() else r.toString()
}

/** Exact: logged value with up to 2 decimals. Otherwise rounded to 0.5 kg / 1 lb. */
fun formatLoad(value: Double, unit: WeightUnit, exact: Boolean): String {
    if (exact) return "${trim2(value)} ${unit.label}"
    val step = if (unit == WeightUnit.Kg) 0.5 else 1.0
    return "${trim2((value / step).roundToLong() * step)} ${unit.label}"
}

fun compareBest(now: BestSet, then: BestSet, unit: WeightUnit): Comparison {
    val nowE = epley(now.weightKg, now.reps)
    val thenE = epley(then.weightKg, then.reps)
    if (nowE < thenE - 1e-9) return Comparison.Worse
    val gain = nowE / thenE - 1
    val exact = now.unit == unit && then.unit == unit
    val nowW = convert(now.weight, now.unit, unit)
    val thenW = convert(then.weight, then.unit, unit)
    val amount = formatLoad(nowW - thenW, unit, exact)
    val delta = amount.substringBefore(' ').toDoubleOrNull() ?: 0.0
    if (delta > 0) return Comparison.Weight(amount, gain)
    val sameWeight = abs(now.weightKg - then.weightKg) < 0.01
    if (sameWeight && now.reps > then.reps) {
        val d = now.reps - then.reps
        return Comparison.Reps("$d ${if (d == 1) "rep" else "reps"}", formatLoad(nowW, unit, exact), gain)
    }
    if (!sameWeight && now.weightKg < then.weightKg) return Comparison.Worse
    return Comparison.Equal
}

fun compareBestIn(now: BestSet, then: BestSet, thenWorkouts: List<Workout>, exerciseId: String, unit: WeightUnit): Comparison {
    val cmp = compareBest(now, then, unit)
    if (cmp !is Comparison.Weight && cmp !is Comparison.Reps) return cmp
    var dominated = false
    for (w in thenWorkouts) for (entry in w.entries) {
        if (entry.exerciseId != exerciseId) continue
        for (set in workingSets(entry)) {
            val kg = toKg(set.weight, set.unit)
            if (kg < now.weightKg - 0.01 || set.reps < now.reps) continue
            if (kg > now.weightKg + 0.01 || set.reps > now.reps) return Comparison.Worse
            dominated = true
        }
    }
    return if (dominated) Comparison.Equal else cmp
}

// ---- Dashboard ------------------------------------------------------------------------------

const val SUMMARY_MS = 5 * 60 * 1000L
const val BREAK_DAYS = 14
const val PERIOD_DAYS = 28
const val MAX_SLIDES = 5
const val WEEKS = 8

data class WeekVolume(val start: LocalDate, val volumeKg: Double)

data class Slide(val key: String, val name: String, val fullName: String, val amount: String, val lead: String, val value: String)

sealed interface Sentence {
    data class Improving(val slides: List<Slide>, val prs: Int, val monthWorkouts: Int) : Sentence
    data class Plateau(val monthWorkouts: Int) : Sentence
    data class Break(val ago: String) : Sentence
    data class Returning(val sinceBreak: Int) : Sentence
    data class Finished(val workoutId: String, val routineName: String, val duration: String, val slides: List<Slide>, val improved: Int, val kept: Boolean = false) : Sentence
    data class Matched(val workoutId: String, val routineName: String, val duration: String, val matched: Int, val kept: Boolean = false) : Sentence
    data class FirstRoutine(val workoutId: String, val routineName: String) : Sentence
    data class Done(val workoutId: String, val routineName: String, val duration: String) : Sentence
}

data class Dashboard(val sentence: Sentence, val weeks: List<WeekVolume>, val summaryEndsAt: Long?)

private data class Timed(val workout: Workout, val original: Workout, val time: Long)
private data class Ranked(val slide: Slide, val gain: Double)

private fun startOfWeek(d: LocalDate): LocalDate = d.minus(DatePeriod(days = d.dayOfWeek.ordinal))
private fun daysBetween(from: Long, to: Long): Int = localDateOf(from).daysUntil(localDateOf(to))

fun buildDashboard(workouts: List<Workout>, userId: String, exerciseNames: Map<String, String>, unit: WeightUnit, now: Long): Dashboard? {
    val own = workouts.filter { it.owner == userId && it.status == WorkoutStatus.Completed && !it.deleted }
        .map { w ->
            val time = minOf(workoutTime(w), now)
            Timed(w.copy(finishedAt = isoOf(time)), w, time)
        }.sortedBy { it.time }
    if (own.isEmpty()) return null

    fun nameOf(id: String): String {
        exerciseNames[id]?.let { return it }
        for (t in own.asReversed()) t.workout.entries.firstOrNull { it.exerciseId == id }?.let { return it.name }
        return id
    }
    val weeks = weeklyVolume(own, now)
    val latest = own.last()
    if (now - latest.time <= SUMMARY_MS) {
        return Dashboard(summary(own, ::nameOf, unit), weeks, latest.time + SUMMARY_MS)
    }
    if (own.size < 2) return null
    val sentence = trend(own, ::nameOf, unit, now)
    if (sentence is Sentence.Improving) return Dashboard(sentence, weeks, null)
    val recap = lastRecap(own, ::nameOf, unit)
    if (recap is Sentence.Finished) return Dashboard(recap.copy(duration = "", kept = true), weeks, null)
    if (sentence != null) return Dashboard(sentence, weeks, null)
    if (recap is Sentence.Matched) return Dashboard(recap.copy(duration = "", kept = true), weeks, null)
    return null
}

private fun sameRoutine(a: Workout, b: Workout): Boolean =
    if (a.routineId != null && b.routineId != null) a.routineId == b.routineId else a.routineName == b.routineName

private fun exerciseIds(ts: List<Timed>): List<String> = ts.flatMap { t -> t.workout.entries.map { it.exerciseId } }.distinct()

private fun lastRecap(own: List<Timed>, nameOf: (String) -> String, unit: WeightUnit): Sentence? {
    val seen = mutableListOf<Workout>()
    var matched: Sentence? = null
    for (i in own.indices.reversed()) {
        if (i == 0) break
        val w = own[i].workout
        if (seen.any { sameRoutine(it, w) }) continue
        seen += w
        val s = summary(own.subList(0, i + 1), nameOf, unit)
        if (s is Sentence.Finished) return s
        if (s is Sentence.Matched && matched == null) matched = s
    }
    return matched
}

private fun summary(own: List<Timed>, nameOf: (String) -> String, unit: WeightUnit): Sentence {
    val latest = own.last()
    val id = latest.workout.id
    val routineName = latest.workout.routineName
    val previous = own.dropLast(1).asReversed().firstOrNull { sameRoutine(it.workout, latest.workout) }
        ?: return Sentence.FirstRoutine(id, routineName)
    val duration = durationLabel(latest.original.startedAt, latest.original.finishedAt)
    val ranked = mutableListOf<Ranked>()
    var matched = 0
    for (eid in exerciseIds(listOf(latest))) {
        val n = bestSetIn(listOf(latest.workout), eid) ?: continue
        val t = bestSetIn(listOf(previous.workout), eid) ?: continue
        val cmp = compareBestIn(n, t, listOf(previous.workout), eid, unit)
        if (cmp == Comparison.Equal) matched++
        toSlide(eid, nameOf(eid), cmp, "since last", "$routineName.")?.let { ranked += it }
    }
    if (ranked.isNotEmpty()) return Sentence.Finished(id, routineName, duration, topSlides(ranked), ranked.size)
    if (matched > 0) return Sentence.Matched(id, routineName, duration, matched)
    return Sentence.Done(id, routineName, duration)
}

private fun trend(own: List<Timed>, nameOf: (String) -> String, unit: WeightUnit, now: Long): Sentence? {
    val latest = own.last()
    val sinceLatest = daysBetween(latest.time, now)
    if (sinceLatest >= BREAK_DAYS) return Sentence.Break(agoLabel(sinceLatest))
    val today = localDateOf(now)
    val recentStart = today.minus(DatePeriod(days = PERIOD_DAYS)).atStartOfDayIn(localZone).toEpochMilliseconds() + (now - today.atStartOfDayIn(localZone).toEpochMilliseconds())
    val baselineStart = recentStart - PERIOD_DAYS * 86_400_000L
    val recent = own.filter { it.time > recentStart }
    val baseline = own.filter { it.time > baselineStart && it.time <= recentStart }
    val recentW = recent.map { it.workout }
    val baseW = baseline.map { it.workout }
    val ranked = mutableListOf<Ranked>()
    var comparable = 0
    for (eid in exerciseIds(recent)) {
        val n = bestSetIn(recentW, eid) ?: continue
        val t = bestSetIn(baseW, eid) ?: continue
        comparable++
        val since = "${sinceLabel(t.time, now)}."
        toSlide(eid, nameOf(eid), compareBestIn(n, t, baseW, eid, unit), "since", since)?.let { ranked += it }
    }
    val monthWorkouts = own.count { val d = localDateOf(it.time); d.year == today.year && d.month == today.month }
    if (ranked.isNotEmpty()) return Sentence.Improving(topSlides(ranked), countPrs(own, now), monthWorkouts)
    workoutsSinceBreak(own, now)?.let { return Sentence.Returning(it) }
    if (comparable > 0) return Sentence.Plateau(monthWorkouts)
    return null
}

private fun workoutsSinceBreak(own: List<Timed>, now: Long): Int? {
    for (i in own.indices.reversed()) {
        if (i == 0) break
        val gap = daysBetween(own[i - 1].time, own[i].time)
        if (gap < BREAK_DAYS) continue
        return if (daysBetween(own[i].time, now) <= BREAK_DAYS) own.size - i else null
    }
    return null
}

/** Lifts this week heavier than every earlier working set of that lift. */
fun countPrsThisWeek(workouts: List<Workout>, userId: String, now: Long): Int {
    val own = workouts.filter { it.owner == userId && it.status == WorkoutStatus.Completed && !it.deleted }
        .map { Timed(it, it, minOf(workoutTime(it), now)) }.sortedBy { it.time }
    return countPrs(own, now)
}

private fun countPrs(own: List<Timed>, now: Long): Int {
    val weekStart = startOfWeek(localDateOf(now)).atStartOfDayIn(localZone).toEpochMilliseconds()
    var prs = 0
    own.forEachIndexed { i, t ->
        if (t.time < weekStart) return@forEachIndexed
        val earlier = own.subList(0, i).filter { it.time < t.time }.map { it.workout }
        for (eid in exerciseIds(listOf(t))) {
            val best = bestSetIn(listOf(t.workout), eid, Int.MAX_VALUE)
            val prior = bestSetIn(earlier, eid, Int.MAX_VALUE)
            if (best != null && prior != null && best.weightKg > prior.weightKg + 0.01) prs++
        }
    }
    return prs
}

private fun weeklyVolume(own: List<Timed>, now: Long): List<WeekVolume> {
    val current = startOfWeek(localDateOf(now))
    val first = startOfWeek(localDateOf(own.first().time))
    val count = minOf(WEEKS, first.daysUntil(current) / 7 + 1)
    val starts = (count - 1 downTo 0).map { current.minus(DatePeriod(days = 7 * it)) }
    val volume = DoubleArray(starts.size)
    for (t in own) {
        val idx = starts.indexOf(startOfWeek(localDateOf(t.time)))
        if (idx < 0) continue
        for (entry in t.workout.entries) for (set in workingSets(entry)) volume[idx] += toKg(set.weight, set.unit) * set.reps
    }
    return starts.mapIndexed { i, s -> WeekVolume(s, volume[i]) }
}

/** Kilograms lifted in [workout]: weight × reps summed over its working sets. */
fun volumeKg(workout: Workout): Double = workout.entries.sumOf { e -> workingSets(e).sumOf { toKg(it.weight, it.unit) * it.reps } }

private fun toSlide(id: String, fullName: String, cmp: Comparison, lead: String, value: String): Ranked? = when (cmp) {
    is Comparison.Weight -> Ranked(Slide(id, displayName(fullName), fullName, cmp.amount, lead, value), cmp.gain)
    is Comparison.Reps -> Ranked(Slide(id, displayName(fullName), fullName, cmp.amount, "at", "${cmp.at}."), cmp.gain)
    else -> null
}

private fun topSlides(ranked: List<Ranked>): List<Slide> =
    ranked.sortedWith(compareByDescending<Ranked> { it.gain }.thenBy { it.slide.name }).take(MAX_SLIDES).map { it.slide }

fun sinceLabel(time: Long, now: Long): String {
    val d = localDateOf(time)
    val n = localDateOf(now)
    return if (d.year == n.year && d.month == n.month) "${monthShort(d)} ${d.day}" else monthName(d)
}

fun agoLabel(days: Int): String = when {
    days <= 0 -> "today"
    days < 14 -> "${plural(days, "day")} ago"
    days < 56 -> "${plural(days / 7, "week")} ago"
    days < 365 -> "${plural(days / 30, "month")} ago"
    else -> "over a year ago"
}

// ---- Chart ----------------------------------------------------------------------------------

/** One session's top set: kg (with [reps]), or seconds when the entry was logged as a hold ([timed]). */
data class ChartPoint(val time: Long, val value: Double, val reps: Int, val workoutId: String, val timed: Boolean = false)

enum class ChartRange(val label: String, val days: Int?) { D30("30D", 30), M3("3M", 91), M6("6M", 182), Y1("1Y", 365), All("All", null) }

/**
 * Every lift's top set per completed session, oldest first, in one pass over [workouts]: the heaviest
 * working set in kg (with its reps), or the longest hold in seconds for a timed lift. One point per
 * session even when a lift appears twice in it.
 */
fun topSetSeries(workouts: Collection<Workout>, userId: String): Map<String, List<ChartPoint>> {
    val out = mutableMapOf<String, MutableList<ChartPoint>>()
    for (w in workouts) {
        if (w.owner != userId || w.status != WorkoutStatus.Completed || w.deleted) continue
        val time = workoutTime(w)
        for (entry in w.entries) {
            if (entry.isSkipped || entry.exerciseId.isEmpty()) continue
            val sets = entry.sets.filter { it.done && it.tag != SetTag.Warmup }
            if (sets.isEmpty()) continue
            val point = if (entry.isTimed) {
                val longest = sets.maxOf { it.seconds ?: 0 }
                if (longest <= 0) continue
                ChartPoint(time, longest.toDouble(), 0, w.id, timed = true)
            } else {
                val top = sets.maxWith(topSetOrder(timed = false))
                ChartPoint(time, toKg(top.weight, top.unit), top.reps, w.id)
            }
            val list = out.getOrPut(entry.exerciseId) { mutableListOf() }
            val last = list.lastOrNull()
            if (last != null && last.workoutId == w.id) {
                // The same order as within one entry: heavier (or longer) first, then more reps.
                if (point.value > last.value || (point.value == last.value && point.reps > last.reps)) list[list.lastIndex] = point
            } else {
                list += point
            }
        }
    }
    return out.mapValues { (_, v) -> v.sortedBy { it.time } }
}

fun filterRange(points: List<ChartPoint>, range: ChartRange, now: Long): List<ChartPoint> {
    val days = range.days ?: return points
    val cutoff = now - days * 86_400_000L
    return points.filter { it.time >= cutoff }
}
