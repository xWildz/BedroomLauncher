package com.bedroom.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object LocalNet {
    fun ipv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}

/** Pairing link for the QR code. The key is after "#", which browsers never send over the network. */
fun remoteUrl(ctx: Context): String? =
    LocalNet.ipv4()?.let { "http://$it:${RemoteServer.PORT}/#k=${RemoteKey.get(ctx)}" }

object Qr {
    fun bitmap(text: String, size: Int): ImageBitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp.asImageBitmap()
    }
}

/**
 * Tiny home-network-only server for the phone app. Runs inside the launcher, so it works whenever the
 * TV is on, even with the PC off. Every /api/ request must be signed with the pairing key (see Auth).
 */
class RemoteServer private constructor(private val app: Context) {

    companion object {
        const val PORT = 8765

        @Volatile
        private var started = false

        fun startOnce(ctx: Context) {
            if (started) return
            started = true
            RemoteServer(ctx.applicationContext).start()
        }
    }

    private val page: String by lazy {
        app.resources.openRawResource(R.raw.remote).bufferedReader().use { it.readText() }
    }

    private fun start() {
        thread(name = "phone-remote", isDaemon = true) {
            // Runs forever: if the socket ever fails (Wi-Fi drop, network change), wait and open it again.
            var wait = 1000L
            while (true) {
                try {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(PORT))
                        Log.i(TAG, "Phone remote listening on port $PORT")
                        wait = 1000L
                        while (true) {
                            val client = ss.accept()
                            thread(isDaemon = true) { handle(client) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Phone remote server stopped; restarting in ${wait / 1000} s", e)
                }
                Thread.sleep(wait)
                wait = minOf(wait * 2, 15_000L)
            }
        }
    }

    /** One request: who sent it, its verified nonce (signed requests only), and how to answer. */
    private inner class Req(val out: OutputStream, val key: String, val nonce: String?) {
        fun sig(bodyHash: String) = nonce?.let { "X-Sig: " + Auth.responseSig(key, it, bodyHash) + "\r\n" } ?: ""

        fun bytes(code: Int, type: String, body: ByteArray, extra: String = "") {
            val status = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                403 -> "Forbidden"
                429 -> "Too Many Requests"
                503 -> "Unavailable"
                else -> "Not Found"
            }
            val head = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                sig(Auth.sha256Hex(body)) + extra +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        fun json(code: Int, body: String) = bytes(code, "application/json", body.toByteArray(Charsets.UTF_8))

        fun file(f: File, name: String) {
            try {
                val safe = name.replace(Regex("[\"\\r\\n]"), "")
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"$safe\"\r\n" +
                    "Cache-Control: no-store\r\n" +
                    sig(Auth.sha256Hex(f)) +
                    "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                f.inputStream().use { it.copyTo(out) }
                out.flush()
            } finally {
                f.delete()
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30000
                val ip = s.inetAddress?.hostAddress ?: "?"
                val out = s.getOutputStream()
                // Home network only.
                if (!Auth.isLocal(s.inetAddress)) return
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(" ")
                val method = requestLine.getOrNull(0) ?: return
                val target = requestLine.getOrNull(1) ?: return
                val headers = lines.drop(1).mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
                }.toMap()
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                val uri = Uri.parse("http://tv$target")
                val path = uri.path ?: "/"
                val key = RemoteKey.get(app)

                if (!path.startsWith("/api/")) {
                    // The only unsigned page: "open the app" (pairing). It contains no secrets.
                    val r = Req(out, key, null)
                    if (path == "/" && method == "GET") r.bytes(200, "text/html; charset=utf-8", page.toByteArray(Charsets.UTF_8))
                    else r.json(404, """{"ok":false}""")
                    return
                }
                // An address that keeps sending forged requests is ignored for a while.
                // (The pairing page above is never blocked, so you can always re-pair.)
                if (Auth.isBlocked(ip)) {
                    Req(out, "", null).json(429, """{"ok":false,"error":"Too many bad requests. Try again in a few minutes."}""")
                    return
                }
                if (headers["x-sig"] == null) {
                    // An older TV Remote app (no signing): ask for the update, don't count it as an attack.
                    Req(out, key, null).json(403, """{"ok":false,"update":true,"error":"Update the TV Remote app, then scan the TV's QR code."}""")
                    return
                }
                val nonce = Auth.verify(key, method, target, headers)
                if (nonce == null) {
                    Auth.fail(ip)
                    Req(out, key, null).json(403, """{"ok":false,"error":"bad signature"}""")
                    return
                }
                val r = Req(out, key, nonce)

                /** Upload body -> temp file, only if its HMAC checks out. */
                fun receive(): File? {
                    val free = runCatching { android.os.StatFs(app.cacheDir.path).availableBytes }.getOrDefault(-1L)
                    if (free in 0 until length + 50L * 1024 * 1024) return null
                    val f = File(app.cacheDir, "in-$nonce.part")
                    if (Auth.receiveVerified(key, nonce, input, length, f)) return f
                    f.delete()
                    return null
                }
                val rejected = """{"ok":false,"error":"The upload didn't arrive intact (or the TV is out of space). Try again."}"""

                when (path) {
                    "/api/savefile" -> {
                        val f = runCatching { RetroSaves.pull(app, uri.getQueryParameter("path").orEmpty()) }.getOrNull()
                        if (f == null) r.json(404, """{"ok":false,"error":"Not found"}""") else r.file(f, f.name.removePrefix("dl-"))
                    }
                    "/api/icon", "/api/art" -> {
                        val pkg = uri.getQueryParameter("pkg")
                        val bytes = runCatching {
                            if (path == "/api/icon") AppIcons.png(app, pkg.orEmpty()) else NowPlaying.art(app, pkg)
                        }.getOrNull()
                        if (bytes == null) r.json(404, """{"ok":false}""")
                        else r.bytes(200, if (path == "/api/icon") "image/png" else "image/jpeg", bytes)
                    }
                    "/api/savesbackup" -> {
                        val f = runCatching { RetroSaves.backupZip(app) }.getOrNull()
                        if (f == null) r.json(404, """{"ok":false,"error":"Can't reach RetroArch's folder"}""") else r.file(f, f.name)
                    }
                    "/api/screen" -> {
                        val bytes = ScreenGrab.jpeg(app, uri.getQueryParameter("w")?.toIntOrNull() ?: 960)
                        if (bytes == null) r.json(503, """{"ok":false,"error":"Couldn't capture the screen. Is \"Allow full remote\" on?"}""")
                        else r.bytes(200, "image/jpeg", bytes)
                    }
                    "/api/stream" -> {
                        // Endless H.264 video (see ScreenStream). Unsigned body: it's just pictures.
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: video/h264\r\nCache-Control: no-store\r\n" +
                                "X-Sig: " + Auth.responseSig(key, nonce, "stream") + "\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        )
                        out.flush()
                        s.soTimeout = 0
                        s.tcpNoDelay = true
                        ScreenStream.pump(
                            app, out,
                            uri.getQueryParameter("w")?.toIntOrNull() ?: 1280,
                            uri.getQueryParameter("h")?.toIntOrNull() ?: 720,
                            uri.getQueryParameter("br")?.toIntOrNull() ?: 3_000_000,
                        )
                    }
                    "/api/filedl" -> {
                        val f = FileBrowser.pull(app, uri.getQueryParameter("path"))
                        if (f == null) r.json(404, """{"ok":false,"error":"Couldn't read that file"}""") else r.file(f, f.name.removePrefix("fdl-"))
                    }
                    "/api/photo" -> {
                        val bytes = runCatching {
                            PhotoStore.thumb(app, uri.getQueryParameter("name"), uri.getQueryParameter("w")?.toIntOrNull() ?: 360)
                        }.getOrNull()
                        if (bytes == null) r.json(404, """{"ok":false}""") else r.bytes(200, "image/jpeg", bytes)
                    }
                    "/api/fileup", "/api/romupload", "/api/saveupload", "/api/photoupload" -> {
                        if (method != "POST") return r.json(400, """{"ok":false,"error":"POST only"}""")
                        val f = receive() ?: return r.json(200, rejected)
                        try {
                            val name = uri.getQueryParameter("name").orEmpty()
                            val res = when (path) {
                                "/api/fileup" -> FileBrowser.upload(app, uri.getQueryParameter("dir"), name, f)
                                "/api/romupload" -> RetroRoms.upload(app, name, f)
                                "/api/photoupload" -> if (f.length() > 40L * 1024 * 1024) """{"ok":false,"error":"Too big for a photo (40 MB max)"}"""
                                else PhotoStore.add(app, name, f, uri.getQueryParameter("album").orEmpty())
                                else -> if (f.length() > 64L * 1024 * 1024) """{"ok":false,"error":"Too big for a save file (64 MB max)"}"""
                                else RetroSaves.upload(app, name, f.readBytes(), uri.getQueryParameter("stop") == "1")
                            }
                            r.json(200, res)
                        } finally {
                            f.delete()
                        }
                    }
                    else -> r.json(200, RemoteActions.run(app, path.removePrefix("/api/"), uri))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phone remote request failed", e)
        }
    }

    /** Request line + headers, up to the blank line (max 16 KB). */
    private fun readHead(input: BufferedInputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("ISO-8859-1").trimEnd()
        }
        return null
    }
}

object RemoteActions {
    private val main = Handler(Looper.getMainLooper())

    private fun ok() = """{"ok":true}"""
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    fun run(ctx: Context, action: String, uri: Uri): String = when (action) {
        "home" -> {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ok()
        }
        "volup" -> { Media.volume(ctx, AudioManager.ADJUST_RAISE); ok() }
        "voldown" -> { Media.volume(ctx, AudioManager.ADJUST_LOWER); ok() }
        "mute" -> { Media.volume(ctx, AudioManager.ADJUST_TOGGLE_MUTE); ok() }
        "play" -> { Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY); ok() }
        "pause" -> { Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); ok() }
        "playpause" -> { Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); ok() }
        "next" -> { Media.key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); ok() }
        "prev" -> { Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); ok() }
        "open" -> {
            val pkg = uri.getQueryParameter("pkg").orEmpty()
            if (pkg.isNotEmpty() && Apps.launch(ctx, pkg)) ok() else err("Couldn't open that app")
        }
        "sleep" -> {
            val minutes = uri.getQueryParameter("min")?.toIntOrNull() ?: 0
            main.post { SleepTimer.set(ctx, minutes) }
            ok()
        }
        "sleepnow", "rest" -> Rest.enter(ctx)
        "wake" -> Rest.exit(ctx)
        "key" -> {
            val name = uri.getQueryParameter("code").orEmpty()
            if (KeyInjector.key(name)) ok() else err("Unknown key")
        }
        "text" -> {
            KeyInjector.text(uri.getQueryParameter("t").orEmpty().take(200))
            ok()
        }
        "connectkeys" -> { KeyInjector.connect(); ok() }
        "touch" -> {
            fun f(k: String) = uri.getQueryParameter(k)?.toFloatOrNull()
            val x = f("x"); val y = f("y")
            if (x == null || y == null) err("Missing position")
            else if (KeyInjector.touch(ctx, uri.getQueryParameter("k") ?: "tap", x, y, f("x2") ?: x, f("y2") ?: y,
                    uri.getQueryParameter("ms")?.toIntOrNull() ?: 250)) ok()
            else err("Bad touch")
        }
        "saves" -> RetroSaves.list(ctx)
        "roms" -> RetroRoms.list(ctx)
        "files" -> FileBrowser.list(ctx, uri.getQueryParameter("path"))
        "fileop" -> FileBrowser.op(ctx, uri.getQueryParameter("op").orEmpty(), uri.getQueryParameter("path"), uri.getQueryParameter("to"))
        "nowplaying" -> NowPlaying.json(ctx)
        "mediaaccess" -> { NowPlaying.requestAccess(ctx); ok() }
        "media" -> NowPlaying.control(
            ctx, uri.getQueryParameter("cmd").orEmpty(),
            uri.getQueryParameter("pkg")?.takeIf { it.isNotBlank() },
            uri.getQueryParameter("pos")?.toLongOrNull(),
        )
        "close" -> {
            val pkg = uri.getQueryParameter("pkg").orEmpty()
            if (RunningApps.close(ctx, pkg)) ok() else err("Couldn't close that app")
        }
        "screenoff" -> ScreenOff.off(ctx)
        "screenon" -> ScreenOff.on(ctx)
        // Bedroom tab
        "bedroom" -> BedroomPrefs.json(ctx).toString()
        "bedroomset" -> BedroomPrefs.set(ctx, uri)
        "bedtime" -> Bedtime.run(ctx)
        "night" -> if (uri.getQueryParameter("on") == "1") NightMode.on(ctx) else NightMode.off(ctx)
        "alarmtest" -> { Alarm.fire(ctx); ok() }
        "frame" -> PhotoFrame.show(ctx)
        "openurl" -> ShareOpen.openUrl(ctx, uri.getQueryParameter("url"))
        "showphoto" -> ShareOpen.showPhoto(ctx, uri.getQueryParameter("name"))
        "playtime" -> PlayTime.json(ctx)
        "photos" -> PhotoStore.json(ctx)
        "photodel" -> PhotoStore.delete(ctx, uri.getQueryParameter("name"))
        "storage" -> Storage.json(ctx)
        "backlight" -> Brightness.json(ctx, uri.getQueryParameter("level")?.toIntOrNull())
        "cleancache" -> Storage.clean(ctx)
        "lastgame" -> RetroHistory.json(ctx)
        "continuegame" -> RetroHistory.launch(ctx)
        "status" -> status(ctx)
        "apps" -> apps(ctx)
        else -> err("Unknown action")
    }

    private fun status(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val left = SleepTimer.minutesLeft()
        return JSONObject()
            .put("ok", true)
            .put("sleepMinutesLeft", left ?: JSONObject.NULL)
            .put("lastChoice", SleepTimer.lastChoice)
            .put("canTurnOff", SleepTimer.canTurnOff(ctx))
            .put("volume", am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
            .put("maxVolume", am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0)
            .put("muted", am?.isStreamMute(AudioManager.STREAM_MUSIC) ?: false)
            .put("keys", KeyInjector.state)
            .put("gamepad", GamepadBridge.state)
            .put("gamepad2", GamepadBridge.state2)
            .put("screenOff", ScreenOff.isOff)
            .put("resting", Rest.isResting)
            .put("night", NightMode.isOn)
            .put("backlight", Brightness.cached)
            .put("tvVersion", BuildConfig.VERSION_NAME)
            .toString()
    }

    /**
     * Every TV app: Watch row + favorites first (pinned), then A-Z. Each says whether it's open (running)
     * and whether it's the one on screen. "stamp" changes when apps are installed/removed.
     */
    private fun apps(ctx: Context): String {
        val data = Apps.load(ctx)
        val pinned = LinkedHashMap<String, String>()
        WATCH.forEach { pin -> data.resolve(pin)?.let { pinned.putIfAbsent(it.pkg, it.label) } }
        Favorites.load(ctx).forEach { pkg -> Favorites.resolve(pkg, data)?.let { pinned.putIfAbsent(it.pkg, it.label) } }
        val (running, front) = RunningApps.get(ctx)
        val arr = JSONArray()
        fun add(pkg: String, label: String, pin: Boolean) = arr.put(
            JSONObject().put("pkg", pkg).put("label", label).put("pinned", pin)
                .put("running", pkg in running).put("front", pkg == front),
        )
        pinned.forEach { (pkg, label) -> add(pkg, label, true) }
        data.all.filter { it.pkg !in pinned }.forEach { add(it.pkg, it.label, false) }
        val stamp = Integer.toHexString(data.all.joinToString(",") { it.pkg }.hashCode())
        return JSONObject().put("ok", true).put("stamp", stamp).put("front", front ?: JSONObject.NULL)
            .put("apps", arr).toString()
    }
}
