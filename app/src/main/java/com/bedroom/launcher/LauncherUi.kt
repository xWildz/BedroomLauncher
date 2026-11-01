@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.bedroom.launcher

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen { Home, AllApps, EditFavorites, PhoneRemote }

// Sizes are computed from the real screen width (960x540 dp on this 1080p TV).
private val Gap = 24.dp
private val FavGap = 16.dp
private val SidePad = 48.dp // ~5% TV-safe margin
private val PillH = 42.dp

/** Tile sizes derived from the available width, so the layout fills the screen edge to edge. */
private data class Dims(val content: Dp, val tileW: Dp, val tileH: Dp, val favW: Dp, val favH: Dp)

private fun dimsFor(screenWidth: Dp): Dims {
    val content = screenWidth - SidePad * 2
    val tileW = (content - Gap * 2) / 3
    // 4.5 favorites per row: the half tile at the edge shows the row scrolls.
    val favW = (content - FavGap * 4) / 4.5f
    return Dims(content, tileW, tileW * (9f / 16f), favW, favW * (9f / 16f))
}
private val TileShape = RoundedCornerShape(14.dp)
private val SmallShape = RoundedCornerShape(10.dp)

private const val KEY_DEFAULT = "watch:0"
private const val KEY_APPS = "apps"
private const val KEY_SETTINGS = "settings"
private const val KEY_ADD = "fav:add"
private const val KEY_SLEEP = "sleep"
private const val KEY_PHONE = "phone"

@Composable
fun LauncherApp(resumeTick: Int, homeTick: Int) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var lastFocused by rememberSaveable { mutableStateOf(KEY_DEFAULT) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val requester: (String) -> FocusRequester = { key -> requesters.getOrPut(key) { FocusRequester() } }

    var favorites by remember { mutableStateOf(Favorites.load(ctx)) }
    val toggleFavorite: (String) -> Unit = { pkg -> favorites = Favorites.toggle(ctx, favorites, pkg) }

    val now by rememberNowMillis()
    val palette = animatedPalette(paletteFor(phaseFor(now)))

    // Re-read installed apps whenever we come back (picks up new installs like RetroArch).
    // Only decode the banners on screen: Watch + Favorites at home, everything in All apps / the picker.
    val showAll = screen == Screen.AllApps || screen == Screen.EditFavorites
    val data by produceState(AppData.EMPTY, resumeTick, showAll, favorites) {
        val wanted = homePackages(favorites)
        value = withContext(Dispatchers.IO) { Apps.load(ctx, decodeAll = showAll, wanted = wanted) }
    }

    // Weather: show the cached forecast immediately, refresh every 30 min (5 min after a failure).
    val initialWeather = remember { WeatherRepo.cached(ctx) }
    var placeSet by remember { mutableStateOf(true) }
    val weather by produceState(initialWeather) {
        while (true) {
            val place = withContext(Dispatchers.IO) { Config.weatherPlace(ctx) }
            placeSet = place.isNotBlank()
            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeatherRepo.refresh(ctx, place)
                } catch (e: Exception) {
                    Log.w(TAG, "Weather refresh failed", e)
                    null
                }
            }
            if (fresh != null) value = fresh
            delay(if (fresh != null) 30 * 60_000L else 5 * 60_000L)
        }
    }

    // Home button: always land on the home screen, first tile.
    LaunchedEffect(homeTick) {
        if (homeTick > 0) {
            screen = Screen.Home
            lastFocused = KEY_DEFAULT
        }
    }

    // Put focus back on whichever tile launched the app we're returning from.
    LaunchedEffect(resumeTick, homeTick, screen) {
        if (screen == Screen.Home) {
            delay(80)
            val target = requesters[lastFocused]
            val ok = target != null && runCatching { target.requestFocus() }.isSuccess
            if (!ok) runCatching { requester(KEY_DEFAULT).requestFocus() }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(palette)
            when (screen) {
                Screen.Home -> HomeScreen(
                    now = now,
                    p = palette,
                    data = data,
                    weather = weather,
                    placeSet = placeSet,
                    favorites = favorites,
                    requester = requester,
                    onFocused = { lastFocused = it },
                    onAllApps = { screen = Screen.AllApps },
                    onEditFavorites = { screen = Screen.EditFavorites },
                    onPhone = { screen = Screen.PhoneRemote },
                )
                Screen.PhoneRemote -> PhoneRemoteScreen(
                    p = palette,
                    onBack = {
                        lastFocused = KEY_PHONE
                        screen = Screen.Home
                    },
                )
                Screen.AllApps -> AppGridScreen(
                    title = "All apps",
                    subtitle = { n -> "$n apps · Hold OK to pin or unpin · Back to return" },
                    data = data,
                    p = palette,
                    favorites = favorites,
                    onSelect = { app -> if (!Apps.launch(ctx, app.pkg)) toast(ctx, "Couldn't open ${app.label}") },
                    onHold = { app ->
                        val wasFav = Favorites.isFavorite(favorites, app.pkg)
                        toggleFavorite(app.pkg)
                        toast(ctx, if (wasFav) "Removed ${app.label} from favorites" else "Added ${app.label} to favorites")
                    },
                    onBack = {
                        lastFocused = KEY_APPS
                        screen = Screen.Home
                    },
                )
                Screen.EditFavorites -> AppGridScreen(
                    title = "Favorites",
                    subtitle = { _ -> "${favorites.size} pinned · OK to add or remove · Back when done" },
                    data = data,
                    p = palette,
                    favorites = favorites,
                    onSelect = { app -> toggleFavorite(app.pkg) },
                    onHold = { app -> toggleFavorite(app.pkg) },
                    onBack = {
                        lastFocused = KEY_ADD
                        screen = Screen.Home
                    },
                )
            }
            Text(
                "v${BuildConfig.VERSION_NAME}",
                color = palette.subtext.copy(alpha = 0.35f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(
    now: Long,
    p: Palette,
    data: AppData,
    weather: Weather?,
    placeSet: Boolean,
    favorites: List<String>,
    requester: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
    onAllApps: () -> Unit,
    onEditFavorites: () -> Unit,
    onPhone: () -> Unit,
) {
    val ctx = LocalContext.current
    // Back on the home screen has nowhere to go; swallow it so nothing odd happens.
    BackHandler(enabled = true) {}

    var favLabel by remember { mutableStateOf<String?>(null) }

    fun Modifier.track(key: String) =
        this.focusRequester(requester(key)).onFocusChanged {
            if (it.isFocused) {
                onFocused(key)
                favLabel = null
            }
        }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = dimsFor(maxWidth)
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = SidePad, end = SidePad, top = 16.dp, bottom = 16.dp),
        ) {
            // Top: clock on the left, weather on the right, and one tidy row of buttons under the clock.
            Row(Modifier.width(d.content), verticalAlignment = Alignment.Top) {
                Header(now, p)
                Spacer(Modifier.weight(1f))
                // Continue: the last RetroArch game, with a picture of where you left off.
                // Reloads each time the home screen comes back (data refreshes then).
                val resume by produceState<RetroHistory.Resume?>(null, data) {
                    value = withContext(Dispatchers.IO) { RetroHistory.resumeInfo(ctx) }
                }
                resume?.let { r ->
                    ContinueCard(
                        r, now, p,
                        modifier = Modifier.track("continue").padding(top = 6.dp),
                        onClick = {
                            toast(ctx, "Starting ${r.game.label}")
                            kotlin.concurrent.thread { RetroHistory.launch(ctx) }
                        },
                        onSetup = {
                            kotlin.concurrent.thread {
                                val msg = RetroHistory.enableResume(ctx)
                                android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, msg) }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
                WeatherPanel(weather, placeSet, p)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TopPill(
                    title = "All apps",
                    p = p,
                    modifier = Modifier.track(KEY_APPS),
                    icon = { GridIcon(p.text, 18.dp) },
                    onClick = onAllApps,
                )
                // Photos: a tiny preview that cycles through your pictures.
                TopPill(
                    title = "Photos",
                    p = p,
                    modifier = Modifier.track("photos"),
                    icon = { PhotoPreview(p) },
                    onClick = { ctx.startActivity(android.content.Intent(ctx, PhotoFrameActivity::class.java)) },
                )
                val left = SleepTimer.minutesLeft(now)
                TopPill(
                    title = if (left == null) "Sleep" else "Sleep ${left}m",
                    p = p,
                    modifier = Modifier.track(KEY_SLEEP),
                    icon = { MoonIcon(if (left != null) Color(0xFFFFD27A) else p.text, 18.dp) },
                    onClick = {
                        val m = SleepTimer.cycle(ctx)
                        toast(ctx, if (m == 0) "Sleep timer off" else "TV will rest in $m min")
                    },
                )
                TopPill(
                    title = "Phone",
                    p = p,
                    modifier = Modifier.track(KEY_PHONE),
                    icon = { PhoneIcon(p.text, 18.dp) },
                    onClick = onPhone,
                )
                TopPill(
                    title = "Settings",
                    p = p,
                    modifier = Modifier.track(KEY_SETTINGS),
                    icon = { GearIcon(p.text, 18.dp) },
                    onClick = { Apps.openSettings(ctx) },
                )
            }
            Spacer(Modifier.height(16.dp)) // always some air between the buttons and the rows below
            Spacer(Modifier.weight(1f))

            SectionLabel("Watch", p)
            Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                WATCH.forEachIndexed { i, pin ->
                    AppTile(
                        pin = pin,
                        data = data,
                        p = p,
                        width = d.tileW,
                        height = d.tileH,
                        modifier = Modifier.track("watch:$i"),
                    )
                }
            }
            Spacer(Modifier.weight(0.7f))

            SectionLabel(favLabel?.let { "Favorites · $it" } ?: "Favorites", p)
            FavoritesRow(
                favorites = favorites,
                data = data,
                p = p,
                rowWidth = d.content,
                tileW = d.favW,
                tileH = d.favH,
                requester = requester,
                onFocused = { key, label ->
                    onFocused(key)
                    favLabel = label
                },
                onEdit = onEditFavorites,
            )
        }
    }
}

@Composable
private fun Header(now: Long, p: Palette) {
    val ctx = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val date = Date(now)
    val locale = Locale.getDefault()
    val time = SimpleDateFormat(if (is24) "H:mm" else "h:mm", locale).format(date)
    val ampm = SimpleDateFormat("a", locale).format(date)
    val day = SimpleDateFormat("EEEE, MMMM d", locale).format(date)
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }

    Column {
        Text(greeting.uppercase(locale), color = p.subtext, fontSize = 13.sp, letterSpacing = 3.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time, color = p.text, fontSize = 60.sp, fontWeight = FontWeight.Light)
            if (!is24) {
                Text(
                    ampm,
                    color = p.subtext,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 13.dp),
                )
            }
        }
        Text(day, color = p.subtext, fontSize = 18.sp)
    }
}

@Composable
private fun SectionLabel(text: String, p: Palette) {
    Text(
        text.uppercase(),
        color = p.subtext,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

// ---------------------------------------------------------------- Tiles

/**
 * TV buttons only react to the remote's OK key. This also lets a tap (touch, or a mouse click in the PC's
 * live video) press them: tap = select, hold = long-press.
 */
private fun Modifier.tap(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = if (onLongClick != null) { _ -> onLongClick() } else null,
        )
    }

@Composable
private fun AppTile(pin: Pinned, data: AppData, p: Palette, width: Dp, height: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = data.resolve(pin)
    var focused by remember { mutableStateOf(false) }

    val open: () -> Unit = {
        when {
            app != null -> if (!Apps.launch(ctx, app.pkg)) toast(ctx, "Couldn't open ${pin.title}")
            data.loaded -> Apps.openStore(ctx, pin)
        }
    }
    Surface(
        onClick = { open() },
        modifier = modifier
            .size(width, height)
            .tap({ open() })
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(TileShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = TileShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(pin.accent.copy(alpha = 0.55f), 12.dp)),
    ) {
        val banner = app?.banner
        if (banner != null) {
            Image(banner, contentDescription = pin.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            PlaceholderArt(
                pin.title,
                pin.accent,
                subtitle = if (data.loaded && app == null) "Not installed · OK to get it" else null,
            )
        }
        DimOverlay(if (focused) p.focusedDim else p.tileDim)
    }
}

@Composable
private fun FavoritesRow(
    favorites: List<String>,
    data: AppData,
    p: Palette,
    rowWidth: Dp,
    tileW: Dp,
    tileH: Dp,
    requester: (String) -> FocusRequester,
    onFocused: (key: String, label: String) -> Unit,
    onEdit: () -> Unit,
) {
    val ctx = LocalContext.current
    // Padding inside the row (and a matching negative offset) leaves room for the focus scale + border.
    LazyRow(
        modifier = Modifier
            .offset(x = (-8).dp)
            .width(rowWidth + 16.dp)
            .height(tileH + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(FavGap),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        items(favorites, key = { it }) { pkg ->
            val app = Favorites.resolve(pkg, data)
            val name = Favorites.nameFor(pkg, data)
            val key = "fav:$pkg"
            FavTile(
                name = name,
                app = app,
                loaded = data.loaded,
                p = p,
                width = tileW,
                height = tileH,
                modifier = Modifier
                    .focusRequester(requester(key))
                    .onFocusChanged { if (it.isFocused) onFocused(key, name) },
                onClick = {
                    when {
                        app != null -> if (!Apps.launch(ctx, app.pkg)) toast(ctx, "Couldn't open $name")
                        data.loaded -> Apps.openStore(ctx, pkg, name)
                    }
                },
                onLongClick = onEdit,
            )
        }
        item(key = "__add") {
            AddTile(
                p = p,
                width = tileW,
                height = tileH,
                modifier = Modifier
                    .focusRequester(requester(KEY_ADD))
                    .onFocusChanged { if (it.isFocused) onFocused(KEY_ADD, "add or remove apps") },
                onClick = onEdit,
            )
        }
    }
}

@Composable
private fun FavTile(
    name: String,
    app: AppEntry?,
    loaded: Boolean,
    p: Palette,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .size(width, height)
            .tap(onClick, onLongClick)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(SmallShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = SmallShape),
        ),
    ) {
        val banner = app?.banner
        val icon = app?.icon
        when {
            banner != null -> Image(banner, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null -> IconWithLabel(icon, name, p)
            else -> PlaceholderArt(
                name,
                Color(0xFF9B8CFF),
                subtitle = if (loaded) "Not installed" else null,
                titleSize = 15,
            )
        }
        DimOverlay(if (focused) p.focusedDim else p.tileDim)
    }
}

@Composable
private fun AddTile(p: Palette, width: Dp, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width, height).tap(onClick),
        shape = ClickableSurfaceDefaults.shape(SmallShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, p.subtext.copy(alpha = 0.35f)), shape = SmallShape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = SmallShape),
        ),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PlusIcon(p.text)
            Spacer(Modifier.height(6.dp))
            Text("Add / remove", color = p.subtext, fontSize = 12.sp)
        }
    }
}

/** Compact pill button in the top bar (All apps, Settings). */
/** The last RetroArch game: a picture of where you left off, its name, and when. OK = carry on; hold OK = set up. */
@Composable
private fun ContinueCard(
    r: RetroHistory.Resume,
    now: Long,
    p: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
    onSetup: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val pic by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, r.shot, r.shotTime) {
        value = r.shot?.let { f -> withContext(Dispatchers.IO) { PhotoStore.decode(f, 480, 320)?.asImageBitmap() } }
    }
    Surface(
        onClick = onClick,
        onLongClick = onSetup,
        modifier = modifier.width(350.dp).height(112.dp).tap(onClick, onSetup),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xFF9B8CFF).copy(alpha = 0.45f), 12.dp)),
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(144.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFF15131F)),
                contentAlignment = Alignment.Center,
            ) {
                val b = pic
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else PlayIcon(Color(0xFF9B8CFF), 30.dp)
                // little play badge in the corner
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { PlayIcon(Color.White, 12.dp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE", color = Color(0xFFB3A8FF), fontSize = 11.sp, letterSpacing = 2.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    r.game.label, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (r.playSecs >= 60) played(r.playSecs) + " · " else "") + when {
                        !r.ready -> "Hold OK: resume where you left off"
                        r.shotTime > 0 -> "your spot " + ago(now, r.shotTime)
                        else -> "picks up where you left off"
                    },
                    color = p.subtext, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun played(secs: Long): String {
    val m = secs / 60
    return if (m < 60) "$m min played" else "${m / 60} h ${m % 60} m played"
}

private fun ago(now: Long, t: Long): String {
    val m = ((now - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}

@Composable
private fun TopPill(
    title: String,
    p: Palette,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(PillH / 2)
    Surface(
        onClick = onClick,
        modifier = modifier.height(PillH).tap(onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xFF8FB8FF).copy(alpha = 0.4f), 10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(20.dp).widthIn(min = 20.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(9.dp))
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun IconWithLabel(icon: androidx.compose.ui.graphics.ImageBitmap, label: String, p: Palette) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(icon, label, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = p.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DimOverlay(alpha: Float) {
    if (alpha > 0.001f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))),
        )
    }
}

@Composable
private fun PlaceholderArt(title: String, accent: Color, subtitle: String? = null, titleSize: Int = 22) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF0B0D16))))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = titleSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- All apps / favorites picker

@Composable
private fun AppGridScreen(
    title: String,
    subtitle: (Int) -> String,
    data: AppData,
    p: Palette,
    favorites: List<String>,
    onSelect: (AppEntry) -> Unit,
    onHold: (AppEntry) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 56.dp, end = 56.dp, top = 28.dp),
    ) {
        Text(title, color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Light)
        Text(
            if (data.loaded) subtitle(data.all.size) else "Loading…",
            color = p.subtext,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 40.dp),
        ) {
            itemsIndexed(data.all, key = { _, a -> a.pkg }) { i, app ->
                GridTile(
                    app = app,
                    p = p,
                    pinned = Favorites.isFavorite(favorites, app.pkg),
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { onSelect(app) },
                    onLongClick = { onHold(app) },
                )
            }
        }
    }

    LaunchedEffect(data.all.isNotEmpty()) {
        if (data.all.isNotEmpty()) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
}

@Composable
private fun GridTile(
    app: AppEntry,
    p: Palette,
    pinned: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tap(onClick, onLongClick)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(SmallShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = SmallShape),
            ),
        ) {
            val banner = app.banner
            val icon = app.icon
            when {
                banner != null -> Image(banner, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                icon != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(icon, app.label, modifier = Modifier.size(48.dp))
                }
                else -> PlaceholderArt(app.label, Color(0xFF8FB8FF))
            }
            DimOverlay(if (focused) p.focusedDim else p.tileDim)
            if (pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color(0xFF3DAA6E), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { CheckIcon(Color.White) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = if (focused) p.text else p.subtext,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- Phone remote (QR)

@Composable
private fun PhoneRemoteScreen(p: Palette, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val url = remember { remoteUrl(ctx) }
    val qr = remember(url) { url?.let { runCatching { Qr.bitmap(it, 560) }.getOrNull() } }
    val done = remember { FocusRequester() }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(280.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, "QR code for the phone remote", modifier = Modifier.fillMaxSize())
            } else {
                Text("No Wi-Fi", color = Color.Black, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.width(48.dp))
        Column {
            Text("Phone remote", color = p.text, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(8.dp))
            Text(
                "Point your phone's camera at the code. Your phone needs to be on the same Wi-Fi.",
                color = p.subtext,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(url ?: "The TV isn't connected to Wi-Fi.", color = p.text, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "Android: install the TV Remote app, scan, then tap \"Open in app\".\n" +
                    "For arrows, OK and typing, press Allow full remote once and accept the TV's prompt.",
                color = p.subtext,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TopPill(
                    title = "Done",
                    p = p,
                    modifier = Modifier.focusRequester(done),
                    icon = {},
                    onClick = onBack,
                )
                val allow: () -> Unit = {
                    KeyInjector.connect()
                    toast(ctx, "Look for \"Allow USB debugging?\" and choose Always allow")
                }
                Surface(
                    onClick = { allow() },
                    modifier = Modifier.size(220.dp, 44.dp).tap({ allow() }),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(22.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = p.card, focusedContainerColor = p.cardFocused),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = RoundedCornerShape(22.dp)),
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Allow full remote", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Full remote: ${KeyInjector.state}", color = p.subtext, fontSize = 12.sp)
        }
    }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { done.requestFocus() }
    }
}

// ---------------------------------------------------------------- Icons (drawn, so no font/emoji surprises)

@Composable
private fun MoonIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val r = s * 0.42f
        val c = Offset(s / 2, s / 2)
        val disc = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        val bite = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.5f, c.y - r * 0.35f), r * 0.85f))
        }
        val crescent = androidx.compose.ui.graphics.Path().apply {
            op(disc, bite, androidx.compose.ui.graphics.PathOperation.Difference)
        }
        drawPath(crescent, color)
    }
}

/** A small thumbnail on the Photos button that changes every few seconds (falls back to the icon). */
@Composable
private fun PhotoPreview(p: Palette) {
    val ctx = LocalContext.current
    val pic by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null) {
        var i = 0
        while (true) {
            val list = withContext(Dispatchers.IO) { PhotoStore.list(ctx) }
            if (list.isNotEmpty()) {
                val f = list[i % list.size]
                i++
                value = withContext(Dispatchers.IO) { PhotoStore.decode(f, 96, 64)?.asImageBitmap() }
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    androidx.compose.animation.Crossfade(targetState = pic, label = "photoPill") { b ->
        if (b == null) PhotoIcon(p.text, 18.dp)
        else Image(
            b, null,
            Modifier.size(30.dp, 20.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun PlayIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.25f, s * 0.15f)
            lineTo(s * 0.85f, s * 0.5f)
            lineTo(s * 0.25f, s * 0.85f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PhotoIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            color = color, topLeft = Offset(s * 0.08f, s * 0.18f), size = Size(s * 0.84f, s * 0.64f),
            cornerRadius = CornerRadius(s * 0.08f), style = Stroke(width = s * 0.09f),
        )
        val m = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.18f, s * 0.72f)
            lineTo(s * 0.42f, s * 0.45f)
            lineTo(s * 0.58f, s * 0.62f)
            lineTo(s * 0.68f, s * 0.52f)
            lineTo(s * 0.82f, s * 0.72f)
            close()
        }
        drawPath(m, color)
        drawCircle(color, radius = s * 0.07f, center = Offset(s * 0.68f, s * 0.34f))
    }
}

@Composable
private fun PhoneIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.52f
        val h = s * 0.9f
        drawRoundRect(
            color = color,
            topLeft = Offset((s - w) / 2, (s - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(s * 0.1f),
            style = Stroke(width = s * 0.1f),
        )
        drawCircle(color, radius = s * 0.05f, center = Offset(s / 2, (s + h) / 2 - s * 0.13f))
    }
}

@Composable
private fun GridIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val gap = this.size.width * 0.16f
        val cell = (this.size.width - gap) / 2f
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.28f),
            )
        }
    }
}

@Composable
private fun GearIcon(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        val stroke = r * 0.22f
        drawCircle(color, radius = r * 0.55f, center = c, style = Stroke(width = stroke))
        for (i in 0 until 8) {
            val a = (Math.PI * 2 * i / 8).toFloat()
            val start = Offset(c.x + cos(a) * r * 0.62f, c.y + sin(a) * r * 0.62f)
            val end = Offset(c.x + cos(a) * r * 0.98f, c.y + sin(a) * r * 0.98f)
            drawLine(color, start, end, strokeWidth = stroke)
        }
    }
}

@Composable
private fun PlusIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.12f
        drawLine(color, Offset(s / 2, s * 0.1f), Offset(s / 2, s * 0.9f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.1f, s / 2), Offset(s * 0.9f, s / 2), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun CheckIcon(color: Color, size: Dp = 13.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.16f
        drawLine(color, Offset(s * 0.12f, s * 0.55f), Offset(s * 0.4f, s * 0.82f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.4f, s * 0.82f), Offset(s * 0.9f, s * 0.2f), strokeWidth = w, cap = StrokeCap.Round)
    }
}
