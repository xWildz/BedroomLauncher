package com.bedroom.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * "Up next": the videos and music you were part-way through, so you can pick them back up.
 *
 * Android's shared "Watch next" list is only readable by system apps on this TV, so Bedroom Home keeps its
 * own: once a minute, while something is playing, it notes what (title, app, how far in, a small picture).
 * YouTube and YouTube Music items reopen at the same spot when the app says which video it was.
 */
object UpNext {
    data class Item(
        val pkg: String, val title: String, val subtitle: String, val mediaId: String,
        val position: Long, val duration: Long, val at: Long, val art: String,
    ) {
        val key get() = pkg + "|" + title
        val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val minutesLeft: Long? get() = if (duration > 0) ((duration - position) / 60_000L).coerceAtLeast(0) else null
    }

    private const val FILE = "upnext.json"
    private const val MAX = 16
    private const val MAX_AGE_MS = 21L * 24 * 3600_000L
    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val SKIP = listOf("com.bedroom", "com.retroarch", "com.android", "com.mstar", "com.google.android.tvlauncher")
    @Volatile private var started = false
    private val lock = Any()
    private var lastSaved = HashMap<String, Long>()
    /** Bumped when the list changes, so the home screen's Up next button refreshes its picture. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "up-next") {
            while (true) {
                try {
                    Thread.sleep(60_000)
                    tick(app)
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Throwable) {
                    Log.w(TAG, "up next tick failed", e)
                }
            }
        }
    }

    private fun tick(ctx: Context) {
        for (c in NowPlaying.controllers(ctx)) {
            if (!NowPlaying.isActive(c)) continue
            val pkg = c.packageName
            if (SKIP.any { pkg.startsWith(it) }) continue
            val m = c.metadata ?: continue
            fun txt(vararg keys: String) = keys.firstNotNullOfOrNull { k -> m.getText(k)?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            val title = txt(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: continue
            val sub = txt(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
            val id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
            val st = c.playbackState
            // The position the app last reported, moved forward by the time since then.
            val pos = (st?.position ?: 0L) + if (st != null && st.lastPositionUpdateTime > 0)
                ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
            val dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
            record(ctx, pkg, title, sub, id, pos.coerceAtLeast(0), dur) { NowPlaying.artBitmap(m) }
        }
    }

    private fun record(ctx: Context, pkg: String, title: String, sub: String, id: String, pos: Long, dur: Long, art: () -> Bitmap?) = synchronized(lock) {
        val list = load(ctx).toMutableList()
        val key = "$pkg|$title"
        val old = list.firstOrNull { it.key == key }
        val now = System.currentTimeMillis()
        // Save at most every 5 minutes per item (no need to write the TV's storage every minute all night),
        // but straight away for something new.
        if (old != null && now - (lastSaved[key] ?: 0L) < 5 * 60_000L && list.firstOrNull()?.key == key) return
        lastSaved[key] = now
        val artName = old?.art?.takeIf { File(dir(ctx), it).exists() } ?: saveArt(ctx, key, art())
        list.removeAll { it.key == key }
        list.add(0, Item(pkg, title, sub, id, pos, dur, now, artName.orEmpty()))
        save(ctx, list)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "upnext").apply { mkdirs() }

    private fun saveArt(ctx: Context, key: String, b: Bitmap?): String? {
        b ?: return null
        return try {
            val scale = minOf(1f, 400f / maxOf(b.width, b.height))
            val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
            val name = Integer.toHexString(key.hashCode()) + ".jpg"
            File(dir(ctx), name).outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (s !== b) s.recycle()
            name
        } catch (e: Throwable) {
            null
        }
    }

    fun artFile(ctx: Context, item: Item): File? = item.art.takeIf { it.isNotBlank() }?.let { File(dir(ctx), it) }?.takeIf { it.exists() }

    private fun load(ctx: Context): List<Item> {
        val arr = runCatching { JSONArray(File(ctx.filesDir, FILE).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("pkg"), o.optString("title"), o.optString("sub"), o.optString("id"),
                o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optString("art"))
        }
    }

    private fun save(ctx: Context, items: List<Item>) {
        val now = System.currentTimeMillis()
        val keep = items.filter { now - it.at < MAX_AGE_MS }.take(MAX)
        // Forget pictures of items that dropped off the list.
        val arts = keep.map { it.art }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.name !in arts) it.delete() }
        val arr = JSONArray()
        keep.forEach {
            arr.put(JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.sub()).put("id", it.mediaId)
                .put("pos", it.position).put("dur", it.duration).put("at", it.at).put("art", it.art))
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(ctx.filesDir, FILE))
        android.os.Handler(android.os.Looper.getMainLooper()).post { version.intValue++ }
    }

    private fun Item.sub() = subtitle

    /** Unfinished things, newest first (finished = within the last 3% or 2 minutes). */
    fun items(ctx: Context): List<Item> = synchronized(lock) {
        load(ctx).filter { it.duration <= 0 || it.position < it.duration * 0.97 && it.duration - it.position > 120_000 }
    }

    fun remove(ctx: Context, item: Item) = synchronized(lock) {
        save(ctx, load(ctx).filterNot { it.key == item.key })
    }

    /** Opens it again: YouTube / YouTube Music at the same spot when possible, otherwise just the app. */
    fun open(ctx: Context, item: Item): Boolean {
        val secs = item.position / 1000
        val uri = when {
            item.mediaId.matches(YT_ID) && item.pkg.startsWith("com.google.android.youtube.tvmusic") ->
                "https://music.youtube.com/watch?v=${item.mediaId}"
            item.mediaId.matches(YT_ID) && item.pkg.startsWith("com.google.android.youtube") ->
                "https://www.youtube.com/watch?v=${item.mediaId}" + if (secs > 10) "&t=${secs}s" else ""
            else -> null
        }
        if (uri != null) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(item.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Up next deep link failed, opening the app", e)
            }
        }
        return Apps.launch(ctx, item.pkg)
    }

    /** Can this one reopen at the same spot (vs. just opening the app)? */
    fun resumable(item: Item) = item.mediaId.matches(YT_ID) && item.pkg.startsWith("com.google.android.youtube")
}
