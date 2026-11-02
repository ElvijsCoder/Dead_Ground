package app.deadground

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun Chip(n: Int, size: Int = 30) {
    Box(Modifier.size(size.dp).background(levelColor(n)), contentAlignment = Alignment.Center) {
        T("$n", size = (size * 0.56).roundToInt(), color = Black, font = Flap, spacing = 0f)
    }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier) {
    Column(modifier.border(1.dp, Line).background(Black).padding(horizontal = 8.dp, vertical = 6.dp)) {
        T(k, size = 11, color = Dim, spacing = 0.12f)
        T(v, size = 17)
    }
}

// ---- 7 DAY · ops calendar ----

@Composable
fun WeekBody(days: List<DayBrief>) {
    if (days.isEmpty()) return T("AWAITING DATA", color = Dim)
    var sel by remember { mutableIntStateOf(0) }
    val d = days[sel.coerceIn(days.indices)]
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WeekGrid(days, sel) { sel = it }

        Column(
            Modifier.fillMaxWidth().border(1.dp, Line).background(Black).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("${d.label} · ${d.condition}", size = 18, spacing = 0.14f)
                Chip(d.wxcon)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Stat("HI/LO", "${d.hi}° / ${d.lo}°", Modifier.weight(1f))
                if (d.snowCm >= 0.1) Stat("SNOW", "%.0f CM".format(maxOf(1.0, d.snowCm)), Modifier.weight(1f))
                else Stat("RAIN", if (d.rainMm < 0.1) "—" else "%.0f MM".format(maxOf(1.0, d.rainMm)), Modifier.weight(1f))
                Stat("GUSTS", "${d.gust} M/S", Modifier.weight(1f))
            }
            d.ice?.let { T("■ ICE $it", color = Red, spacing = 0.06f) }
            Row {
                T("LOADOUT ▸ ", color = Dim)
                T(d.loadout)
            }
        }
        T("TAP A DAY FOR ITS BRIEF", size = 12, color = Dim)
    }
}

/** Seven day columns: WXCON tile, hi/lo, precip blocks, code. Selectable on 7 DAY, read-only in AO. */
@Composable
fun WeekGrid(days: List<DayBrief>, sel: Int?, onSel: ((Int) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        days.forEachIndexed { i, day ->
            Column(
                Modifier.weight(1f).background(Black).border(1.dp, if (i == sel) White else Line)
                    .then(if (onSel != null) Modifier.clickable { onSel(i) } else Modifier)
                    .padding(horizontal = 3.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                T(day.label, size = 13, spacing = 0f)
                Box(Modifier.fillMaxWidth().height(40.dp).background(levelColor(day.wxcon)), contentAlignment = Alignment.Center) {
                    T("${day.wxcon}", size = 24, color = Black, font = Flap, spacing = 0f)
                }
                T("${day.hi}°", size = 16, spacing = 0f)
                T("${day.lo}°", size = 13, color = Dim, spacing = 0f)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (row in 2 downTo 0) {
                        val block = Modifier.fillMaxWidth().height(12.dp)
                        Box(
                            when {
                                row >= day.level -> block.background(Off)
                                day.type == Precip.SNOW -> block.border(1.dp, White)
                                day.type == Precip.ICE -> block.background(Red)
                                else -> block.background(White)
                            },
                        )
                    }
                }
                T(day.abbr, size = 12, color = Dim, spacing = 0f)
            }
        }
    }
}

// ---- AO · roster ----

@Composable
fun AoBody(
    aos: List<Place>,
    active: Int, // -1 = current position
    here: Brief?,
    hereDays: List<DayBrief>, // empty unless CURRENT POS is the active AO
    roster: List<Forecast?>,
    onSelect: (Int) -> Unit,
    onAdd: (Place) -> Unit,
    onRemove: (Int) -> Unit,
    search: suspend (String) -> List<Place>,
    gps: Boolean,
    onGps: (Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Int?>(null) } // -1 = CURRENT POS, i = saved, null = none
    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            note = "SEARCHING…"
            results = runCatching { search(query.trim()) }.getOrElse { emptyList() }
            note = if (results.isEmpty()) "NO MATCH" else null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("ADD AO", size = 12, color = Dim, spacing = 0.16f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicTextField(
                query,
                { query = it },
                Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, White).padding(horizontal = 12.dp, vertical = 12.dp),
                textStyle = TextStyle(color = White, fontSize = 14.sp, fontFamily = Mono, letterSpacing = 0.08.em),
                singleLine = true,
                cursorBrush = SolidColor(Green),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) T("SEARCH PLACE", color = Dim)
                        field()
                    }
                },
            )
            Box(
                Modifier.size(width = 64.dp, height = 44.dp).background(White).clickable { runSearch() },
                contentAlignment = Alignment.Center,
            ) { T("FIND", color = Black) }
        }
        note?.let { T(it, size = 12, color = Dim) }
        results.forEach { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, Line).clickable {
                    onAdd(p)
                    results = emptyList()
                    query = ""
                }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { T("+ ${p.name}", size = 13) }
        }

        AoRow("CURRENT POS", "LIVE · ALERTS ON", here, active == -1, live = true, onClick = { open = if (open == -1) null else -1 })
        if (open == -1) WeekPanel(hereDays, active == -1) { onSelect(-1) }
        aos.forEachIndexed { i, p ->
            val f = roster.getOrNull(i)
            val b = remember(f) { f?.let { runCatching { derive(it) }.getOrNull() } }
            AoRow(
                p.name.substringBefore(","), if (active == i) "ACTIVE" else "SAVED", b, active == i,
                live = false, onClick = { open = if (open == i) null else i }, onRemove = { onRemove(i) },
                country = p.name.substringAfterLast(", ", "").takeIf { it.length == 2 },
            )
            if (open == i) {
                val days = remember(f) { f?.let { runCatching { week(it) }.getOrNull() }.orEmpty() }
                WeekPanel(days, active == i) { onSelect(i) }
            }
        }
        T("ALERTS FOLLOW CURRENT POS ONLY\nSAVED AOS STORED ON THIS DEVICE", size = 12, color = Dim)

        T("LOCATION SOURCE", size = 12, color = Dim, spacing = 0.16f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "NETWORK", true to "GPS ONLY").forEach { (value, label) ->
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).background(if (gps == value) White else Black)
                        .border(1.dp, if (gps == value) White else Line).clickable { onGps(value) },
                    contentAlignment = Alignment.Center,
                ) { T(label, color = if (gps == value) Black else White, spacing = 0.12f) }
            }
        }
        T(
            if (gps) "SATELLITE FIX ONLY · NO NETWORK SERVICE ASKED\nSLOWER INDOORS · STILL ROUNDED TO ±1 KM"
            else "FAST · ON PHONES WITH GOOGLE SERVICES THE FIX\nCOMES FROM GOOGLE · ROUNDED TO ±1 KM",
            size = 12, color = Dim,
        )
    }
}

/** The strip that drops under an AO row: its week, plus the switch. */
@Composable
private fun WeekPanel(days: List<DayBrief>, isActive: Boolean, onActivate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Line).background(Black).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (days.isEmpty()) T(if (isActive) "AWAITING DATA" else "WEEK LOADS WHEN THIS AO IS ACTIVE", size = 12, color = Dim)
        else WeekGrid(days, sel = null, onSel = null)
        if (isActive) T("■ ACTIVE AO", size = 12, color = Green, spacing = 0.12f)
        else Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(White).clickable(onClick = onActivate),
            contentAlignment = Alignment.Center,
        ) { T("SET AS AO", color = Black, spacing = 0.14f) }
    }
}

@Composable
private fun AoRow(
    name: String,
    sub: String,
    b: Brief?,
    selected: Boolean,
    live: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    country: String? = null, // ISO code from place search, shown as a mini flap tile
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Black).border(1.dp, if (selected) Green else Line)
            .clickable(onClick = onClick).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            if (live) Modifier.size(10.dp).background(Green, CircleShape)
            else Modifier.size(10.dp).border(1.dp, Dim),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(name, size = 16, spacing = 0.1f)
                country?.let { FlapTiles(it, 15.dp, 22.dp, 14.sp) }
            }
            T(sub, size = 11, color = if (live) Green else Dim, spacing = 0.1f)
        }
        if (b != null) {
            T(abbr(b.code), size = 13, color = Dim)
            FlapTiles(tempText(b.temp).dropLast(1), 26.dp, 38.dp, 26.sp)
            Chip(b.wxcon)
        } else T("—", color = Dim)
        if (onRemove != null) {
            Box(Modifier.size(44.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                T("✕", color = Dim)
            }
        } else Box(Modifier.size(width = 2.dp, height = 44.dp))
    }
}

// ---- RADAR · model scope ----

@Composable
fun RadarBody(radar: Scope?, note: String?) {
    if (radar == null) return T(note ?: "SCANNING…", color = Dim)
    val frames = radar.times.size
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(700)
            frame = (frame + 1) % frames
        }
    }
    val f = frame.coerceIn(0, frames - 1)
    val r = readout(radar, f)
    val ctx = LocalContext.current
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ctx.resources.getFont(R.font.share_tech_mono)
            color = Dim.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "sweep",
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("FIRST CONTACT IN", size = 12, color = Dim, spacing = 0.16f)
            FlapTiles(r.countdown, 40.dp, 60.dp, 44.sp)
            T(r.headline, spacing = 0.1f)
        }

        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val c = Offset(size.width / 2, size.height / 2)
            val big = size.minDimension / 2 - 2.dp.toPx()
            val line = Stroke(1.dp.toPx())
            drawCircle(Color(0xFF050505), big, c)
            drawCircle(Line, big, c, style = Stroke(1.5.dp.toPx()))
            labelPaint.textSize = 10.dp.toPx()
            RANGES.forEach { km ->
                val rr = big * km / 30f * 0.92f
                drawCircle(White.copy(alpha = 0.14f), rr, c, style = line)
                drawContext.canvas.nativeCanvas.drawText("$km KM", c.x + 18.dp.toPx(), c.y - rr + 12.dp.toPx(), labelPaint)
            }
            drawLine(White.copy(alpha = 0.1f), Offset(c.x, c.y - big), Offset(c.x, c.y + big))
            drawLine(White.copy(alpha = 0.1f), Offset(c.x - big, c.y), Offset(c.x + big, c.y))
            labelPaint.textSize = 11.dp.toPx()
            listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (s, b) ->
                val rad = Math.toRadians(b.toDouble())
                val rr = big - 14.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(
                    s, c.x + (rr * sin(rad)).toFloat(), c.y - (rr * cos(rad)).toFloat() + 4.dp.toPx(), labelPaint,
                )
            }

            // Cells: white by intensity, outlined for snow, red core when heavy.
            radar.cells.forEach { cell ->
                val v = cell.precip[f]
                if (v < 0.1) return@forEach
                val rad = Math.toRadians(cell.bearing.toDouble())
                val rr = big * cell.km / 30f * 0.92f
                val p = Offset(c.x + (rr * sin(rad)).toFloat(), c.y - (rr * cos(rad)).toFloat())
                val radius = big * (0.12f + 0.06f * cell.km / 30f)
                val alpha = (0.15f + v.toFloat() * 0.45f).coerceAtMost(0.6f)
                if (cell.snow[f] > 0) drawCircle(White.copy(alpha = alpha + 0.2f), radius, p, style = line)
                else drawCircle(White.copy(alpha = alpha), radius, p)
                if (intensity(v) == "HEAVY") drawCircle(Red, radius * 0.25f, p)
            }

            drawArc(Green.copy(alpha = 0.12f), sweep - 90f - 25f, 25f, true, Offset(c.x - big, c.y - big), Size(big * 2, big * 2))
            val sr = Math.toRadians(sweep.toDouble())
            drawLine(Green, c, Offset(c.x + (big * sin(sr)).toFloat(), c.y - (big * cos(sr)).toFloat()), 1.5.dp.toPx())
            drawCircle(Green, 5.dp.toPx(), c)
            drawCircle(Green.copy(alpha = 0.5f), 11.dp.toPx(), c, style = line)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Stat("CONTACT", r.contact, Modifier.weight(1f))
            Stat("RANGE", r.range, Modifier.weight(1f))
            Stat("ETA", r.eta, Modifier.weight(1f))
            Stat("CORE", r.core, Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).border(1.dp, White).clickable { playing = !playing },
                contentAlignment = Alignment.Center,
            ) { T(if (playing) "■" else "▶", size = 16) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (i in 0 until frames) {
                        Box(
                            Modifier.weight(1f).height(16.dp)
                                .background(if (i == f) Green else if (i < f) White.copy(alpha = 0.5f) else Line)
                                .clickable {
                                    playing = false
                                    frame = i
                                },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T("NOW", size = 11, color = if (f == 0) White else Dim)
                    T(radar.times[f].substring(11, 16), size = 11, color = Green)
                    T("+4H", size = 11, color = Dim)
                }
            }
        }
        T("MODEL SCOPE · OPEN-METEO 15 MIN · NOT LIVE RADAR", size = 11, color = Dim)
    }
}
