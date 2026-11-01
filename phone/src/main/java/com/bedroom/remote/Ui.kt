package com.bedroom.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's shared look (dark take on the PC panel's dashboard): cards with a small gradient badge in the
 * header, and a gradient "hero" card for the main thing on each tab.
 */
object Ui {
    val Bg = Color(0xFF0B1120)
    val Card = Color(0xFF141E33)
    val Card2 = Color(0xFF1E2A44)
    val Edge = Color(0xFF1F2B45)
    val Text = Color(0xFFEEF3FB)
    val Sub = Color(0xFF93A4C0)
    val Accent = Color(0xFF8FB8FF)

    // Badge gradients (same idea as the PC panel's coloured card icons)
    val Blue = listOf(Color(0xFF5B8DEF), Color(0xFF3A62C9))
    val Pink = listOf(Color(0xFFEC407A), Color(0xFFD81B60))
    val Gold = listOf(Color(0xFFFFB74D), Color(0xFFF57C00))
    val Green = listOf(Color(0xFF66BB6A), Color(0xFF388E3C))
    val Purple = listOf(Color(0xFF9C7CF4), Color(0xFF6A4FC9))
    val Teal = listOf(Color(0xFF26C6DA), Color(0xFF00838F))

    val HeroBlue = listOf(Color(0xFF1C2D55), Color(0xFF111A30))
    val HeroGreen = listOf(Color(0xFF173A35), Color(0xFF13203A))
}

/** Small rounded square with a gradient and a white symbol. */
@Composable
fun Badge(symbol: String, colors: List<Color>, size: Int = 34) {
    Box(
        Modifier.size(size.dp).shadow(6.dp, RoundedCornerShape(10.dp), ambientColor = colors.last(), spotColor = colors.last())
            .clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = Color.White, fontSize = (size * 0.47f).sp, fontWeight = FontWeight.SemiBold) }
}

/** Card with a badge + title header. */
@Composable
fun DashCard(
    title: String,
    symbol: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ui.Card)
            .border(1.dp, Ui.Edge, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(symbol, colors)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = Ui.Sub, fontSize = 12.sp)
            }
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** Gradient card for the main control on a tab. */
@Composable
fun HeroCard(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Small section label with a badge (for lists that aren't inside a card). */
@Composable
fun SectionHeader(title: String, symbol: String, colors: List<Color>, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(symbol, colors, size = 26)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Ui.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Ui.Sub, fontSize = 12.sp)
    }
}

/** Two or more options side by side, one picked. */
@Composable
fun SegmentRow(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Ui.Card2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (on) Brush.linearGradient(Ui.Blue) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color.White else Ui.Sub, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}
