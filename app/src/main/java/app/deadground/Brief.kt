package app.deadground

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Raw forecast, times are local ISO strings ("2026-11-01T14:15") so they compare as text.
data class Current(val time: String, val temp: Double, val feels: Double, val code: Int, val precip: Double)
data class Hour(val time: String, val temp: Double, val precip: Double, val snowCm: Double, val code: Int, val gust: Double)
data class Slot(val time: String, val precip: Double) // 15-minute precipitation
data class Forecast(val current: Current, val hours: List<Hour>, val slots: List<Slot>)

enum class Precip { NONE, RAIN, SNOW, ICE } // ordered by severity

data class StripCol(val hour: String, val type: Precip, val level: Int)

data class Brief(
    val stamp: String,
    val temp: Int,
    val feels: Int,
    val condition: String,
    val vsYesterday: Int?,
    val wxcon: Int,
    val reason: String,
    val go: Boolean,
    val window: String,
    val threat: String?,
    val threatWhen: String?,
    val strip: List<StripCol>,
    val stripLabel: String,
    val stripLegend: String,
    val loadout: String,
    val nowPrecip: Precip,
)

private const val WET = 0.1 // mm; below this a slot/hour counts as dry
private val FREEZING = setOf(56, 57, 66, 67)

private fun hh(t: String) = t.substring(11, 13)
private fun hhmm(t: String) = t.substring(11, 16)
private fun hourBefore(t: String) = "%02d".format((hh(t).toInt() + 23) % 24)

fun level(mm: Double) = when {
    mm < WET -> 0
    mm < 0.5 -> 1
    mm < 2.5 -> 2
    else -> 3
}

fun precipType(h: Hour) = when {
    h.code in FREEZING -> Precip.ICE
    h.precip < WET -> Precip.NONE
    h.snowCm > 0 -> Precip.SNOW
    h.temp <= 0 -> Precip.ICE
    else -> Precip.RAIN
}

fun condition(code: Int) = when (code) {
    0 -> "CLEAR"
    1 -> "MAINLY CLEAR"
    2 -> "PARTLY CLOUDY"
    3 -> "OVERCAST"
    45, 48 -> "FOG"
    51, 53, 55 -> "DRIZZLE"
    56, 57 -> "FREEZING DRIZZLE"
    61, 63, 65 -> "RAIN"
    66, 67 -> "FREEZING RAIN"
    71, 73, 75, 77 -> "SNOW"
    80, 81, 82 -> "SHOWERS"
    85, 86 -> "SNOW SHOWERS"
    95 -> "THUNDERSTORM"
    96, 99 -> "THUNDER + HAIL"
    else -> "—"
}

private fun name(p: Precip) = when (p) {
    Precip.SNOW -> "SNOW"
    Precip.ICE -> "FREEZING RAIN"
    else -> "RAIN"
}

private fun amount(x: Double) = if (x < 1) "%.1f".format(Locale.US, x) else x.roundToInt().toString()

fun derive(f: Forecast): Brief {
    val c = f.current
    val nowHour = c.time.substring(0, 13) + ":00"
    // Hourly precip is the total for the hour BEFORE its timestamp, so "20:00" is already past at 20:47.
    // The first hour that still matters is the one stamped after now.
    val idx = f.hours.indexOfFirst { it.time > c.time }.let { if (it < 0) f.hours.size else it }
    val past6 = f.hours.subList(maxOf(0, idx - 6), idx)
    val next24 = f.hours.drop(idx).take(24)
    val types = next24.map(::precipType)

    // Ice = freezing precip, or at/below 0° within 6 h of wet ground.
    val iceHours = next24.indices.filter { i ->
        types[i] == Precip.ICE || (
            types[i] == Precip.NONE && next24[i].temp <= 0 &&
                (past6 + next24.take(i)).takeLast(6).any { it.precip >= WET }
            )
    }
    val iceSpan = iceHours.takeIf { it.isNotEmpty() }
        ?.let { "${hourBefore(next24[it.first()].time)}00–${hh(next24[it.last()].time)}00" }
    val maxGust = next24.maxOfOrNull { it.gust } ?: 0.0

    val threat: String?
    val threatWhen: String?
    when {
        iceSpan != null -> { threat = "ICE"; threatWhen = iceSpan }
        maxGust >= 18 -> { threat = "WIND"; threatWhen = "GUSTS ${maxGust.roundToInt()} M/S" }
        else -> { threat = null; threatWhen = null }
    }

    // GO/NO-GO from 15-minute data; falls back to hourly where 15-min isn't available.
    val slots = f.slots.filter { it.time >= c.time.take(16) }
        .ifEmpty { next24.map { Slot(it.time, it.precip) } }
    val wetNow = c.precip >= WET || (slots.firstOrNull()?.precip ?: 0.0) >= WET
    val iceNow = iceHours.firstOrNull() == 0
    val firstWetSlot = slots.firstOrNull { it.precip >= WET }
    val window = when {
        iceNow -> {
            val run = iceHours.withIndex().takeWhile { it.index == it.value }.last().value
            "ICE UNTIL ~${hh(next24[run].time)}00"
        }
        wetNow -> slots.firstOrNull { it.precip < WET }?.let { "NEXT WINDOW ~${hhmm(it.time)}" } ?: "NO WINDOW 24H"
        else -> firstWetSlot?.let { "WINDOW CLOSES ${hhmm(it.time)}" } ?: "DRY 24H"
    }

    val firstWet = types.indexOfFirst { it != Precip.NONE }
    val reason = when {
        wetNow -> "${name(types.firstOrNull { it != Precip.NONE } ?: Precip.RAIN)} NOW"
        firstWetSlot != null -> "${name(types.getOrNull(firstWet) ?: Precip.RAIN)} INBOUND ~${hhmm(firstWetSlot.time)}"
        // Hourly sees a little, 15-min never crosses the threshold: say so instead of claiming it's inbound.
        firstWet >= 0 -> "TRACE ${name(types[firstWet])} ~${hourBefore(next24[firstWet].time)}:00"
        else -> "NO PRECIP 24H"
    }

    val levels = next24.map { level(it.precip) }
    val codes = next24.map { it.code }.toSet()
    // ponytail: hand-tuned thresholds; adjust after a few weeks of real use. MeteoAlarm feeds in later.
    val wxcon = when {
        codes.any { it in setOf(66, 67, 96, 99) } || maxGust >= 25 -> 1
        iceHours.isNotEmpty() || next24.any { it.snowCm >= 1.0 } || maxGust >= 18 || 95 in codes -> 2
        levels.take(12).any { it >= 2 } || maxGust >= 12 -> 3
        levels.any { it >= 1 } -> 4
        else -> 5
    }

    val strip = next24.indices.chunked(3).map { ix ->
        val t = ix.map { if (it in iceHours) Precip.ICE else types[it] }.maxBy { it.ordinal }
        val lv = ix.maxOf { levels[it] }
        StripCol(hourBefore(next24[ix.first()].time), t, if (t == Precip.ICE) maxOf(1, lv) else lv)
    }
    val seen = strip.map { it.type }.filter { it != Precip.NONE }.distinct()
    val stripLabel = "T+24H " + when (seen.toSet()) {
        setOf(Precip.RAIN) -> "RAIN"
        setOf(Precip.SNOW) -> "SNOW"
        else -> "PRECIP"
    }
    val stripLegend = seen.joinToString(" ▸ ") {
        when (it) {
            Precip.RAIN -> "RAIN ${amount(next24.filterIndexed { i, _ -> types[i] == Precip.RAIN }.sumOf { h -> h.precip })} MM"
            Precip.SNOW -> "SNOW ${amount(next24.sumOf { h -> h.snowCm })} CM"
            else -> "ICE $iceSpan"
        }
    }.ifEmpty { "DRY 24H" }

    val loadout = buildList {
        add(
            when {
                c.feels >= 20 -> "T-SHIRT"
                c.feels >= 14 -> "LIGHT LAYER"
                c.feels >= 8 -> "JACKET"
                c.feels >= 2 -> "COAT"
                else -> "INSULATED"
            }
        )
        if (wetNow || types.take(6).any { it != Precip.NONE }) add("HOOD")
        if (iceHours.any { it < 12 }) add("GRIP BOOTS")
        if (c.temp <= 3) add("GLOVES")
        else next24.take(12).firstOrNull { it.temp <= 3 }?.let { add("GLOVES AFTER ${hh(it.time)}00") }
    }.joinToString(" · ")

    val yesterday = LocalDateTime.parse(nowHour).minusDays(1).toString()
    val vs = f.hours.firstOrNull { it.time == yesterday }?.let { (c.temp - it.temp).roundToInt() }

    return Brief(
        stamp = LocalDateTime.parse(c.time)
            .format(DateTimeFormatter.ofPattern("HHmm'L' · dd MMM", Locale.ENGLISH)).uppercase(),
        temp = c.temp.roundToInt(),
        feels = c.feels.roundToInt(),
        condition = condition(c.code),
        vsYesterday = vs,
        wxcon = wxcon,
        reason = reason,
        go = !wetNow && !iceNow,
        window = window,
        threat = threat,
        threatWhen = threatWhen,
        strip = strip,
        stripLabel = stripLabel,
        stripLegend = stripLegend,
        loadout = loadout,
        nowPrecip = if (wetNow) types.firstOrNull { it != Precip.NONE } ?: Precip.RAIN else Precip.NONE,
    )
}
