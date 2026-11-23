package com.bedroom.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "TV info": every detail the chip and Android report, in sections on the left, details on the right.
 * Up / Down moves through sections (or rows), Right goes into the details, Back returns. OK on a section
 * reads everything again (speeds and memory change all the time).
 */
@Composable
fun TvInfoScreen(p: Palette, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val sections by produceState<List<TvInfo.Section>?>(null, reload) {
        value = withContext(Dispatchers.IO) { runCatching { TvInfo.collect(ctx) }.getOrNull() }
    }
    var selected by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val detailState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 16.dp)) {
        Text("TV info", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Light)
        Text(
            "Everything the TV's chip and Android report. Read only. OK on a section reads it again · Back to return",
            color = p.subtext, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        val list = sections
        if (list == null) {
            Text("Reading the TV…", color = p.subtext, fontSize = 16.sp)
            return@Column
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(250.dp).fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(list) { i, sec ->
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .padding(vertical = 3.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    focused -> Color.White.copy(alpha = 0.9f)
                                    i == selected -> p.cardFocused
                                    else -> Color.Transparent
                                },
                            )
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused && selected != i) selected = i
                            }
                            .onKeyEvent { e ->
                                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { reload++; true } else false
                            }
                            .focusable()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            sec.title + "  ·  ${sec.rows.size}",
                            color = if (focused) Color(0xFF0B0D16) else p.text, fontSize = 14.sp, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            val rows = list.getOrNull(selected)?.rows.orEmpty()
            LaunchedEffect(selected) { detailState.scrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(p.card),
                state = detailState,
                contentPadding = PaddingValues(12.dp),
            ) {
                itemsIndexed(rows) { _, (k, v) ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        if (k.isNotBlank()) {
                            Text(k, color = p.subtext, fontSize = 13.sp, modifier = Modifier.width(250.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            v, color = p.text, fontSize = 13.sp,
                            fontFamily = if (k.isBlank()) FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(sections != null) {
        if (sections != null) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
}

/** A little chip (square with pins) for the "TV info" button. */
@Composable
fun ChipIcon(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawRoundRect(color, Offset(s * 0.22f, s * 0.22f), Size(s * 0.56f, s * 0.56f), CornerRadius(s * 0.08f), style = Stroke(w))
        drawRect(color, Offset(s * 0.38f, s * 0.38f), Size(s * 0.24f, s * 0.24f))
        for (i in 0..2) {
            val o = s * (0.32f + 0.18f * i)
            drawLine(color, Offset(o, s * 0.04f), Offset(o, s * 0.22f), w)
            drawLine(color, Offset(o, s * 0.78f), Offset(o, s * 0.96f), w)
            drawLine(color, Offset(s * 0.04f, o), Offset(s * 0.22f, o), w)
            drawLine(color, Offset(s * 0.78f, o), Offset(s * 0.96f, o), w)
        }
    }
}
