package app.deadground

import java.time.Duration
import java.time.LocalDateTime

enum class Prec { PRIORITY, IMMEDIATE, FLASH }

/** One MeteoAlarm warning. level: 2 yellow, 3 orange, 4 red. Times in epoch ms. */
data class Warning(
    val id: String,
    val level: Int,
    val type: String, // "WIND", "RAIN", …
    val area: String,
    val onset: String, // "HH:mm" local, for display
    val expires: String,
    val onsetMs: Long,
    val expiresMs: Long,
)

/** key dedupes: the same key never fires twice. channel = Android notification channel. */
data class Alert(val key: String, val channel: String, val prec: Prec, val lines: List<String>, val wxcon: Int)

const val QUIET_FROM = 22
const val QUIET_TO = 7
const val DAILY_CAP = 4
private const val HOUR_MS = 3_600_000L

private fun color(level: Int) = when (level) {
    4 -> "RED"
    3 -> "ORANGE"
    else -> "YELLOW"
}

/** Warnings that matter now: not expired, starting within 24 h. */
fun activeWarnings(ws: List<Warning>, nowMs: Long) =
    ws.filter { it.level >= 2 && it.expiresMs > nowMs && it.onsetMs < nowMs + 24 * HOUR_MS }

/** Official warnings outrank the model: yellow → WXCON 3, orange → 2, red → 1. */
fun applyWarnings(b: Brief, ws: List<Warning>, nowMs: Long): Brief {
    val top = activeWarnings(ws, nowMs).maxByOrNull { it.level } ?: return b
    return b.copy(
        wxcon = minOf(b.wxcon, 5 - top.level),
        threat = "${color(top.level)} · ${top.type}",
        threatWhen = "${top.onset}–${top.expires}",
    )
}

fun evaluate(f: Forecast, aqi: Int?, warnings: List<Warning>, nowMs: Long): List<Alert> {
    val b = derive(f)
    val c = f.current
    val now = LocalDateTime.parse(c.time)
    val date = c.time.take(10)
    val hour = now.hour
    val idx = f.hours.indexOfFirst { it.time > c.time }.let { if (it < 0) f.hours.size else it }
    val out = mutableListOf<Alert>()

    // Rain inbound: dry now, first wet 15-min slot within the hour.
    if (b.go) {
        f.slots.firstOrNull { it.time >= c.time.take(16) && it.precip >= 0.1 }?.let { s ->
            val mins = Duration.between(now, LocalDateTime.parse(s.time)).toMinutes()
            if (mins in 1..60) {
                val kind = f.hours.firstOrNull { it.time > s.time }?.let(::precipType)
                    ?.takeIf { it != Precip.NONE } ?: Precip.RAIN
                val next2h = f.slots.filter { it.time >= s.time }.take(8)
                out += Alert(
                    "rain:${s.time}", "rain", Prec.PRIORITY,
                    listOf(
                        "DG // ${precipName(kind)} INBOUND // ETA ${s.time.substring(11, 16)}",
                        "INT ${intensity(next2h.maxOf { it.precip })} // ${amountText(next2h.sumOf { it.precip })} MM IN 2H",
                        "WINDOW CLOSES T−$mins",
                    ),
                    b.wxcon,
                )
            }
        }
    }

    // Rain turning to snow or freezing rain within 6 h.
    val next6 = f.hours.drop(idx).take(6)
    val t6 = next6.map(::precipType)
    val firstWet = t6.indexOfFirst { it != Precip.NONE }
    if (firstWet >= 0 && t6[firstWet] == Precip.RAIN) {
        t6.withIndex().drop(firstWet).firstOrNull { it.value == Precip.SNOW || it.value == Precip.ICE }?.let { (i, to) ->
            out += Alert(
                "type:$date:$to", "type", Prec.PRIORITY,
                listOf(
                    "DG // RAIN TURNING ${precipName(to)} // ~${hourStart(next6[i].time)}",
                    if (to == Precip.ICE) "SURFACES WILL ICE" else "SNOW ${amountText(next6.sumOf { it.snowCm })} CM IN 6H",
                ),
                b.wxcon,
            )
        }
    }

    // Ice: evening heads-up for tonight, morning reminder if it's still icy.
    if (hour in 17..21 && b.threat == "ICE") {
        out += Alert("ice-eve:$date", "ice", Prec.IMMEDIATE, listOf("DG // ICE // ${b.threatWhen}", "LOADOUT: GRIP BOOTS"), b.wxcon)
    }
    if (hour in 6..9 && iceIn(f.hours, idx, 4).isNotEmpty()) {
        out += Alert("ice-am:$date", "ice", Prec.IMMEDIATE, listOf("DG // ICE ON PATHS // NOW", "LOADOUT: GRIP BOOTS"), b.wxcon)
    }

    // Official warnings: yellow PRIORITY, orange IMMEDIATE, red FLASH.
    activeWarnings(warnings, nowMs).forEach { w ->
        out += Alert(
            "warn${w.level}:${w.id}", "warn",
            when (w.level) {
                4 -> Prec.FLASH
                3 -> Prec.IMMEDIATE
                else -> Prec.PRIORITY
            },
            listOf("DG // OFFICIAL WARNING // ${color(w.level)}", "${w.type} // ${w.area}", "${w.onset}–${w.expires}"),
            minOf(b.wxcon, 5 - w.level),
        )
    }

    // Air quality: European AQI 60+ is "poor".
    if (aqi != null && aqi >= 60) {
        out += Alert(
            "air:$date", "air", Prec.PRIORITY,
            listOf("DG // AIR QUALITY ${if (aqi >= 80) "VERY POOR" else "POOR"} // EAQI $aqi", "LIMIT OUTDOOR EFFORT"),
            b.wxcon,
        )
    }
    return out
}

/**
 * What actually gets sent: never the same key twice, rain at most once per 3 h,
 * nothing but FLASH in quiet hours or once [DAILY_CAP] is used up in the last 24 h.
 */
fun gate(alerts: List<Alert>, sent: Map<String, Long>, hour: Int, nowMs: Long): List<Alert> {
    val quiet = hour >= QUIET_FROM || hour < QUIET_TO
    var used = sent.count { (k, t) -> nowMs - t < 24 * HOUR_MS && !k.startsWith("warn4:") }
    val rainCooling = sent.any { (k, t) -> k.startsWith("rain:") && nowMs - t < 3 * HOUR_MS }
    return alerts.filter { a ->
        when {
            a.key in sent -> false
            a.key.startsWith("rain:") && rainCooling -> false
            a.prec == Prec.FLASH -> true
            quiet || used >= DAILY_CAP -> false
            else -> {
                used++
                true
            }
        }
    }
}

/** "WXCON 5 4 [3] 2 1" — text survives every notification surface (lock screen, watch, light theme). */
fun wxconBar(level: Int) = "WXCON  " + (5 downTo 1).joinToString(" ") { if (it == level) "[$it]" else "$it" }
