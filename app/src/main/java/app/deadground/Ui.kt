package app.deadground

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val Green = Color(0xFF39FF6A)
val Red = Color(0xFFFF3B30)
val Dim = Color(0xFF9A9A9A)
val Line = Color(0xFF3A3A3A)
val Off = Color(0xFF1F1F1F)
val TileBg = Color(0xFF161616)

val Mono = FontFamily(Font(R.font.share_tech_mono))
val Flap = FontFamily(Font(R.font.oswald))

fun levelColor(n: Int) = when {
    n >= 4 -> Green
    n == 3 -> White
    else -> Red
}

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = White,
    font: FontFamily = Mono,
    spacing: Float = 0.08f,
) = BasicText(text, modifier, TextStyle(color = color, fontSize = size.sp, fontFamily = font, letterSpacing = spacing.em))

@Composable
fun FlapTiles(text: String, w: Dp, h: Dp, size: TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        text.forEach { ch ->
            Box(Modifier.size(w, h).background(TileBg, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                BasicText(ch.toString(), style = TextStyle(color = White, fontSize = size, fontFamily = Flap))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Black)) // the split
            }
        }
    }
}

fun tempText(t: Int) = (if (t < 0) "−" else "") + "%02d°".format(kotlin.math.abs(t))

// ---- Background: static topo contours + slow precip layer only while it's actually falling ----

private class Ring(val cx: Float, val cy: Float, val k: Int, val ph: Double, val alpha: Float)
private class Particle(val x: Float, val y: Float, val len: Float, val alpha: Float)

private fun topoRings(): List<Ring> {
    val rnd = java.util.Random(7)
    return listOf(Triple(300f, 230f, 9), Triple(60f, 640f, 8)).flatMap { (cx, cy, n) ->
        (1..n).map { k -> Ring(cx, cy, k, rnd.nextDouble() * 6, if (k % 4 == 0) 0.16f else 0.08f) }
    }
}

/** Contour shape at drift phase d (radians). d = 0 is the static mockup. */
private fun ringPoints(ring: Ring, d: Double): List<Offset> {
    val r0 = 22.0 * ring.k
    // ponytail: whole-terrain drift + slow "breathing"; one cycle ≈ 2 min, imperceptible frame to frame
    val dx = sin(d) * 14
    val dy = sin(d * 2) * 8
    return (0 until 360 step 6).map { a ->
        val t = Math.toRadians(a.toDouble())
        val r = r0 * (1 + 0.18 * sin(3 * t + ring.ph + ring.k * 0.3 + d) + 0.08 * sin(5 * t + ring.ph * 1.7 - d))
        Offset((ring.cx + dx + r * cos(t) * 1.25).toFloat(), (ring.cy + dy + r * sin(t)).toFloat())
    }
}

@Composable
fun TerrainBackground(precip: Precip) {
    val rings = remember { topoRings() }
    val particles = remember {
        val rnd = java.util.Random(3)
        List(100) { Particle(rnd.nextFloat(), rnd.nextFloat(), 14f + rnd.nextFloat() * 20f, listOf(0.06f, 0.1f, 0.14f)[rnd.nextInt(3)]) }
    }
    val drift = rememberInfiniteTransition(label = "terrain").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "drift",
    )
    val snow = precip == Precip.SNOW
    val phase: State<Float>? = if (precip == Precip.NONE) null else {
        rememberInfiniteTransition(label = "precip").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (snow) 18_000 else 7_000, easing = LinearEasing)), label = "fall",
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / 390f
        val sy = size.height / 844f
        val stroke = Stroke(1.dp.toPx())
        val d = drift.value.toDouble()
        rings.forEach { ring ->
            val path = Path()
            ringPoints(ring, d).forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * sx, p.y * sy) else path.lineTo(p.x * sx, p.y * sy)
            }
            path.close()
            drawPath(path, White.copy(alpha = ring.alpha), style = stroke)
        }
        val t = phase?.value ?: return@Canvas
        particles.forEach { p ->
            val y = ((p.y + t) % 1f) * size.height
            val x = p.x * size.width
            if (snow) {
                drawCircle(White.copy(alpha = p.alpha * 1.8f), p.len / 14f * sx, Offset(x + sin((t * 6 + p.x * 10).toDouble()).toFloat() * 8f * sx, y))
            } else {
                drawLine(White.copy(alpha = p.alpha), Offset(x, y), Offset(x - p.len * 0.28f * sx, y + p.len * sy), 1.dp.toPx())
            }
        }
    }
}

// ---- Brief screen ----

@Composable
fun BriefBody(b: Brief) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlapTiles(tempText(b.temp), 72.dp, 106.dp, 78.sp)
            T("${b.condition} · FEELS ${b.feels}°", size = 18, spacing = 0.18f)
            b.vsYesterday?.let { T((if (it > 0) "+" else if (it < 0) "−" else "±") + "${kotlin.math.abs(it)}° VS YESTERDAY", color = Dim) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("WXCON", size = 12, color = Dim, spacing = 0.16f)
                T("5 CALM ··· 1 SEVERE", size = 12, color = Dim, spacing = 0.16f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in 5 downTo 1) {
                    val on = n == b.wxcon
                    val c = levelColor(n)
                    Box(
                        Modifier.weight(1f).height(52.dp).background(if (on) c else Black).border(1.dp, if (on) c else Line),
                        contentAlignment = Alignment.Center,
                    ) { T("$n", size = 26, color = if (on) Black else Color(0xFF6E6E6E), font = Flap, spacing = 0f) }
                }
            }
            T(b.reason)
        }

        Row(
            Modifier.fillMaxWidth().background(if (b.go) Green else Red).padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            T(if (b.go) "GO" else "NO-GO", size = 19, color = Black, font = Flap)
            T(b.window, size = 19, color = Black, font = Flap)
        }

        if (b.threat != null) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Red).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                T("■ THREAT: ${b.threat}", color = Red)
                T(b.threatWhen.orEmpty(), color = Red)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(b.stripLabel, size = 12, color = Dim, spacing = 0.16f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                b.strip.forEach { col ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 2 downTo 0) {
                            val block = Modifier.fillMaxWidth().height(7.dp)
                            Box(
                                when {
                                    i >= col.level -> block.background(Off)
                                    col.type == Precip.SNOW -> block.border(1.dp, White)
                                    col.type == Precip.ICE -> block.background(Red)
                                    else -> block.background(White)
                                },
                            )
                        }
                        T(col.hour, size = 12, color = Dim)
                    }
                }
            }
            T(b.stripLegend, size = 12, color = Dim, spacing = 0.06f)
        }

        Row {
            T("LOADOUT ▸ ", color = Dim)
            T(b.loadout)
        }
    }
}

@Composable
fun Nav(tab: String, onTab: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("BRIEF", "RADAR", "7 DAY", "AO").forEach { t ->
            val sel = t == tab
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).background(if (sel) White else Black)
                    .border(1.dp, if (sel) White else Line).clickable { onTab(t) },
                contentAlignment = Alignment.Center,
            ) { T(t, color = if (sel) Black else White, spacing = 0.12f) }
        }
    }
}
