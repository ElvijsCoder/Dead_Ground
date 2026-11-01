package app.deadground

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * MODEL SCOPE — not live radar. Open-Meteo's 15-minute forecast for a ring of points
 * around the AO (8 bearings × 10/20/30 km + centre), fetched in one request.
 */

val BEARINGS = (0 until 360 step 45).toList()
val RANGES = listOf(10, 20, 30)

/** One sample point. precip/snow are per 15-min frame, frame 0 = now. */
data class Cell(val bearing: Int, val km: Int, val precip: List<Double>, val snow: List<Double>)

/** cells[0] is the centre (km 0); the rest follow [scopePoints] order. */
data class Scope(val times: List<String>, val cells: List<Cell>)

data class Readout(
    val countdown: String, // "02:20" until first precip at the centre, "--:--" if none in range
    val headline: String,
    val contact: String,
    val range: String,
    val eta: String,
    val core: String,
)

private const val WET15 = 0.1 // mm per 15 min

/** Centre first, then each range ring clockwise from north. Same order the cells come back in. */
fun scopePoints(lat: Double, lon: Double): List<Triple<Int, Int, Pair<Double, Double>>> {
    fun r3(x: Double) = (x * 1000).roundToInt() / 1000.0
    val ring = RANGES.flatMap { km ->
        BEARINGS.map { b ->
            val rad = Math.toRadians(b.toDouble())
            val dLat = km * cos(rad) / 111.0
            val dLon = km * sin(rad) / (111.0 * cos(Math.toRadians(lat)))
            Triple(b, km, r3(lat + dLat) to r3(lon + dLon))
        }
    }
    return listOf(Triple(0, 0, lat to lon)) + ring
}

fun compass(bearing: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((bearing % 360) + 360 + 22.5) / 45).toInt() % 8]

fun intensity(mm15: Double): String {
    val perHour = mm15 * 4
    return when {
        mm15 < WET15 -> "NIL"
        perHour < 2.5 -> "LIGHT"
        perHour < 7.6 -> "MOD"
        else -> "HEAVY"
    }
}

/** Where the weighted precip mass drifts between two frames, as a compass heading. */
fun motion(s: Scope, from: Int, to: Int): String? {
    fun centroid(f: Int): Pair<Double, Double>? {
        var w = 0.0
        var x = 0.0
        var y = 0.0
        s.cells.forEach { c ->
            val v = c.precip.getOrElse(f) { 0.0 }
            val rad = Math.toRadians(c.bearing.toDouble())
            w += v
            x += v * c.km * sin(rad)
            y += v * c.km * cos(rad)
        }
        return if (w < WET15) null else (x / w) to (y / w)
    }
    val a = centroid(from) ?: return null
    val b = centroid(to) ?: return null
    val dx = b.first - a.first
    val dy = b.second - a.second
    if (dx * dx + dy * dy < 4.0) return null // under 2 km of drift: call it stationary
    return compass(Math.toDegrees(atan2(dx, dy)))
}

fun readout(s: Scope, frame: Int): Readout {
    val centre = s.cells[0]
    val first = centre.precip.indexOfFirst { it >= WET15 }
    val kind = if (s.cells.any { c -> c.snow.any { it > 0 } }) "SNOW" else "RAIN"
    // Nearest wet cell this frame; among equals, the heavier one.
    val contact = s.cells.drop(1).filter { it.precip[frame] >= WET15 }
        .minWithOrNull(compareBy<Cell> { it.km }.thenByDescending { it.precip[frame] })
    val core = intensity(s.cells.maxOf { it.precip[frame] })
    val moving = motion(s, frame, minOf(frame + 4, s.times.lastIndex))?.let { " · MOVING $it" }.orEmpty()
    val headline = when {
        centre.precip[frame] >= WET15 -> "$kind OVERHEAD · $core"
        contact != null -> "$kind · $core · FROM ${compass(contact.bearing.toDouble())}$moving"
        s.cells.any { c -> c.precip.any { it >= WET15 } } -> "$kind IN RANGE LATER"
        else -> "NO CONTACT 4H"
    }
    return Readout(
        countdown = if (first < 0) "--:--" else "%02d:%02d".format(first * 15 / 60, first * 15 % 60),
        headline = headline,
        contact = contact?.let { compass(it.bearing.toDouble()) } ?: "—",
        range = contact?.let { "${it.km} KM" } ?: "—",
        eta = if (first < 0) "—" else "~" + s.times[first].substring(11, 16),
        core = core,
    )
}
