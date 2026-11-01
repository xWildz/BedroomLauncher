package com.bedroom.remote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "Bedroom TV" in the phone's Share menu:
 *  - a link: YouTube opens in the TV's YouTube app, other links in whatever on the TV opens them
 *  - a photo: sent to the TV's photo frame (album "Shared from phone") and shown right away
 */
class ShareActivity : ComponentActivity() {

    private sealed interface What {
        data class Link(val url: String) : What
        data class Photo(val uri: Uri) : What
        data class Nothing(val why: String) : What
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TvClient(this)
        val what = read(intent)
        setContent { Sheet(tv, what) }
    }

    private fun read(i: Intent?): What {
        if (i?.action != Intent.ACTION_SEND) return What.Nothing("Nothing to share")
        val type = i.type.orEmpty()
        if (type.startsWith("image/")) {
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            return u?.let { What.Photo(it) } ?: What.Nothing("Couldn't read that picture")
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        // Apps often share "Title https://link" - take the first web link.
        val url = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
        return url?.let { What.Link(it) } ?: What.Nothing("There's no web link in that")
    }

    private fun nameAndSize(u: Uri): Pair<String, Long> {
        var name = "shared.jpg"
        var size = -1L
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    @androidx.compose.runtime.Composable
    private fun Sheet(tv: TvClient, what: What) {
        var line by remember { mutableStateOf("") }
        var progress by remember { mutableFloatStateOf(-1f) }
        var done by remember { mutableStateOf(false) }
        var ok by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (!tv.configured) {
                line = "Not paired with the TV yet. Open TV Remote first."
                done = true
                return@LaunchedEffect
            }
            when (what) {
                is What.Nothing -> { line = what.why; done = true }
                is What.Link -> {
                    line = "Opening on the TV…"
                    val r = tv.action("openurl", "url" to what.url)
                    ok = r?.optBoolean("ok") == true
                    line = if (r == null) "Can't reach the TV" else if (ok) "Opening on the TV" else r.optString("error", "The TV couldn't open it")
                    done = true
                }
                is What.Photo -> {
                    var (name, size) = withContext(Dispatchers.IO) { nameAndSize(what.uri) }
                    // The upload needs the exact size; some apps don't say, so copy it once to find out.
                    var open: () -> java.io.InputStream? = { contentResolver.openInputStream(what.uri) }
                    if (size <= 0) {
                        val tmp = java.io.File(cacheDir, "share.tmp")
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(what.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        }
                        size = tmp.length()
                        open = { tmp.inputStream() }
                    }
                    line = "Sending $name…"
                    progress = 0f
                    val total = size
                    val r = if (total <= 0) null else tv.uploadPhoto(name, total, open, album = "Shared from phone") { sent ->
                        progress = (sent.toFloat() / total).coerceIn(0f, 1f)
                    }
                    progress = -1f
                    if (r?.optBoolean("ok") == true) {
                        line = "Showing it on the TV…"
                        val s = tv.action("showphoto", "name" to r.optString("name"))
                        ok = s?.optBoolean("ok") == true
                        line = if (ok) "On the TV now (and in the photo frame's \"Shared from phone\" album)" else s?.optString("error") ?: "Can't reach the TV"
                    } else {
                        line = r?.optString("error")?.ifBlank { null } ?: "Can't reach the TV"
                    }
                    done = true
                }
            }
            if (ok) { delay(1600); finish() }
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = done) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Ui.Card)
                    .clickable(enabled = false) {}.navigationBarsPadding().padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge("📺", Ui.Blue)
                    Spacer(Modifier.padding(start = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bedroom TV", color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (what) {
                                is What.Link -> what.url
                                is What.Photo -> "Photo"
                                is What.Nothing -> ""
                            },
                            color = Ui.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(line, color = if (done && !ok) Color(0xFFFFB36B) else Ui.Text, fontSize = 15.sp)
                if (!done) {
                    Spacer(Modifier.height(12.dp))
                    if (progress >= 0f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = Ui.Accent)
                    else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ui.Accent)
                }
                if (done) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Close", color = Ui.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { finish() }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
