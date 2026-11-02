package app.deadground

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/** Two decimals ≈ 1 km grid. This is the only precision that ever leaves the phone. */
fun round2(x: Double) = (x * 100).roundToLong() / 100.0

fun gpsOnly(ctx: Context) = prefs(ctx).getBoolean("gps_only", false)

/**
 * Plain LocationManager, no Play Services library.
 * Default: network provider (fast; on phones with Google services it is answered by Google).
 * GPS-only mode: satellite fix only, nothing asked of any network service. Needs precise permission.
 */
@SuppressLint("MissingPermission")
suspend fun locate(ctx: Context): Pair<Double, Double>? {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val gps = gpsOnly(ctx)
    val enabled = lm.getProviders(true).let { all -> if (gps) all.filter { it == LocationManager.GPS_PROVIDER } else all }
    val provider = (if (gps) listOf(LocationManager.GPS_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER))
        .firstOrNull { it in enabled } ?: return null
    val fresh: Location? = withTimeoutOrNull(if (gps) 45_000 else 20_000) { // cold GPS fixes indoors are slow
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

// ---- Saved areas of operation ----

data class Place(val name: String, val lat: Double, val lon: Double)

fun loadAos(ctx: Context): List<Place> {
    val a = JSONArray(prefs(ctx).getString("aos", "[]"))
    return (0 until a.length()).map { a.getJSONObject(it) }
        .map { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) }
}

fun saveAos(ctx: Context, aos: List<Place>) {
    val a = JSONArray(aos.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })
    prefs(ctx).edit().putString("aos", a.toString()).apply()
}

private fun get(url: String): String {
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

object OpenMeteo {
    private const val FIELDS = "&current=temperature_2m,apparent_temperature,weather_code,precipitation,is_day" +
        "&hourly=temperature_2m,precipitation,snowfall,weather_code,wind_gusts_10m,apparent_temperature,is_day" +
        "&timezone=auto&wind_speed_unit=ms&past_days=1"

    // No key, no account, no headers identifying the app or the phone.
    fun fetch(lat: Double, lon: Double): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon$FIELDS" +
            "&minutely_15=precipitation&forecast_minutely_15=96&forecast_days=7",
    )

    /** All saved AOs in ONE request (Open-Meteo returns an array for several coordinates). */
    fun fetchMany(points: List<Place>): List<Forecast> {
        if (points.isEmpty()) return emptyList()
        val text = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${points.joinToString(",") { "${it.lat}" }}" +
                "&longitude=${points.joinToString(",") { "${it.lon}" }}$FIELDS&forecast_days=7", // 7: the roster opens a week strip
        )
        return if (text.trimStart().startsWith("[")) {
            val a = JSONArray(text)
            (0 until a.length()).map { parse(a.getJSONObject(it)) }
        } else listOf(parse(JSONObject(text)))
    }

    /**
     * Model scope: 15-min precip + snow for the AO and 24 points around it, one request.
     * The ring is derived from the AO, so it reveals nothing the normal forecast call doesn't.
     */
    fun fetchScope(lat: Double, lon: Double): Scope {
        val pts = scopePoints(lat, lon)
        val text = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${pts.joinToString(",") { "${it.third.first}" }}" +
                "&longitude=${pts.joinToString(",") { "${it.third.second}" }}" +
                "&minutely_15=precipitation,snowfall&forecast_minutely_15=24&timezone=auto",
        )
        val arr = JSONArray(text)
        val now = java.time.LocalDateTime.now().let { it.withMinute(it.minute / 15 * 15) }.toString().take(16)
        val times = arr.getJSONObject(0).getJSONObject("minutely_15").getJSONArray("time")
        val keep = (0 until times.length()).filter { times.getString(it) >= now }.take(16)
        check(keep.size >= 2) { "no scope frames" }
        val cells = pts.indices.map { i ->
            val m = arr.getJSONObject(i).getJSONObject("minutely_15")
            val p = m.getJSONArray("precipitation")
            val sn = m.getJSONArray("snowfall")
            Cell(pts[i].first, pts[i].second, keep.map { p.optDouble(it, 0.0) }, keep.map { sn.optDouble(it, 0.0) })
        }
        return Scope(keep.map { times.getString(it) }, cells)
    }

    /** European AQI at the alert position, same provider. */
    fun fetchAqi(lat: Double, lon: Double): Int? =
        JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=european_aqi"))
            .getJSONObject("current").optInt("european_aqi", -1).takeIf { it >= 0 }

    /**
     * Whole-country MeteoAlarm feed: the request carries the country, never the position.
     * ponytail: country-wide, the area name is shown in the alert; per-region matching once we've seen real feeds.
     */
    fun fetchWarnings(country: String = "latvia"): List<Warning> {
        val arr = JSONObject(get("https://feeds.meteoalarm.org/api/v1/warnings/feeds-$country")).optJSONArray("warnings")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { warning(arr.getJSONObject(i)) }.getOrNull() }
    }

    private fun warning(w: JSONObject): Warning {
        val alert = w.getJSONObject("alert")
        val infos = alert.getJSONArray("info").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val info = infos.firstOrNull { it.optString("language").startsWith("en") } ?: infos.first()
        val params = info.optJSONArray("parameter")?.let { a ->
            (0 until a.length()).associate { a.getJSONObject(it).optString("valueName") to a.getJSONObject(it).optString("value") }
        }.orEmpty()
        // awareness_level looks like "3; orange; Severe"; fall back to CAP severity.
        val level = params["awareness_level"]?.substringBefore(";")?.trim()?.toIntOrNull()
            ?: when (info.optString("severity")) {
                "Extreme" -> 4
                "Severe" -> 3
                "Moderate" -> 2
                else -> 1
            }
        val type = params["awareness_type"]?.substringAfter(";")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: info.optString("event").uppercase()
        val areas = info.optJSONArray("area")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("areaDesc") } }.orEmpty()
        val onset = info.optString("onset").ifEmpty { info.getString("effective") }
        val expires = info.getString("expires")
        fun ms(t: String) = java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        return Warning(
            w.optString("uuid").ifEmpty { alert.optString("identifier") }, level, type,
            areas.joinToString(", ").uppercase().take(40), onset.substring(11, 16), expires.substring(11, 16), ms(onset), ms(expires),
        )
    }

    /** Place search sends only the typed text, never your position. */
    fun search(query: String): List<Place> {
        val o = JSONObject(
            get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=6&language=en&format=json"),
        )
        val r = o.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.map {
            val region = listOf(it.optString("admin1"), it.optString("country_code")).filter { s -> s.isNotEmpty() }
            Place(
                (listOf(it.getString("name")) + region).joinToString(", ").uppercase(),
                round2(it.getDouble("latitude")), round2(it.getDouble("longitude")),
            )
        }
    }

    fun parse(json: String): Forecast = parse(JSONObject(json))

    fun parse(o: JSONObject): Forecast {
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val time = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val precip = h.getJSONArray("precipitation")
        val snow = h.getJSONArray("snowfall")
        val code = h.getJSONArray("weather_code")
        val gust = h.getJSONArray("wind_gusts_10m")
        val feels = h.optJSONArray("apparent_temperature") // optional: older caches don't have these
        val day = h.optJSONArray("is_day")
        val hours = (0 until time.length()).map { i ->
            val t = temp.optDouble(i, 0.0)
            Hour(
                time.getString(i), t, precip.optDouble(i, 0.0),
                snow.optDouble(i, 0.0), code.optInt(i, 0), gust.optDouble(i, 0.0),
                feels?.optDouble(i, t) ?: t, (day?.optInt(i, 1) ?: 1) == 1,
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
                cur.getInt("weather_code"), cur.optDouble("precipitation", 0.0), cur.optInt("is_day", 1) == 1,
            ),
            hours,
            slots,
        )
    }
}

// ---- Small persisted state for alerts ----

fun saveWarnings(ctx: Context, ws: List<Warning>) {
    val a = JSONArray(ws.map {
        JSONObject().put("id", it.id).put("level", it.level).put("type", it.type).put("area", it.area)
            .put("onset", it.onset).put("expires", it.expires).put("onsetMs", it.onsetMs).put("expiresMs", it.expiresMs)
    })
    prefs(ctx).edit().putString("warnings", a.toString()).apply()
}

fun loadWarnings(ctx: Context): List<Warning> = runCatching {
    val a = JSONArray(prefs(ctx).getString("warnings", "[]"))
    (0 until a.length()).map { a.getJSONObject(it) }.map {
        Warning(
            it.getString("id"), it.getInt("level"), it.getString("type"), it.getString("area"),
            it.getString("onset"), it.getString("expires"), it.getLong("onsetMs"), it.getLong("expiresMs"),
        )
    }
}.getOrDefault(emptyList())

fun loadSent(ctx: Context): Map<String, Long> = runCatching {
    val o = JSONObject(prefs(ctx).getString("sent", "{}"))
    o.keys().asSequence().associateWith { o.getLong(it) }
}.getOrDefault(emptyMap())

fun saveSent(ctx: Context, sent: Map<String, Long>) =
    prefs(ctx).edit().putString("sent", JSONObject(sent as Map<*, *>).toString()).apply()

fun coords(ctx: Context, latKey: String, lonKey: String): Pair<Double, Double>? {
    val p = prefs(ctx)
    val lat = p.getString(latKey, null)?.toDoubleOrNull() ?: return null
    val lon = p.getString(lonKey, null)?.toDoubleOrNull() ?: return null
    return lat to lon
}
