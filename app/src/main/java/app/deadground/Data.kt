package app.deadground

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/** Two decimals ≈ 1 km grid. This is the only precision that ever leaves the phone. */
fun round2(x: Double) = (x * 100).roundToLong() / 100.0

/** Plain LocationManager, no Play Services library. Caller must hold ACCESS_COARSE_LOCATION. */
@SuppressLint("MissingPermission")
suspend fun locate(ctx: Context): Pair<Double, Double>? {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val enabled = lm.getProviders(true)
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { it in enabled } ?: return null
    val fresh: Location? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { cont.resume(it) }
        }
    }
    val fix = fresh ?: enabled.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    return fix?.let { round2(it.latitude) to round2(it.longitude) }
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("dg", Context.MODE_PRIVATE)

/** Fetch, validate, cache. Shared by the app and the widget so both always read the same data. */
fun fetchAndStore(ctx: Context, lat: Double, lon: Double): String {
    val json = OpenMeteo.fetch(lat, lon)
    OpenMeteo.parse(json) // a bad payload throws here and never replaces a good cache
    prefs(ctx).edit()
        .putString("json", json)
        .putLong("at", System.currentTimeMillis())
        .putString("lat", lat.toString())
        .putString("lon", lon.toString())
        .apply()
    return json
}

object OpenMeteo {
    // No key, no account, no headers identifying the app or the phone.
    fun fetch(lat: Double, lon: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,precipitation" +
            "&hourly=temperature_2m,precipitation,snowfall,weather_code,wind_gusts_10m" +
            "&minutely_15=precipitation&forecast_minutely_15=96" +
            "&past_days=1&forecast_days=7&timezone=auto&wind_speed_unit=ms"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): Forecast {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val time = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val precip = h.getJSONArray("precipitation")
        val snow = h.getJSONArray("snowfall")
        val code = h.getJSONArray("weather_code")
        val gust = h.getJSONArray("wind_gusts_10m")
        val hours = (0 until time.length()).map { i ->
            Hour(
                time.getString(i), temp.optDouble(i, 0.0), precip.optDouble(i, 0.0),
                snow.optDouble(i, 0.0), code.optInt(i, 0), gust.optDouble(i, 0.0),
            )
        }
        // 15-min data is missing (null) in some regions; drop those slots so the hourly fallback kicks in.
        val slots = o.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time")
            val p = m.getJSONArray("precipitation")
            (0 until t.length()).map { Slot(t.getString(it), p.optDouble(it, Double.NaN)) }
        }.orEmpty().filterNot { it.precip.isNaN() }
        return Forecast(
            Current(
                cur.getString("time"), cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"),
                cur.getInt("weather_code"), cur.optDouble("precipitation", 0.0),
            ),
            hours,
            slots,
        )
    }
}
