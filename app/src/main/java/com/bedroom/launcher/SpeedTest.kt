package com.bedroom.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures the TV's real internet speed (download, upload, delay) against Cloudflare's public speed test
 * (speed.cloudflare.com, the same one their website uses). Only test data is sent; nothing about the TV.
 * Results are kept so the phone can show a history, and say whether 4K streaming is realistic.
 */
object SpeedTest {
    private const val PREFS = "speedtest"
    private const val MAX = 20
    @Volatile private var running = false

    fun run(ctx: Context): String {
        if (running) return JSONObject().put("ok", false).put("error", "A speed test is already running").toString()
        running = true
        return try {
            val ping = (1..5).mapNotNull { timeMs { get("https://speed.cloudflare.com/__down?bytes=0", 0) } }.sorted()
                .let { if (it.isEmpty()) null else it[it.size / 2] }
            val down = (1..2).mapNotNull { mbps(25_000_000) { get("https://speed.cloudflare.com/__down?bytes=25000000", 25_000_000) } }.maxOrNull()
            val up = mbps(5_000_000) { post("https://speed.cloudflare.com/__up", 5_000_000) }
            if (down == null) return JSONObject().put("ok", false).put("error", "Couldn't reach the speed test. Is the TV online?").toString()
            val r = JSONObject().put("at", System.currentTimeMillis()).put("down", round1(down))
                .put("up", up?.let { round1(it) } ?: JSONObject.NULL).put("ping", ping ?: JSONObject.NULL)
            save(ctx, r)
            Health.event(ctx, "Speed test: ${round1(down)} Mbps down" + (up?.let { ", ${round1(it)} up" } ?: ""))
            json(ctx).put("last", r).toString()
        } catch (e: Throwable) {
            Log.w(TAG, "speed test failed", e)
            JSONObject().put("ok", false).put("error", "Speed test failed (${e.javaClass.simpleName})").toString()
        } finally {
            running = false
        }
    }

    /** History + what it means for streaming. */
    fun json(ctx: Context): JSONObject {
        val hist = history(ctx)
        val last = if (hist.length() > 0) hist.getJSONObject(hist.length() - 1) else null
        return JSONObject().put("ok", true).put("history", hist).put("running", running)
            .put("verdict", last?.let { verdict(it.optDouble("down")) } ?: JSONObject.NULL)
    }

    fun verdict(down: Double): String = when {
        down >= 25 -> "Fast enough for 4K"
        down >= 10 -> "Good for HD (1080p); 4K may pause"
        down >= 5 -> "OK for 720p"
        else -> "Slow: expect lower quality and buffering"
    }

    private fun history(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", "[]")) }.getOrDefault(JSONArray())

    private fun save(ctx: Context, r: JSONObject) {
        val h = history(ctx)
        h.put(r)
        while (h.length() > MAX) h.remove(0)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", h.toString()).apply()
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private inline fun timeMs(block: () -> Boolean): Long? {
        val t0 = SystemClock.elapsedRealtime()
        return if (block()) SystemClock.elapsedRealtime() - t0 else null
    }

    private inline fun mbps(bytes: Long, block: () -> Boolean): Double? {
        val t0 = SystemClock.elapsedRealtimeNanos()
        if (!block()) return null
        val secs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        return if (secs > 0) bytes * 8 / secs / 1e6 else null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 20000
        setRequestProperty("User-Agent", "BedroomHome/${BuildConfig.VERSION_NAME}")
    }

    /** Downloads and throws away [expect] bytes. */
    private fun get(url: String, expect: Long): Boolean {
        val c = open(url)
        return try {
            if (c.responseCode != 200) return false
            val buf = ByteArray(64 * 1024)
            var total = 0L
            c.inputStream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; total += n } }
            total >= expect
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    /** Uploads [bytes] of zeros. */
    private fun post(url: String, bytes: Int): Boolean {
        val c = open(url)
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val buf = ByteArray(64 * 1024)
            c.outputStream.use { o -> var left = bytes; while (left > 0) { val n = minOf(left, buf.size); o.write(buf, 0, n); left -= n } }
            c.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }
}
