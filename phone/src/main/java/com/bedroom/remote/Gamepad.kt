package com.bedroom.remote

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot

/** Button bits (match the TV's virtual gamepad: bit n = HID button n+1). */
object Pad {
    const val A = 1 shl 0       // BTN_A
    const val B = 1 shl 1       // BTN_B
    const val X = 1 shl 3       // BTN_X
    const val Y = 1 shl 4       // BTN_Y
    const val L = 1 shl 6       // BTN_TL
    const val R = 1 shl 7       // BTN_TR
    const val L2 = 1 shl 8      // BTN_TL2
    const val R2 = 1 shl 9      // BTN_TR2
    const val SELECT = 1 shl 10 // BTN_SELECT
    const val START = 1 shl 11  // BTN_START
    const val MENU = 1 shl 12   // BTN_MODE (bind as RetroArch "Menu toggle")
}

/** Sends the pad state to the TV over UDP (signed) on every change, plus a heartbeat. */
class GamepadSender(private val tv: TvClient) {
    /** 1 or 2. Player 2 shows up on the TV as a second controller ("Bedroom Phone Gamepad 2"). */
    @Volatile var player = 1
    private val io = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    private var seq = System.currentTimeMillis()
    @Volatile private var buttons = 0
    @Volatile private var hat = 8

    fun update(newButtons: Int, newHat: Int) {
        buttons = newButtons
        hat = newHat
        send()
    }

    fun send() {
        val host = tv.host
        val key = tv.key
        if (host.isBlank() || key.isBlank()) return
        // Signed with the pairing key (the key itself is never sent); the TV ignores old or unsigned packets.
        val body = if (player == 1) "BGP2 ${++seq} ${"%04x".format(buttons)} $hat"
        else "BGP3 $player ${++seq} ${"%04x".format(buttons)} $hat"
        val mac = Signer.hex(Signer.mac(key).doFinal(body.toByteArray(Charsets.US_ASCII))).take(32)
        val msg = "$body $mac".toByteArray(Charsets.US_ASCII)
        io.execute {
            try {
                val s = socket ?: DatagramSocket().also { socket = it }
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), 8766))
            } catch (e: Exception) {
                socket = null
            }
        }
    }

    fun releaseAll() = update(0, 8)
}

// Colors (same family as the rest of the app)
private val PadBg = Color(0xFF070B16)
private val BtnBase = Color(0xFF1B2741)
private val BtnDown = Color(0xFF3A5285)
private val Label = Color(0xFFEEF3FB)
private val Faint = Color(0xFF93A4C0)

@Composable
fun GamepadScreen(tv: TvClient, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val sender = remember { GamepadSender(tv) }
    val prefs = remember { ctx.getSharedPreferences("pad", android.content.Context.MODE_PRIVATE) }
    var player by remember { mutableIntStateOf(prefs.getInt("player", 1)) }
    sender.player = player
    val scope = rememberCoroutineScope()
    var buttons by remember { mutableIntStateOf(0) }
    var hat by remember { mutableIntStateOf(8) }

    // Landscape + screen stays on while the gamepad is open; back to portrait after.
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        // Full screen: hide the status bar and navigation bar (swipe from an edge to peek at them).
        val bars = act?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            sender.releaseAll()
            view.keepScreenOn = false
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    BackHandler { /* ignore edge swipes; use the Exit button */ }

    // Heartbeat: keeps the TV's virtual pad alive and makes it appear as soon as this screen opens.
    LaunchedEffect(Unit) {
        while (isActive) {
            sender.send()
            delay(250)
        }
    }

    fun setButton(bit: Int, down: Boolean) {
        val next = if (down) buttons or bit else buttons and bit.inv()
        if (next != buttons) {
            buttons = next
            if (down) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sender.update(buttons, hat)
        }
    }

    fun setHat(h: Int) {
        if (h != hat) {
            if (h != 8) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hat = h
            sender.update(buttons, hat)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PadBg)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        val small = maxHeight < 340.dp
        val dpad = if (small) 170.dp else 210.dp
        val face = if (small) 58.dp else 70.dp

        // Shoulder buttons
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("L2", Pad.L2, ::setButton, Modifier.size(72.dp, 40.dp), RoundedCornerShape(12.dp))
            PadButton("L", Pad.L, ::setButton, Modifier.size(96.dp, 40.dp), RoundedCornerShape(12.dp))
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("R", Pad.R, ::setButton, Modifier.size(96.dp, 40.dp), RoundedCornerShape(12.dp))
            PadButton("R2", Pad.R2, ::setButton, Modifier.size(72.dp, 40.dp), RoundedCornerShape(12.dp))
        }

        // D-pad (slide your thumb between directions; diagonals work)
        DPad(
            size = dpad,
            hat = hat,
            onHat = ::setHat,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(y = 18.dp)
                .systemGestureExclusion(),
        )

        // Face buttons: X top, Y left, B right, A bottom
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(y = 18.dp)
                .size(face * 3)
                .systemGestureExclusion(),
        ) {
            PadButton("X", Pad.X, ::setButton, Modifier.align(Alignment.TopCenter).size(face), CircleShape, Color(0xFF8FB8FF))
            PadButton("Y", Pad.Y, ::setButton, Modifier.align(Alignment.CenterStart).size(face), CircleShape, Color(0xFF7BE0A0))
            PadButton("A", Pad.A, ::setButton, Modifier.align(Alignment.BottomCenter).size(face), CircleShape, Color(0xFFFF8A80))
            PadButton("B", Pad.B, ::setButton, Modifier.align(Alignment.CenterEnd).size(face), CircleShape, Color(0xFFFFD27A))
        }

        // Middle: Select / Menu / Start, and the exit button
        Column(
            Modifier.align(Alignment.Center).offset(y = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PadButton("Select", Pad.SELECT, ::setButton, Modifier.size(88.dp, 36.dp), RoundedCornerShape(18.dp))
                PadButton("Start", Pad.START, ::setButton, Modifier.size(88.dp, 36.dp), RoundedCornerShape(18.dp))
            }
            Spacer(Modifier.height(12.dp))
            PadButton("Menu", Pad.MENU, ::setButton, Modifier.size(88.dp, 36.dp), RoundedCornerShape(18.dp), Faint)
        }
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TapPill("▶ RetroArch", Color(0xFF7BE0A0)) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tv.send(scope, "open", "pkg" to "com.retroarch")
                }
                TapPill("▶ Continue", Color(0xFFB9A8FF)) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tv.send(scope, "continuegame")
                }
                TapPill("Player $player", if (player == 1) Color(0xFFFF8A80) else Color(0xFF8FB8FF)) {
                    // Release everything on the old pad before switching.
                    sender.releaseAll()
                    player = if (player == 1) 2 else 1
                    sender.player = player
                    prefs.edit().putInt("player", player).apply()
                }
                TapPill("✕ Exit", Faint) { onExit() }
                TapPill("⌂ TV Home", Color(0xFF8FB8FF)) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tv.send(scope, "home")
                }
            }
            Text(
                gamepadNote(tv, player),
                color = Faint, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun gamepadNote(tv: TvClient, player: Int): String {
    val s = tv.status ?: return if (tv.online == false) "Can't reach the TV" else "Connecting…"
    val st = if (player == 2) s.gamepad2 else s.gamepad
    return when {
        st == "ready" -> "Connected as Player $player"
        st == "starting" -> "Starting the gamepad on the TV…"
        st == "off" -> "Player $player · press any button to connect"
        st.startsWith("error") -> "TV: $st (press Allow full remote on the TV)"
        else -> st
    }
}

/** Small tap-on-release button for the top row. */
@Composable
private fun TapPill(label: String, color: Color, onTap: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(BtnBase)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) onTap()
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = color, fontSize = 13.sp) }
}

@Composable
internal fun PadButton(
    label: String,
    bit: Int,
    onChange: (Int, Boolean) -> Unit,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    accent: Color = Label,
) {
    var down by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .clip(shape)
            .background(if (down > 0) BtnDown else BtnBase)
            .border(1.dp, accent.copy(alpha = if (down > 0) 0.9f else 0.35f), shape)
            .pointerInput(bit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = 1
                    onChange(bit, true)
                    waitForUpOrCancellation()
                    down = 0
                    onChange(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontSize = if (label.length > 2) 13.sp else 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 8-way D-pad: hat 0=up, 1=up-right, 2=right ... 7=up-left, 8=centered. */
@Composable
internal fun DPad(size: Dp, hat: Int, onHat: (Int) -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF0E1628))
            .border(1.dp, Color(0xFF22304D), CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val p = ev.changes.firstOrNull { it.pressed }
                        if (p == null) {
                            onHat(8)
                        } else {
                            val cx = this.size.width / 2f
                            val cy = this.size.height / 2f
                            val dx = p.position.x - cx
                            val dy = p.position.y - cy
                            if (hypot(dx, dy) < this.size.width * 0.12f) {
                                onHat(8)
                            } else {
                                // 0 deg = up, clockwise; 8 sectors of 45 deg
                                var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
                                if (deg < 0) deg += 360.0
                                onHat((((deg + 22.5) / 45.0).toInt()) % 8)
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val arm = size / 3
        val lit = { dir: Int -> hat != 8 && (hat == dir || hat == (dir + 1) % 8 || hat == (dir + 7) % 8) }
        DirArm("▲", lit(0), Modifier.align(Alignment.TopCenter).size(arm))
        DirArm("▶", lit(2), Modifier.align(Alignment.CenterEnd).size(arm))
        DirArm("▼", lit(4), Modifier.align(Alignment.BottomCenter).size(arm))
        DirArm("◀", lit(6), Modifier.align(Alignment.CenterStart).size(arm))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(arm)
                .background(BtnBase),
        )
    }
}

@Composable
private fun DirArm(glyph: String, lit: Boolean, modifier: Modifier) {
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (lit) BtnDown else BtnBase),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (lit) Label else Faint, fontSize = 20.sp)
    }
}

// ---------------------------------------------------------------- gamepad inside the live view

/** Pad state + sender, shared by the live view's portrait and full-screen layouts. */
class PadController(val sender: GamepadSender, private val view: android.view.View, private val prefs: android.content.SharedPreferences) {
    var buttons by mutableIntStateOf(0)
        private set
    var hat by mutableIntStateOf(8)
        private set
    var player by mutableIntStateOf(prefs.getInt("player", 1))
        private set

    init { sender.player = player }

    fun press(bit: Int, down: Boolean) {
        val next = if (down) buttons or bit else buttons and bit.inv()
        if (next != buttons) {
            buttons = next
            if (down) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sender.update(buttons, hat)
        }
    }

    fun moveHat(h: Int) {
        if (h != hat) {
            if (h != 8) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hat = h
            sender.update(buttons, hat)
        }
    }

    fun switchPlayer() {
        sender.releaseAll()
        buttons = 0; hat = 8
        player = if (player == 1) 2 else 1
        sender.player = player
        prefs.edit().putInt("player", player).apply()
    }
}

@Composable
fun rememberPad(tv: TvClient): PadController {
    val ctx = LocalContext.current
    val view = LocalView.current
    val pad = remember {
        PadController(GamepadSender(tv), view, ctx.getSharedPreferences("pad", android.content.Context.MODE_PRIVATE))
    }
    // Heartbeat: keeps the TV's virtual pad alive while it's on screen.
    LaunchedEffect(Unit) {
        while (isActive) {
            pad.sender.send()
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { pad.sender.releaseAll() } }
    return pad
}

/** Compact pad for under the live picture (portrait). */
@Composable
fun PortraitPad(tv: TvClient, pad: PadController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            PadButton("L2", Pad.L2, pad::press, Modifier.size(58.dp, 34.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            PadButton("L", Pad.L, pad::press, Modifier.size(72.dp, 34.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.weight(1f))
            PadButton("R", Pad.R, pad::press, Modifier.size(72.dp, 34.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            PadButton("R2", Pad.R2, pad::press, Modifier.size(58.dp, 34.dp), RoundedCornerShape(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DPad(146.dp, pad.hat, pad::moveHat, Modifier.systemGestureExclusion())
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("Select", Pad.SELECT, pad::press, Modifier.size(64.dp, 30.dp), RoundedCornerShape(15.dp))
                PadButton("Start", Pad.START, pad::press, Modifier.size(64.dp, 30.dp), RoundedCornerShape(15.dp))
                PadButton("Menu", Pad.MENU, pad::press, Modifier.size(64.dp, 30.dp), RoundedCornerShape(15.dp), Faint)
            }
            Spacer(Modifier.weight(1f))
            FaceButtons(48.dp, pad, Modifier.systemGestureExclusion())
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TapPill("Player ${pad.player}", if (pad.player == 1) Color(0xFFFF8A80) else Color(0xFF8FB8FF)) { pad.switchPlayer() }
            Spacer(Modifier.width(10.dp))
            Text(gamepadNote(tv, pad.player), color = Faint, fontSize = 11.sp)
        }
    }
}

/** See-through pad laid over the full-screen picture (landscape). */
@Composable
fun OverlayPad(pad: PadController, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().alpha(0.72f).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("L2", Pad.L2, pad::press, Modifier.size(62.dp, 36.dp), RoundedCornerShape(12.dp))
            PadButton("L", Pad.L, pad::press, Modifier.size(80.dp, 36.dp), RoundedCornerShape(12.dp))
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("R", Pad.R, pad::press, Modifier.size(80.dp, 36.dp), RoundedCornerShape(12.dp))
            PadButton("R2", Pad.R2, pad::press, Modifier.size(62.dp, 36.dp), RoundedCornerShape(12.dp))
        }
        DPad(156.dp, pad.hat, pad::moveHat, Modifier.align(Alignment.BottomStart).systemGestureExclusion())
        FaceButtons(52.dp, pad, Modifier.align(Alignment.BottomEnd).systemGestureExclusion())
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("Select", Pad.SELECT, pad::press, Modifier.size(70.dp, 30.dp), RoundedCornerShape(15.dp))
            PadButton("Menu", Pad.MENU, pad::press, Modifier.size(62.dp, 30.dp), RoundedCornerShape(15.dp), Faint)
            PadButton("Start", Pad.START, pad::press, Modifier.size(70.dp, 30.dp), RoundedCornerShape(15.dp))
        }
    }
}

/** X top, Y left, B right, A bottom (same as the full gamepad). */
@Composable
private fun FaceButtons(face: Dp, pad: PadController, modifier: Modifier) {
    Box(modifier.size(face * 3)) {
        PadButton("X", Pad.X, pad::press, Modifier.align(Alignment.TopCenter).size(face), CircleShape, Color(0xFF8FB8FF))
        PadButton("Y", Pad.Y, pad::press, Modifier.align(Alignment.CenterStart).size(face), CircleShape, Color(0xFF7BE0A0))
        PadButton("A", Pad.A, pad::press, Modifier.align(Alignment.BottomCenter).size(face), CircleShape, Color(0xFFFF8A80))
        PadButton("B", Pad.B, pad::press, Modifier.align(Alignment.CenterEnd).size(face), CircleShape, Color(0xFFFFD27A))
    }
}
