package com.bedroom.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

// ---------------------------------------------------------------- "Share to TV" from the phone

/** Opens a link shared from the phone: YouTube in the TV's YouTube app, everything else in whatever opens it. */
object ShareOpen {
    private val YT_TV = listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube")
    private val YT_MUSIC = listOf("com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music")

    fun openUrl(ctx: Context, raw: String?): String {
        val app = ctx.applicationContext
        val url = raw?.trim().orEmpty()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (url.length > 2000 || uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            return err("That isn't a web link")
        }
        wake(app)
        val host = uri.host!!.lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
        val target = when {
            // youtu.be/ID -> the full form the TV app understands
            host == "youtu.be" -> Uri.parse("https://www.youtube.com/watch?v=" + uri.lastPathSegment.orEmpty() +
                (uri.getQueryParameter("t")?.let { "&t=$it" } ?: ""))
            host == "youtube.com" && uri.path.orEmpty().startsWith("/shorts/") ->
                Uri.parse("https://www.youtube.com/watch?v=" + uri.lastPathSegment.orEmpty())
            else -> uri
        }
        val prefer = when {
            host == "music.youtube.com" -> YT_MUSIC
            host == "youtube.com" || host == "youtu.be" -> YT_TV
            else -> emptyList()
        }
        val pm = app.packageManager
        for (pkg in prefer) {
            if (runCatching { pm.getPackageInfo(pkg, 0) }.isFailure) continue
            if (start(app, Intent(Intent.ACTION_VIEW, target).setPackage(pkg))) return done("Opening on the TV")
        }
        if (start(app, Intent(Intent.ACTION_VIEW, target))) return done("Opening on the TV")
        return err("No app on the TV can open that link")
    }

    /** Shows one photo full screen (paused) in the photo frame; Play carries on with the slideshow. */
    fun showPhoto(ctx: Context, name: String?): String {
        val app = ctx.applicationContext
        if (PhotoStore.find(app, name) == null) return err("Photo not found on the TV")
        thread { PhotoFrame.show(app, start = name) }
        return done("Showing it on the TV")
    }

    private fun wake(app: Context) {
        try {
            if (Rest.isResting) Rest.exit(app)
            if (app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) {
                TvShell.wakeScreen(app)
                Thread.sleep(700)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "wake before share failed", e)
        }
    }

    private fun start(app: Context, i: Intent): Boolean = try {
        app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun done(msg: String) = JSONObject().put("ok", true).put("done", msg).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
}

// ---------------------------------------------------------------- play time

/**
 * Time played per RetroArch game. Counted here once a minute while RetroArch is on screen (credited to the
 * game RetroArch's history says is running), and merged with RetroArch's own runtime logs when it keeps them.
 */
object PlayTime {
    private const val FILE = "playtime.json"
    @Volatile private var started = false
    private val lock = Any()

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "playtime") {
            var lastPath: String? = null
            var tick = 0
            while (true) {
                try {
                    Thread.sleep(60_000)
                    val front = RunningApps.get(app).second.orEmpty()
                    val awake = app.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
                    if (front.startsWith("com.retroarch") && awake && !Rest.isResting) {
                        // Re-read RetroArch's history now and then (it changes when a new game starts).
                        if (lastPath == null || tick % 5 == 0) lastPath = RetroHistory.refresh(app)?.path
                        val g = RetroHistory.last
                        if (g != null) add(app, g.path, g.label, 60)
                        tick++
                    } else {
                        lastPath = null
                        tick = 0
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "play time tick failed", e)
                }
            }
        }
    }

    private fun load(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, FILE)
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    private fun add(ctx: Context, path: String, label: String, secs: Long) = synchronized(lock) {
        val all = load(ctx)
        val g = all.optJSONObject(path) ?: JSONObject()
        g.put("label", label).put("secs", g.optLong("secs") + secs).put("last", System.currentTimeMillis())
        all.put(path, g)
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(all.toString())
        tmp.renameTo(f)
    }

    private fun romBase(path: String) = path.substringBefore('#').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.US)

    /** RetroArch's own logs (Settings > Saving > "Save runtime log"): game base name -> (seconds, last played ms). */
    private fun retroLogs(ctx: Context): Map<String, Pair<Long, Long>> {
        val pkg = RETROARCH.packages.firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess } ?: return emptyMap()
        val base = "/sdcard/Android/data/$pkg/files/playlists/logs"
        val txt = try {
            TvShell.run(ctx, "find '$base' -maxdepth 2 -name '*.lrtl' 2>/dev/null | while IFS= read -r f; do echo \"@@\$f\"; cat \"\$f\"; done")
        } catch (e: Throwable) {
            return emptyMap()
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val out = HashMap<String, Pair<Long, Long>>()
        for (chunk in txt.split("@@").drop(1)) {
            val name = chunk.substringBefore('\n').trim().substringAfterLast('/').removeSuffix(".lrtl").substringBeforeLast('.').lowercase(Locale.US)
            val body = chunk.substringAfter('\n')
            val rt = Regex(""""runtime"\s*:\s*"(\d+):(\d+):(\d+)"""").find(body)?.groupValues ?: continue
            val secs = rt[1].toLong() * 3600 + rt[2].toLong() * 60 + rt[3].toLong()
            val last = Regex(""""last_played"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                ?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: 0L
            val prev = out[name]
            // Same game with two cores: add the times up.
            out[name] = if (prev == null) secs to last else (prev.first + secs) to maxOf(prev.second, last)
        }
        return out
    }

    fun secsFor(ctx: Context, path: String): Long = load(ctx).optJSONObject(path)?.optLong("secs") ?: 0L

    fun json(ctx: Context): String {
        val mine = load(ctx)
        val logs = retroLogs(ctx)
        data class Row(val label: String, val path: String, val secs: Long, val last: Long)
        val rows = mutableListOf<Row>()
        val usedLogs = HashSet<String>()
        for (path in mine.keys()) {
            val g = mine.getJSONObject(path)
            val log = logs[romBase(path)]
            if (log != null) usedLogs += romBase(path)
            rows += Row(
                g.optString("label"), path,
                maxOf(g.optLong("secs"), log?.first ?: 0L),
                maxOf(g.optLong("last"), log?.second ?: 0L),
            )
        }
        for ((name, v) in logs) if (name !in usedLogs && v.first > 0) rows += Row(name, "", v.first, v.second)
        rows.sortByDescending { it.last }
        val arr = JSONArray()
        rows.forEach { r -> arr.put(JSONObject().put("label", r.label).put("path", r.path).put("secs", r.secs).put("last", r.last)) }
        return JSONObject().put("ok", true).put("games", arr).put("total", rows.sumOf { it.secs }).toString()
    }
}
