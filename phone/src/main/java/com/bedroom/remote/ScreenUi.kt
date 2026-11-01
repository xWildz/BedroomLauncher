package com.bedroom.remote

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SBg = Color(0xFF0B1120)
private val SCard = Color(0xFF18233A)
private val SCard2 = Color(0xFF22304D)
private val SText = Color(0xFFEEF3FB)
private val SSub = Color(0xFF93A4C0)
private val SAccent = Color(0xFF8FB8FF)
private val SGood = Color(0xFF5FD38D)
private val SWarn = Color(0xFFFFB36B)

@Composable
private fun RBtn(label: String, size: Int = 56, main: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (main) SAccent else SCard2).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (main) SBg else SText, fontSize = if (label.length > 2) 12.sp else 18.sp, fontWeight = FontWeight.SemiBold) }
}

/** Live view of the TV screen with a small remote underneath. */
@Composable
fun ScreenViewer(tv: TvClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    var fps by remember { mutableStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var lastFrameAt by remember { mutableLongStateOf(0L) }
    // Video (hardware H.264 from the TV) first; snapshots only if video can't start.
    var videoMode by remember { mutableStateOf(true) }
    var vState by remember { mutableStateOf("connecting") }
    val frames = remember { java.util.concurrent.atomic.AtomicInteger() }
    val packets = remember { java.util.concurrent.atomic.AtomicInteger() }
    var tvFps by remember { mutableStateOf(0f) }
    // 0 = Light 360p (default: easiest on the TV during video), 1 = Smooth 480p, 2 = Sharp 720p.
    var quality by remember { mutableIntStateOf(0) }
    // The TV only sends video when the picture changes. Until the first video frame, show snapshots.
    var gotVideo by remember { mutableStateOf(false) }

    // Smart switch: mirroring a playing video makes the TV draw every frame twice, and it stutters.
    // While a video app is playing full screen, show what's playing (+ a still now and then) instead.
    var videoPlaying by remember { mutableStateOf(false) }
    var forceLive by remember { mutableStateOf(false) }
    val showCard = videoMode && !paused && videoPlaying && !forceLive

    LaunchedEffect(Unit) {
        while (isActive) {
            tv.refreshNowPlaying()
            val np = tv.nowPlaying
            val now = if (np != null && np.playing && np.video) {
                tv.refreshApps()
                tv.apps.firstOrNull { it.front }?.pkg == np.pkg
            } else false
            if (now != videoPlaying) {
                videoPlaying = now
                if (!now) { forceLive = false; gotVideo = false }
            }
            delay(2500)
        }
    }

    // A still picture every 8 s while the card is up (not for paid apps: theirs is always black).
    LaunchedEffect(showCard, tv.nowPlaying?.pkg) {
        if (!showCard) return@LaunchedEffect
        val pkg = tv.nowPlaying?.pkg.orEmpty()
        if (PROTECTED.any { pkg.contains(it) }) { frame = null; return@LaunchedEffect }
        while (isActive) {
            tv.screenFrame(640)?.let { frame = it }
            delay(8000)
        }
    }

    LaunchedEffect(videoMode, gotVideo, paused) {
        if (!videoMode || gotVideo || paused) return@LaunchedEffect
        while (isActive && !gotVideo) {
            tv.screenFrame(960)?.let { frame = it }
            delay(2000)
        }
    }

    LaunchedEffect(videoMode, paused) {
        if (!videoMode || paused) return@LaunchedEffect
        view.keepScreenOn = true
        while (isActive) {
            delay(1000)
            fps = frames.getAndSet(0).toFloat()
            tvFps = packets.getAndSet(0).toFloat()
        }
    }

    LaunchedEffect(paused, videoMode) {
        if (paused || videoMode) return@LaunchedEffect
        view.keepScreenOn = true
        while (isActive) {
            val t0 = System.currentTimeMillis()
            val f = tv.screenFrame(960)
            if (f != null) {
                frame = f
                fails = 0
                val now = System.currentTimeMillis()
                if (lastFrameAt > 0) fps = 1000f / (now - lastFrameAt).coerceAtLeast(1)
                lastFrameAt = now
            } else {
                fails++
            }
            val took = System.currentTimeMillis() - t0
            delay(if (f == null) 1500 else (350 - took).coerceAtLeast(30))
        }
    }

    var full by remember { mutableStateOf(false) }
    val act = LocalContext.current as? Activity
    // Touch: the picture works like the TV's touchscreen (tap, hold, swipe).
    val prefs = remember { act?.getSharedPreferences("screen", android.content.Context.MODE_PRIVATE) }
    var touchOn by remember { mutableStateOf(prefs?.getBoolean("touch", true) ?: true) }
    var touchMark by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // Controls under the picture: 0 = remote, 1 = gamepad (remembered).
    var controls by remember { mutableIntStateOf(prefs?.getInt("controls", 0) ?: 0) }
    fun pickControls(c: Int) {
        controls = c
        prefs?.edit()?.putInt("controls", c)?.apply()
    }
    // The pad lives as long as gamepad mode is on (same pad in portrait and full screen).
    val pad = if (controls == 1) rememberPad(tv) else null

    /** Transparent layer over the picture that turns finger gestures into TV touches. */
    @Composable
    fun TouchLayer() {
        if (!touchOn || (full && pad != null)) return // gamepad on top of the picture: the pad gets the touches
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    val t0 = down.uptimeMillis
                    var end = start
                    var t1 = t0
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        end = c.position
                        t1 = c.uptimeMillis
                        c.consume()
                        if (!c.pressed) break
                    }
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val moved = (end - start).getDistance() > viewConfiguration.touchSlop * 1.5f
                    val kind = when {
                        moved -> "swipe"
                        t1 - t0 > 550 -> "long"
                        else -> "tap"
                    }
                    touchMark = start
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    tv.touch(
                        scope, kind,
                        (start.x / w).coerceIn(0f, 1f), (start.y / h).coerceIn(0f, 1f),
                        (end.x / w).coerceIn(0f, 1f), (end.y / h).coerceIn(0f, 1f),
                        (t1 - t0).toInt(),
                    )
                }
            },
        ) {
            touchMark?.let { m ->
                LaunchedEffect(m) { delay(350); touchMark = null }
                val d = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White.copy(alpha = 0.55f), d, m)
                    drawCircle(SAccent, d, m, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
            }
        }
    }

    @Composable
    fun Picture(modifier: Modifier) {
        if (showCard) {
            PlayingCard(tv, frame, modifier) { forceLive = true; gotVideo = false }
        } else if (videoMode && !paused) {
            Box(modifier, contentAlignment = Alignment.Center) {
                androidx.compose.runtime.key(quality) { LiveVideo(
                    tv, Modifier.fillMaxSize(), quality,
                    onPacket = { packets.incrementAndGet() },
                    onState = { st ->
                        vState = st
                        if (st == "failed") videoMode = false // fall back to snapshots
                    },
                    onFrame = {
                        frames.incrementAndGet()
                        if (!gotVideo) view.post { gotVideo = true }
                    },
                ) }
                // Snapshot on top until real video arrives (a still screen sends no video).
                if (!gotVideo) {
                    frame?.let { Image(it, "TV screen", Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit) }
                }
                TouchLayer()
            }
        } else {
            Box(modifier, contentAlignment = Alignment.Center) {
                frame?.let { Image(it, "TV screen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Text("Waiting for the picture…", color = SSub, fontSize = 13.sp)
                TouchLayer()
            }
        }
    }

    // Full screen: the picture fills the phone sideways. Tap it (or Back) to return.
    DisposableEffect(full) {
        if (full) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (full) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    Dialog(onDismissRequest = { view.keepScreenOn = false; onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (full) {
            BackHandler { gotVideo = false; full = false }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Picture(Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                if (pad != null) OverlayPad(pad)
                Row(
                    Modifier.align(if (pad != null) Alignment.TopCenter else Alignment.TopEnd).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .clickable { pickControls(1 - controls) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(if (pad != null) "Hide gamepad" else "🎮 Gamepad", color = SText, fontSize = 12.sp) }
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .clickable { gotVideo = false; full = false }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("✕ Exit full screen", color = SText, fontSize = 12.sp) }
                }
            }
            return@Dialog
        }
        Column(
            Modifier.fillMaxSize().background(SBg).statusBarsPadding().navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 72.dp), // keep clear of the phone's own Back/Home bar
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TV screen", color = SText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            paused -> "Paused"
                            showCard -> "Video playing · live view eased off so the TV stays smooth"
                            videoMode && vState != "playing" -> "Starting video…"
                            videoMode && !gotVideo -> "Live video ready · updates when the picture changes"
                            videoMode -> "Live · TV sends %.0f fps · showing %.0f fps".format(tvFps, fps)
                            fails >= 3 -> "Can't get the picture. Is \"Allow full remote\" on?"
                            frame == null -> "Connecting…"
                            else -> "Snapshots · %.1f frames/s".format(fps)
                        },
                        color = if (!videoMode && fails >= 3) SWarn else if (paused) SSub else SGood, fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (videoMode) {
                            Text(
                                "Quality: " + listOf("Light 360p", "Smooth 480p", "Sharp 720p")[quality] + " ▾",
                                color = SAccent, fontSize = 11.sp,
                                modifier = Modifier.clickable { quality = (quality + 1) % 3; gotVideo = false; vState = "connecting" },
                            )
                        }
                        Text(
                            if (touchOn) "Touch: on" else "Touch: off",
                            color = SAccent, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                touchOn = !touchOn
                                prefs?.edit()?.putBoolean("touch", touchOn)?.apply()
                            },
                        )
                        Text(
                            if (videoMode) "Snapshots" else "Try video again",
                            color = SAccent, fontSize = 11.sp,
                            modifier = Modifier.clickable { videoMode = !videoMode; vState = "connecting"; fps = 0f; gotVideo = false },
                        )
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(SCard).clickable { paused = !paused }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(if (paused) "Resume" else "Pause", color = SText, fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(SCard).clickable { view.keepScreenOn = false; onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = SSub, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(10.dp))
            // Edge-to-edge picture; tap for full screen.
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Picture(Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).clickable { gotVideo = false; full = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text("⤢ Full screen", color = SText, fontSize = 12.sp) }
            }
            Text(
                "Tap, hold or swipe the picture to touch the TV. Paid video apps (Hulu, Netflix…) show black: that's their copy protection.",
                color = SSub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
            )

            // Remote or gamepad, right under the picture
            Spacer(Modifier.height(12.dp))
            SegmentRow(listOf("Remote", "Gamepad"), controls, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { pickControls(it) }
            Spacer(Modifier.height(14.dp))
            if (pad != null) {
                PortraitPad(tv, pad, Modifier.padding(horizontal = 8.dp))
                Text(
                    "Tip: Full screen puts the gamepad over the picture.",
                    color = SSub, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                RBtn("▲") { tv.key(scope, "up") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RBtn("◀") { tv.key(scope, "left") }
                    RBtn("OK", size = 66, main = true) { tv.key(scope, "ok") }
                    RBtn("▶") { tv.key(scope, "right") }
                }
                RBtn("▼") { tv.key(scope, "down") }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RBtn("Back", size = 56) { tv.key(scope, "back") }
                    RBtn("Home", size = 56) { tv.key(scope, "home") }
                    RBtn("⏯", size = 56) { tv.send(scope, "playpause") }
                    RBtn("Vol−", size = 56) { tv.send(scope, "voldown") }
                    RBtn("Vol+", size = 56) { tv.send(scope, "volup") }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Paid apps whose picture is always black in captures (copy protection): no point taking stills. */
private val PROTECTED = listOf("hulu", "netflix", "disney", "hbo", "wbd", "primevideo", "amazon", "peacock", "paramount", "cbs")

/** Shown instead of live video while a show plays: title, art, controls, and a still of the screen. */
@Composable
private fun PlayingCard(tv: TvClient, still: ImageBitmap?, modifier: Modifier, onLive: () -> Unit) {
    val scope = rememberCoroutineScope()
    val m = tv.nowPlaying
    val art by androidx.compose.runtime.produceState<ImageBitmap?>(null, m?.pkg, m?.artKey) {
        value = if (m?.artKey != null) tv.image("art", "pkg" to m.pkg, "key" to m.artKey) else null
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val bg = still ?: art
        bg?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = if (still != null) ContentScale.Fit else ContentScale.Crop, alpha = 0.45f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            Text(m?.app.orEmpty(), color = SSub, fontSize = 12.sp)
            Text(
                m?.title?.ifBlank { "Playing" } ?: "Playing", color = SText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!m?.artist.isNullOrBlank()) Text(m!!.artist, color = SSub, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RBtn("-10", size = 48) { tv.media(scope, "rw", m?.pkg) }
                RBtn(if (m?.state == "playing") "❚❚" else "▶", size = 56, main = true) { tv.media(scope, "toggle", m?.pkg) }
                RBtn("+30", size = 48) { tv.media(scope, "ff", m?.pkg) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Show live video anyway", color = SAccent, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onLive() }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** The TV's live video on a SurfaceView (hardware decoded). */
@Composable
private fun LiveVideo(
    tv: TvClient,
    modifier: Modifier,
    quality: Int,
    onPacket: () -> Unit,
    onState: (String) -> Unit,
    onFrame: () -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    var player: VideoPlayer? = null
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        player = VideoPlayer(
                            tv, h.surface,
                            width = intArrayOf(640, 848, 1280)[quality],
                            height = intArrayOf(352, 480, 720)[quality],
                            bitrate = intArrayOf(1_000_000, 2_000_000, 3_000_000)[quality],
                            onState = { st -> post { onState(st) } },
                            onFrame = onFrame,
                            onPacket = onPacket,
                        ).also { it.start() }
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                        player?.stop()
                        player = null
                    }
                })
            }
        },
    )
}
