package com.bedroom.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GCard2 = Color(0xFF22304D)
private val GText = Color(0xFFEEF3FB)
private val GSub = Color(0xFF93A4C0)
private val GAccent = Color(0xFF8FB8FF)
private val GGood = Color(0xFF5FD38D)
private val GWarn = Color(0xFFFFB36B)
private val GGold = Color(0xFFFFD27A)
private val GBg = Color(0xFF0B1120)

private val STATE_RE = Regex("""\.state(\d+|\.auto)?$""", RegexOption.IGNORE_CASE)

/** "My Game.gba" and "My Game.srm" / ".state3" share the key "my game". */
private fun gameKey(name: String) = name.substringBeforeLast('.').lowercase()
private fun saveKey(name: String) =
    (if (STATE_RE.containsMatchIn(name)) name.replace(STATE_RE, "") else name.substringBeforeLast('.')).lowercase()

private class Picked(val uri: Uri, val name: String, val size: Long)

private fun describe(ctx: Context, uri: Uri): Picked? {
    return try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            Picked(uri, name, size)
        }
    } catch (e: Exception) {
        null
    }
}

/** Size unknown (some cloud files): copy to the phone's cache first so we know how much to send. */
private fun withKnownSize(ctx: Context, p: Picked): Picked? {
    if (p.size > 0) return p
    return try {
        val f = java.io.File(ctx.cacheDir, "upload-" + p.name.hashCode())
        ctx.contentResolver.openInputStream(p.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return null
        Picked(Uri.fromFile(f), p.name, f.length())
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ActionButton(label: String, main: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(if (main) GAccent else GCard2)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (main) GBg else GText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * Games tab: every game on the TV, grouped by system, with its save files listed under it.
 * Send games, send saves and back up saves from one place.
 */
@Composable
fun GamesTab(tv: TvClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var results by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var openSystems by remember { mutableStateOf(setOf<String>()) }
    var openGames by remember { mutableStateOf(setOf<String>()) }
    var pendingDownload by remember { mutableStateOf<SaveFile?>(null) }
    var askStop by remember { mutableStateOf<List<Pair<String, ByteArray>>?>(null) }
    var browsing by remember { mutableStateOf(false) }

    suspend fun refreshAll() {
        tv.refreshRoms()
        tv.refreshSaves()
    }
    LaunchedEffect(tv.host, tv.key) { refreshAll(); tv.refreshLastGame(); tv.refreshPlaytime() }

    // ---- saves: send (asks before closing RetroArch), back up, copy one ----
    fun sendSaves(files: List<Pair<String, ByteArray>>, stop: Boolean) = scope.launch {
        val lines = mutableListOf<Pair<String, Boolean>>()
        for ((i, f) in files.withIndex()) {
            busy = "Sending save ${f.first} (${i + 1}/${files.size})"
            total = 0
            val r = tv.uploadSave(f.first, f.second, stop)
            when {
                r == null -> lines += "${f.first}: can't reach the TV" to false
                r.optBoolean("running") -> {
                    busy = null
                    askStop = files.drop(i)
                    results = lines
                    return@launch
                }
                !r.optBoolean("ok") -> lines += "${f.first}: ${r.optString("error")}" to false
                else -> {
                    val res = r.optJSONArray("results")
                    for (k in 0 until (res?.length() ?: 0)) {
                        val o = res!!.getJSONObject(k)
                        lines += "${o.optString("name")}: ${o.optString("result")}" to !o.optString("result").startsWith("skipped")
                    }
                }
            }
        }
        busy = null
        results = lines
        refreshAll()
    }

    val savesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = "Reading files…"
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    describe(ctx, u)?.let { p -> ctx.contentResolver.openInputStream(u)?.use { p.name to it.readBytes() } }
                }
            }
            sendSaves(files, stop = false)
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = "Backing up every save…"
            total = 0
            val out = withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri) }
            val ok = out != null && tv.downloadSave(null, out)
            busy = null
            results = listOf((if (ok) "All saves backed up to your phone." else "Backup failed. Is the TV on?") to ok)
        }
    }
    val saveOnePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingDownload
        pendingDownload = null
        if (uri != null && f != null) scope.launch {
            busy = "Copying ${f.name}…"
            total = 0
            val out = withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri) }
            val ok = out != null && tv.downloadSave(f.path, out)
            busy = null
            results = listOf((if (ok) "${f.name} copied to your phone." else "Couldn't copy ${f.name}.") to ok)
        }
    }

    // ---- games: stream each file with progress ----
    val gamesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val lines = mutableListOf<Pair<String, Boolean>>()
            for ((i, u) in uris.withIndex()) {
                busy = "Preparing…"
                val p = withContext(Dispatchers.IO) { describe(ctx, u)?.let { withKnownSize(ctx, it) } }
                if (p == null) {
                    lines += "Couldn't read one of the files" to false
                    continue
                }
                busy = "Sending ${p.name} (${i + 1}/${uris.size})"
                sent = 0
                total = p.size
                val r = tv.uploadRom(p.name, p.size, { ctx.contentResolver.openInputStream(p.uri) }) { sent = it }
                lines += when {
                    r == null -> "${p.name}: lost the connection to the TV" to false
                    r.optBoolean("ok") -> "${p.name} → ${r.optString("folder")}" + (if (r.optBoolean("replaced")) " (replaced)" else "") to true
                    else -> "${p.name}: ${r.optString("error")}" to false
                }
                if (p.uri.scheme == "file") p.uri.path?.let { java.io.File(it).delete() }
            }
            busy = null
            total = 0
            results = lines
            refreshAll()
        }
    }

    // Group saves under their game.
    val saves = tv.saves.orEmpty()
    val gameKeys = tv.roms.orEmpty().flatMap { s -> s.files.map { gameKey(it.name) } }.toSet()
    val savesByGame = saves.groupBy { saveKey(it.name) }
    val loose = saves.filter { saveKey(it.name) !in gameKeys }

    LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            HeroCard(Ui.HeroGreen) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Badge("🎮", Ui.Green, size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Games & saves", color = GText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("RetroArch on the TV · use games you own", color = GSub, fontSize = 12.sp)
                    }
                }
                tv.lastGame?.let { g ->
                    Spacer(Modifier.height(14.dp))
                    ActionButton("▶  Continue $g", true, busy == null, Modifier.fillMaxWidth()) {
                        scope.launch { tv.action("continuegame") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Send games", tv.lastGame == null, busy == null, Modifier.weight(1f)) { gamesPicker.launch(arrayOf("*/*")) }
                    ActionButton("Send saves", false, busy == null, Modifier.weight(1f)) { savesPicker.launch(arrayOf("*/*")) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Back up saves", false, busy == null, Modifier.weight(1f)) {
                        backupPicker.launch("retroarch-saves-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip")
                    }
                    ActionButton("Browse files", false, busy == null, Modifier.weight(1f)) { browsing = true }
                }
                Text(
                    "Games go into a folder per system automatically.",
                    color = GSub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---- play time (counted on the TV while RetroArch is on screen)
        tv.playtime?.takeIf { it.isNotEmpty() }?.let { rows ->
            item {
                var all by remember { mutableStateOf(false) }
                DashCard(
                    "Play time", "⏱", Ui.Teal,
                    subtitle = "${hours(rows.sumOf { it.secs })} in total",
                ) {
                    val top = rows.maxOf { it.secs }.coerceAtLeast(1)
                    (if (all) rows else rows.take(5)).forEach { g ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.label, color = GText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(hours(g.secs), color = GGold, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(GCard2)) {
                                Box(
                                    Modifier.fillMaxWidth((g.secs.toFloat() / top).coerceIn(0.03f, 1f)).height(5.dp)
                                        .background(Brush.horizontalGradient(Ui.Teal)),
                                )
                            }
                            if (g.last > 0) Text(
                                "Last played " + DateUtils.getRelativeTimeSpanString(g.last),
                                color = GSub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (rows.size > 5) Text(
                        if (all) "Show less" else "Show all ${rows.size}", color = GAccent, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).clickable { all = !all },
                    )
                }
            }
        }

        busy?.let { label ->
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.Card).border(1.dp, Ui.Edge, RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Text(label, color = GText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (total > 0) {
                        Spacer(Modifier.height(8.dp))
                        val frac = (sent.toFloat() / total).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(GCard2)) {
                            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(GAccent))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (sent >= total) "Saving it on the TV…"
                            else "${Formatter.formatShortFileSize(ctx, sent)} of ${Formatter.formatShortFileSize(ctx, total)}",
                            color = GSub, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.Card).border(1.dp, Ui.Edge, RoundedCornerShape(18.dp))
                        .clickable { results = emptyList() }.padding(14.dp),
                ) {
                    results.forEach { (line, ok) -> Text(line, color = if (ok) GGood else GWarn, fontSize = 13.sp) }
                    Text("Tap to hide", color = GSub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        item {
            SectionHeader("Library", "▤", Ui.Purple)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val games = tv.roms?.sumOf { it.files.size }
                val err = tv.romsError ?: tv.savesError
                Text(
                    when {
                        err != null -> err
                        games == null -> "Loading…"
                        else -> "$games games · ${saves.size} saves" +
                            if (tv.romsFree >= 0) " · ${Formatter.formatShortFileSize(ctx, tv.romsFree)} free" else ""
                    },
                    color = if (err != null) GWarn else GSub, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { refreshAll() } }, enabled = busy == null) { Text("Refresh", color = GAccent) }
            }
            Text("Tap a system to see its games, a game to see its saves, and a save to copy it to your phone.", color = GSub, fontSize = 11.sp)
        }

        if (tv.roms?.isEmpty() == true) {
            item { Text("No games on the TV yet. Tap Send games.", color = GSub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        items(tv.roms.orEmpty(), key = { "sys:" + it.system }) { sys ->
            val expanded = sys.system in openSystems
            val sysSaves = sys.files.sumOf { savesByGame[gameKey(it.name)]?.size ?: 0 }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.Card).border(1.dp, Ui.Edge, RoundedCornerShape(18.dp))) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { openSystems = if (expanded) openSystems - sys.system else openSystems + sys.system }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sys.system, color = GText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        "${sys.files.size} games" + (if (sysSaves > 0) " · $sysSaves saves" else "") + "  " + (if (expanded) "▴" else "▾"),
                        color = GSub, fontSize = 13.sp,
                    )
                }
                if (expanded) {
                    sys.files.forEach { f ->
                        val key = sys.system + "/" + f.name
                        val gs = savesByGame[gameKey(f.name)].orEmpty()
                        val gameOpen = key in openGames
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = gs.isNotEmpty()) { openGames = if (gameOpen) openGames - key else openGames + key }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.name, color = GText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (gs.isNotEmpty()) {
                                Box(
                                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(GCard2)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) { Text("${gs.size} save" + (if (gs.size > 1) "s" else "") + if (gameOpen) " ▴" else " ▾", color = GGold, fontSize = 11.sp) }
                            }
                            Text(Formatter.formatShortFileSize(ctx, f.size), color = GSub, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (gameOpen) {
                            gs.forEach { s -> SaveRow(s, enabled = busy == null) { pendingDownload = s; saveOnePicker.launch(s.name) } }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        if (loose.isNotEmpty()) {
            item {
                val expanded = "~loose" in openSystems
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.Card).border(1.dp, Ui.Edge, RoundedCornerShape(18.dp))) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { openSystems = if (expanded) openSystems - "~loose" else openSystems + "~loose" }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text("Saves without a matching game", color = GText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${loose.size}  " + (if (expanded) "▴" else "▾"), color = GSub, fontSize = 13.sp)
                    }
                    if (expanded) {
                        Text(
                            "A save only loads if its name matches the game's file name (Game.gba → Game.srm).",
                            color = GSub, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        loose.forEach { s -> SaveRow(s, enabled = busy == null) { pendingDownload = s; saveOnePicker.launch(s.name) } }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (browsing) FilesBrowser(tv) { browsing = false; scope.launch { refreshAll() } }

    askStop?.let { files ->
        AlertDialog(
            onDismissRequest = { askStop = null },
            title = { Text("RetroArch is open on the TV") },
            text = {
                Text(
                    "RetroArch would overwrite the new save when it closes. Quit the game first, " +
                        "or let the app close RetroArch now (anything not saved in the game is lost).",
                )
            },
            confirmButton = { TextButton(onClick = { askStop = null; sendSaves(files, stop = true) }) { Text("Close it and send") } },
            dismissButton = { TextButton(onClick = { askStop = null }) { Text("Cancel") } },
            containerColor = Color(0xFF141E33),
        )
    }
}

private fun hours(secs: Long): String {
    val m = secs / 60
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} m"
}

@Composable
private fun SaveRow(s: SaveFile, enabled: Boolean, onCopy: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onCopy() }.padding(start = 28.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.state) GAccent else GGold))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = GText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (s.state) "Save state" else "Game save") + " · " + DateUtils.getRelativeTimeSpanString(s.modified) +
                    " · " + Formatter.formatShortFileSize(ctx, s.size),
                color = GSub, fontSize = 11.sp, maxLines = 1,
            )
        }
        Text("Copy", color = GAccent, fontSize = 12.sp)
    }
}
