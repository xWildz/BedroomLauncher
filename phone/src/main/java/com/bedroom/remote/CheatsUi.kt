package com.bedroom.remote

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val CBg = Color(0xFF0B1120)
private val CCard = Color(0xFF141E33)
private val CCard2 = Color(0xFF1E2A44)
private val CText = Color(0xFFEEF3FB)
private val CSub = Color(0xFF93A4C0)
private val CAccent = Color(0xFF8FB8FF)
private val CGood = Color(0xFF5FD38D)
private val CWarn = Color(0xFFFFB36B)

private data class CheatGame(val label: String, val path: String, val core: String)
private class Cheat(desc: String, code: String, enable: Boolean) {
    var desc by mutableStateOf(desc)
    var code by mutableStateOf(code)
    var enable by mutableStateOf(enable)
}

/** Cheat codes per game (RetroArch). Games come from RetroArch's history (it knows which emulator each uses). */
@Composable
fun CheatsDialog(tv: TvClient, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<CheatGame>?>(null) }
    var game by remember { mutableStateOf<CheatGame?>(null) }
    val cheats = remember { mutableStateListOf<Cheat>() }
    var note by remember { mutableStateOf<String?>(null) }
    var noteGood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var newDesc by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val j = tv.action("cheatgames")
        val arr = j?.optJSONArray("games")
        games = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { CheatGame(it.optString("label"), it.optString("path"), it.optString("core")) }
        if (j == null) note = "Can't reach the TV"
    }
    LaunchedEffect(game) {
        val g = game ?: return@LaunchedEffect
        cheats.clear(); dirty = false; note = null
        val j = tv.action("cheats", "path" to g.path)
        if (j == null || !j.optBoolean("ok")) { note = j?.optString("error") ?: "Can't reach the TV"; noteGood = false; return@LaunchedEffect }
        val arr = j.optJSONArray("cheats") ?: JSONArray()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { cheats += Cheat(it.optString("desc"), it.optString("code"), it.optBoolean("enable")) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(CBg).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(game?.label ?: "Cheat codes", color = CText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (game != null) TextButton(onClick = { game = null; note = null }) { Text("Games", color = CAccent) }
                TextButton(onClick = onClose) { Text("Close", color = CAccent) }
            }
            note?.let { Text(it, color = if (noteGood) CGood else CWarn, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            val g = game
            if (g == null) {
                Text("Pick a game. Games show up here once you've played them in RetroArch.", color = CSub, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                val list = games
                if (list == null) Text("Loading…", color = CSub)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list) { _, it ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CCard).clickable { game = it }.padding(12.dp)) {
                            Text(it.label, color = CText, fontSize = 15.sp)
                            Text(it.core, color = CSub, fontSize = 12.sp)
                        }
                    }
                }
                return@Column
            }
            Text("Emulator: ${g.core} · On = applied when the game starts", color = CSub, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cheats) { i, c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CCard).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.desc.ifBlank { "Cheat ${i + 1}" }, color = CText, fontSize = 14.sp)
                            Text(c.code, color = CSub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                        }
                        Switch(checked = c.enable, onCheckedChange = { c.enable = it; dirty = true })
                        TextButton(onClick = { cheats.removeAt(i); dirty = true }) { Text("✕", color = CWarn) }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CCard2).padding(10.dp)) {
                        Text("Add a cheat", color = CText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(newDesc, { newDesc = it }, label = { Text("Name (e.g. Infinite money)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(newCode, { newCode = it }, label = { Text("Code (GameShark / Action Replay / Game Genie…)") }, modifier = Modifier.fillMaxWidth())
                        Text("A code with several lines is fine: paste it as-is.", color = CSub, fontSize = 11.sp)
                        Box(
                            Modifier.padding(top = 6.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(CAccent)
                                .clickable(enabled = newCode.isNotBlank()) {
                                    cheats += Cheat(newDesc.trim(), newCode.trim().replace(Regex("\\s+"), "+"), true)
                                    newDesc = ""; newCode = ""; dirty = true
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text("Add", color = CBg, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(if (dirty && !busy) CGood else CCard2)
                    .clickable(enabled = dirty && !busy) {
                        busy = true
                        scope.launch {
                            val arr = JSONArray()
                            cheats.forEach { arr.put(JSONObject().put("desc", it.desc).put("code", it.code).put("enable", it.enable)) }
                            val r = tv.action("cheatsave", "path" to g.path, "data" to arr.toString())
                            busy = false
                            if (r != null && r.optBoolean("ok")) {
                                dirty = false; noteGood = true
                                note = "Saved ${r.optInt("saved")} cheat(s). They turn on next time you start this game" + if (r.optBoolean("autoApply")) "." else " (turn on Auto-Apply Cheats in RetroArch)."
                            } else { noteGood = false; note = r?.optString("error") ?: "Can't reach the TV" }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (busy) "Saving…" else if (dirty) "Save to the TV" else "Saved", color = if (dirty && !busy) CBg else CSub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Text("RetroArch must be closed while saving (it keeps its own copy while a game is open).", color = CSub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
