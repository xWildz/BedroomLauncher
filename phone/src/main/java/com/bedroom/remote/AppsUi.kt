package com.bedroom.remote

import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val ABg = Color(0xFF0B1120)
private val ACard = Color(0xFF18233A)
private val ACard2 = Color(0xFF22304D)
private val AText = Color(0xFFEEF3FB)
private val ASub = Color(0xFF93A4C0)
private val AAccent = Color(0xFF8FB8FF)
private val AGood = Color(0xFF5FD38D)
private val AWarn = Color(0xFFFFB36B)

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Album art for what's playing, or the app's icon if there's none. */
@Composable
private fun ArtImage(tv: TvClient, m: MediaInfo, modifier: Modifier, crop: Boolean = true) {
    val img by produceState<ImageBitmap?>(null, m.pkg, m.artKey) {
        value = if (m.artKey != null) tv.image("art", "pkg" to m.pkg, "key" to m.artKey) else null
        if (value == null) value = tv.image("icon", "pkg" to m.pkg)
    }
    Box(modifier.background(ACard2), contentAlignment = Alignment.Center) {
        img?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = if (crop && m.artKey != null) ContentScale.Crop else ContentScale.Fit)
        } ?: Text(m.app.take(1), color = ASub, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppIcon(tv: TvClient, pkg: String, label: String, modifier: Modifier) {
    val img by produceState(tv.cachedImage("iconpkg=$pkg"), pkg) {
        if (value == null) value = tv.image("icon", "pkg" to pkg)
    }
    Box(modifier.background(ACard2), contentAlignment = Alignment.Center) {
        img?.let { Image(it, label, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) }
            ?: Text(label.take(1), color = ASub, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** A ticking clock for progress bars. */
@Composable
private fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(if (running) 500 else 2000)
        }
    }
    return now
}

private data class Ctl(val label: String, val cmd: String, val main: Boolean = false)

/** The buttons this app supports (from its media session). */
private fun controlsFor(m: MediaInfo): List<Ctl> {
    val seek = m.can(PlaybackState.ACTION_SEEK_TO)
    val out = mutableListOf<Ctl>()
    if (m.can(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) out += Ctl("⏮︎", "prev")
    if (seek || m.can(PlaybackState.ACTION_REWIND)) out += Ctl("−10", "rw")
    out += Ctl(if (m.playing) "❚❚" else "▶︎", "toggle", main = true)
    if (seek || m.can(PlaybackState.ACTION_FAST_FORWARD)) out += Ctl("+30", "ff")
    when {
        m.can(PlaybackState.ACTION_SKIP_TO_NEXT) -> out += Ctl(if (m.video) "Next ep." else "⏭︎", "next")
        m.video && seek && m.duration > 0 -> out += Ctl("Next ep.", "end")
    }
    return out
}

@Composable
private fun MediaButtons(tv: TvClient, m: MediaInfo, big: Boolean) {
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controlsFor(m).forEach { c ->
            val size: Dp = when {
                c.main && big -> 76.dp
                c.main -> 54.dp
                big -> 56.dp
                else -> 44.dp
            }
            val wide = c.label.length > 3
            Box(
                Modifier
                    .height(size)
                    .width(if (wide) size * 1.7f else size)
                    .clip(if (wide) RoundedCornerShape(size / 2) else CircleShape)
                    .background(if (c.main) AAccent else ACard2)
                    .clickable { if (c.cmd == "end") confirmEnd = true else tv.media(scope, c.cmd, m.pkg) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.label,
                    color = if (c.main) ABg else AText,
                    fontSize = when {
                        c.main && big -> 28.sp
                        wide -> 13.sp
                        big -> 18.sp
                        else -> 15.sp
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("Skip to the next episode?") },
            text = { Text("${m.app} doesn't have a Next button for remotes, so this jumps to the last 15 seconds. The app's own \"next episode\" countdown then starts.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; tv.media(scope, "end", m.pkg) }) { Text("Skip") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
            containerColor = Color(0xFF141E33),
        )
    }
}

@Composable
private fun Progress(tv: TvClient, m: MediaInfo, now: Long, seekable: Boolean) {
    val scope = rememberCoroutineScope()
    if (m.duration <= 0) {
        Text(if (m.playing) "Playing" else m.state.replaceFirstChar { it.uppercase() }, color = ASub, fontSize = 12.sp)
        return
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val pos = if (dragging) (dragValue * m.duration).toLong() else m.positionAt(now)
    Column {
        if (seekable && m.can(PlaybackState.ACTION_SEEK_TO)) {
            Slider(
                value = pos.toFloat() / m.duration,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    tv.media(scope, "seek", m.pkg, (dragValue * m.duration).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(thumbColor = AText, activeTrackColor = AAccent, inactiveTrackColor = ACard2),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ACard2)) {
                Box(
                    Modifier
                        .fillMaxWidth((pos.toFloat() / m.duration).coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(AAccent),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = ASub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("-" + fmt(m.duration - pos), color = ASub, fontSize = 12.sp)
        }
    }
}

/** Card at the top of the Apps tab. Tap it for the full-screen player. */
@Composable
private fun NowPlayingCard(tv: TvClient, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val m = tv.nowPlaying
    val shape = RoundedCornerShape(22.dp)
    when {
        tv.mediaAccess == false -> Column(
            Modifier.fillMaxWidth().clip(shape).background(ACard).padding(16.dp),
        ) {
            Text("See what's playing", color = AText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Show the song or show on the TV here, with its artwork and controls (play, skip, next episode). " +
                    "It lets the TV home screen read what's playing; it doesn't read your notifications.",
                color = ASub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(AAccent)
                    .clickable { tv.send(scope, "mediaaccess") }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("Turn on", color = ABg, fontWeight = FontWeight.SemiBold) }
            Text(
                "Needs \"Allow full remote\" on the TV's Phone screen first.",
                color = ASub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        m == null || (m.title.isBlank() && !m.playing) -> Row(
            Modifier.fillMaxWidth().clip(shape).background(ACard).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(ASub))
            Spacer(Modifier.width(10.dp))
            Text("Nothing playing on the TV", color = ASub, fontSize = 14.sp)
        }
        else -> {
            val now = rememberNow(m.playing)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(ACard).clickable { onOpen() }.padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(tv, m, Modifier.size(if (m.video) 112.dp else 84.dp, 84.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (m.playing) AGood else ASub))
                            Spacer(Modifier.width(6.dp))
                            Text(m.app.uppercase(), color = AAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Text(
                            m.title.ifBlank { m.app }, color = AText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (m.artist.isNotBlank()) {
                            Text(m.artist, color = ASub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("⤢", color = ASub, fontSize = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
                Progress(tv, m, now, seekable = false)
                Spacer(Modifier.height(8.dp))
                MediaButtons(tv, m, big = false)
            }
        }
    }
}

/** Full-screen "now playing" (great for YouTube Music): big artwork, seek bar, controls, volume. */
@Composable
private fun NowPlayingScreen(tv: TvClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val m = tv.nowPlaying
        Box(Modifier.fillMaxSize().background(ABg)) {
            if (m == null) {
                Text("Nothing playing", color = ASub, modifier = Modifier.align(Alignment.Center))
                Text("✕", color = AText, fontSize = 22.sp, modifier = Modifier.statusBarsPadding().padding(20.dp).clickable { onClose() })
                return@Box
            }
            // Blurred artwork behind everything (blur needs Android 12+; older phones get a dimmed copy).
            ArtImage(
                tv, m,
                Modifier.fillMaxSize().alpha(if (Build.VERSION.SDK_INT >= 31) 0.55f else 0.25f)
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ABg.copy(alpha = 0.35f), ABg.copy(alpha = 0.92f)))))

            val now = rememberNow(m.playing)
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(ACard.copy(alpha = 0.7f)).clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { Text("⌄", color = AText, fontSize = 22.sp) }
                    Text(
                        "PLAYING ON THE TV · " + m.app.uppercase(), color = ASub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Spacer(Modifier.size(40.dp))
                }
                Spacer(Modifier.weight(0.6f))
                ArtImage(
                    tv, m,
                    Modifier.fillMaxWidth().aspectRatio(if (m.video) 16f / 9f else 1f).clip(RoundedCornerShape(24.dp)),
                )
                Spacer(Modifier.weight(0.5f))
                Text(
                    m.title.ifBlank { m.app }, color = AText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOf(m.artist, m.album).filter { it.isNotBlank() && it != m.title }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, color = ASub, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(18.dp))
                Progress(tv, m, now, seekable = true)
                Spacer(Modifier.height(14.dp))
                MediaButtons(tv, m, big = true)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Vol −" to "voldown", "Mute" to "mute", "Vol +" to "volup").forEach { (label, action) ->
                        Box(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(ACard.copy(alpha = 0.8f))
                                .clickable { tv.send(scope, action) },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = AText, fontSize = 14.sp) }
                    }
                }
                Spacer(Modifier.weight(0.4f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(tv: TvClient, app: TvApp, onMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { tv.send(scope, "open", "pkg" to app.pkg) }, onLongClick = onMenu)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(
                tv, app.pkg, app.label,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                    .then(if (app.front) Modifier.border(2.dp, AAccent, RoundedCornerShape(14.dp)) else Modifier),
            )
            if (app.running) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(9.dp).clip(CircleShape).background(AGood))
            }
        }
        Text(
            app.label, color = if (app.front) AAccent else AText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * Apps tab: what's playing (with per-app controls), apps open on the TV, every installed app (live),
 * then [extra] (sleep timer).
 */
@Composable
fun LiveAppsTab(tv: TvClient, extra: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<TvApp?>(null) }

    // Live updates: now playing every second, the app list every 5 seconds.
    LaunchedEffect(tv.host, tv.key) {
        var n = 0
        while (isActive) {
            tv.refreshNowPlaying()
            if (n % 5 == 0) tv.refreshApps()
            n++
            delay(1000)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) { NowPlayingCard(tv) { player = true } }

        val open = tv.apps.filter { it.running || it.front }
        if (open.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) {
                SectionHeader("Open on the TV", "◉", Ui.Teal)
            }
            item(span = { GridItemSpan(3) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(open, key = { it.pkg }) { a ->
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(if (a.front) ACard2 else ACard)
                                .clickable { tv.send(scope, "open", "pkg" to a.pkg) }
                                .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(tv, a.pkg, a.label, Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text(a.label, color = if (a.front) AAccent else AText, fontSize = 13.sp, maxLines = 1)
                            Box(
                                Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable { menuFor = a },
                                contentAlignment = Alignment.Center,
                            ) { Text("✕", color = ASub, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(3) }) {
            SectionHeader("Apps on the TV", "▦", Ui.Blue, trailing = "${tv.apps.size}")
        }
        if (tv.apps.isEmpty()) {
            item(span = { GridItemSpan(3) }) { Text("Apps appear here once the TV is connected.", color = ASub, fontSize = 13.sp) }
        }
        items(tv.apps, key = { it.pkg }) { app -> AppTile(tv, app) { menuFor = app } }
        item(span = { GridItemSpan(3) }) { Text("Hold an app for more. A green dot means it's open.", color = ASub, fontSize = 11.sp) }

        item(span = { GridItemSpan(3) }) { Column(Modifier.padding(top = 8.dp)) { extra() } }
    }

    if (player) NowPlayingScreen(tv) { player = false }

    menuFor?.let { a ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(a.label) },
            text = {
                Text(
                    if (a.running) "Close stops the app completely (like Force stop). Anything playing in it stops."
                    else "This app isn't open right now.",
                )
            },
            confirmButton = {
                TextButton(onClick = { tv.send(scope, "open", "pkg" to a.pkg); menuFor = null }) { Text("Open") }
            },
            dismissButton = {
                if (a.running) {
                    TextButton(onClick = {
                        tv.send(scope, "close", "pkg" to a.pkg)
                        menuFor = null
                    }) { Text("Close app", color = AWarn) }
                }
            },
            containerColor = Color(0xFF141E33),
        )
    }
}
