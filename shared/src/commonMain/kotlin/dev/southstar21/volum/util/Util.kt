package dev.southstar21.volum.util

import dev.southstar21.volum.model.WeightUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

/** 15-character ids from the same alphabet as the web app (PocketBase's id format). */
fun newId(): String = buildString(15) { repeat(15) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) } }

// ---- Time -------------------------------------------------------------------------------------

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** ISO-8601 with milliseconds and Z, like JavaScript's toISOString(). */
fun isoOf(millis: Long): String {
    val instant = Instant.fromEpochMilliseconds(millis)
    val dt = instant.toLocalDateTime(TimeZone.UTC)
    fun two(n: Int) = n.toString().padStart(2, '0')
    val ms = (millis % 1000 + 1000) % 1000
    return "${dt.year.toString().padStart(4, '0')}-${two(dt.month.ordinal + 1)}-${two(dt.day)}T" +
        "${two(dt.hour)}:${two(dt.minute)}:${two(dt.second)}.${ms.toString().padStart(3, '0')}Z"
}

fun nowIso(): String = isoOf(nowMillis())

/** Milliseconds for an ISO or PocketBase ("2026-01-02 03:04:05.678Z") timestamp, 0 when missing or invalid. */
fun parseTime(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    return try {
        Instant.parse(value.trim().replace(' ', 'T')).toEpochMilliseconds()
    } catch (_: Throwable) {
        0
    }
}

val localZone: TimeZone get() = TimeZone.currentSystemDefault()

fun localDateTimeOf(millis: Long): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(localZone)

fun localDateOf(millis: Long): LocalDate = localDateTimeOf(millis).date

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

fun monthName(date: LocalDate): String = MONTHS[date.month.ordinal]
fun monthShort(date: LocalDate): String = MONTHS[date.month.ordinal].take(3)
fun weekdayName(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.ordinal]
fun weekdayShort(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.ordinal].take(3)

/** "Thu 30 Oct" */
fun shortDate(millis: Long): String {
    val d = localDateOf(millis)
    return "${weekdayShort(d)} ${d.day} ${monthShort(d)}"
}

/** "30 Oct 2026" */
fun longDate(millis: Long): String {
    val d = localDateOf(millis)
    return "${d.day} ${monthShort(d)} ${d.year}"
}

fun timeOfDay(millis: Long): String {
    val t = localDateTimeOf(millis)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

// ---- Units ------------------------------------------------------------------------------------

const val KG_PER_LB = 0.45359237

fun lbToKg(lb: Double): Double = lb * KG_PER_LB
fun kgToLb(kg: Double): Double = kg / KG_PER_LB

fun convert(weight: Double, from: WeightUnit, to: WeightUnit): Double = when {
    from == to -> weight
    from == WeightUnit.Kg -> kgToLb(weight)
    else -> lbToKg(weight)
}

fun toKg(weight: Double, unit: WeightUnit): Double = if (unit == WeightUnit.Kg) weight else lbToKg(weight)

/** One decimal at most: 62.5, 60, 137.8. */
fun formatWeightValue(weight: Double): String {
    val rounded = (weight * 10).roundToLong() / 10.0
    return if (rounded == floor(rounded)) rounded.toLong().toString() else {
        val tenths = abs((rounded * 10).roundToLong() % 10)
        "${if (rounded < 0 && rounded > -1) "-" else ""}${rounded.toLong()}.$tenths"
    }
}

fun formatWeight(weight: Double, unit: WeightUnit): String = "${formatWeightValue(weight)} ${unit.label}"

/** "45 s" under a minute, "1:05" from a minute up. */
fun formatSeconds(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    if (total < 60) return "$total s"
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

fun durationLabel(startedAt: String, finishedAt: String?): String {
    if (finishedAt == null) return ""
    val ms = parseTime(finishedAt) - parseTime(startedAt)
    if (ms <= 0) return ""
    val minutes = (ms / 60000.0).roundToInt()
    if (minutes >= 60) {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
    }
    return "$minutes min"
}

/** "34:12" or "1:04:12" since [startMillis]. */
fun elapsedLabel(startMillis: Long, now: Long): String {
    val total = ((now - startMillis) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

fun plural(n: Int, word: String): String = "$n ${if (n == 1) word else "${word}s"}"

/** "Incline press (30°)" -> "Incline press" */
private val PARENTHESISED = Regex("\\([^)]*\\)")
private val WHITESPACE = Regex("\\s+")

fun displayName(name: String): String = name.replace(PARENTHESISED, " ").replace(WHITESPACE, " ").trim()

/** Two-letter badge for an exercise: "Bench press" -> "BP". */
fun initials(name: String): String {
    val words = displayName(name).split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Volume as "6.4 t" from 1000 kg up, else "640 kg". */
fun formatVolume(kg: Double): String =
    if (kg >= 1000) "${formatWeightValue(kg / 1000)} t" else "${kg.roundToInt()} kg"

fun formatThousands(n: Int): String {
    val s = abs(n).toString()
    val grouped = s.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}
