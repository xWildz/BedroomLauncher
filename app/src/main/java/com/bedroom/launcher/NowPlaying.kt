package com.bedroom.launcher

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Empty notification listener. Android only lets an app see other apps' media sessions (what's playing,
 * album art, play/pause/next) when it's an enabled notification listener. It reads no notifications.
 */
class MediaListener : NotificationListenerService()

/** "Now playing" for the phone app: title/artist/art/position of Hulu, YouTube Music, etc., plus controls. */
object NowPlaying {
    private val VIDEO_APPS = setOf(
        "com.hulu.livingroomplus", "com.hulu.plus", "com.netflix.ninja",
        "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
    )

    @Volatile private var askedForAccess = false
    private val artCache = LruCache<String, ByteArray>(6)

    private fun component(ctx: Context) = ComponentName(ctx, MediaListener::class.java)

    fun hasAccess(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))
    } catch (e: Throwable) {
        false
    }

    /** Phone's "Turn on" button: switch on our media access through the TV's own adb (same approval as the full remote). */
    fun requestAccess(ctx: Context) {
        if (askedForAccess) return
        askedForAccess = true
        thread(isDaemon = true) {
            try {
                val d = Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx))
                d.use { it.shell("cmd notification allow_listener ${component(ctx).flattenToString()}") }
                Log.i(TAG, "Media access requested")
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't turn on media access", e)
            } finally {
                askedForAccess = false
            }
        }
    }

    private fun controllers(ctx: Context): List<MediaController> {
        if (!hasAccess(ctx)) return emptyList()
        return try {
            ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(ctx))
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun isActive(c: MediaController) = c.playbackState?.state in setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
    )

    /** The session to show: playing first, then paused with a title. */
    private fun pick(list: List<MediaController>, pkg: String? = null): MediaController? =
        if (pkg != null) list.firstOrNull { it.packageName == pkg }
        else list.firstOrNull { isActive(it) } ?: list.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

    private fun artBitmap(m: MediaMetadata?): Bitmap? = m?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun artKey(c: MediaController): String? {
        val m = c.metadata ?: return null
        val b = artBitmap(m) ?: return null
        val t = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        return Integer.toHexString((t + b.width + "x" + b.height + System.identityHashCode(b)).hashCode())
    }

    private fun label(ctx: Context, pkg: String) = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun describe(ctx: Context, c: MediaController): JSONObject {
        val m = c.metadata
        val st = c.playbackState
        fun txt(vararg keys: String) = keys.firstNotNullOfOrNull { k -> m?.getText(k)?.toString()?.takeIf { it.isNotBlank() } }
        val pkg = c.packageName
        return JSONObject()
            .put("pkg", pkg)
            .put("app", label(ctx, pkg))
            .put("video", pkg in VIDEO_APPS)
            .put("title", txt(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
            .put("artist", txt(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
            .put("album", txt(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION) ?: "")
            .put("duration", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .put("state", when (st?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_BUFFERING -> "buffering"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "seeking"
                PlaybackState.STATE_ERROR -> "error"
                else -> "idle"
            })
            .put("position", st?.position ?: 0L)
            .put("positionAge", if (st != null && st.lastPositionUpdateTime > 0) SystemClock.elapsedRealtime() - st.lastPositionUpdateTime else 0L)
            .put("speed", (st?.playbackSpeed ?: 1f).toDouble())
            .put("actions", st?.actions ?: 0L)
            .put("artKey", artKey(c) ?: JSONObject.NULL)
    }

    /** True while any app is playing (music in the background counts). */
    fun anyPlaying(ctx: Context): Boolean = runCatching { controllers(ctx).any { isActive(it) } }.getOrDefault(false)

    fun json(ctx: Context): String {
        val list = controllers(ctx)
        val cur = pick(list)
        val arr = JSONArray()
        list.forEach { arr.put(describe(ctx, it)) }
        return JSONObject()
            .put("ok", true)
            .put("access", hasAccess(ctx))
            .put("current", cur?.let { describe(ctx, it) } ?: JSONObject.NULL)
            .put("sessions", arr)
            .toString()
    }

    /** Album art / thumbnail as JPEG (max 600 px). */
    fun art(ctx: Context, pkg: String?): ByteArray? {
        val c = pick(controllers(ctx), pkg) ?: return null
        val key = (c.packageName + ":" + artKey(c))
        artCache.get(key)?.let { return it }
        val b = artBitmap(c.metadata) ?: return null
        val scale = minOf(1f, 600f / maxOf(b.width, b.height))
        val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        val out = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (s !== b) s.recycle()
        return out.toByteArray().also { artCache.put(key, it) }
    }

    /** play, pause, toggle, next, prev, ff, rw, seek (pos=ms), end (jump near the end: starts "next episode"). */
    fun control(ctx: Context, cmd: String, pkg: String?, pos: Long?): String {
        val c = pick(controllers(ctx), pkg)
        if (c == null) {
            // No media session: fall back to plain media keys.
            val code = when (cmd) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                "rw" -> KeyEvent.KEYCODE_MEDIA_REWIND
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            Media.key(ctx, code)
            return JSONObject().put("ok", true).put("via", "key").toString()
        }
        val tc = c.transportControls
        val st = c.playbackState
        val actions = st?.actions ?: 0L
        val canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L
        val now = st?.let {
            val age = if (it.lastPositionUpdateTime > 0) SystemClock.elapsedRealtime() - it.lastPositionUpdateTime else 0L
            it.position + if (it.state == PlaybackState.STATE_PLAYING) (age * it.playbackSpeed).toLong() else 0L
        } ?: 0L
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        when (cmd) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "toggle" -> if (isActive(c)) tc.pause() else tc.play()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            "ff" -> if (canSeek) tc.seekTo((now + 30_000).let { if (dur > 0) minOf(it, dur - 1000) else it }) else tc.fastForward()
            "rw" -> if (canSeek) tc.seekTo(maxOf(0, now - 10_000)) else tc.rewind()
            "seek" -> if (pos != null) tc.seekTo(pos.coerceAtLeast(0))
            "end" -> if (canSeek && dur > 0) tc.seekTo(maxOf(0, dur - 15_000)) else tc.skipToNext()
            "stop" -> tc.stop()
        }
        return JSONObject().put("ok", true).put("via", "session").toString()
    }
}

/** App icons for the phone (banner if the app has one, else its icon), as PNG. */
object AppIcons {
    private val cache = object : LruCache<String, ByteArray>(2 * 1024 * 1024) { // ~2 MB of PNGs
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun png(ctx: Context, pkg: String): ByteArray? {
        val pm = ctx.packageManager
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return null }
        val key = pkg + ":" + ai.sourceDir
        synchronized(cache) { cache.get(key)?.let { return it } }
        val d = (try { pm.getApplicationBanner(pkg) } catch (e: Exception) { null }) ?: pm.getApplicationIcon(ai)
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else 160
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else 90
        val scale = minOf(1f, 320f / iw, 180f / ih)
        val w = (iw * scale).toInt().coerceAtLeast(1)
        val h = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        synchronized(cache) { cache.put(key, bytes) }
        return bytes
    }
}

/**
 * Which apps are open (running) and which one is on screen, via the TV's adb.
 * Answers instantly from the last check and refreshes in the background (at most every 3 s), so the
 * phone's app list never waits on the slow "dumpsys" call.
 */
object RunningApps {
    @Volatile private var at = 0L
    @Volatile private var running: Set<String> = emptySet()
    @Volatile private var front: String? = null
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun get(ctx: Context): Pair<Set<String>, String?> {
        if (SystemClock.elapsedRealtime() - at > 3000 && busy.compareAndSet(false, true)) {
            thread(isDaemon = true) {
                try {
                    refresh(ctx)
                } finally {
                    busy.set(false)
                }
            }
        }
        return running to front
    }

    private fun refresh(ctx: Context) {
        try {
            Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
                val out = d.shell("ps -A -o NAME; echo @@; dumpsys activity activities | grep -m1 mResumedActivity").output
                val (ps, top) = out.split("@@").let { it[0] to it.getOrElse(1) { "" } }
                running = ps.lines().map { it.trim().substringBefore(':') }.filter { it.contains('.') }.toSet()
                front = Regex(""" ([A-Za-z0-9_.]+)/""").find(top)?.groupValues?.get(1)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Couldn't check running apps", e)
        }
        at = SystemClock.elapsedRealtime()
    }

    fun close(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) return false
        return try {
            Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { it.shell("am force-stop $pkg") }
            at = 0
            true
        } catch (e: Throwable) {
            false
        }
    }
}
