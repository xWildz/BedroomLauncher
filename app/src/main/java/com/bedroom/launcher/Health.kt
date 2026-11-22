package com.bedroom.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

// ---------------------------------------------------------------- idle: stop animating when nobody's watching

/**
 * "Is anyone using the home screen?" Animations (weather, Photos thumbnail) only run while this is true.
 * False after 10 minutes without a button press, and whenever the TV is resting or the screen is off.
 * The TV restarting itself overnight was traced to the home screen redrawing all night on a 1.3 GB TV.
 */
object Idle {
    private const val AFTER_MS = 10 * 60_000L
    @Volatile var lastInputAt = SystemClock.elapsedRealtime()
        private set
    /** Compose reads this; only changes on the main thread. */
    val active = mutableStateOf(true)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var started = false

    /** A remote button, a touch, or anything from the phone / PC. Safe from any thread. */
    fun poke() {
        lastInputAt = SystemClock.elapsedRealtime()
        if (!active.value) main.post { update() }
    }

    fun isActiveNow(): Boolean =
        !ScreenOff.isOff && SystemClock.elapsedRealtime() - lastInputAt < AFTER_MS

    private fun update() {
        val now = isActiveNow()
        if (active.value != now) {
            active.value = now
            Log.i(TAG, if (now) "Home screen active: animations on" else "Idle: animations paused")
        }
    }

    fun start() {
        if (started) return
        started = true
        main.post(object : Runnable {
            override fun run() {
                update()
                main.postDelayed(this, 15_000)
            }
        })
    }
}

// ---------------------------------------------------------------- events + health report

object Health {
    private const val PREFS = "health"
    private const val MAX_EVENTS = 30
    private val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
    @Volatile private var bootReason: String? = null
    @Volatile private var restarts: List<Long> = emptyList()

    /** Something worth showing later ("Closed Hulu (not playing)", "Wi-Fi reconnected"...). */
    fun event(ctx: Context, text: String) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(p.getString("events", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("text", text))
        while (arr.length() > MAX_EVENTS) arr.remove(0)
        p.edit().putString("events", arr.toString()).apply()
        Log.i(TAG, "Event: $text")
    }

    fun bootAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Once per app start: why the TV last started, and the restarts Android remembers (read-only). */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "health-boot") {
            Thread.sleep(20_000) // let the TV finish starting first
            try {
                val out = TvShell.run(app, "getprop sys.boot.reason; dumpsys dropbox 2>/dev/null | grep ' SYSTEM_BOOT ' | tail -n 10")
                val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                bootReason = lines.firstOrNull()?.trimEnd(',')
                val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                restarts = lines.drop(1).mapNotNull { runCatching { f.parse(it.take(19))?.time }.getOrNull() }
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't read the boot reason", e)
            }
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val boot = bootAt()
            // Record each TV start once (the app also restarts on updates; those aren't TV restarts).
            if (Math.abs(p.getLong("lastBoot", 0) - boot) > 60_000) {
                p.edit().putLong("lastBoot", boot).apply()
                event(app, "TV started (${describe(bootReason)})")
            }
        }
    }

    fun describe(reason: String?): String = when {
        reason == null -> "reason unknown"
        reason.startsWith("reboot") -> "restarted by the TV itself"
        reason.contains("cold") || reason.contains("power") -> "power on"
        reason.contains("watchdog") -> "the TV's safety watchdog"
        reason.contains("panic") || reason.contains("kernel") -> "system crash"
        else -> reason
    }

    private fun memInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").readLines().associate { l ->
            l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }
    }.getOrDefault(emptyMap())

    fun json(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val m = memInfo()
        val load = runCatching { File("/proc/loadavg").readText().split(' ').take(3).map { it.toDouble() } }.getOrDefault(emptyList())
        val swapTotal = m["SwapTotal"] ?: 0
        val swapUsedPct = if (swapTotal > 0) ((swapTotal - (m["SwapFree"] ?: 0)) * 100 / swapTotal).toInt() else 0
        val evs = runCatching {
            JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]"))
        }.getOrDefault(JSONArray())
        val recent = JSONArray()
        for (i in evs.length() - 1 downTo maxOf(0, evs.length() - 12)) {
            val e = evs.getJSONObject(i)
            recent.put(JSONObject().put("at", e.optLong("at")).put("when", fmt.format(e.optLong("at"))).put("text", e.optString("text")))
        }
        val restartArr = JSONArray()
        restarts.sortedDescending().take(8).forEach { restartArr.put(JSONObject().put("at", it).put("when", fmt.format(it))) }
        return JSONObject()
            .put("ok", true)
            .put("uptimeMin", SystemClock.elapsedRealtime() / 60_000)
            .put("bootAt", bootAt())
            .put("bootWhen", fmt.format(bootAt()))
            .put("bootReason", describe(bootReason))
            .put("memFreeMb", (m["MemAvailable"] ?: 0) / 1024)
            .put("memTotalMb", (m["MemTotal"] ?: 0) / 1024)
            .put("swapUsedPct", swapUsedPct)
            .put("load", if (load.isNotEmpty()) load[0] else -1.0)
            .put("load15", if (load.size > 2) load[2] else -1.0)
            .put("homeMb", runCatching { android.os.Debug.getPss() / 1024 }.getOrDefault(-1))
            .put("idle", !Idle.isActiveNow())
            .put("restarts", restartArr)
            .put("events", recent)
            .put("nightClose", NightCleanup.enabled(app))
            .put("tempC", Thermal.lastC ?: JSONObject.NULL)
            .put("tempMaxC", Thermal.maxC ?: JSONObject.NULL)
    }

    fun jsonString(ctx: Context): String = json(ctx).toString()
}

// ---------------------------------------------------------------- night clean-up

/**
 * After the TV has been resting / screen off for an hour, close video apps that are open but NOT playing,
 * to give memory back. Never touches: YouTube, YouTube Music, anything playing, the app on screen,
 * RetroArch (an unsaved game), or Bedroom Home itself.
 */
object NightCleanup {
    private val CLOSE = listOf(
        "com.hulu", "com.netflix", "com.disney", "com.amazon.amazonvideo", "com.amazon.avod",
        "com.hbo", "com.wbd", "com.peacock", "com.cbs", "com.paramount", "tv.twitch", "com.kick",
        "com.plexapp", "com.tubitv", "tv.pluto", "com.crunchyroll", "com.vudu", "com.fubo", "com.sling",
    )
    private val NEVER = listOf("com.google.android.youtube", "com.retroarch", "com.bedroom")
    @Volatile private var offSince = 0L
    @Volatile private var started = false

    fun enabled(ctx: Context) = BedroomPrefs.get(ctx).getBoolean("nightClose", true)

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "night-cleanup") {
            while (true) {
                try {
                    Thread.sleep(5 * 60_000L)
                    if (!ScreenOff.isOff) { offSince = 0; continue }
                    if (offSince == 0L) offSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - offSince < 60 * 60_000L || !enabled(app)) continue
                    val (running, front) = RunningApps.get(app)
                    Thread.sleep(4000) // RunningApps refreshes in the background; read again for a fresh list
                    val (running2, front2) = RunningApps.get(app)
                    val playing = NowPlaying.playingPackages(app)
                    for (pkg in (running + running2)) {
                        if (CLOSE.none { pkg.startsWith(it) }) continue
                        if (NEVER.any { pkg.startsWith(it) }) continue
                        if (pkg == front || pkg == front2 || pkg in playing) continue
                        if (RunningApps.close(app, pkg)) {
                            Health.event(app, "Closed ${Apps.labelFor(app, pkg)} overnight (open but not playing)")
                        }
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "night clean-up failed", e)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Wi-Fi self-repair

/**
 * If Wi-Fi has been fully disconnected for 3 minutes, switch it off and on again (at most every 15 minutes).
 * A working connection is never touched, so a stream that's playing is never interrupted.
 */
object WifiRepair {
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "wifi-repair") {
            var bad = 0
            var lastFix = 0L
            Thread.sleep(90_000) // give Wi-Fi time to come up after a restart
            while (true) {
                try {
                    Thread.sleep(60_000)
                    if (connected(app)) { bad = 0; continue }
                    bad++
                    if (bad >= 3 && SystemClock.elapsedRealtime() - lastFix > 15 * 60_000L) {
                        lastFix = SystemClock.elapsedRealtime()
                        Log.w(TAG, "Wi-Fi down for $bad min: restarting Wi-Fi")
                        TvShell.run(app, "svc wifi disable; sleep 4; svc wifi enable")
                        Thread.sleep(45_000)
                        Health.event(app, if (connected(app)) "Wi-Fi dropped; reconnected it" else "Wi-Fi dropped; tried to reconnect")
                        bad = 0
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "Wi-Fi check failed", e)
                }
            }
        }
    }

    fun connected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (e: Throwable) {
        true // can't tell: leave it alone
    }
}

// ---------------------------------------------------------------- memory watchdog

/**
 * While nobody's watching (screen off / resting / idle), checks free memory every 2 minutes. When it's very
 * low, Bedroom Home gives back everything it can (decoded banners, cover art) so Android doesn't have to.
 * Never closes other apps (the night clean-up does that, and never YouTube).
 */
object MemoryWatch {
    private const val LOW_MB = 140L
    @Volatile private var started = false
    @Volatile private var lastNote = 0L

    fun freeMb(): Long = runCatching {
        File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemAvailable:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.div(1024)
    }.getOrNull() ?: -1L

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "memory-watch") {
            while (true) {
                try {
                    Thread.sleep(2 * 60_000L)
                    if (Idle.isActiveNow()) continue
                    val free = freeMb()
                    if (free in 0 until LOW_MB) {
                        Apps.trim()
                        NowPlaying.trim()
                        System.gc()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNote > 60 * 60_000L) {
                            lastNote = now
                            Health.event(app, "Memory was low ($free MB free); Bedroom Home freed what it could")
                        }
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "memory check failed", e)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- temperature

/**
 * The TV chip's temperature, from Android's thermal sensors (read-only). Checked every 2 minutes; the
 * hottest reading since the TV started is kept, and a very hot reading is noted in the health report.
 */
object Thermal {
    private const val HOT_C = 90
    val shown = androidx.compose.runtime.mutableStateOf<Int?>(null)
    @Volatile var lastC: Int? = null
        private set
    @Volatile var maxC: Int? = null
        private set
    @Volatile private var started = false
    @Volatile private var useShell = false
    @Volatile private var lastHotNote = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Highest of the thermal zones that look like the CPU/SoC, in °C. */
    fun read(ctx: Context): Int? {
        val raw: List<Pair<String, String>> = if (!useShell) {
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            val vals = zones.mapNotNull { z ->
                val t = runCatching { File(z, "temp").readText().trim() }.getOrNull() ?: return@mapNotNull null
                (runCatching { File(z, "type").readText().trim() }.getOrDefault("")) to t
            }
            if (vals.isEmpty()) useShell = true
            vals
        } else emptyList()
        val list = raw.ifEmpty {
            runCatching {
                TvShell.run(ctx, "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null)|\$(cat \$z/temp 2>/dev/null)\"; done")
                    .lines().mapNotNull { l -> l.split('|').takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }
            }.getOrDefault(emptyList())
        }
        val temps = list.mapNotNull { (type, v) ->
            val n = v.trim().toDoubleOrNull() ?: return@mapNotNull null
            val c = if (n > 1000) n / 1000 else n // millidegrees on most chips
            if (c < 5 || c > 150 || type.contains("battery", true)) null else c.toInt()
        }
        return temps.maxOrNull()
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "thermal") {
            Thread.sleep(30_000)
            var misses = 0
            while (true) {
                try {
                    val c = read(app)
                    if (c == null && ++misses >= 3) {
                        // This TV doesn't report a temperature (the Philips/Funai set doesn't): stop asking.
                        Log.i(TAG, "No temperature sensor on this TV")
                        return@thread
                    }
                    if (c != null) {
                        misses = 0
                        lastC = c
                        if (c > (maxC ?: 0)) maxC = c
                        main.post { shown.value = c }
                        val now = SystemClock.elapsedRealtime()
                        if (c >= HOT_C && now - lastHotNote > 60 * 60_000L) {
                            lastHotNote = now
                            Health.event(app, "The TV was running hot ($c °C)")
                        }
                    }
                    Thread.sleep(2 * 60_000L)
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "temperature read failed", e)
                    Thread.sleep(10 * 60_000L)
                }
            }
        }
    }
}
