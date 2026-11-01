package app.deadground

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.widget.RemoteViews
import kotlin.concurrent.thread

/**
 * W3 terrain widget. Drawn as one bitmap so it matches the app pixel for pixel
 * (RemoteViews can't do custom fonts, contour lines or the strip).
 *
 * Location: the widget never asks for it. It refreshes the forecast for the last AO
 * the app resolved (already rounded to ~1 km), so it needs no background-location permission.
 */
class DeadGroundWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync() // network off the main thread; system allows ~10 s
        thread {
            try {
                val p = prefs(ctx)
                val lat = p.getString("lat", null)?.toDoubleOrNull()
                val lon = p.getString("lon", null)?.toDoubleOrNull()
                val stale = System.currentTimeMillis() - p.getLong("at", 0L) > 15 * 60_000L
                if (lat != null && lon != null && stale) runCatching { fetchAndStore(ctx, lat, lon) }
                renderAll(ctx)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        renderAll(ctx) // resized: redraw at the new size

    companion object {
        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DeadGroundWidget::class.java))
            if (ids.isEmpty()) return
            val brief = prefs(ctx).getString("json", null)
                ?.let { runCatching { applyWarnings(derive(OpenMeteo.parse(it)), loadWarnings(ctx), System.currentTimeMillis()) }.getOrNull() }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            ids.forEach { id ->
                val o = mgr.getAppWidgetOptions(id)
                // Portrait size = min width × max height. Some launchers report 0 before first layout.
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 300
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 150
                val views = RemoteViews(ctx.packageName, R.layout.widget)
                views.setImageViewBitmap(R.id.art, WidgetArt.render(ctx, w, h, brief))
                views.setContentDescription(R.id.art, brief?.let { "Dead Ground: ${it.temp}°, ${headline(it)}" } ?: "Dead Ground")
                views.setOnClickPendingIntent(R.id.art, open)
                mgr.updateAppWidget(id, views)
            }
        }

        fun headline(b: Brief) =
            if (b.go) b.window.replace("WINDOW CLOSES", "DRY UNTIL")
            else "NO-GO · " + b.window.removePrefix("NEXT WINDOW ")
    }
}

object WidgetArt {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val GREEN = 0xFF39FF6A.toInt()
    private const val RED = 0xFFFF3B30.toInt()
    private const val DIM = 0xFF9A9A9A.toInt()
    private const val OFF = 0xFF1F1F1F.toInt()
    private const val EDGE = 0xFF2A2A2A.toInt()

    fun render(ctx: Context, wDp: Int, hDp: Int, b: Brief?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceAtLeast(1)
        val h = (hDp * d).toInt().coerceAtLeast(1)
        val wide = wDp >= 250 // 4×2 layout above this, 2×2 below
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = 24 * d
        val shape = Path().apply { addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW) }
        c.clipPath(shape)
        c.drawColor(BLACK)

        // Static contours — same terrain formula as the app background, frozen.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
        val (dw, dh) = if (wide) 384f to 176f else 176f to 176f
        val rnd = java.util.Random(if (wide) 4L else 9L)
        val peaks = if (wide) listOf(Triple(300f, 50f, 8) to 16.0, Triple(30f, 160f, 6) to 18.0)
        else listOf(Triple(120f, 60f, 7) to 14.0)
        peaks.forEach { (peak, step) ->
            val (cx, cy, n) = peak
            for (k in 1..n) {
                val ring = Ring(cx, cy, k, rnd.nextDouble() * 6, if (k % 4 == 0) 0.16f else 0.08f, step)
                val path = Path()
                ringPoints(ring, 0.0).forEachIndexed { i, p ->
                    val x = p.x / dw * w
                    val y = p.y / dh * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                line.color = WHITE
                line.alpha = (ring.alpha * 255).toInt()
                c.drawPath(path, line)
            }
        }

        val pad = 16 * d
        val flap = ctx.resources.getFont(R.font.oswald)
        val mono = ctx.resources.getFont(R.font.share_tech_mono)
        fun text(size: Float, color: Int, font: android.graphics.Typeface, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * d; this.color = color; typeface = font; textAlign = align; letterSpacing = 0.06f }

        if (b == null) {
            c.drawText("DEAD GROUND", pad, pad + 14 * d, text(14f, WHITE, mono))
            c.drawText("OPEN APP TO SET AO", pad, pad + 34 * d, text(11f, DIM, mono))
        } else {
            val big = text(if (wide) 56f else 44f, WHITE, flap).apply { letterSpacing = 0f }
            val tempStr = tempText(b.temp)
            val tempBase = pad + big.textSize * 0.82f
            c.drawText(tempStr, pad, tempBase, big)

            val head = DeadGroundWidget.headline(b)
            val headColor = if (b.go) GREEN else RED
            if (wide) {
                val right = w - pad
                val maxW = right - (pad + big.measureText(tempStr) + 16 * d)
                val cond = "${b.condition} · FEELS ${b.feels}°"
                c.drawText(head, right, pad + 12 * d, shrink(text(13f, headColor, mono, Paint.Align.RIGHT), head, maxW))
                c.drawText(cond, right, pad + 30 * d, shrink(text(12f, DIM, mono, Paint.Align.RIGHT), cond, maxW))
                if (b.threat != null) {
                    val t = "${b.threat} ${b.threatWhen.orEmpty()}"
                    c.drawText(t, right, pad + 47 * d, shrink(text(12f, RED, mono, Paint.Align.RIGHT), t, maxW))
                }
            } else {
                c.drawText(head, pad, tempBase + 22 * d, shrink(text(12f, headColor, mono), head, w - 2 * pad))
            }

            // T+24H strip along the bottom: rain solid, snow outlined, ice red.
            val cols = if (wide) b.strip.take(8) else b.strip.take(4)
            val block = 7 * d
            val gap = 2 * d
            val stripTop = h - pad - (3 * block + 2 * gap)
            if (wide) {
                c.drawText("T+24H", pad, stripTop - 6 * d, text(10f, DIM, mono))
                c.drawText(b.stamp.substringBefore(" ·"), w - pad, stripTop - 6 * d, text(10f, DIM, mono, Paint.Align.RIGHT))
            }
            val colGap = 3 * d
            val colW = (w - 2 * pad - colGap * (cols.size - 1)) / cols.size
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = WHITE }
            cols.forEachIndexed { i, col ->
                val x = pad + i * (colW + colGap)
                for (row in 0..2) { // row 0 = bottom block
                    val top = stripTop + (2 - row) * (block + gap)
                    val on = row < col.level
                    when {
                        !on -> c.drawRect(x, top, x + colW, top + block, fill.apply { color = OFF })
                        col.type == Precip.SNOW -> c.drawRect(x + d / 2, top + d / 2, x + colW - d / 2, top + block - d / 2, stroke)
                        col.type == Precip.ICE -> c.drawRect(x, top, x + colW, top + block, fill.apply { color = RED })
                        else -> c.drawRect(x, top, x + colW, top + block, fill.apply { color = WHITE })
                    }
                }
            }
        }

        c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = EDGE })
        return bmp
    }
}

/** Shrink the font until the text fits; long headlines like "NO-GO · NO WINDOW 24H" stay on one line. */
private fun shrink(p: Paint, s: String, maxW: Float): Paint {
    while (p.measureText(s) > maxW && p.textSize > 8f) p.textSize *= 0.92f
    return p
}
