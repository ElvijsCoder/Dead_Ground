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
}
