package com.bedroom.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Photo frame pictures, kept in the launcher's own folder on the TV:
 *   /sdcard/Android/data/com.bedroom.launcher/files/frame
 * Filled from the "TV Photo Frame" folder on the PC (panel sync) and from the phone app.
 * Removing a photo on the phone writes its name to ".deleted", so the PC sync doesn't send it back.
 */
object PhotoStore {
    private val EXT = setOf("jpg", "jpeg", "png", "webp")
    private val SAFE = Regex("[^A-Za-z0-9 ._()\\-]")

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "frame").apply { mkdirs() }
    private fun deletedFile(ctx: Context) = File(dir(ctx), ".deleted")

    private fun isPhoto(f: File) = f.isFile && f.extension.lowercase() in EXT && !f.name.startsWith(".")

    /** Albums = subfolders of the frame folder (from subfolders of "TV Photo Frame" on the PC). */
    fun albums(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted() ?: emptyList()

    /** Photos in one album ("" = every photo, loose ones and all albums), newest first. */
    fun list(ctx: Context, album: String = ""): List<File> {
        val root = dir(ctx)
        val dirs = if (album.isBlank()) listOf(root) + albums(ctx).map { File(root, it) }
                   else listOfNotNull(File(root, album).takeIf { it.isDirectory && it.parentFile == root })
        return dirs.flatMap { d -> d.listFiles()?.filter { isPhoto(it) } ?: emptyList() }.sortedByDescending { it.lastModified() }
    }

    /** "album/name.jpg" or "name.jpg": how the phone refers to a photo. */
    private fun rel(ctx: Context, f: File) = if (f.parentFile == dir(ctx)) f.name else "${f.parentFile!!.name}/${f.name}"

    fun count(ctx: Context) = list(ctx).size

    fun json(ctx: Context): String {
        val arr = JSONArray()
        list(ctx).forEach {
            arr.put(JSONObject().put("name", rel(ctx, it)).put("album", if (it.parentFile == dir(ctx)) "" else it.parentFile!!.name)
                .put("size", it.length()).put("modified", it.lastModified()))
        }
        val alb = JSONArray()
        albums(ctx).forEach { a -> alb.put(JSONObject().put("name", a).put("count", list(ctx, a).size)) }
        val free = runCatching { android.os.StatFs(dir(ctx).path).availableBytes }.getOrDefault(-1L)
        return JSONObject().put("ok", true).put("photos", arr).put("albums", alb).put("free", free).toString()
    }

    internal fun find(ctx: Context, name: String?): File? {
        val n = name?.trim().orEmpty()
        val parts = n.split('/')
        if (parts.size > 2 || parts.any { it.isEmpty() || it.startsWith(".") }) return null
        val f = File(dir(ctx), n)
        val okParent = f.parentFile == dir(ctx) || f.parentFile?.parentFile == dir(ctx)
        return f.takeIf { it.isFile && okParent }
    }

    /** Phone upload: fixed rotation, shrunk to 1920 px, saved as JPEG (saves the TV's small storage). */
    fun add(ctx: Context, rawName: String, src: File, album: String = ""): String {
        val folder = SAFE.replace(album.trim(), "_").take(40).takeIf { it.isNotBlank() && !it.startsWith(".") }
            ?.let { File(dir(ctx), it).apply { mkdirs() } } ?: dir(ctx)
        val base = SAFE.replace(rawName.substringAfterLast('/').substringBeforeLast('.'), "_").take(60).ifBlank { "photo" }
        val bmp = decode(src, 1920, 1920) ?: return JSONObject().put("ok", false).put("error", "That isn't a picture the TV can read").toString()
        val rotated = rotate(bmp, src)
        var out = File(folder, "$base.jpg")
        var i = 2
        while (out.exists()) out = File(folder, "$base ($i).jpg").also { i++ }
        out.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        rotated.recycle()
        // If it was deleted before, it's wanted again.
        val del = deletedFile(ctx)
        val r = rel(ctx, out)
        if (del.exists()) del.writeText(del.readLines().filter { it != r && it != out.name }.joinToString("\n"))
        return JSONObject().put("ok", true).put("done", "Added ${out.name}").put("name", r).toString()
    }

    fun delete(ctx: Context, name: String?): String {
        val f = find(ctx, name) ?: return JSONObject().put("ok", false).put("error", "Photo not found").toString()
        val r = rel(ctx, f)
        f.delete()
        deletedFile(ctx).appendText(r + "\n")
        return JSONObject().put("ok", true).toString()
    }

    fun thumb(ctx: Context, name: String?, w: Int): ByteArray? {
        val f = find(ctx, name) ?: return null
        val size = w.coerceIn(120, 1280)
        val b = decode(f, size, size) ?: return null
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 78, out)
        b.recycle()
        return out.toByteArray()
    }

    /** Decodes no bigger than needed (this TV has little memory). */
    fun decode(f: File, maxW: Int, maxH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW || o.outHeight / (sample * 2) >= maxH) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxW.toFloat() / b.width, maxH.toFloat() / b.height)
        if (scale >= 0.999f) return b
        val s = Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
        if (s !== b) b.recycle()
        return s
    }

    private fun rotate(b: Bitmap, src: File): Bitmap {
        val deg = runCatching {
            when (android.media.ExifInterface(src.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (deg == 0f) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        if (r !== b) b.recycle()
        return r
    }
}

object PhotoFrame {
    @Volatile var showing = false

    /** Ends the screensaver if it's on, then shows the slideshow. Call off the main thread. */
    fun show(ctx: Context, fromScreensaver: Boolean = false, start: String? = null): String {
        val app = ctx.applicationContext
        if (Rest.isResting) Rest.exit(app)
        // Only press "wake" when the screensaver is on (or the screen is asleep): an extra key press
        // arriving after the frame opens would close it straight away.
        val asleep = app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false
        if (fromScreensaver || asleep) {
            TvShell.wakeScreen(app)
            Thread.sleep(900)
        }
        app.startActivity(
            Intent(app, PhotoFrameActivity::class.java)
                .apply { if (start != null) putExtra("start", start) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        return JSONObject().put("ok", true).put("photos", PhotoStore.count(app)).toString()
    }
}

/** Options for the slideshow (edited from the phone's Bedroom tab). */
data class FrameOptions(
    val secs: Int, val shuffle: Boolean, val clock: Boolean, val weather: Boolean,
    val zoom: Boolean, val fill: Boolean, val album: String,
)

/**
 * Full-screen slideshow with the time and temperature.
 * Remote: Left / Right = previous / next, OK = pause, Back = home. Touch (or a click in the live view):
 * left / right third = previous / next, middle = pause, and the ← button goes home.
 */
class PhotoFrameActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var openedAt = 0L
    /** +1 next, -1 previous, 0 pause/resume, 9 = show the controls. */
    private val commands = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhotoFrame.showing = true
        openedAt = android.os.SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val p = BedroomPrefs.get(this)
        val opts = FrameOptions(
            secs = p.getInt("frameSecs", 15).coerceIn(5, 600),
            shuffle = p.getBoolean("frameShuffle", true),
            clock = p.getBoolean("frameClock", true),
            weather = p.getBoolean("frameWeather", true),
            zoom = p.getBoolean("frameZoom", true),
            fill = p.getBoolean("frameFill", false),
            album = p.getString("frameAlbum", "").orEmpty(),
        )
        val restMin = p.getInt("frameRest", 60)
        // After a while with nobody around, rest the TV (0 = never).
        if (restMin > 0) handler.postDelayed({
            if (!closed) {
                val app = applicationContext
                close()
                thread { Rest.enter(app) }
            }
        }, restMin * 60_000L)
        startReq.value = intent?.getStringExtra("start")?.let { it to System.nanoTime() }
        setContent { FrameScreen(opts, commands, startReq, onHome = { close() }) }
    }

    /** A photo shared from the phone while the frame is already up: jump to it. */
    private val startReq = androidx.compose.runtime.mutableStateOf<Pair<String, Long>?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("start")?.let { startReq.value = it to System.nanoTime() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        // Ignore the first moment: a press that was already on its way shouldn't do anything.
        if (event.action != KeyEvent.ACTION_UP || android.os.SystemClock.elapsedRealtime() - openedAt < 1500) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> commands.trySend(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> commands.trySend(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> commands.trySend(0)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> commands.trySend(9)
            else -> close() // Back, Home and anything else: back to the home screen
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) close() // something else came on screen
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PhotoFrame.showing = false
        super.onDestroy()
    }

    private fun close() {
        if (closed) return
        closed = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

@Composable
private fun FrameScreen(
    o: FrameOptions,
    commands: kotlinx.coroutines.channels.Channel<Int>,
    startReq: androidx.compose.runtime.State<Pair<String, Long>?>,
    onHome: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pic by remember { mutableStateOf<ImageBitmap?>(null) }
    var order by remember { mutableStateOf<List<File>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var controlsUntil by remember { mutableStateOf(System.currentTimeMillis() + 4000) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val zoom = remember { Animatable(1f) }

    // A photo shared from the phone goes first (once), even if it isn't in the chosen album.
    var pinned by remember { mutableStateOf<File?>(null) }
    suspend fun reload() {
        val l = withContext(Dispatchers.IO) { PhotoStore.list(ctx, o.album) }
        val ordered = if (o.shuffle) l.shuffled() else l
        val pin = pinned
        order = if (pin != null) listOf(pin) + ordered.filter { it != pin } else ordered
        pinned = null
        loaded = true
    }
    LaunchedEffect(startReq.value) {
        val req = startReq.value
        val f = req?.let { withContext(Dispatchers.IO) { PhotoStore.find(ctx, it.first) } }
        if (f != null) {
            pinned = f
            paused = true
            controlsUntil = System.currentTimeMillis() + 4000
        }
        reload()
        index = 0
    }

    // Show the current photo; when its time is up, move on (restarts whenever the photo changes).
    LaunchedEffect(index, order, paused) {
        if (order.isEmpty()) {
            if (loaded) { delay(10_000); reload() }
            return@LaunchedEffect
        }
        val f = order[index.coerceIn(0, order.size - 1)]
        val b = withContext(Dispatchers.IO) { PhotoStore.decode(f, 1920, 1080)?.asImageBitmap() }
        if (b != null) pic = b
        zoom.snapTo(1f)
        if (paused) return@LaunchedEffect
        coroutineScope {
            if (o.zoom) launch { zoom.animateTo(1.07f, tween(o.secs * 1000, easing = LinearEasing)) }
            delay(o.secs * 1000L)
        }
        if (index + 1 >= order.size) { reload(); index = 0 } else index++
    }
    // Remote / touch commands
    LaunchedEffect(Unit) {
        for (c in commands) {
            controlsUntil = System.currentTimeMillis() + 4000
            val n = order.size
            if (n == 0) continue
            when (c) {
                1 -> index = (index + 1) % n
                -1 -> index = (index - 1 + n) % n
                0 -> paused = !paused
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val showControls = now < controlsUntil || paused

    // One tap handler for the whole screen (buttons are drawn on top but the hit-testing lives here, so a
    // recomposition can never swallow a tap): top-left corner = home, otherwise left / middle / right thirds.
    val home by androidx.compose.runtime.rememberUpdatedState(onHome)
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures { pos ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                if (pos.x < w * 0.22f && pos.y < h * 0.2f) { home(); return@detectTapGestures }
                val third = w / 3f
                commands.trySend(if (pos.x < third) -1 else if (pos.x > third * 2) 1 else 0)
            }
        },
    ) {
        Crossfade(targetState = pic, animationSpec = tween(1200), label = "photo") { b ->
            if (b != null) {
                Image(
                    b, null,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                    contentScale = if (o.fill) ContentScale.Crop else ContentScale.Fit,
                )
            }
        }
        if (loaded && order.isEmpty()) {
            Text(
                if (o.album.isBlank()) "No photos yet.\nPut some in the \"TV Photo Frame\" folder on your PC, or send them from the phone app."
                else "The \"${o.album}\" album is empty.\nPick another album in the phone app (Bedroom tab).",
                color = Color(0xFFB8C4D8), fontSize = 26.sp,
                modifier = Modifier.align(Alignment.Center).padding(48.dp),
            )
        }
        val shadow = Shadow(Color(0xCC000000), blurRadius = 12f)
        if (o.clock || o.weather) {
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 40.dp), horizontalAlignment = Alignment.End) {
                if (o.clock) Text(
                    SimpleDateFormat("h:mm", Locale.US).format(Date(now)),
                    style = TextStyle(color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, shadow = shadow),
                )
                val w = remember(now / 600_000) { if (o.weather) WeatherRepo.cached(ctx) else null }
                Text(
                    (if (o.clock) SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(now)) else "") +
                        (w?.let { (if (o.clock) " · " else "") + "${it.temp}°" } ?: ""),
                    style = TextStyle(color = Color(0xFFE8EEF8), fontSize = 22.sp, shadow = shadow),
                )
            }
        }
        // Controls: ← home (top-left), previous / pause / next (bottom), and where you are in the list.
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(tween(600)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                FrameButton("←  Home", Modifier.align(Alignment.TopStart).padding(36.dp)) { onHome() }
                androidx.compose.foundation.layout.Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FrameButton("◀") { commands.trySend(-1) }
                    FrameButton(if (paused) "▶  Play" else "❚❚  Pause") { commands.trySend(0) }
                    FrameButton("▶") { commands.trySend(1) }
                }
                if (order.isNotEmpty()) Text(
                    "${index + 1} / ${order.size}" + if (o.album.isNotBlank()) "  ·  ${o.album}" else "",
                    style = TextStyle(color = Color.White, fontSize = 20.sp, shadow = shadow),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 46.dp, end = 56.dp),
                )
            }
        }
    }
}

@Composable
private fun FrameButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cb by androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { cb() } }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) { Text(label, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)) }
}

// ---------------------------------------------------------------- continue last game

/** RetroArch's own "History" list: the most recent game and the core it was played with. */
object RetroHistory {
    data class Game(val label: String, val path: String, val core: String, val pkg: String)

    @Volatile var last: Game? = null
        private set

    fun refresh(ctx: Context): Game? {
        val pkg = RETROARCH.packages.firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess } ?: return null
        val base = "/sdcard/Android/data/$pkg/files"
        return try {
            val txt = TvShell.run(
                ctx,
                "cat $base/playlists/builtin/content_history.lpl 2>/dev/null || cat $base/playlists/content_history.lpl 2>/dev/null",
            )
            val items = JSONObject(txt.substring(txt.indexOf('{'))).optJSONArray("items") ?: return null
            val it0 = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull {
                it.optString("path").isNotBlank() && it.optString("core_path").let { c -> c.isNotBlank() && c != "DETECT" }
            } ?: return null
            val path = it0.getString("path")
            val label = it0.optString("label").ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }
            Game(label, path, it0.getString("core_path"), pkg).also { last = it }
        } catch (e: Throwable) {
            Log.w(TAG, "RetroArch history unreadable", e)
            null
        }
    }

    fun json(ctx: Context): String {
        val g = refresh(ctx) ?: return JSONObject().put("ok", true).put("game", JSONObject.NULL).toString()
        return JSONObject().put("ok", true).put("game", JSONObject().put("label", g.label).put("path", g.path)).toString()
    }

    /** What the home screen's Continue card shows. shot = a picture of where you were (or box art if none). */
    data class Resume(
        val game: Game,
        val shot: File?,
        val shotTime: Long,     // 0 = box art / title picture, not a picture of your spot
        val ready: Boolean,     // RetroArch saves your spot on quit + picks it up again on start
        val playSecs: Long = 0, // time played (PlayTime)
    )

    private val KEYS = listOf("savestate_auto_save", "savestate_auto_load", "savestate_thumbnail_enable")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun globEsc(s: String) = s.replace(Regex("""([\[\]*?\\])"""), "\\\\$1")

    fun resumeInfo(ctx: Context): Resume? {
        val g = refresh(ctx) ?: return null
        val base = "/sdcard/Android/data/${g.pkg}/files"
        val romPath = g.path.substringBefore('#').replace("/storage/emulated/0/", "/sdcard/")
        val rom = globEsc(romPath.substringAfterLast('/').substringBeforeLast('.'))
        val romDir = romPath.substringBeforeLast('/')
        // RetroArch's box-art file names swap these characters for "_".
        val art = globEsc(g.label.replace(Regex("""[&*/:`<>?\\|"]"""), "_")) + ".png"
        val out = File(ctx.getExternalFilesDir(null), "lastgame.png")
        val script = buildString {
            append("n1=${q("$rom.*png")}; n2=${q("$rom-*.png")}; ")
            append("f=$( { find ${q("$base/states")} ${q("$base/screenshots")} ${q("$base/saves")} /sdcard/RetroArch/states /sdcard/RetroArch/screenshots ")
            append("-maxdepth 3 -type f \\( -iname \"\$n1\" -o -iname \"\$n2\" \\) 2>/dev/null; ")
            append("find ${q(romDir)} -maxdepth 1 -type f \\( -iname \"\$n1\" -o -iname \"\$n2\" \\) 2>/dev/null; } ")
            append("| while IFS= read -r x; do echo \"$(stat -c %Y \"\$x\") \$x\"; done | sort -rn | head -n 1); ")
            append("if [ -z \"\$f\" ]; then f=$(find ${q("$base/thumbnails")} -maxdepth 3 -type f -iname ${q(art)} 2>/dev/null ")
            append("| awk '/Named_Snaps/{print 1, \$0; next} /Named_Titles/{print 2, \$0; next} {print 3, \$0}' | sort -n | head -n 1 ")
            append("| cut -d' ' -f2- | sed 's/^/0 /'); fi; ")
            append("if [ -n \"\$f\" ]; then cp \"\${f#* }\" ${q(out.path.replace("/storage/emulated/0/", "/sdcard/"))} && echo \"SHOT \${f%% *}\"; fi; ")
            append("grep -E '^(${KEYS.joinToString("|")}) ' ${q("$base/retroarch.cfg")}")
        }
        return try {
            val txt = TvShell.run(ctx, script)
            val time = Regex("""SHOT (\d+)""").find(txt)?.groupValues?.get(1)?.toLongOrNull()
            val on = KEYS.all { k -> Regex("""(?m)^$k = "true"""").containsMatchIn(txt) }
            Resume(g, if (time != null && out.exists()) out else null, (time ?: 0L) * 1000L, on, PlayTime.secsFor(ctx, g.path))
        } catch (e: Throwable) {
            Log.w(TAG, "Resume picture lookup failed", e)
            Resume(g, null, 0L, false, PlayTime.secsFor(ctx, g.path))
        }
    }

    /**
     * Turns on RetroArch's "save my spot on quit", "load it on start" and "save a picture with it".
     * Only while RetroArch is closed (it rewrites its settings file when it quits). Keeps a copy of the old file.
     */
    fun enableResume(ctx: Context): String {
        val g = last ?: refresh(ctx) ?: return "No recent game yet"
        val cfg = "/sdcard/Android/data/${g.pkg}/files/retroarch.cfg"
        return try {
            if (TvShell.run(ctx, "pidof ${g.pkg}").trim().isNotEmpty()) return "Close RetroArch first (Quit in its menu), then hold OK again"
            val cmd = buildString {
                append("[ -f ${q(cfg)} ] || exit 3; [ -f ${q("$cfg.bedroom-backup")} ] || cp ${q(cfg)} ${q("$cfg.bedroom-backup")}; ")
                for (k in KEYS) append("if grep -q '^$k ' ${q(cfg)}; then sed -i 's/^$k = .*/$k = \"true\"/' ${q(cfg)}; else echo '$k = \"true\"' >> ${q(cfg)}; fi; ")
                append("echo OK")
            }
            if (TvShell.run(ctx, cmd).contains("OK")) "Done: quit a game from RetroArch's menu and Continue will pick up right there"
            else "Couldn't find RetroArch's settings file"
        } catch (e: Throwable) {
            Log.w(TAG, "enableResume failed", e)
            "Couldn't change RetroArch's settings"
        }
    }

    /** Opens RetroArch straight into the game (the same way game launchers like Daijisho do). */
    fun launch(ctx: Context): String {
        val g = last ?: refresh(ctx) ?: return JSONObject().put("ok", false).put("error", "No recent game in RetroArch's history yet").toString()
        val i = Intent(Intent.ACTION_MAIN)
            .setClassName(g.pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
            .putExtra("ROM", g.path)
            .putExtra("LIBRETRO", g.core)
            .putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/${g.pkg}/files/retroarch.cfg")
            .putExtra("IME", "com.android.inputmethod.latin/.LatinIME")
            .putExtra("DATADIR", "/data/data/${g.pkg}")
            .putExtra("SDCARD", "/storage/emulated/0")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            ctx.startActivity(i)
            JSONObject().put("ok", true).put("done", "Starting ${g.label}").toString()
        } catch (e: Exception) {
            Log.w(TAG, "Continue game failed", e)
            JSONObject().put("ok", false).put("error", "RetroArch wouldn't open the game directly").toString()
        }
    }
}
