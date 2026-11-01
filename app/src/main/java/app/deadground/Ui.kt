package app.deadground

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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
        text.forEachIndexed { i, ch -> key(i) { FlapTile(ch, w, h, size, delayMs = i * 70L) } }
    }
}

/**
 * One split-flap tile. The glyph is centred on the split by the ink of "0", not the font's
 * line box (Oswald sits low in its box, which is why 8 used to show more below the split).
 * When the character changes: the old top half falls to the split, then the new bottom half falls into place.
 */
@Composable
private fun FlapTile(ch: Char, w: Dp, h: Dp, size: TextUnit, delayMs: Long) {
    var current by remember { mutableStateOf(ch) }
    var previous by remember { mutableStateOf(ch) }
    val flip = remember { Animatable(1f) }
    LaunchedEffect(ch) {
        if (ch != current) {
            delay(delayMs) // left-to-right cascade
            previous = current
            current = ch
            flip.snapTo(0f)
            flip.animateTo(1f, tween(420, easing = LinearEasing))
        }
    }
    val p = flip.value
    Box(Modifier.size(w, h)) {
        FlapHalf(current, top = true, w, h, size, Modifier.align(Alignment.TopCenter))
        FlapHalf(if (p < 1f) previous else current, top = false, w, h, size, Modifier.align(Alignment.BottomCenter))
        if (p < 0.5f) {
            FlapHalf(
                previous, top = true, w, h, size,
                Modifier.align(Alignment.TopCenter).graphicsLayer {
                    rotationX = -180f * p // 0 → -90: falls toward you
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    cameraDistance = 16f * density
                },
            )
        } else if (p < 1f) {
            FlapHalf(
                current, top = false, w, h, size,
                Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    rotationX = 180f * (1f - p) // 90 → 0: lands
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    cameraDistance = 16f * density
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(Black)) // the split
    }
}

@Composable
private fun FlapHalf(ch: Char, top: Boolean, w: Dp, h: Dp, size: TextUnit, modifier: Modifier) {
    val ctx = LocalContext.current
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ctx.resources.getFont(R.font.oswald)
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val corner = 3.dp
    val shape = if (top) RoundedCornerShape(topStart = corner, topEnd = corner) else RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
    Canvas(modifier.size(w, h / 2).clip(shape).background(TileBg)) {
        paint.textSize = size.toPx()
        val ink = android.graphics.Rect().also { paint.getTextBounds("0", 0, 1, it) }
        val full = this.size.height * 2
        val baseline = full / 2 - ink.exactCenterY() - if (top) 0f else this.size.height
        drawContext.canvas.nativeCanvas.drawText(ch.toString(), this.size.width / 2, baseline, paint)
    }
}

fun tempText(t: Int) = (if (t < 0) "−" else "") + "%02d°".format(kotlin.math.abs(t))

// ---- Background: static topo contours + slow precip layer only while it's actually falling ----

internal class Ring(val cx: Float, val cy: Float, val k: Int, val ph: Double, val alpha: Float, val step: Double = 22.0)
private class Particle(val x: Float, val y: Float, val len: Float, val alpha: Float)

private fun topoRings(): List<Ring> {
    val rnd = java.util.Random(7)
    return listOf(Triple(300f, 230f, 9), Triple(60f, 640f, 8)).flatMap { (cx, cy, n) ->
        (1..n).map { k -> Ring(cx, cy, k, rnd.nextDouble() * 6, if (k % 4 == 0) 0.16f else 0.08f) }
    }
}

private const val MAX_RING = 9

/** Contour shape at drift phase d (radians). d = 0 is the static mockup. */
internal fun ringPoints(ring: Ring, d: Double): List<Offset> {
    val r0 = ring.step * ring.k
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
fun TerrainBackground(precip: Precip, reveal: Float = 1f) {
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
            // Intro: contours sweep outward from each peak, inner rings first.
            val shown = (reveal * (MAX_RING + 2) - ring.k).coerceIn(0f, 1f)
            if (shown > 0f) drawPath(path, White.copy(alpha = ring.alpha * shown), style = stroke)
        }
        if (reveal < 1f) return@Canvas // no rain/snow layer until the terrain is in
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
fun BriefBody(b: Brief, revealed: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val temp = tempText(b.temp)
            // Blank tiles until the splash hands over, then they flip to the reading.
            FlapTiles(if (revealed) temp else " ".repeat(temp.length), 72.dp, 106.dp, 78.sp)
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
