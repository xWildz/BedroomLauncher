package com.bedroom.remote

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Same palette family as Bedroom Home on the TV.
private val Bg = Color(0xFF0B1120)
private val Card = Color(0xFF18233A)
private val CardPressed = Color(0xFF2A3A5C)
private val TextC = Color(0xFFEEF3FB)
private val Sub = Color(0xFF93A4C0)
private val Accent = Color(0xFF8FB8FF)
private val Gold = Color(0xFFFFD27A)
private val Warn = Color(0xFFFFB36B)
private val Good = Color(0xFF5FD38D)
private val BedNight = Color(0xFFB9A8FF)

private enum class Tab { Remote, Gamepad, Apps, Saves, Bedroom }

@Composable
fun RemoteApp(tv: TvClient) {
    val scheme = darkColorScheme(
        primary = Accent, onPrimary = Bg, background = Bg, surface = Bg,
        surfaceContainer = Color(0xFF101829), onSurface = TextC, onSurfaceVariant = Sub,
        secondaryContainer = Color(0xFF22304D),
    )
    MaterialTheme(colorScheme = scheme) {
        var tab by remember { mutableStateOf(Tab.Remote) }
        var showSettings by remember { mutableStateOf(!tv.configured) }
        var showKeyboard by remember { mutableStateOf(false) }

        // Poll the TV while the app is open.
        LaunchedEffect(tv.host, tv.key) {
            tv.refreshApps()
            while (isActive) {
                tv.refreshStatus()
                // Check more often while the TV isn't answering, so it reconnects quickly.
                delay(if (tv.online == false) 2000 else 4000)
            }
        }

        if (tab == Tab.Gamepad) {
            GamepadScreen(tv) { tab = Tab.Remote }
        } else Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF101829)) {
                    NavigationBarItem(
                        selected = tab == Tab.Remote,
                        onClick = { tab = Tab.Remote },
                        icon = { DpadGlyph(if (tab == Tab.Remote) Accent else Sub) },
                        label = { Text("Remote") },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF22304D)),
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { tab = Tab.Gamepad },
                        icon = { PadGlyph(Sub) },
                        label = { Text("Gamepad") },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF22304D)),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Apps,
                        onClick = { tab = Tab.Apps },
                        icon = { GridGlyph(if (tab == Tab.Apps) Accent else Sub) },
                        label = { Text("Apps & Sleep") },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF22304D)),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Saves,
                        onClick = { tab = Tab.Saves },
                        icon = { SaveGlyph(if (tab == Tab.Saves) Accent else Sub) },
                        label = { Text("Games") },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF22304D)),
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Bedroom,
                        onClick = { tab = Tab.Bedroom },
                        icon = { MoonGlyph(if (tab == Tab.Bedroom) BedNight else Sub) },
                        label = { Text("Bedroom") },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF2A2350)),
                    )
                }
            },
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 20.dp),
            ) {
                Header(tv, onSettings = { showSettings = true })
                when (tab) {
                    Tab.Remote -> RemoteTab(tv, onKeyboard = { showKeyboard = true })
                    Tab.Apps -> LiveAppsTab(tv) { SleepSection(tv) }
                    Tab.Gamepad -> Unit
                    Tab.Saves -> GamesTab(tv)
                    Tab.Bedroom -> BedroomTab(tv)
                }
            }
        }

        if (showSettings) SettingsDialog(tv) { showSettings = false }
        if (showKeyboard) KeyboardDialog(tv) { showKeyboard = false }
    }
}

@Composable
private fun Header(tv: TvClient, onSettings: () -> Unit) {
    val (dot, line) = when {
        tv.tampered -> Warn to "Warning: a fake reply was blocked. Something on your Wi-Fi may be interfering."
        !tv.configured -> Warn to "Not paired yet"
        tv.badKey -> Warn to "Not accepted. Scan the QR code on the TV's Phone screen again."
        tv.online == false -> Warn to "Can't reach the TV"
        tv.online == true -> {
            val s = tv.status
            val sleep = if (s?.resting == true) "Resting" else s?.sleepMinutesLeft?.let { "Rest in $it min" } ?: "Connected"
            Good to (sleep + (s?.let { " · Vol ${it.volume}/${it.maxVolume}" + if (it.muted) " (muted)" else "" } ?: ""))
        }
        else -> Sub to "Connecting…"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Bedroom TV", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dot, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(line, color = Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var reconnecting by remember { mutableStateOf(false) }
        PressButton(
            onClick = {
                if (!reconnecting) {
                    reconnecting = true
                    scope.launch {
                        tv.reconnect()
                        TvNotification.show(ctx.applicationContext)
                        reconnecting = false
                    }
                }
            },
            modifier = Modifier.height(44.dp),
            shape = RoundedCornerShape(22.dp),
        ) {
            Text(
                if (reconnecting) "Connecting…" else "↻ Reconnect",
                color = if (tv.online == false) Warn else Accent,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        val resting = tv.status?.resting == true
        PressButton(
            onClick = { scope.launch { tv.setRest(!resting) } },
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            base = if (resting) Accent else Card,
        ) { PowerGlyph(if (resting) Bg else Warn) }
        Spacer(Modifier.width(8.dp))
        PressButton(onClick = onSettings, modifier = Modifier.size(44.dp), shape = CircleShape) {
            GearGlyph(Sub)
        }
    }
}

// ---------------------------------------------------------------- Remote tab

@Composable
private fun RemoteTab(tv: TvClient, onKeyboard: () -> Unit) {
    val scope = rememberCoroutineScope()
    var watching by remember { mutableStateOf(false) }
    if (watching) ScreenViewer(tv) { watching = false }
    val keysNote = tv.status?.keys
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tv.online == true && keysNote != null && keysNote != "ok" && keysNote != "idle") {
            Text(
                if (keysNote.startsWith("waiting")) "Accept \"Allow USB debugging?\" on the TV (Always allow) to enable the arrows."
                else "Arrows: $keysNote",
                color = Warn, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        if (tv.status?.resting == true) {
            PressButton(
                onClick = { scope.launch { tv.setRest(false) } },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                base = Accent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PowerGlyph(Bg)
                    Spacer(Modifier.width(10.dp))
                    Text("TV is resting · tap to wake", color = Bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Hero: the D-pad and the three everyday buttons
        HeroCard(Ui.HeroBlue) {
            Dpad(
                onKey = { tv.key(scope, it) },
                modifier = Modifier.size(260.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundAction("Back", Modifier.weight(1f)) { tv.key(scope, "back") }
                RoundAction("Home", Modifier.weight(1f)) { tv.key(scope, "home") }
                RoundAction("Keyboard", Modifier.weight(1f)) { onKeyboard() }
            }
        }

        // ---- Playback & sound
        Spacer(Modifier.height(14.dp))
        DashCard(
            "Playback & sound", "▶", Ui.Pink,
            subtitle = tv.status?.let { "Volume ${it.volume}/${it.maxVolume}" + if (it.muted) " · muted" else "" },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PressButton(onClick = { tv.send(scope, "prev") }, modifier = Modifier.weight(1f).height(56.dp), base = Ui.Card2) { SkipGlyph(TextC, forward = false) }
                PressButton(onClick = { tv.send(scope, "playpause") }, modifier = Modifier.weight(1.3f).height(56.dp), base = Color(0xFF3A2440), pressed = Color(0xFF55304F)) { PlayPauseGlyph(Color(0xFFFF8AB0)) }
                PressButton(onClick = { tv.send(scope, "next") }, modifier = Modifier.weight(1f).height(56.dp), base = Ui.Card2) { SkipGlyph(TextC, forward = true) }
            }
            Spacer(Modifier.height(10.dp))
            // Volume rocker
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Ui.Card2),
            ) {
                PressButton(onClick = { tv.send(scope, "voldown") }, repeat = true, modifier = Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp), base = Ui.Card2) {
                    Text("−", color = TextC, fontSize = 30.sp)
                }
                PressButton(onClick = { tv.send(scope, "mute") }, modifier = Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp), base = Ui.Card2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpeakerGlyph(if (tv.status?.muted == true) Warn else Sub)
                        Spacer(Modifier.width(6.dp))
                        Text(if (tv.status?.muted == true) "Muted" else "Mute", color = Sub, fontSize = 14.sp)
                    }
                }
                PressButton(onClick = { tv.send(scope, "volup") }, repeat = true, modifier = Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp), base = Ui.Card2) {
                    Text("+", color = TextC, fontSize = 28.sp)
                }
            }
            Text(
                "Your phone's volume buttons control the TV while this app is open.",
                color = Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }

        // ---- Screen & search
        Spacer(Modifier.height(14.dp))
        // Voice search: speak, and the words are typed into the TV's search.
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (said.isNotEmpty()) scope.launch {
                tv.key(this, "search")
                delay(1800)
                tv.send(this, "text", "t" to said.take(200))
                delay(600)
                tv.key(this, "enter")
            }
        }
        val vctx = LocalContext.current
        val screenOff = tv.status?.screenOff == true
        DashCard("Screen & search", "☀", Ui.Gold) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardAction("📺  Live view", Modifier.weight(1f)) { watching = true }
                CardAction("🎤  Voice search", Modifier.weight(1f)) {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search the TV")
                    try {
                        voice.launch(i)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(vctx, "No voice typing on this phone", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            CardAction(
                if (screenOff) "☀  Screen back on" else "🌙  Screen off (sound stays on)",
                Modifier.fillMaxWidth(),
                color = if (screenOff) Gold else TextC,
            ) { scope.launch { tv.setScreen(!screenOff) } }
            Spacer(Modifier.height(10.dp))
            BrightnessSlider(tv, dark = false)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CardAction(label: String, modifier: Modifier, color: Color = TextC, onClick: () -> Unit) {
    PressButton(onClick = onClick, modifier = modifier.height(50.dp), shape = RoundedCornerShape(25.dp), base = Ui.Card2) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Circular D-pad with an OK button in the middle. Arrows repeat while held. */
@Composable
private fun Dpad(onKey: (String) -> Unit, modifier: Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Color(0xFF0D1528))
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val arrow = 76.dp
        PressButton({ onKey("up") }, Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(arrow), CircleShape, repeat = true, base = Color.Transparent) { ArrowGlyph(TextC, 0f) }
        PressButton({ onKey("down") }, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(arrow), CircleShape, repeat = true, base = Color.Transparent) { ArrowGlyph(TextC, 180f) }
        PressButton({ onKey("left") }, Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(arrow), CircleShape, repeat = true, base = Color.Transparent) { ArrowGlyph(TextC, 270f) }
        PressButton({ onKey("right") }, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(arrow), CircleShape, repeat = true, base = Color.Transparent) { ArrowGlyph(TextC, 90f) }
        PressButton({ onKey("ok") }, Modifier.size(104.dp), CircleShape, base = Color(0xFF22304D), pressed = Color(0xFF34476F)) {
            Text("OK", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RoundAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    PressButton(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(26.dp), base = Color.White.copy(alpha = 0.07f), pressed = Color.White.copy(alpha = 0.16f)) {
        Text(label, color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- Apps & sleep tab

@Composable
private fun SleepSection(tv: TvClient) {
    val scope = rememberCoroutineScope()
    var confirmOff by remember { mutableStateOf(false) }
    val s = tv.status

    DashCard(
        "Sleep timer", "☾", Ui.Purple,
        subtitle = s?.sleepMinutesLeft?.let { "The TV rests in $it min" } ?: "Rest the TV after a while",
    ) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val choices = listOf(0 to "Off", 30 to "30 min", 60 to "60 min", 90 to "90 min", 120 to "2 hours")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            choices.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (min, label) ->
                        val on = if (s?.sleepMinutesLeft == null) min == 0 else min == s?.lastChoice
                        PressButton(
                            onClick = {
                                tv.send(scope, "sleep", "min" to min.toString())
                                scope.launch { delay(500); tv.refreshStatus() }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            base = if (on) Accent else Ui.Card2,
                        ) {
                            Text(label, color = if (on) Bg else TextC, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val resting = s?.resting == true
        PressButton(
            onClick = { if (resting) { scope.launch { tv.setRest(false) } } else { confirmOff = true } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            base = Ui.Card2,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (resting) PowerGlyph(Gold) else MoonGlyph(Warn)
                Spacer(Modifier.width(8.dp))
                Text(if (resting) "Wake the TV" else "Rest the TV now", color = if (resting) Gold else Warn, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(
            "Resting looks off (paused, muted, screen dark) but the TV stays online, so this app can wake it. " +
                "The Power button on the TV's own remote still turns it fully off, and then only that remote can turn it on.",
            color = Sub, fontSize = 12.sp,
        )
    }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Rest the TV?") },
            text = { Text("Playback pauses, the sound mutes and the screen goes dark. Wake it from this app or with any button on the TV remote.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { tv.setRest(true) }; confirmOff = false }) { Text("Rest") }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
            containerColor = Color(0xFF141E33),
        )
    }
}

// ---------------------------------------------------------------- Dialogs

@Composable
private fun SettingsDialog(tv: TvClient, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var host by remember { mutableStateOf(tv.host) }
    var key by remember { mutableStateOf("") } // never shown back on screen
    var error by remember { mutableStateOf<String?>(null) }
    var notif by remember { mutableStateOf(TvNotification.enabled(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to your TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Easiest: on the TV press Phone, scan the code with your camera, then tap \"Open in the TV Remote app\".\n\nOr type it in:",
                    color = Sub, fontSize = 14.sp,
                )
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("TV address (e.g. 192.168.1.50)") }, singleLine = true)
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = null }, singleLine = true,
                    label = { Text(if (tv.key.isNotBlank()) "New key (leave empty to keep the saved one)" else "Key (the k=… part of the link)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                error?.let { Text(it, color = Warn, fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TV controls in notifications", color = TextC, fontSize = 15.sp)
                        Text("Volume and play/pause with the app closed", color = Sub, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(
                        checked = notif,
                        onCheckedChange = {
                            notif = it
                            TvNotification.setEnabled(ctx, it)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newKey = key.ifBlank { tv.key }
                    if (tv.save(host, newKey)) {
                        TvNotification.show(ctx)
                        onDismiss()
                    } else {
                        error = "That doesn't look right. The address must be on your home network (like 192.168.1.50) " +
                            "and the key is 32 letters/numbers. Scanning the TV's QR code is easiest."
                    }
                },
                enabled = host.isNotBlank() && (key.isNotBlank() || tv.key.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Color(0xFF141E33),
    )
}

@Composable
private fun KeyboardDialog(tv: TvClient, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sentCount by remember { mutableIntStateOf(0) }
    fun sendText() {
        if (text.isNotEmpty()) {
            tv.send(scope, "text", "t" to text)
            text = ""
            sentCount++
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on the TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select a search box on the TV first, then type here.", color = Sub, fontSize = 14.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(if (sentCount > 0) "Sent - type more" else "Text") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PressButton(onClick = { tv.key(scope, "backspace") }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp), repeat = true) {
                        Text("⌫ Delete", color = TextC)
                    }
                    PressButton(onClick = { sendText(); tv.key(scope, "enter") }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(24.dp)) {
                        Text("Search ↵", color = TextC)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { sendText() }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = Color(0xFF141E33),
    )
}

// ---------------------------------------------------------------- Press button (haptics, repeat, press state)

@Composable
private fun PressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    repeat: Boolean = false,
    base: Color = Card,
    pressed: Color = CardPressed,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var isDown by remember { mutableStateOf(false) }
    Box(
        modifier
            .scale(if (isDown) 0.96f else 1f)
            .clip(shape)
            .background(if (isDown) pressed else base)
            .pointerInput(repeat, onClick) {
                detectTapGestures(onPress = {
                    isDown = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                    val job = if (repeat) {
                        scope.launch {
                            delay(450)
                            while (isActive) {
                                onClick()
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                delay(140)
                            }
                        }
                    } else {
                        null
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    isDown = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------- Drawn glyphs (no icon library needed)

@Composable
private fun Glyph(size: Dp = 24.dp, draw: DrawScope.(Float) -> Unit) {
    Canvas(Modifier.size(size)) { draw(this.size.minDimension) }
}

@Composable
private fun ArrowGlyph(c: Color, rotation: Float) = Glyph(28.dp) { s ->
    val path = Path().apply {
        moveTo(s * 0.2f, s * 0.65f)
        lineTo(s * 0.5f, s * 0.32f)
        lineTo(s * 0.8f, s * 0.65f)
    }
    rotate(rotation, Offset(s / 2, s / 2)) {
        drawPath(path, c, style = Stroke(width = s * 0.11f, cap = StrokeCap.Round))
    }
}

@Composable
private fun SkipGlyph(c: Color, forward: Boolean) = Glyph(26.dp) { s ->
    val dir = if (forward) 1f else -1f
    val cx = s / 2
    for (i in 0..1) {
        val ox = cx + dir * (i * s * 0.24f - s * 0.18f)
        val tri = Path().apply {
            moveTo(ox - dir * s * 0.16f, s * 0.25f)
            lineTo(ox + dir * s * 0.16f, s * 0.5f)
            lineTo(ox - dir * s * 0.16f, s * 0.75f)
            close()
        }
        drawPath(tri, c)
    }
    val bx = cx + dir * s * 0.36f
    drawLine(c, Offset(bx, s * 0.25f), Offset(bx, s * 0.75f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
}

@Composable
private fun PlayPauseGlyph(c: Color) = Glyph(30.dp) { s ->
    val tri = Path().apply {
        moveTo(s * 0.12f, s * 0.22f)
        lineTo(s * 0.48f, s * 0.5f)
        lineTo(s * 0.12f, s * 0.78f)
        close()
    }
    drawPath(tri, c)
    drawLine(c, Offset(s * 0.64f, s * 0.24f), Offset(s * 0.64f, s * 0.76f), strokeWidth = s * 0.11f, cap = StrokeCap.Round)
    drawLine(c, Offset(s * 0.84f, s * 0.24f), Offset(s * 0.84f, s * 0.76f), strokeWidth = s * 0.11f, cap = StrokeCap.Round)
}

@Composable
private fun SpeakerGlyph(c: Color) = Glyph(20.dp) { s ->
    val body = Path().apply {
        moveTo(s * 0.12f, s * 0.38f)
        lineTo(s * 0.3f, s * 0.38f)
        lineTo(s * 0.52f, s * 0.18f)
        lineTo(s * 0.52f, s * 0.82f)
        lineTo(s * 0.3f, s * 0.62f)
        lineTo(s * 0.12f, s * 0.62f)
        close()
    }
    drawPath(body, c)
    drawArc(c, -45f, 90f, false, topLeft = Offset(s * 0.42f, s * 0.28f), size = Size(s * 0.44f, s * 0.44f), style = Stroke(width = s * 0.08f, cap = StrokeCap.Round))
}

@Composable
private fun MoonGlyph(c: Color) = Glyph(20.dp) { s ->
    val r = s * 0.42f
    val ctr = Offset(s / 2, s / 2)
    val disc = Path().apply { addOval(Rect(ctr, r)) }
    val bite = Path().apply { addOval(Rect(Offset(ctr.x + r * 0.5f, ctr.y - r * 0.35f), r * 0.85f)) }
    drawPath(Path().apply { op(disc, bite, PathOperation.Difference) }, c)
}

/** The standard power symbol: an open ring with a bar through the gap. */
@Composable
private fun PowerGlyph(c: Color) = Glyph(20.dp) { s ->
    val w = s * 0.12f
    val r = s * 0.36f
    val ctr = Offset(s / 2, s / 2 + s * 0.04f)
    drawArc(c, -60f, 300f, false, Offset(ctr.x - r, ctr.y - r), Size(r * 2, r * 2), style = Stroke(width = w, cap = StrokeCap.Round))
    drawLine(c, Offset(s / 2, s * 0.08f), Offset(s / 2, s * 0.48f), strokeWidth = w, cap = StrokeCap.Round)
}

@Composable
private fun GearGlyph(c: Color) = Glyph(22.dp) { s ->
    val ctr = Offset(s / 2, s / 2)
    val r = s / 2
    drawCircle(c, r * 0.5f, ctr, style = Stroke(width = r * 0.22f))
    for (i in 0 until 8) {
        val a = (Math.PI * 2 * i / 8).toFloat()
        drawLine(
            c,
            Offset(ctr.x + kotlin.math.cos(a) * r * 0.62f, ctr.y + kotlin.math.sin(a) * r * 0.62f),
            Offset(ctr.x + kotlin.math.cos(a) * r * 0.95f, ctr.y + kotlin.math.sin(a) * r * 0.95f),
            strokeWidth = r * 0.22f,
        )
    }
}

@Composable
private fun DpadGlyph(c: Color) = Glyph(24.dp) { s ->
    drawCircle(c, s * 0.42f, Offset(s / 2, s / 2), style = Stroke(width = s * 0.08f))
    drawCircle(c, s * 0.14f, Offset(s / 2, s / 2))
}

@Composable
private fun PadGlyph(c: Color) = Glyph(24.dp) { s ->
    drawRoundRect(c, Offset(0f, s * 0.22f), Size(s, s * 0.56f), CornerRadius(s * 0.28f), style = Stroke(width = s * 0.08f))
    drawRect(c, Offset(s * 0.16f, s * 0.46f), Size(s * 0.24f, s * 0.08f))
    drawRect(c, Offset(s * 0.24f, s * 0.38f), Size(s * 0.08f, s * 0.24f))
    drawCircle(c, s * 0.06f, Offset(s * 0.70f, s * 0.42f))
    drawCircle(c, s * 0.06f, Offset(s * 0.80f, s * 0.56f))
}

@Composable
private fun SaveGlyph(c: Color) = Glyph(22.dp) { s ->
    // floppy disk
    drawRoundRect(c, Offset(0f, 0f), Size(s, s), CornerRadius(s * 0.14f), style = Stroke(width = s * 0.09f))
    drawRect(c, Offset(s * 0.26f, 0f), Size(s * 0.48f, s * 0.30f))
    drawRoundRect(c, Offset(s * 0.2f, s * 0.56f), Size(s * 0.6f, s * 0.3f), CornerRadius(s * 0.05f), style = Stroke(width = s * 0.08f))
}

@Composable
private fun GridGlyph(c: Color) = Glyph(22.dp) { s ->
    val gap = s * 0.16f
    val cell = (s - gap) / 2f
    for (row in 0..1) for (col in 0..1) {
        drawRoundRect(c, Offset(col * (cell + gap), row * (cell + gap)), Size(cell, cell), CornerRadius(cell * 0.28f))
    }
}
