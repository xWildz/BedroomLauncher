package com.bedroom.remote

import android.app.TimePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BBg = Color(0xFF0B1120)
private val BCard = Color(0xFF141E33)
private val BCard2 = Color(0xFF1E2A44)
private val BText = Color(0xFFEEF3FB)
private val BSub = Color(0xFF93A4C0)
private val BAccent = Color(0xFF8FB8FF)
private val BGold = Color(0xFFFFD27A)
private val BGood = Color(0xFF5FD38D)
private val BWarn = Color(0xFFFFB36B)
private val BNight = Color(0xFFB9A8FF)

/** Bedroom tab: bedtime, night mode, wake-up alarm, idle behaviour, photo frame, storage. */
@Composable
fun BedroomTab(tv: TvClient) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tv.host, tv.key) {
        tv.refreshBedroom()
        tv.refreshPhotos()
    }
    val b = tv.bedroom

    fun set(vararg p: Pair<String, String>) {
        scope.launch { tv.setBedroom(*p) }
    }
    fun act(name: String, vararg p: Pair<String, String>, after: (org.json.JSONObject?) -> Unit = {}) {
        scope.launch {
            val r = tv.action(name, *p)
            after(r)
            tv.refreshBedroom()
            tv.refreshStatus()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (b == null) {
            Text(if (tv.online == false) "Can't reach the TV" else "Loading…", color = BSub, fontSize = 14.sp)
            return@Column
        }
        msg?.let { Text(it, color = BGood, fontSize = 13.sp) }

        // ---------------- Bedtime (hero)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF15213D))))
                .padding(18.dp),
        ) {
            Text("Bedtime", color = BText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                (if (b.bool("bedNight")) "Night mode · " else "") + "volume ${b.int("bedVolume")} · sleep music · rests in ${b.int("bedMinutes")} min",
                color = BSub, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            BigButton("🌙  Start bedtime", BNight, Color(0xFF1A1433)) {
                act("bedtime") { r -> msg = r?.optString("done")?.takeIf { it.isNotBlank() } ?: "Couldn't reach the TV" }
            }
            var open by remember { mutableStateOf(false) }
            Text(
                if (open) "Hide settings ▴" else "Bedtime settings ▾", color = BAccent, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp).clickable { open = !open },
            )
            if (open) {
                Spacer(Modifier.height(8.dp))
                Label("Rest after")
                Chips(listOf(30, 45, 60, 90, 120), b.int("bedMinutes"), { "$it min" }) { set("bedMinutes" to it.toString()) }
                Label("Bedtime volume: ${b.int("bedVolume")}")
                IntSlider(b.int("bedVolume"), 0, b.int("maxVolume", 100).coerceAtMost(40)) { set("bedVolume" to it.toString()) }
                SwitchRow("Night mode at bedtime", b.bool("bedNight")) { set("bedNight" to if (it) "1" else "0") }
                var url by remember(b.str("bedUrl")) { mutableStateOf(b.str("bedUrl")) }
                OutlinedTextField(
                    value = url, onValueChange = { url = it.take(300) }, singleLine = true,
                    label = { Text("Sleep playlist link (optional)") },
                    placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Save link") {
                        if (url.isBlank() || url.startsWith("https://")) set("bedUrl" to url.trim()) else msg = "The link must start with https://"
                    }
                    Text("Empty = YouTube Music picks up where it left off.", color = BSub, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }

        // ---------------- Brightness + night mode
        BrightnessSlider(tv)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("Night mode")
                    Sub("Dimmer backlight" + if (b.bool("nightWarm")) " and a warmer picture" else "")
                }
                Switch(
                    checked = b.bool("night"),
                    onCheckedChange = { on -> act("night", "on" to if (on) "1" else "0") },
                    colors = SwitchDefaults.colors(checkedTrackColor = BNight),
                )
            }
            Label("Brightness at night: ${b.int("nightLevel")}%")
            IntSlider(b.int("nightLevel"), 2, 60) {
                set("nightLevel" to it.toString())
                if (b.bool("night")) act("night", "on" to "1")
            }
            SwitchRow("Warm tint", b.bool("nightWarm")) { set("nightWarm" to if (it) "1" else "0") }
        }

        // ---------------- Wake-up alarm
        AlarmCard(tv, b, { p -> set(*p) }) { act("alarmtest"); msg = "Alarm test: the TV wakes up and starts the music" }

        // ---------------- When idle
        Card {
            Title("When the screensaver would start")
            Sub("Instead of the TV's screensaver (not while music is playing).")
            Spacer(Modifier.height(10.dp))
            val idle = b.str("idle", "off")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "Screensaver", "rest" to "Rest", "frame" to "Photo frame").forEach { (k, label) ->
                    Segment(label, idle == k, Modifier.weight(1f)) { set("idle" to k) }
                }
            }
        }

        // ---------------- Photo frame
        PhotoCard(tv, b, { p -> set(*p) }, onShow = { act("frame") }, onMsg = { msg = it })

        // ---------------- Storage
        StorageCard(tv)
    }
}

@Composable
private fun AlarmCard(tv: TvClient, b: BedroomInfo, set: (Array<out Pair<String, String>>) -> Unit, onTest: () -> Unit) {
    val ctx = LocalContext.current
    fun s(vararg p: Pair<String, String>) = set(p)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title("Wake-up alarm")
                val next = b.j.optLong("alarmNext", 0L)
                Sub(
                    if (b.bool("alarmOn") && next > 0) "Next: " + SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(next))
                    else "Off",
                )
            }
            Switch(
                checked = b.bool("alarmOn"), onCheckedChange = { s("alarmOn" to if (it) "1" else "0") },
                colors = SwitchDefaults.colors(checkedTrackColor = BGold),
            )
        }
        val h = b.int("alarmHour", 7)
        val m = b.int("alarmMin")
        Text(
            String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM"),
            color = BText, fontSize = 40.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable {
                TimePickerDialog(ctx, { _, hh, mm -> s("alarmHour" to hh.toString(), "alarmMin" to mm.toString(), "alarmOn" to "1") }, h, m, false).show()
            }.padding(horizontal = 4.dp),
        )
        val days = b.int("alarmDays", 31)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                val on = days and (1 shl i) != 0
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (on) BGold else BCard2)
                        .clickable { s("alarmDays" to (days xor (1 shl i)).toString()) },
                    contentAlignment = Alignment.Center,
                ) { Text(d, color = if (on) BBg else BSub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val max = b.int("maxVolume", 100).coerceAtMost(60)
        Label("Starts at volume ${b.int("alarmVolFrom")}, fades up to ${b.int("alarmVolTo")}")
        IntSlider(b.int("alarmVolFrom"), 0, max) { s("alarmVolFrom" to it.toString()) }
        IntSlider(b.int("alarmVolTo"), 0, max) { s("alarmVolTo" to it.toString()) }
        Label("Fade over")
        Chips(listOf(1, 5, 10, 15, 30), b.int("alarmRamp", 5), { "$it min" }) { s("alarmRamp" to it.toString()) }
        Sub("Plays YouTube Music and puts the home screen (clock + weather) back on top.")
        Spacer(Modifier.height(8.dp))
        SmallButton("Test it now") { onTest() }
    }
}

@Composable
private fun PhotoCard(
    tv: TvClient,
    b: BedroomInfo,
    set: (Array<out Pair<String, String>>) -> Unit,
    onShow: () -> Unit,
    onMsg: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var confirm by remember { mutableStateOf<PhotoItem?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    fun s(vararg p: Pair<String, String>) = set(p)
    if (showOptions) PhotoOptions(b, set) { showOptions = false }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            for ((i, u) in uris.withIndex()) {
                var name = "photo-${System.currentTimeMillis()}.jpg"
                var size = -1L
                runCatching {
                    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                }
                if (size <= 0) {
                    size = runCatching { ctx.contentResolver.openInputStream(u)?.use { it.skip(Long.MAX_VALUE) } ?: -1L }.getOrDefault(-1L)
                }
                if (size <= 0) continue
                busy = "Sending ${i + 1} of ${uris.size}…"
                progress = 0f
                val r = tv.uploadPhoto(name, size, { ctx.contentResolver.openInputStream(u) }, b.str("frameAlbum")) { sent -> progress = sent.toFloat() / size }
                if (r?.optBoolean("ok") == true) ok++
            }
            busy = null
            onMsg("Added $ok of ${uris.size} photos")
            tv.refreshPhotos()
            tv.refreshBedroom()
        }
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title("Photo frame")
                Sub("${tv.photos.size} photos · also syncs with the TV Photo Frame folder on your PC")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("🖼  Show on TV", BAccent, BBg, Modifier.weight(1f)) { onShow() }
            BigButton("＋ Add photos", BCard2, BText, Modifier.weight(1f)) { if (busy == null) picker.launch("image/*") }
        }
        busy?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = BText, fontSize = 13.sp)
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        val album = b.str("frameAlbum")
        Sub(
            "Showing: " + (if (album.isBlank()) "all photos" else "\"$album\" album") + " · " +
                (if (b.bool("frameShuffle")) "shuffled" else "newest first") + " · ${b.int("frameSecs", 15)} s each",
        )
        Spacer(Modifier.height(8.dp))
        SmallButton("⚙  Photo frame options") { showOptions = true }
        if (tv.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Sub("Tap a photo to remove it from the frame.")
            Spacer(Modifier.height(6.dp))
            tv.photos.take(60).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { p -> Thumb(tv, p, Modifier.weight(1f)) { confirm = p } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (tv.photos.size > 60) Sub("…and ${tv.photos.size - 60} more")
        }
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove this photo?") },
            text = { Text("${p.name} leaves the TV's photo frame. It's also moved out of the PC folder at the next sync (into its Removed folder).") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { tv.action("photodel", "name" to p.name); tv.refreshPhotos() }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = BCard,
        )
    }
}

@Composable
private fun Thumb(tv: TvClient, p: PhotoItem, modifier: Modifier, onClick: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, p.name) { value = tv.photoThumb(p.name) }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(BCard2).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        img?.let { Image(it, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text("…", color = BSub)
    }
}

@Composable
private fun StorageCard(tv: TvClient) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<StorageInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    fun load() {
        scope.launch { loading = true; info = tv.storage(); loading = false }
    }

    Card {
        Title("TV storage")
        val i = info
        if (i == null) {
            Sub(if (loading) "Checking…" else "See what's using the TV's space.")
            Spacer(Modifier.height(8.dp))
            SmallButton(if (loading) "Checking…" else "Check storage") { if (!loading) load() }
            return@Card
        }
        val used = (i.total - i.free).coerceAtLeast(0)
        Sub("${gb(i.free)} free of ${gb(i.total)} · app caches ${mb(i.cacheTotal)}")
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(BCard2)) {
            val frac = if (i.total > 0) used.toFloat() / i.total else 0f
            Box(
                Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (frac > 0.9f) BWarn else BAccent),
            )
        }
        Spacer(Modifier.height(10.dp))
        BigButton(if (loading) "Cleaning…" else "🧹  Clean up app caches", BCard2, BText) {
            if (!loading) scope.launch {
                loading = true
                val r = tv.action("cleancache")
                note = if (r?.optBoolean("ok") == true) "Freed ${mb(r.optLong("freed"))}" else "The TV didn't allow cleaning caches"
                info = tv.storage()
                loading = false
            }
        }
        note?.let { Text(it, color = BGood, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        i.note?.let { Sub(it) }
        Spacer(Modifier.height(10.dp))
        i.apps.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, color = BText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(mb(a.total), color = BSub, fontSize = 13.sp)
            }
        }
        Sub("To remove an app: TV Settings → Apps.")
    }
}

private fun mb(b: Long) = if (b >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", b / 1073741824.0) else "${b / 1048576} MB"
private fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1073741824.0)

// ---------------------------------------------------------------- small pieces

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BCard)
            .border(1.dp, Color(0xFF1F2B45), RoundedCornerShape(20.dp)).padding(16.dp),
        content = content,
    )
}

@Composable private fun Title(t: String) = Text(t, color = BText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
@Composable private fun Sub(t: String) = Text(t, color = BSub, fontSize = 12.sp)
@Composable private fun Label(t: String) = Text(t, color = BSub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
private fun BigButton(label: String, bg: Color, fg: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(BCard2).clickable { onClick() }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = BAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Segment(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(if (on) BAccent else BCard2).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) BBg else BText, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) BAccent else BCard2)
                    .clickable { onPick(o) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), color = if (on) BBg else BText, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = BText, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Slider that only tells the TV when you let go. */
@Composable
private fun IntSlider(value: Int, min: Int, max: Int, onDone: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Slider(
        value = v.coerceIn(min.toFloat(), max.toFloat().coerceAtLeast(min + 1f)),
        onValueChange = { v = it },
        onValueChangeFinished = { onDone(v.toInt()) },
        valueRange = min.toFloat()..max.toFloat().coerceAtLeast(min + 1f),
        colors = SliderDefaults.colors(thumbColor = BAccent, activeTrackColor = BAccent),
    )
}


/** Photo frame options: which album, order, timing, what's on screen. */
@Composable
private fun PhotoOptions(b: BedroomInfo, set: (Array<out Pair<String, String>>) -> Unit, onClose: () -> Unit) {
    fun s(vararg p: Pair<String, String>) = set(p)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(BBg).padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Photo frame options", color = BText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SmallButton("Done") { onClose() }
            }
            Spacer(Modifier.height(14.dp))
            Card {
                Title("Which photos")
                Sub("Albums are the subfolders inside the TV Photo Frame folder on your PC. New photos you add from the phone go into the album shown here.")
                Spacer(Modifier.height(8.dp))
                val albums = b.j.optJSONArray("albums")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val cur = b.str("frameAlbum")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("") + albums).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { a ->
                                Segment(if (a.isBlank()) "All photos" else a, cur == a, Modifier.weight(1f)) { s("frameAlbum" to a) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (albums.isEmpty()) Sub("No albums yet: make a subfolder in Documents\\TV Photo Frame on the PC, put photos in it, then press Sync photos.")
            }
            Spacer(Modifier.height(14.dp))
            Card {
                Title("Slideshow")
                SwitchRow("Shuffle", b.bool("frameShuffle")) { s("frameShuffle" to if (it) "1" else "0") }
                SwitchRow("Slow zoom (Ken Burns)", b.bool("frameZoom")) { s("frameZoom" to if (it) "1" else "0") }
                SwitchRow("Fill the screen (crops edges)", b.bool("frameFill")) { s("frameFill" to if (it) "1" else "0") }
                Label("Each photo shows for")
                Chips(listOf(8, 15, 30, 60, 120), b.int("frameSecs", 15), { if (it < 60) "$it s" else "${it / 60} min" }) { s("frameSecs" to it.toString()) }
            }
            Spacer(Modifier.height(14.dp))
            Card {
                Title("On screen")
                SwitchRow("Clock and date", b.bool("frameClock")) { s("frameClock" to if (it) "1" else "0") }
                SwitchRow("Temperature", b.bool("frameWeather")) { s("frameWeather" to if (it) "1" else "0") }
            }
            Spacer(Modifier.height(14.dp))
            Card {
                Title("Rest the TV after")
                Sub("If nobody touches anything, the TV rests after this long on the photo frame.")
                Spacer(Modifier.height(6.dp))
                Chips(listOf(30, 60, 120, 0), b.int("frameRest", 60), { if (it == 0) "Never" else if (it < 60) "$it min" else "${it / 60} h" }) {
                    s("frameRest" to it.toString())
                }
            }
            Spacer(Modifier.height(14.dp))
            Sub("On the TV: Left / Right = previous / next, OK = pause, Back = home. On a touchscreen or the live view: tap the left or right side, the middle to pause, ← Home to leave.")
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** TV backlight slider (the same setting as the TV's own picture menu). Updates the TV as you slide. */
@Composable
fun BrightnessSlider(tv: TvClient, dark: Boolean = true) {
    val scope = rememberCoroutineScope()
    var level by remember { mutableFloatStateOf(-1f) }
    var target by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tv.host, tv.key) {
        val st = tv.status?.backlight ?: -1
        level = (if (st >= 0) st else tv.backlight()).toFloat()
    }
    // Send while sliding, at most ~6 times a second.
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        tv.backlight(t)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (dark) BCard else BCard2)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☀  TV brightness", color = BText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (level < 0) "…" else "${level.toInt()}" + if (level >= 0 && level < 25) "  extra dim" else "", color = BSub, fontSize = 13.sp)
        }
        if (level >= 0) {
            Slider(
                value = level, onValueChange = { level = it; target = it.toInt() },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = BGold, activeTrackColor = BGold),
            )
        } else {
            Sub("Reading the TV's brightness…")
        }
        if (tv.status?.night == true) Sub("Night mode is on: turning it off puts your normal brightness back.")
    }
}
