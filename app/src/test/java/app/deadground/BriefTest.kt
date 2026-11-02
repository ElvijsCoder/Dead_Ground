package app.deadground

import org.junit.Test

class BriefTest {
    // Mixed day: dry now, rain from 16:45, freezing overnight, snow by morning.
    private fun mixedDay(): Forecast {
        val hours = mutableListOf<Hour>()
        for (d in listOf("2026-10-31", "2026-11-01", "2026-11-02")) for (h in 0..23) {
            val t = "%sT%02d:00".format(d, h)
            val today = d == "2026-11-01"
            val tomorrow = d == "2026-11-02"
            val (temp, mm, snow) = when {
                d == "2026-10-31" -> Triple(11.0, 0.0, 0.0)
                today && h < 16 -> Triple(7.0, 0.0, 0.0)
                today && h <= 20 -> Triple(6.0 - (h - 16), 1.5, 0.0)
                today && h == 21 -> Triple(1.0, 0.2, 0.0)
                today -> Triple(0.0, if (h == 22) 0.3 else 0.0, 0.0)
                tomorrow && h in 1..2 -> Triple(-1.0, 0.0, 0.0)
                tomorrow && h in 3..8 -> Triple(-1.0, 0.7, 1.0)
                else -> Triple(-2.0, 0.0, 0.0)
            }
            hours += Hour(t, temp, mm, snow, if (snow > 0) 73 else if (mm > 0) 61 else 3, 6.0)
        }
        val slots = (0 until 96).map { i ->
            val min = 14 * 60 + 15 + i * 15
            val t = if (min < 24 * 60) "2026-11-01T%02d:%02d".format(min / 60, min % 60)
            else "2026-11-02T%02d:%02d".format((min / 60) % 24, min % 60)
            Slot(t, if (t >= "2026-11-01T16:45" && t < "2026-11-01T21:00") 0.4 else 0.0)
        }
        return Forecast(Current("2026-11-01T14:15", 7.0, 3.0, 3, 0.0), hours, slots)
    }

    @Test
    fun mixedDayBrief() {
        val b = derive(mixedDay())
        check(b.go) { "go: $b" }
        check(b.window == "WINDOW CLOSES 16:45") { b.window }
        check(b.reason == "RAIN INBOUND ~16:45") { b.reason }
        check(b.wxcon == 2) { "wxcon ${b.wxcon}" }
        check(b.threat == "ICE") { "threat ${b.threat}" }
        check(b.stripLabel == "T+24H PRECIP") { b.stripLabel }
        check(b.strip.map { it.type }.containsAll(listOf(Precip.RAIN, Precip.ICE, Precip.SNOW))) { "${b.strip}" }
        check(b.stripLegend.startsWith("RAIN")) { b.stripLegend }
        check(b.vsYesterday == -4) { "vs ${b.vsYesterday}" }
        check(b.loadout == "COAT · HOOD · GRIP BOOTS · GLOVES AFTER 1900") { b.loadout }
        check(b.stamp == "1415L · 01 NOV") { b.stamp }
        check(b.nowPrecip == Precip.NONE)
    }

    @Test
    fun rainingNowIsNoGo() {
        val f = mixedDay()
        val b = derive(f.copy(current = f.current.copy(time = "2026-11-01T17:00", precip = 1.2)))
        check(!b.go && b.window == "NEXT WINDOW ~21:00") { b.window }
        check(b.nowPrecip == Precip.RAIN)
    }

    // Your 20:47 case: rain fell 19–20, nothing ahead. Must not claim "inbound ~20:00".
    @Test
    fun pastHourRainIsIgnored() {
        val hours = (0..47).map { i ->
            val t = "2026-11-%02dT%02d:00".format(1 + i / 24, i % 24)
            Hour(t, 6.0, if (t == "2026-11-01T20:00") 0.2 else 0.0, 0.0, 3, 5.0)
        }
        val slots = (0 until 96).map { i ->
            val m = 20 * 60 + 45 + i * 15
            Slot("2026-11-%02dT%02d:%02d".format(1 + m / 1440, (m / 60) % 24, m % 60), 0.0)
        }
        val b = derive(Forecast(Current("2026-11-01T20:45", 6.0, 3.0, 3, 0.0), hours, slots))
        check(b.reason == "NO PRECIP 24H") { b.reason }
        check(b.window == "DRY 24H") { b.window }
        check(b.strip.all { it.level == 0 }) { "${b.strip}" }
        check(b.go)
    }

    // Light rain ahead in hourly data that never reaches the 15-min threshold: "TRACE", not "INBOUND".
    @Test
    fun traceRainIsLabelled() {
        val hours = (0..47).map { i ->
            val t = "2026-11-%02dT%02d:00".format(1 + i / 24, i % 24)
            Hour(t, 6.0, if (t == "2026-11-01T23:00") 0.2 else 0.0, 0.0, 3, 5.0)
        }
        val slots = (0 until 96).map { i ->
            val m = 20 * 60 + 45 + i * 15
            Slot("2026-11-%02dT%02d:%02d".format(1 + m / 1440, (m / 60) % 24, m % 60), 0.0)
        }
        val b = derive(Forecast(Current("2026-11-01T20:45", 6.0, 3.0, 3, 0.0), hours, slots))
        check(b.reason == "TRACE RAIN ~22:00") { b.reason }
        check(b.window == "DRY 24H") { b.window }
    }

    @Test
    fun weekFromMixedDay() {
        val w = week(mixedDay())
        check(w.size == 2) { "days ${w.size}" }
        check(w[0].label == "SUN") { w[0].label }
        check(w[0].wxcon == 2 && w[0].type == Precip.ICE) { "${w[0]}" }
        check(w[0].hi == 7 && w[0].lo == 0) { "${w[0]}" }
        check(w[1].snowCm == 6.0) { "${w[1]}" }
        check(w[1].loadout.contains("GRIP BOOTS")) { w[1].loadout }
    }

    // Rain cell walks in from the north-west: 30 km → 20 → 10 → overhead after 2 h.
    @Test
    fun scopeTracksApproachingRain() {
        val times = (0 until 16).map { i -> val m = 14 * 60 + 15 + i * 15; "2026-11-01T%02d:%02d".format(m / 60, m % 60) }
        fun cell(b: Int, km: Int, wet: IntRange?) =
            Cell(b, km, (0 until 16).map { if (wet != null && it in wet) 0.5 else 0.0 }, List(16) { 0.0 })
        val ring = RANGES.flatMap { km ->
            BEARINGS.map { b ->
                cell(b, km, if (b == 315) mapOf(30 to 0..3, 20 to 2..6, 10 to 4..8)[km] else null)
            }
        }
        val s = Scope(times, listOf(cell(0, 0, 8..15)) + ring)
        val r0 = readout(s, 0)
        check(r0.contact == "NW" && r0.range == "30 KM") { "$r0" }
        check(r0.countdown == "02:00" && r0.eta == "~16:15") { "$r0" }
        check(r0.headline == "RAIN · LIGHT · FROM NW · MOVING SE") { r0.headline }
        check(readout(s, 8).headline == "RAIN OVERHEAD · LIGHT") { readout(s, 8).headline }
        check(scopePoints(56.95, 24.11).size == 25)
        check(compass(350.0) == "N" && compass(200.0) == "S")
    }

    @Test
    fun alertsOnMixedDay() {
        val f = mixedDay()
        val at1600 = f.copy(current = f.current.copy(time = "2026-11-01T16:00"))
        val a = evaluate(at1600, null, emptyList(), 0L)
        val rain = a.single { it.key.startsWith("rain:") }
        check(rain.key == "rain:2026-11-01T16:45" && rain.lines[2] == "WINDOW CLOSES T−45") { "$rain" }
        check(a.any { it.key == "type:2026-11-01:ICE" }) { "$a" }

        val at1800 = f.copy(current = f.current.copy(time = "2026-11-01T18:00", precip = 1.0))
        val b = evaluate(at1800, 72, emptyList(), 0L)
        check(b.none { it.key.startsWith("rain:") }) { "raining already: $b" }
        check(b.any { it.key == "ice-eve:2026-11-01" && it.prec == Prec.IMMEDIATE }) { "$b" }
        check(b.any { it.key == "air:2026-11-01" }) { "$b" }
    }

    @Test
    fun gateHonoursQuietHoursCapAndDedupe() {
        val now = 100 * 3_600_000L
        fun al(k: String, p: Prec = Prec.PRIORITY) = Alert(k, "x", p, listOf(k), 3)
        // Quiet hours: only FLASH passes.
        check(gate(listOf(al("a"), al("warn4:r", Prec.FLASH)), emptyMap(), 23, now).map { it.key } == listOf("warn4:r"))
        // Already sent never repeats; rain cools down for 3 h.
        check(gate(listOf(al("a")), mapOf("a" to 0L), 12, now).isEmpty())
        check(gate(listOf(al("rain:2")), mapOf("rain:1" to now - 3_600_000L), 12, now).isEmpty())
        // Cap: 3 sent in the last 24 h → only one more gets through.
        val sent = mapOf("s1" to now - 1, "s2" to now - 1, "s3" to now - 1)
        check(gate(listOf(al("b"), al("c")), sent, 12, now).map { it.key } == listOf("b"))
    }

    @Test
    fun warningsOutrankModel() {
        val now = 1_000_000L
        val w = Warning("x", 3, "WIND", "RĪGA", "18:00", "23:00", now - 1, now + 3_600_000L)
        val b = applyWarnings(derive(mixedDay()), listOf(w), now)
        check(b.wxcon == 2 && b.threat == "ORANGE · WIND" && b.threatWhen == "18:00–23:00") { "$b" }
        val a = evaluate(mixedDay(), null, listOf(w), now).single { it.key == "warn3:x" }
        check(a.prec == Prec.IMMEDIATE && a.lines[1] == "WIND // RĪGA") { "$a" }
        check(applyWarnings(derive(mixedDay()), listOf(w.copy(expiresMs = now - 1)), now).threat == "ICE")
        check(wxconBar(2) == "WXCON  5 4 3 [2] 1")
    }

    @Test
    fun widgetSymbols() {
        check(symbolFor(0, true) == Sym.SUN && symbolFor(1, false) == Sym.MOON)
        check(symbolFor(3, true) == Sym.CLOUD && symbolFor(45, false) == Sym.CLOUD)
        check(symbolFor(61, true) == Sym.RAIN && symbolFor(81, false) == Sym.RAIN && symbolFor(95, true) == Sym.RAIN)
        check(symbolFor(73, true) == Sym.SNOW && symbolFor(86, false) == Sym.SNOW)
        val f = mixedDay()
        check(derive(f.copy(current = f.current.copy(code = 0, isDay = false))).symbol == Sym.MOON)
    }

    @Test
    fun hourlyRows() {
        val h = derive(mixedDay()).hourly
        check(h.size == 24) { "${h.size}" }
        check(h[0].time == "14:00" && h[0].temp == 7 && h[0].precip == "—") { "${h[0]}" }
        val r = h.first { it.time == "15:00" }
        check(r.precip == "1.5 MM" && r.type == Precip.RAIN && r.symbol == Sym.RAIN) { "$r" }
        val snow = h.first { it.time == "03:00" }
        check(snow.type == Precip.SNOW && snow.precip == "1.0 CM" && snow.symbol == Sym.SNOW) { "$snow" }
    }
}
