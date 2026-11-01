package com.bedroom.remote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the TV reports about itself (refreshed every few seconds). */
data class TvStatus(
    val sleepMinutesLeft: Int?,
    val lastChoice: Int,
    val canTurnOff: Boolean,
    val volume: Int,
    val maxVolume: Int,
    val muted: Boolean,
    val keys: String,
    val gamepad: String = "off",
    val screenOff: Boolean = false,
    val resting: Boolean = false,
    val gamepad2: String = "off",
    val night: Boolean = false,
    val backlight: Int = -1,
)

data class TvApp(
    val pkg: String,
    val label: String,
    val pinned: Boolean = false,
    val running: Boolean = false,
    val front: Boolean = false,
)

/** What's playing on the TV (from the app's media session). Positions are in ms. */
data class MediaInfo(
    val pkg: String,
    val app: String,
    val video: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val state: String,
    val position: Long,
    val capturedAt: Long, // phone's elapsedRealtime() at the moment [position] was true
    val speed: Float,
    val actions: Long,
    val artKey: String?,
) {
    val playing get() = state == "playing" || state == "buffering"
    fun can(action: Long) = actions and action != 0L
    fun positionAt(now: Long): Long {
        val p = if (state == "playing") position + ((now - capturedAt) * speed).toLong() else position
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

/** Bedroom settings on the TV (alarm, bedtime, night mode, idle, photo frame). Raw JSON keeps it flexible. */
data class BedroomInfo(val j: JSONObject) {
    fun int(k: String, d: Int = 0) = j.optInt(k, d)
    fun bool(k: String) = j.optBoolean(k)
    fun str(k: String, d: String = "") = j.optString(k, d)
}

data class PhotoItem(val name: String, val size: Long, val modified: Long)

data class StorageApp(val label: String, val pkg: String, val total: Long, val cache: Long)
data class StorageInfo(val free: Long, val total: Long, val cacheTotal: Long, val apps: List<StorageApp>, val note: String?)

/** RetroArch games on the TV, by system folder. */
data class RomFile(val name: String, val size: Long)
data class RomSystem(val system: String, val files: List<RomFile>)

/** One entry in the games & saves browser. */
data class TvFile(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long, val locked: Boolean)
data class TvFolder(val path: String, val root: String, val free: Long, val items: List<TvFile>)

/** A RetroArch save on the TV. */
data class SaveFile(val path: String, val name: String, val rel: String, val size: Long, val modified: Long, val state: Boolean)

/** Talks to Bedroom Home's phone-remote server on the TV (http://<tv>:8765/api/...). */
class TvClient(ctx: Context) {

    companion object {
        /**
         * Blocking call using the saved pairing, for the notification buttons (app may be closed).
         * Call off the main thread.
         */
        fun callBlocking(ctx: Context, action: String, vararg params: Pair<String, String>): JSONObject? {
            val host = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE).getString("host", "").orEmpty()
            val key = SecureKey.load(ctx)
            if (host.isBlank() || key.isBlank()) return null
            return try {
                val conn = Signer.open(host, key, action, params.toList())
                conn.connectTimeout = 3000
                conn.readTimeout = 4000
                try {
                    if (conn.responseCode != 200) null
                    else JSONObject(String(Signer.readVerified(key, conn), Charsets.UTF_8))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }

        fun isPaired(ctx: Context): Boolean =
            !ctx.getSharedPreferences("tv", Context.MODE_PRIVATE).getString("host", "").isNullOrBlank() && SecureKey.has(ctx)

        /** "Vol 12/100 · Off in 30 min" from a status reply. */
        fun statusLine(j: JSONObject?): String {
            if (j == null) return "Can't reach the TV"
            val vol = "Vol ${j.optInt("volume")}/${j.optInt("maxVolume")}" + if (j.optBoolean("muted")) " (muted)" else ""
            val sleep = if (j.isNull("sleepMinutesLeft")) "" else " · Off in ${j.optInt("sleepMinutesLeft")} min"
            return vol + sleep
        }
    }
    private val appCtx = ctx.applicationContext
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    var host by mutableStateOf(prefs.getString("host", "") ?: "")
        private set
    var key by mutableStateOf(SecureKey.load(ctx))
        private set
    /** Set if a reply failed its signature check (something on the network pretending to be the TV). */
    var tampered by mutableStateOf(false)
        private set

    /** null = not checked yet, true = TV answered, false = unreachable / wrong key. */
    var online by mutableStateOf<Boolean?>(null)
        private set
    var badKey by mutableStateOf(false)
        private set
    var status by mutableStateOf<TvStatus?>(null)
        private set
    var apps by mutableStateOf<List<TvApp>>(emptyList())
        private set
    var nowPlaying by mutableStateOf<MediaInfo?>(null)
        private set
    /** null = unknown, false = the TV needs "now playing" access turned on. */
    var mediaAccess by mutableStateOf<Boolean?>(null)
        private set

    var saves by mutableStateOf<List<SaveFile>?>(null)
        private set
    var savesError by mutableStateOf<String?>(null)
        private set
    var roms by mutableStateOf<List<RomSystem>?>(null)
        private set
    var romsFree by mutableStateOf(-1L)
        private set
    var romsError by mutableStateOf<String?>(null)
        private set

    val configured: Boolean get() = host.isNotBlank() && key.isNotBlank()

    /** Pair with a TV. Only home-network addresses and real 32-character keys are accepted. */
    fun save(newHost: String, newKey: String): Boolean {
        val h = newHost.trim().removePrefix("http://").substringBefore("/").substringBefore(":")
        val k = newKey.trim().lowercase()
        if (!Signer.validHost(h) || !Signer.validKey(k)) return false
        SecureKey.save(appCtx, k)
        prefs.edit().putString("host", h).apply()
        host = h
        key = k
        online = null
        badKey = false
        tampered = false
        return true
    }

    private fun signed(action: String, vararg params: Pair<String, String>, method: String = "GET") =
        Signer.open(host, key, action, params.toList(), method)

    private fun body(conn: HttpURLConnection): String = try {
        String(Signer.readVerified(key, conn), Charsets.UTF_8)
    } catch (e: SecurityException) {
        tampered = true
        throw e
    }

    /** Actions the TV takes a while over (shell commands, waking the screen). */
    private val SLOW = setOf("storage", "cleancache", "bedtime", "frame", "lastgame", "continuegame", "rest", "wake", "night", "bedroomset")

    /** Failed calls in a row. One slow reply isn't "offline"; two in a row is. */
    private var failures = 0

    private suspend fun call(action: String, vararg params: Pair<String, String>): JSONObject? =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext null
            // The TV said our key is wrong: wait for a re-pair (or the Reconnect button) instead of
            // retrying every few seconds, which would look like an attack and get the phone blocked.
            if (badKey && action != "connectkeys") return@withContext null
            // Two tries: the first request after the phone or TV wakes Wi-Fi up is often the slow one.
            for (attempt in 1..2) {
                try {
                    val conn = signed(action, *params)
                    conn.connectTimeout = if (attempt == 1) 3000 else 5000
                    conn.readTimeout = if (action in SLOW) 30000 else 8000
                    conn.useCaches = false
                    try {
                        when (conn.responseCode) {
                            403 -> {
                                badKey = true
                                online = false
                                return@withContext null
                            }
                            429 -> { // briefly blocked by the TV: back off
                                online = false
                                return@withContext null
                            }
                        }
                        val body = body(conn)
                        badKey = false
                        failures = 0
                        online = true
                        return@withContext JSONObject(body)
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    if (attempt == 1) kotlinx.coroutines.delay(400)
                }
            }
            failures++
            if (failures >= 2) online = false
            null
        }

    /** Fire-and-forget command. Volume and play/pause go the instant way (see KeySender). */
    fun send(scope: CoroutineScope, action: String, vararg params: Pair<String, String>) {
        if (action in INSTANT && params.isEmpty() && !badKey) {
            KeySender.press(host, key, action)
            scope.launch { kotlinx.coroutines.delay(600); refreshStatus() } // update the volume shown
            return
        }
        scope.launch { call(action, *params) }
    }

    /** Remote buttons (arrows, OK, Back, Home...): instant, over UDP. */
    fun key(scope: CoroutineScope, name: String) {
        if (badKey || !configured) return
        KeySender.press(host, key, name)
    }

    private val INSTANT = setOf("volup", "voldown", "mute", "playpause")

    suspend fun refreshStatus() {
        val j = call("status") ?: return
        status = TvStatus(
            sleepMinutesLeft = if (j.isNull("sleepMinutesLeft")) null else j.optInt("sleepMinutesLeft"),
            lastChoice = j.optInt("lastChoice"),
            canTurnOff = j.optBoolean("canTurnOff"),
            volume = j.optInt("volume"),
            maxVolume = j.optInt("maxVolume"),
            muted = j.optBoolean("muted"),
            keys = j.optString("keys", "idle"),
            gamepad = j.optString("gamepad", "off"),
            screenOff = j.optBoolean("screenOff"),
            resting = j.optBoolean("resting"),
            gamepad2 = j.optString("gamepad2", "off"),
            night = j.optBoolean("night"),
            backlight = j.optInt("backlight", -1),
        )
    }

    /** Screen off (sound keeps playing) / back on. */
    suspend fun setScreen(off: Boolean) {
        call(if (off) "screenoff" else "screenon")
        refreshStatus()
    }

    /** Rest (looks off, stays online) / wake back up. */
    suspend fun setRest(rest: Boolean) {
        call(if (rest) "rest" else "wake")
        refreshStatus()
    }

    /** Reconnect button: re-check the TV, re-link the full remote/gamepad, reload apps. */
    suspend fun reconnect() {
        online = null
        badKey = false
        status = null
        call("connectkeys")
        refreshStatus()
        refreshApps()
    }

    suspend fun refreshApps() {
        val arr = call("apps")?.optJSONArray("apps") ?: return
        apps = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TvApp(o.getString("pkg"), o.getString("label"), o.optBoolean("pinned"), o.optBoolean("running"), o.optBoolean("front"))
        }
    }

    suspend fun refreshNowPlaying() {
        val j = call("nowplaying") ?: return
        mediaAccess = j.optBoolean("access", false)
        val c = j.optJSONObject("current")
        nowPlaying = c?.let {
            MediaInfo(
                pkg = it.optString("pkg"),
                app = it.optString("app"),
                video = it.optBoolean("video"),
                title = it.optString("title"),
                artist = it.optString("artist"),
                album = it.optString("album"),
                duration = it.optLong("duration"),
                state = it.optString("state", "idle"),
                position = it.optLong("position"),
                capturedAt = android.os.SystemClock.elapsedRealtime() - it.optLong("positionAge"),
                speed = it.optDouble("speed", 1.0).toFloat(),
                actions = it.optLong("actions"),
                artKey = if (it.isNull("artKey")) null else it.optString("artKey"),
            )
        }
    }

    /** play, pause, toggle, next, prev, ff (+30 s), rw (-10 s), seek (pos ms), end (skip to the end -> next episode). */
    fun media(scope: CoroutineScope, cmd: String, pkg: String? = null, pos: Long? = null) {
        scope.launch {
            val params = mutableListOf("cmd" to cmd)
            if (pkg != null) params += "pkg" to pkg
            if (pos != null) params += "pos" to pos.toString()
            call("media", *params.toTypedArray())
            kotlinx.coroutines.delay(350)
            refreshNowPlaying()
        }
    }

    // ---- images (app icons, album art) ----
    private val images = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > 80
    }

    fun cachedImage(key: String) = synchronized(images) { images[key] }

    /** GET /api/icon?pkg= or /api/art?pkg=&key= as an image (cached). */
    suspend fun image(action: String, vararg params: Pair<String, String>): androidx.compose.ui.graphics.ImageBitmap? {
        val cacheKey = action + params.joinToString("&") { "${it.first}=${it.second}" }
        cachedImage(cacheKey)?.let { return it }
        if (!configured) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = signed(action, *params)
                conn.connectTimeout = 3000
                conn.readTimeout = 8000
                try {
                    if (conn.responseCode != 200) return@withContext null
                    val bytes = Signer.readVerified(key, conn)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        val img = it.asImageBitmap()
                        synchronized(images) { images[cacheKey] = img }
                        img
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun refreshSaves() = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        try {
            val conn = signed("saves")
            conn.connectTimeout = 3000
            conn.readTimeout = 30000
            val j = try {
                JSONObject(body(conn))
            } finally {
                conn.disconnect()
            }
            if (!j.optBoolean("ok")) {
                savesError = j.optString("error", "The TV couldn't list the saves")
                return@withContext
            }
            val arr = j.optJSONArray("saves") ?: JSONArray()
            saves = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SaveFile(o.getString("path"), o.getString("name"), o.optString("rel"), o.optLong("size"), o.optLong("modified"), o.optBoolean("state"))
            }
            savesError = null
        } catch (e: Exception) {
            savesError = "Can't reach the TV (is it on and is Bedroom Home running?)"
        }
    }

    /** Download one save (path) or the whole backup zip (path = null) into [out]. */
    suspend fun downloadSave(path: String?, out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = if (path == null) signed("savesbackup") else signed("savefile", "path" to path)
            conn.connectTimeout = 3000
            conn.readTimeout = 120000
            try {
                if (conn.responseCode != 200) return@withContext false
                Signer.copyVerified(key, conn, out)
                true
            } finally {
                conn.disconnect()
                out.close()
            }
        } catch (e: Exception) {
            if (e is SecurityException) tampered = true
            false
        }
    }

    /** Send a save file (or a backup .zip) to the TV. Returns the TV's JSON reply, or null if unreachable. */
    suspend fun uploadSave(name: String, bytes: ByteArray, stopRetroArch: Boolean): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = signed("saveupload", "name" to name, "stop" to if (stopRetroArch) "1" else "0", method = "POST")
            conn.connectTimeout = 3000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size + 64)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            try {
                conn.outputStream.use { o -> Signer.writeSignedBody(key, conn, bytes.inputStream(), o) }
                JSONObject(body(conn))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun refreshRoms() = withContext(Dispatchers.IO) {
        if (!configured) return@withContext
        try {
            val conn = signed("roms")
            conn.connectTimeout = 4000
            conn.readTimeout = 30000
            val j = try {
                JSONObject(body(conn))
            } finally {
                conn.disconnect()
            }
            if (!j.optBoolean("ok")) {
                romsError = j.optString("error", "The TV couldn't list the games")
                return@withContext
            }
            val arr = j.optJSONArray("systems") ?: JSONArray()
            roms = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fa = o.optJSONArray("files") ?: JSONArray()
                RomSystem(
                    o.getString("system"),
                    (0 until fa.length()).map { k -> fa.getJSONObject(k).let { RomFile(it.getString("name"), it.optLong("size")) } }
                        .sortedBy { it.name.lowercase() },
                )
            }
            romsFree = j.optLong("free", -1)
            romsError = null
        } catch (e: Exception) {
            romsError = "Can't reach the TV (is it on and is Bedroom Home running?)"
        }
    }

    /**
     * Stream one game to the TV ([length] bytes from [open]), reporting bytes sent.
     * Returns the TV's reply, or null if the connection failed.
     */
    suspend fun uploadRom(name: String, length: Long, open: () -> java.io.InputStream?, onProgress: (Long) -> Unit): JSONObject? =
        uploadStream(length, open, onProgress, "romupload", "name" to name)

    /** Send a file into a folder of the games & saves browser. */
    suspend fun uploadFile(dir: String, name: String, length: Long, open: () -> java.io.InputStream?, onProgress: (Long) -> Unit): JSONObject? =
        uploadStream(length, open, onProgress, "fileup", "dir" to dir, "name" to name)

    /** List a folder in the games & saves browser (null path = the top). Returns the folder or an error message. */
    suspend fun listFiles(path: String?): Pair<TvFolder?, String?> {
        val j = if (path == null) call("files") else call("files", "path" to path)
        j ?: return null to "Can't reach the TV."
        if (!j.optBoolean("ok")) return null to j.optString("error", "Couldn't open that folder.")
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TvFile(o.getString("name"), o.getString("path"), o.optBoolean("dir"), o.optLong("size"), o.optLong("modified"), o.optBoolean("locked"))
        }
        return TvFolder(j.getString("path"), j.optString("root"), j.optLong("free", -1), items) to null
    }

    /** delete / rename / mkdir. Returns (ok, message). */
    suspend fun fileOp(op: String, path: String, to: String? = null): Pair<Boolean, String> {
        val params = mutableListOf("op" to op, "path" to path)
        if (to != null) params += "to" to to
        val j = call("fileop", *params.toTypedArray()) ?: return false to "Can't reach the TV."
        return if (j.optBoolean("ok")) true to j.optString("done", "Done") else false to j.optString("error", "That didn't work.")
    }

    /** Download a file from the browser into [out]. */
    suspend fun downloadFile(path: String, out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = signed("filedl", "path" to path)
            conn.connectTimeout = 4000
            conn.readTimeout = 10 * 60 * 1000
            try {
                if (conn.responseCode != 200) return@withContext false
                Signer.copyVerified(key, conn, out)
                true
            } finally {
                conn.disconnect()
                out.close()
            }
        } catch (e: Exception) {
            if (e is SecurityException) tampered = true
            false
        }
    }

    private suspend fun uploadStream(
        length: Long,
        open: () -> java.io.InputStream?,
        onProgress: (Long) -> Unit,
        action: String,
        vararg params: Pair<String, String>,
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val conn = signed(action, *params, method = "POST")
                conn.connectTimeout = 5000
                conn.readTimeout = 10 * 60 * 1000 // big games take a while to land on the TV
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(length + 64)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                try {
                    val input = open() ?: return@withContext null
                    input.use { ins -> conn.outputStream.use { out -> Signer.writeSignedBody(key, conn, ins, out, onProgress) } }
                    JSONObject(body(conn))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }

    /** A touch on the TV screen; positions are 0..1 of the picture. kind = tap, long or swipe. */
    fun touch(scope: CoroutineScope, kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Int) {
        if (badKey || !configured) return
        fun f(v: Float) = String.format(java.util.Locale.US, "%.4f", v)
        scope.launch { call("touch", "k" to kind, "x" to f(x), "y" to f(y), "x2" to f(x2), "y2" to f(y2), "ms" to ms.toString()) }
    }

    // ---- Bedroom ----
    var bedroom by mutableStateOf<BedroomInfo?>(null)
    var photos by mutableStateOf<List<PhotoItem>>(emptyList())
    var lastGame by mutableStateOf<String?>(null)

    suspend fun refreshBedroom() {
        call("bedroom")?.let { bedroom = BedroomInfo(it) }
    }

    /** Saves bedroom settings (only the ones given) and keeps what the TV sends back. */
    suspend fun setBedroom(vararg params: Pair<String, String>) {
        call("bedroomset", *params)?.let { bedroom = BedroomInfo(it) }
    }

    /** Runs a one-shot action and returns the TV's reply (or null). */
    suspend fun action(name: String, vararg params: Pair<String, String>): JSONObject? = call(name, *params)

    suspend fun refreshPhotos() {
        val arr = call("photos")?.optJSONArray("photos") ?: return
        photos = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PhotoItem(o.getString("name"), o.optLong("size"), o.optLong("modified"))
        }
    }

    suspend fun uploadPhoto(name: String, length: Long, open: () -> java.io.InputStream?, album: String = "", onProgress: (Long) -> Unit): JSONObject? =
        uploadStream(length, open, onProgress, "photoupload", "name" to name, "album" to album)

    /** The TV's backlight (0-100). null = just read it. Returns the level, or -1. */
    suspend fun backlight(level: Int? = null): Int {
        val j = if (level == null) call("backlight") else call("backlight", "level" to level.toString())
        return j?.optInt("level", -1) ?: -1
    }

    suspend fun photoThumb(name: String) = image("photo", "name" to name, "w" to "360")

    suspend fun storage(): StorageInfo? {
        val j = call("storage") ?: return null
        val arr = j.optJSONArray("apps")
        val apps = if (arr == null) emptyList() else (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StorageApp(o.optString("label"), o.optString("pkg"), o.optLong("app") + o.optLong("data") + o.optLong("cache"), o.optLong("cache"))
        }
        return StorageInfo(j.optLong("free"), j.optLong("total"), j.optLong("cacheTotal"), apps, j.optString("note").takeIf { it.isNotBlank() })
    }

    /** Time played per RetroArch game (newest first). */
    var playtime by mutableStateOf<List<PlayRow>?>(null)

    suspend fun refreshPlaytime() {
        val arr = call("playtime")?.optJSONArray("games") ?: return
        playtime = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlayRow(o.optString("label"), o.optLong("secs"), o.optLong("last"))
        }
    }

    suspend fun refreshLastGame() {
        val g = call("lastgame") ?: return
        lastGame = g.optJSONObject("game")?.optString("label")
    }

    /** One frame of the TV screen (not cached). Null = couldn't get one. */
    suspend fun screenFrame(width: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = signed("screen", "w" to width.toString())
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            try {
                if (conn.responseCode != 200) return@withContext null
                val bytes = Signer.readVerified(key, conn)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Opens the TV's live H.264 video stream (raw Annex-B). Caller reads and closes it. Null = unavailable. */
    fun openStream(width: Int, height: Int, bitrate: Int): HttpURLConnection? {
        if (!configured || badKey) return null
        return try {
            val conn = signed("stream", "w" to width.toString(), "h" to height.toString(), "br" to bitrate.toString())
            conn.connectTimeout = 4000
            conn.readTimeout = 20000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            Signer.checkStream(key, conn)
            conn
        } catch (e: Exception) {
            if (e is SecurityException) tampered = true
            null
        }
    }
}

data class PlayRow(val label: String, val secs: Long, val last: Long)
