package com.bedroom.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class DayForecast(val date: String, val code: Int, val hi: Int, val lo: Int, val precipPct: Int?)

data class Weather(
    val place: String,
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val windMph: Int,
    val days: List<DayForecast>,
    val fetchedAt: Long,
)

enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Storm }

/** WMO weather codes, as used by Open-Meteo. */
fun skyFor(code: Int): Sky = when (code) {
    0, 1 -> Sky.Clear
    2 -> Sky.PartlyCloudy
    3 -> Sky.Cloudy
    45, 48 -> Sky.Fog
    51, 53, 55, 56, 57 -> Sky.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.Rain
    71, 73, 75, 77, 85, 86 -> Sky.Snow
    95, 96, 99 -> Sky.Storm
    else -> Sky.Cloudy
}

fun describe(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorms"
    96, 99 -> "Storms with hail"
    else -> "—"
}

fun dayLabel(date: String, index: Int): String {
    if (index == 0) return "Today"
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        if (d == null) "" else SimpleDateFormat("EEE", Locale.getDefault()).format(d)
    } catch (e: Exception) {
        ""
    }
}

/**
 * Runtime settings pushed to the TV with tools\set-weather.ps1, so changing the
 * city never needs a rebuild. Lives in the app's own folder (works without storage permission).
 */
object Config {
    /** Used only when no config.json has been pushed to the TV. */
    const val DEFAULT_WEATHER_PLACE = ""

    fun weatherPlace(ctx: Context): String {
        val dir = ctx.getExternalFilesDir(null) ?: return DEFAULT_WEATHER_PLACE
        val f = File(dir, "config.json")
        if (!f.exists()) {
            Log.i(TAG, "No config.json at ${f.absolutePath}; using default place")
            return DEFAULT_WEATHER_PLACE
        }
        return try {
            val text = f.readText().trimStart('﻿') // PowerShell may add a BOM
            JSONObject(text).optString("weatherPlace", DEFAULT_WEATHER_PLACE).trim().also {
                Log.i(TAG, "Loaded config.json from ${f.absolutePath}: weatherPlace='$it'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad config.json", e)
            DEFAULT_WEATHER_PLACE
        }
    }
}

/** Free Open-Meteo forecast (no API key). Results are cached so the panel shows instantly after a reboot. */
object WeatherRepo {
    private const val PREFS = "weather"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(ctx: Context): Weather? {
        val p = prefs(ctx)
        val json = p.getString("forecast", null) ?: return null
        return try {
            parse(p.getString("placeLabel", "") ?: "", JSONObject(json), p.getLong("fetchedAt", 0L))
        } catch (e: Exception) {
            null
        }
    }

    /** Hits the network. Call off the main thread. Returns null when no place is set. */
    fun refresh(ctx: Context, place: String): Weather? {
        if (place.isBlank()) return null
        val p = prefs(ctx)
        var lat = p.getFloat("lat", Float.NaN)
        var lon = p.getFloat("lon", Float.NaN)
        var label = p.getString("placeLabel", null)

        if (p.getString("placeQuery", null) != place || lat.isNaN() || lon.isNaN()) {
            val geo = JSONObject(
                get(
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" +
                        URLEncoder.encode(place, "UTF-8"),
                ),
            )
            val r = geo.optJSONArray("results")?.optJSONObject(0)
            if (r == null) {
                Log.w(TAG, "No location found for '$place'")
                return null
            }
            lat = r.getDouble("latitude").toFloat()
            lon = r.getDouble("longitude").toFloat()
            label = listOf(r.optString("name"), r.optString("admin1"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            p.edit()
                .putString("placeQuery", place)
                .putFloat("lat", lat)
                .putFloat("lon", lon)
                .putString("placeLabel", label)
                .apply()
            Log.i(TAG, "Geocoded '$place' -> $label ($lat, $lon)")
        }

        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
            "&timezone=auto&forecast_days=5"
        val body = get(url)
        val now = System.currentTimeMillis()
        val w = parse(label ?: place, JSONObject(body), now)
        p.edit().putString("forecast", body).putLong("fetchedAt", now).apply()
        Log.i(TAG, "Weather updated: ${w.temp}° ${describe(w.code)}, ${w.days.size} days")
        return w
    }

    private fun parse(place: String, j: JSONObject, fetchedAt: Long): Weather {
        val c = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val time = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val hi = d.getJSONArray("temperature_2m_max")
        val lo = d.getJSONArray("temperature_2m_min")
        val pp = d.optJSONArray("precipitation_probability_max")
        val days = (0 until time.length()).map { i ->
            DayForecast(
                date = time.getString(i),
                code = codes.optInt(i, 3),
                hi = Math.round(hi.optDouble(i, 0.0)).toInt(),
                lo = Math.round(lo.optDouble(i, 0.0)).toInt(),
                precipPct = if (pp == null || pp.isNull(i)) null else pp.optInt(i),
            )
        }
        return Weather(
            place = place,
            temp = Math.round(c.optDouble("temperature_2m", 0.0)).toInt(),
            code = c.optInt("weather_code", 3),
            isDay = c.optInt("is_day", 1) == 1,
            windMph = Math.round(c.optDouble("wind_speed_10m", 0.0)).toInt(),
            days = days,
            fetchedAt = fetchedAt,
        )
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "BedroomLauncher/${BuildConfig.VERSION_NAME}")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
