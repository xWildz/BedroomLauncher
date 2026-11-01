package com.bedroom.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import dadb.Dadb
import org.json.JSONObject
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Bedroom features: wake-up alarm, bedtime button, night mode, and what to do when the screensaver
 * would start (nothing / rest / photo frame). Settings live in one prefs file and are edited from the phone.
 */
object BedroomPrefs {
    private const val PREFS = "bedroom"
    fun get(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val YT_MUSIC = "com.google.android.youtube.tvmusic"

    /** Everything the phone's Bedroom tab shows. */
    fun json(ctx: Context): JSONObject {
        val p = get(ctx)
        return JSONObject()
            .put("ok", true)
            .put("alarmOn", p.getBoolean("alarmOn", false))
            .put("alarmHour", p.getInt("alarmHour", 7))
            .put("alarmMin", p.getInt("alarmMin", 0))
            .put("alarmDays", p.getInt("alarmDays", 0b0011111)) // bit 0 = Monday ... bit 6 = Sunday
            .put("alarmVolFrom", p.getInt("alarmVolFrom", 2))
            .put("alarmVolTo", p.getInt("alarmVolTo", 12))
            .put("alarmRamp", p.getInt("alarmRamp", 5))
            .put("alarmNext", Alarm.nextAt(ctx) ?: JSONObject.NULL)
            .put("bedMinutes", p.getInt("bedMinutes", 45))
            .put("bedVolume", p.getInt("bedVolume", 6))
            .put("bedNight", p.getBoolean("bedNight", true))
            .put("bedUrl", p.getString("bedUrl", ""))
            .put("idle", p.getString("idle", "off"))
            .put("frameSecs", p.getInt("frameSecs", 15))
            .put("frameRest", p.getInt("frameRest", 60))
            .put("frameShuffle", p.getBoolean("frameShuffle", true))
            .put("frameClock", p.getBoolean("frameClock", true))
            .put("frameWeather", p.getBoolean("frameWeather", true))
            .put("frameZoom", p.getBoolean("frameZoom", true))
            .put("frameFill", p.getBoolean("frameFill", false))
            .put("frameAlbum", p.getString("frameAlbum", ""))
            .put("albums", org.json.JSONArray(PhotoStore.albums(ctx)))
            .put("nightLevel", p.getInt("nightLevel", 20))
            .put("nightWarm", p.getBoolean("nightWarm", true))
            .put("night", NightMode.isOn)
            .put("maxVolume", ctx.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 100)
            .put("photos", PhotoStore.count(ctx))
    }

    private val INTS = setOf("alarmHour", "alarmMin", "alarmDays", "alarmVolFrom", "alarmVolTo", "alarmRamp", "bedMinutes", "bedVolume", "frameSecs", "frameRest", "nightLevel")
    private val BOOLS = setOf("alarmOn", "bedNight", "nightWarm", "frameShuffle", "frameClock", "frameWeather", "frameZoom", "frameFill")

    /** Saves any settings present in the request, then re-arms the alarm. */
    fun set(ctx: Context, uri: Uri): String {
        val e = get(ctx).edit()
        for (k in uri.queryParameterNames) {
            val v = uri.getQueryParameter(k) ?: continue
            when {
                k in INTS -> v.toIntOrNull()?.let { e.putInt(k, it.coerceIn(0, 1440)) }
                k in BOOLS -> e.putBoolean(k, v == "1" || v == "true")
                k == "idle" && v in setOf("off", "rest", "frame") -> e.putString(k, v)
                k == "frameAlbum" -> if (v.isBlank() || v in PhotoStore.albums(ctx)) e.putString(k, v)
                k == "bedUrl" -> {
                    val u = v.trim().take(300)
                    if (u.isEmpty() || u.startsWith("https://")) e.putString(k, u)
                }
            }
        }
        e.commit()
        Alarm.schedule(ctx)
        return json(ctx).toString()
    }
}

/** Shell commands through the TV's own adb (same approval as the full remote). */
object TvShell {
    fun run(ctx: Context, cmd: String): String =
        Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { it.shell(cmd).let { r -> r.output + r.errorOutput } }

    /** Turns the screen on / ends the screensaver. Does nothing when the TV is already awake. */
    fun wakeScreen(ctx: Context) {
        try {
            run(ctx, "input keyevent KEYCODE_WAKEUP")
        } catch (e: Throwable) {
            Log.w(TAG, "wakeScreen failed", e)
        }
    }
}

// ---------------------------------------------------------------- wake-up alarm

object Alarm {
    private const val ACTION = "com.bedroom.launcher.ALARM_FIRE"

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Next ring time (epoch ms), or null when the alarm is off. */
    fun nextAt(ctx: Context): Long? {
        val p = BedroomPrefs.get(ctx)
        if (!p.getBoolean("alarmOn", false)) return null
        val days = p.getInt("alarmDays", 0b0011111).let { if (it == 0) 0b1111111 else it }
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("alarmHour", 7))
            set(Calendar.MINUTE, p.getInt("alarmMin", 0))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        repeat(8) {
            // Calendar: Sunday = 1 ... Saturday = 7  ->  bit: Monday = 0 ... Sunday = 6
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (c.timeInMillis > now + 5_000 && days and (1 shl bit) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(ctx))
        val at = nextAt(ctx) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx)) // within a few minutes
        }
        Log.i(TAG, "Alarm set for ${java.util.Date(at)}")
    }

    /** Wake the TV, start YouTube Music quietly and fade the volume up; the home screen (with weather) comes back on top. */
    fun fire(ctx: Context) {
        val app = ctx.applicationContext
        val p = BedroomPrefs.get(app)
        thread(name = "alarm") {
            try {
                Log.i(TAG, "Alarm ringing")
                if (Rest.isResting) Rest.exit(app)
                NightMode.off(app)
                TvShell.wakeScreen(app)
                Thread.sleep(2500)
                val audio = app.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val from = p.getInt("alarmVolFrom", 2).coerceIn(0, max)
                val to = p.getInt("alarmVolTo", 12).coerceIn(from, max)
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, from, 0)
                Apps.launch(app, BedroomPrefs.YT_MUSIC)
                Thread.sleep(9000)
                if (!NowPlaying.anyPlaying(app)) Media.key(app, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                Thread.sleep(4000)
                // Bring Home (clock + weather) back if the music keeps playing behind it.
                goHome(app)
                Thread.sleep(3000)
                if (!NowPlaying.anyPlaying(app)) {
                    Apps.launch(app, BedroomPrefs.YT_MUSIC) // this music app stops in the background: keep it on screen
                    Thread.sleep(5000)
                    if (!NowPlaying.anyPlaying(app)) Media.key(app, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                }
                val steps = (to - from).coerceAtLeast(1)
                val stepMs = p.getInt("alarmRamp", 5).coerceIn(0, 60) * 60_000L / steps
                for (v in from + 1..to) {
                    Thread.sleep(stepMs.coerceAtLeast(500))
                    val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (cur < v - 1 || cur > v) break // someone changed the volume: stop fading
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Alarm failed", e)
            } finally {
                schedule(app)
            }
        }
    }

    fun goHome(ctx: Context) {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.bedroom.launcher.ALARM_FIRE") Alarm.fire(context)
    }
}

// ---------------------------------------------------------------- bedtime

object Bedtime {
    /** Night mode, bedtime volume, sleep music, and the sleep timer (which rests the TV at the end). */
    fun run(ctx: Context): String {
        val app = ctx.applicationContext
        val p = BedroomPrefs.get(app)
        if (p.getBoolean("bedNight", true)) NightMode.on(app)
        val vol = p.getInt("bedVolume", 6)
        if (vol > 0) app.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
        val url = p.getString("bedUrl", "").orEmpty()
        var opened = false
        if (url.startsWith("https://")) {
            opened = runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(BedroomPrefs.YT_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Apps.launch(app, BedroomPrefs.YT_MUSIC)
            thread {
                Thread.sleep(9000)
                if (!NowPlaying.anyPlaying(app)) Media.key(app, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
        val minutes = p.getInt("bedMinutes", 45)
        Handler(Looper.getMainLooper()).post { SleepTimer.set(app, minutes) }
        return JSONObject().put("ok", true).put("done", "Goodnight: resting in $minutes min").toString()
    }
}

// ---------------------------------------------------------------- night mode

/**
 * Dimmer picture: the TV's own backlight level (same as its picture menu), plus an optional faint warm tint.
 * The previous level is remembered and put back when night mode ends.
 */
object NightMode {
    @Volatile var isOn = false
        private set
    private var tint: View? = null
    private val main = Handler(Looper.getMainLooper())

    fun on(ctx: Context): String {
        val app = ctx.applicationContext
        val p = BedroomPrefs.get(app)
        val r = ScreenOff.request(app, "level")
        val before = r.removePrefix("ok ").trim().toIntOrNull()
        if (before != null && !isOn) p.edit().putInt("nightPrev", before).apply()
        val target = p.getInt("nightLevel", 20).coerceIn(0, 100)
        val set = ScreenOff.request(app, "set:$target")
        isOn = true
        p.edit().putBoolean("nightActive", true).apply()
        // If the backlight can't be set, a dark tint does the dimming instead.
        val dimByTint = !set.startsWith("ok")
        if (p.getBoolean("nightWarm", true) || dimByTint) showTint(app, dimByTint)
        Log.i(TAG, "Night mode on: $set")
        return JSONObject().put("ok", true).put("night", true).put("detail", set).toString()
    }

    fun off(ctx: Context): String {
        val app = ctx.applicationContext
        val p = BedroomPrefs.get(app)
        if (!isOn && !p.getBoolean("nightActive", false)) return JSONObject().put("ok", true).put("night", false).toString()
        val prev = p.getInt("nightPrev", -1)
        val r = if (prev >= 0) ScreenOff.request(app, "set:$prev") else "no previous level"
        isOn = false
        p.edit().putBoolean("nightActive", false).apply()
        hideTint(app)
        return JSONObject().put("ok", true).put("night", false).put("detail", r).toString()
    }

    internal fun ensureOverlay(ctx: Context): Boolean {
        if (Settings.canDrawOverlays(ctx)) return true
        return try {
            TvShell.run(ctx, "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
            Thread.sleep(300)
            Settings.canDrawOverlays(ctx)
        } catch (e: Throwable) {
            false
        }
    }

    private fun showTint(ctx: Context, dark: Boolean) {
        thread {
            if (!ensureOverlay(ctx)) return@thread
            main.post {
                hideTint(ctx)
                try {
                    val v = View(ctx).apply {
                        // Amber at ~10%: warmer without looking orange. Dark mode (no backlight control): ~45% black.
                        setBackgroundColor(if (dark) Color.argb(115, 10, 5, 0) else Color.argb(28, 255, 140, 0))
                    }
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    )
                    ctx.getSystemService(WindowManager::class.java).addView(v, lp)
                    tint = v
                } catch (e: Exception) {
                    Log.w(TAG, "Night tint failed", e)
                }
            }
        }
    }

    private fun hideTint(ctx: Context) {
        main.post {
            tint?.let { runCatching { ctx.getSystemService(WindowManager::class.java).removeView(it) } }
            tint = null
        }
    }
}

// ---------------------------------------------------------------- screensaver replacement

/** When the TV's screensaver starts (nothing touched for a while), rest or show the photo frame instead. */
object IdleWatcher {
    private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != Intent.ACTION_DREAMING_STARTED) return
                val mode = BedroomPrefs.get(app).getString("idle", "off")
                if (mode == "off") return
                thread {
                    if (NowPlaying.anyPlaying(app)) return@thread // music with the screensaver: leave it alone
                    Log.i(TAG, "Screensaver started -> $mode")
                    if (mode == "rest") Rest.enter(app) else PhotoFrame.show(app, fromScreensaver = true)
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, r, IntentFilter(Intent.ACTION_DREAMING_STARTED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

// ---------------------------------------------------------------- brightness

/** The TV's own backlight level (0-100), the same setting as its picture menu. */
/**
 * The brightness slider (0-100) used by the phone and the PC.
 *  - 0..SPLIT: backlight at its lowest, plus a black see-through layer over everything that gets darker towards 0
 *    (dimmer than the TV can go on its own; up to ~85% darker).
 *  - SPLIT..100: the backlight on a curve, so more of the slider is spent on the dim end where changes are visible.
 * The slider position is remembered, and the dark layer comes back after a reboot.
 */
object Brightness {
    private const val SPLIT = 25
    private const val MAX_SHADE = 0.85f
    @Volatile var cached: Int = -1
        private set
    private var shadeView: View? = null
    private val main = Handler(Looper.getMainLooper())

    private fun backlightFor(s: Int): Int =
        if (s <= SPLIT) 0 else Math.round(100f * Math.pow((s - SPLIT) / (100.0 - SPLIT), 2.0).toFloat()).coerceIn(0, 100)

    private fun sliderFor(backlight: Int): Int =
        if (backlight <= 0) SPLIT else (SPLIT + (100 - SPLIT) * Math.sqrt(backlight / 100.0)).toInt().coerceIn(SPLIT, 100)

    private fun shadeFor(s: Int): Float = if (s >= SPLIT) 0f else MAX_SHADE * (SPLIT - s) / SPLIT

    fun get(ctx: Context): Int {
        val app = ctx.applicationContext
        val p = BedroomPrefs.get(app)
        val bl = ScreenOff.request(app, "level").removePrefix("ok ").trim().toIntOrNull() ?: return -1
        val saved = p.getInt("brightSlider", -1)
        // Trust the remembered slider if the backlight still matches it (nobody changed it in the TV's own menu).
        val v = if (saved >= 0 && backlightFor(saved) == bl) saved else sliderFor(bl)
        if (v != saved && saved >= 0 && saved < SPLIT) applyShade(app, 0f)
        cached = v
        return v
    }

    fun set(ctx: Context, level: Int): String {
        val app = ctx.applicationContext
        val v = level.coerceIn(0, 100)
        val r = ScreenOff.request(app, "set:${backlightFor(v)}")
        if (!r.startsWith("ok")) return r
        BedroomPrefs.get(app).edit().putInt("brightSlider", v).apply()
        applyShade(app, shadeFor(v))
        cached = v
        return r
    }

    /** At start-up: put the dark layer back if the slider was left in the extra-dim range. */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        val s = BedroomPrefs.get(app).getInt("brightSlider", -1)
        if (s in 0 until SPLIT) applyShade(app, shadeFor(s))
    }

    // The wanted darkness. Every change is applied on the main thread from this one value, so quick slider
    // moves can't leave an old layer stuck on screen.
    @Volatile private var wantShade = 0f

    private fun applyShade(app: Context, alpha: Float) {
        wantShade = alpha
        thread {
            if (alpha > 0f && !NightMode.ensureOverlay(app)) return@thread
            main.post { syncShade(app) }
        }
    }

    private fun syncShade(app: Context) {
        val a = wantShade
        val wm = app.getSystemService(WindowManager::class.java)
        if (a <= 0f) {
            shadeView?.let { runCatching { wm.removeView(it) } }
            shadeView = null
            return
        }
        val color = Color.argb((a * 255).toInt(), 0, 0, 0)
        shadeView?.let { it.setBackgroundColor(color); return }
        try {
            val v = View(app).apply { setBackgroundColor(color) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            wm.addView(v, lp)
            shadeView = v
        } catch (e: Exception) {
            Log.w(TAG, "Dim layer failed", e)
        }
    }

    /** level = null: just read it. */
    fun json(ctx: Context, level: Int?): String {
        if (level != null) {
            val r = set(ctx, level)
            if (!r.startsWith("ok")) return JSONObject().put("ok", false).put("error", "The TV didn't accept that: $r").toString()
        }
        return JSONObject().put("ok", true).put("level", get(ctx)).toString()
    }
}

// ---------------------------------------------------------------- storage

object Storage {
    /** Free space + the biggest apps (from the TV's own disk stats). */
    fun json(ctx: Context): String {
        val st = android.os.StatFs("/data")
        val o = JSONObject().put("ok", true).put("free", st.availableBytes).put("total", st.totalBytes)
        try {
            val txt = TvShell.run(ctx, "dumpsys diskstats")
            fun arr(label: String): List<String> =
                Regex("$label: \\[(.*?)]").find(txt)?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('"') } ?: emptyList()
            val names = arr("Package Names")
            val app = arr("App Sizes").map { it.toLongOrNull() ?: 0 }
            val data = arr("App Data Sizes").map { it.toLongOrNull() ?: 0 }
            val cache = arr("Cache Sizes").map { it.toLongOrNull() ?: 0 }
            val pm = ctx.packageManager
            val list = names.indices.map { i ->
                val pkg = names[i]
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                JSONObject().put("pkg", pkg).put("label", label)
                    .put("app", app.getOrElse(i) { 0 }).put("data", data.getOrElse(i) { 0 }).put("cache", cache.getOrElse(i) { 0 })
            }.sortedByDescending { it.getLong("app") + it.getLong("data") + it.getLong("cache") }.take(15)
            o.put("apps", org.json.JSONArray(list)).put("cacheTotal", cache.sum())
        } catch (e: Throwable) {
            o.put("apps", org.json.JSONArray()).put("note", "App sizes need \"Allow full remote\".")
        }
        return o.toString()
    }

    /** Asks Android to empty every app's cache (apps rebuild them as needed; nothing personal is removed). */
    fun clean(ctx: Context): String {
        val before = android.os.StatFs("/data").availableBytes
        val out = runCatching { TvShell.run(ctx, "pm trim-caches 999G") }.getOrElse { "error: ${it.message}" }
        Thread.sleep(1500)
        val after = android.os.StatFs("/data").availableBytes
        return JSONObject().put("ok", !out.contains("rror")).put("freed", (after - before).coerceAtLeast(0)).put("free", after)
            .put("detail", out.trim().take(200)).toString()
    }
}
