package com.bedroom.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val IBg = Color(0xFF0B1120)
private val ICard = Color(0xFF141E33)
private val ICard2 = Color(0xFF1E2A44)
private val IText = Color(0xFFEEF3FB)
private val ISub = Color(0xFF93A4C0)
private val IAccent = Color(0xFF8FB8FF)
private val IGood = Color(0xFF5FD38D)
private val IWarn = Color(0xFFFFB36B)

data class InfoSection(val title: String, val rows: List<Pair<String, String>>)
data class SpeedRow(val at: Long, val down: Double, val up: Double?, val ping: Long?)

/** Everything the TV's chip reports (same as the TV's "TV info" screen), with a search box. */
@Composable
fun TvInfoDialog(tv: TvClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf<List<InfoSection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        sections = null
        error = null
        val r = tv.tvInfo()
        if (r == null) error = "Couldn't read the TV (is it on? Bedroom Home 2.2 or newer is needed)" else sections = r
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(IBg).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TV info", color = IText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { reload++ }) { Text("Refresh", color = IAccent) }
                TextButton(onClick = onClose) { Text("Close", color = IAccent) }
            }
            Text("Everything the TV's chip and Android report. Read only.", color = ISub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search everything (e.g. hevc, widevine, wifi)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val list = sections
            when {
                error != null -> Text(error!!, color = IWarn, fontSize = 14.sp)
                list == null -> Text("Reading the TV… (a few seconds)", color = ISub, fontSize = 14.sp)
                else -> {
                    val q = query.trim().lowercase(Locale.getDefault())
                    if (q.isEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            list.forEachIndexed { i, s ->
                                Box(
                                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (i == pick) IAccent else ICard2)
                                        .clickable { pick = i }.padding(horizontal = 12.dp, vertical = 7.dp),
                                ) { Text(s.title, color = if (i == pick) IBg else IText, fontSize = 13.sp) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val rows: List<Pair<String, String>> = if (q.isEmpty()) list.getOrNull(pick)?.rows.orEmpty()
                    else list.flatMap { s -> s.rows.filter { (k, v) -> k.lowercase().contains(q) || v.lowercase().contains(q) || s.title.lowercase().contains(q) }
                        .map { (k, v) -> (if (k.isBlank()) s.title else "${s.title} · $k") to v } }
                    if (q.isNotEmpty()) Text("${rows.size} matches", color = ISub, fontSize = 12.sp)
                    LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(ICard)) {
                        items(rows) { (k, v) ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                if (k.isNotBlank()) Text(k, color = ISub, fontSize = 12.sp)
                                Text(v, color = IText, fontSize = 13.sp, fontFamily = if (k.isBlank()) FontFamily.Monospace else FontFamily.Default)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Network speed test card (the TV measures its own connection). */
@Composable
fun SpeedCard(tv: TvClient) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<SpeedRow>>(emptyList()) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun apply(j: org.json.JSONObject?) {
        if (j == null) return
        if (!j.optBoolean("ok", true)) { error = j.optString("error"); return }
        val h = j.optJSONArray("history")
        if (h != null) history = (0 until h.length()).map { h.getJSONObject(it) }.map {
            SpeedRow(it.optLong("at"), it.optDouble("down"), if (it.isNull("up")) null else it.optDouble("up"), if (it.isNull("ping")) null else it.optLong("ping"))
        }
        verdict = if (j.isNull("verdict")) null else j.optString("verdict")
    }
    LaunchedEffect(tv.host, tv.key) { apply(tv.action("speedhistory")) }
    val last = history.lastOrNull()
    DashCard(
        "Internet speed", "⇅", Ui.Blue,
        subtitle = last?.let { "${fmtMbps(it.down)} down" + (it.up?.let { u -> " · ${fmtMbps(u)} up" } ?: "") + (it.ping?.let { p -> " · ${p} ms" } ?: "") } ?: "Not tested yet",
    ) {
        verdict?.let { Text(it, color = if (it.startsWith("Fast")) IGood else IWarn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        error?.let { Text(it, color = IWarn, fontSize = 13.sp) }
        if (history.size >= 2) {
            Spacer(Modifier.height(6.dp))
            val max = (history.maxOf { it.down }).coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                val n = history.size
                val bw = size.width / n
                history.forEachIndexed { i, r ->
                    val h = (r.down / max * size.height).toFloat()
                    drawRect(if (r.down >= 25) IGood else IWarn, Offset(i * bw + bw * 0.15f, size.height - h), Size(bw * 0.7f, h))
                }
            }
            Text("Last ${history.size} tests (download)", color = ISub, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (running) ICard2 else IAccent)
                .clickable(enabled = !running) {
                    running = true
                    error = null
                    scope.launch {
                        apply(tv.speedTest() ?: org.json.JSONObject().put("ok", false).put("error", "No answer from the TV"))
                        running = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (running) "Testing… (about 20 seconds)" else "Test the TV's internet speed", color = if (running) ISub else IBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        last?.let { Text("Last test " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(it.at)) + " · uses Cloudflare's public speed test", color = ISub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

private fun fmtMbps(v: Double) = if (v >= 100) "${v.toInt()} Mbps" else String.format(Locale.US, "%.1f Mbps", v)

/** Card in the Bedroom tab that opens TV info. */
@Composable
fun TvInfoCard(tv: TvClient) {
    var open by remember { mutableStateOf(false) }
    if (open) TvInfoDialog(tv) { open = false }
    DashCard("TV info", "ⓘ", Ui.Purple, subtitle = "Chip, memory, display, codecs, DRM, network… everything the TV reports") {
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(ICard2).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Text("Open TV info", color = IText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}
