package com.bedroom.remote

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FBg = Color(0xFF0B1120)
private val FCard = Color(0xFF18233A)
private val FCard2 = Color(0xFF22304D)
private val FText = Color(0xFFEEF3FB)
private val FSub = Color(0xFF93A4C0)
private val FAccent = Color(0xFF8FB8FF)
private val FGood = Color(0xFF5FD38D)
private val FWarn = Color(0xFFFFB36B)
private val FGold = Color(0xFFFFD27A)

private enum class SortBy { Name, Size, Date }

@Composable
private fun Pill(label: String, on: Boolean = false, color: Color = FText, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) FAccent else FCard2)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (on) FBg else color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Full-screen browser for RetroArch's games & saves on the TV: sort, rename/move, delete, copy in and out. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesBrowser(tv: TvClient, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<TvFolder?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(SortBy.Name) }
    var desc by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var menuFor by remember { mutableStateOf<TvFile?>(null) }
    var renaming by remember { mutableStateOf<TvFile?>(null) }
    var moving by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<TvFile>?>(null) }
    var pendingCopy by remember { mutableStateOf<TvFile?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(path, reload) {
        loading = true
        val (f, e) = tv.listFiles(path)
        if (f != null) {
            folder = f
            error = null
            selected = selected.filter { s -> f.items.any { it.path == s } }.toSet()
        } else {
            error = e
        }
        loading = false
    }
    fun refresh() { reload++ }
    fun goUp() {
        val f = folder ?: return
        if (f.path != f.root) { path = f.path.substringBeforeLast('/'); selected = emptySet() } else onClose()
    }

    val copyOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingCopy
        pendingCopy = null
        if (uri != null && f != null) scope.launch {
            busy = "Copying ${f.name} to your phone…"
            total = 0
            val out = withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri) }
            val ok = out != null && tv.downloadFile(f.path, out)
            busy = null
            message = (if (ok) "${f.name} copied to your phone." else "Couldn't copy ${f.name}.") to ok
        }
    }
    val sendIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = folder?.path ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val lines = mutableListOf<String>()
            var allOk = true
            for ((i, u) in uris.withIndex()) {
                val (name, size) = withContext(Dispatchers.IO) {
                    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) (c.getString(0) ?: "file") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
                    } ?: ("file" to -1L)
                }
                var src: Uri = u
                var len = size
                if (len <= 0) {
                    val tmp = java.io.File(ctx.cacheDir, "fup-" + name.hashCode())
                    withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { i2 -> tmp.outputStream().use { o -> i2.copyTo(o) } } }
                    src = Uri.fromFile(tmp)
                    len = tmp.length()
                }
                busy = "Sending $name (${i + 1}/${uris.size})"
                sent = 0
                total = len
                val r = tv.uploadFile(dir, name, len, { ctx.contentResolver.openInputStream(src) }) { sent = it }
                if (r == null || !r.optBoolean("ok")) {
                    allOk = false
                    lines += "$name: " + (r?.optString("error") ?: "lost the connection")
                } else {
                    lines += "Sent $name"
                }
                if (src.scheme == "file") src.path?.let { java.io.File(it).delete() }
            }
            busy = null
            total = 0
            message = lines.joinToString("\n") to allOk
            refresh()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (selected.isNotEmpty()) selected = emptySet() else goUp() }
        Column(Modifier.fillMaxSize().background(FBg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(FCard).clickable { goUp() },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = FText, fontSize = 24.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Games & saves on the TV", color = FText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val f = folder
                    val rel = if (f == null) "" else f.path.removePrefix(f.root).trim('/')
                    Text(
                        if (rel.isEmpty()) "RetroArch" else "RetroArch / " + rel.replace("/", " / "),
                        color = FSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(FCard).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = FSub, fontSize = 16.sp) }
            }

            // Sort + actions
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SortBy.values().forEach { s ->
                    Pill(s.name + if (sortBy == s) (if (desc) " ↓" else " ↑") else "", on = sortBy == s) {
                        if (sortBy == s) desc = !desc else { sortBy = s; desc = s != SortBy.Name }
                    }
                }
                Spacer(Modifier.weight(1f))
                Pill("↻", enabled = !loading) { refresh() }
            }
            val inside = folder?.let { it.path != it.root } == true
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("+ Send files here", color = FAccent, enabled = inside && busy == null) { sendIn.launch(arrayOf("*/*")) }
                Pill("New folder", enabled = inside && busy == null) { newFolder = true }
            }
            if (!inside) Text("Open ROMs, saves or states to add or change files.", color = FSub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            busy?.let { label ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(FCard).padding(12.dp)) {
                    Text(label, color = FText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (total > 0) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(FCard2)) {
                            Box(Modifier.fillMaxWidth((sent.toFloat() / total).coerceIn(0f, 1f)).height(5.dp).background(FAccent))
                        }
                    }
                }
            }
            message?.let { (text, ok) ->
                Text(
                    text, color = if (ok) FGood else FWarn, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { message = null },
                )
            }

            // Selection bar
            if (selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(FCard2).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${selected.size} selected", color = FText, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    Pill("Move to…") { moving = true }
                    Pill("Delete", color = FWarn) {
                        confirmDelete = folder?.items?.filter { it.path in selected }
                    }
                    Pill("✕") { selected = emptySet() }
                }
            }

            error?.let { Text(it, color = FWarn, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }

            val items = folder?.items.orEmpty().let { list ->
                val cmp: Comparator<TvFile> = when (sortBy) {
                    SortBy.Name -> compareBy { it.name.lowercase() }
                    SortBy.Size -> compareBy { it.size }
                    SortBy.Date -> compareBy { it.modified }
                }
                list.sortedWith(compareBy<TvFile> { !it.dir }.then(if (desc) cmp.reversed() else cmp))
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (folder != null && items.isEmpty()) {
                    item { Text("This folder is empty.", color = FSub, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
                items(items, key = { it.path }) { f ->
                    val sel = f.path in selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (sel) FCard2 else FCard)
                            .then(if (sel) Modifier.border(1.dp, FAccent, RoundedCornerShape(14.dp)) else Modifier)
                            .combinedClickable(
                                onClick = {
                                    when {
                                        selected.isNotEmpty() && !f.locked -> selected = if (sel) selected - f.path else selected + f.path
                                        f.dir -> { path = f.path; selected = emptySet() }
                                        else -> menuFor = f
                                    }
                                },
                                onLongClick = { if (!f.locked) selected = if (sel) selected - f.path else selected + f.path },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (f.dir) FCard2 else FBg),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (f.dir) "▤" else "▪", color = if (f.dir) FGold else FAccent, fontSize = 16.sp) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = FText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (f.dir) "Folder" else Formatter.formatShortFileSize(ctx, f.size)) + " · " +
                                    DateUtils.getRelativeTimeSpanString(f.modified) + if (f.locked) " · locked" else "",
                                color = FSub, fontSize = 11.sp, maxLines = 1,
                            )
                        }
                        if (f.dir) Text("›", color = FSub, fontSize = 20.sp)
                        else if (!f.locked) Text("⋯", color = FSub, fontSize = 18.sp)
                    }
                }
                item {
                    val free = folder?.free ?: -1
                    Text(
                        "Hold a file or folder to select several (to move or delete). " +
                            if (free >= 0) "${Formatter.formatShortFileSize(ctx, free)} free on the TV." else "",
                        color = FSub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }

        // ---- dialogs ----
        menuFor?.let { f ->
            AlertDialog(
                onDismissRequest = { menuFor = null },
                title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${Formatter.formatShortFileSize(ctx, f.size)} · changed ${DateUtils.getRelativeTimeSpanString(f.modified)}", color = FSub, fontSize = 13.sp)
                        if (f.locked) Text("Locked: RetroArch needs this file.", color = FSub, fontSize = 12.sp)
                        TextButton(onClick = { menuFor = null; pendingCopy = f; copyOut.launch(f.name) }) { Text("Copy to phone") }
                        if (!f.locked) {
                            TextButton(onClick = { menuFor = null; renaming = f }) { Text("Rename") }
                            TextButton(onClick = { menuFor = null; selected = setOf(f.path); moving = true }) { Text("Move to…") }
                            TextButton(onClick = { menuFor = null; confirmDelete = listOf(f) }) { Text("Delete", color = FWarn) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } },
                containerColor = Color(0xFF141E33),
            )
        }

        renaming?.let { f ->
            var name by remember(f.path) { mutableStateOf(f.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename") },
                text = {
                    Column {
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                        if (!f.dir && f.path.contains("/ROMs/")) {
                            Text(
                                "Tip: if you rename a game, rename its saves to match (Game.gba → Game.srm), or RetroArch won't find them.",
                                color = FSub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        renaming = null
                        scope.launch {
                            val (ok, msg) = tv.fileOp("rename", f.path, name.trim())
                            message = msg to ok
                            refresh()
                        }
                    }) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
                containerColor = Color(0xFF141E33),
            )
        }

        if (moving) {
            val root = folder?.root ?: ""
            var dest by remember { mutableStateOf(folder?.path?.removePrefix(root)?.trim('/') ?: "ROMs") }
            AlertDialog(
                onDismissRequest = { moving = false },
                title = { Text("Move ${selected.size} item" + if (selected.size == 1) "" else "s") },
                text = {
                    Column {
                        Text("Folder inside RetroArch, for example ROMs/GBA or saves:", color = FSub, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = dest, onValueChange = { dest = it }, singleLine = true)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ROMs", "saves", "states").forEach { s -> Pill(s) { dest = s } }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        moving = false
                        val targets = selected.toList()
                        selected = emptySet()
                        scope.launch {
                            var okCount = 0
                            var lastErr: String? = null
                            for (p in targets) {
                                val (ok, msg) = tv.fileOp("rename", p, "$root/${dest.trim('/')}/${p.substringAfterLast('/')}")
                                if (ok) okCount++ else lastErr = msg
                            }
                            message = (if (lastErr == null) "Moved $okCount item(s) to ${dest.trim('/')}." else "Moved $okCount; $lastErr") to (lastErr == null)
                            refresh()
                        }
                    }) { Text("Move") }
                },
                dismissButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
                containerColor = Color(0xFF141E33),
            )
        }

        if (newFolder) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { newFolder = false },
                title = { Text("New folder") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Folder name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        newFolder = false
                        val p = folder?.path
                        if (p != null) scope.launch {
                            val (ok, msg) = tv.fileOp("mkdir", p, name.trim())
                            message = msg to ok
                            refresh()
                        }
                    }) { Text("Make") }
                },
                dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
                containerColor = Color(0xFF141E33),
            )
        }

        confirmDelete?.let { list ->
            val folders = list.count { it.dir }
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(if (list.size == 1) "Delete ${list[0].name}?" else "Delete ${list.size} items?") },
                text = {
                    Text(
                        "This permanently removes " + (if (folders > 0) "the selected folders and everything in them" else "the selected files") +
                            " from the TV. It can't be undone. Back up saves first if you might want them.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        selected = emptySet()
                        scope.launch {
                            var okCount = 0
                            var lastErr: String? = null
                            for (f in list) {
                                val (ok, msg) = tv.fileOp("delete", f.path)
                                if (ok) okCount++ else lastErr = msg
                            }
                            message = (if (lastErr == null) "Deleted $okCount item(s)." else "Deleted $okCount; $lastErr") to (lastErr == null)
                            refresh()
                        }
                    }) { Text("Delete", color = FWarn) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
                containerColor = Color(0xFF141E33),
            )
        }
    }
}
