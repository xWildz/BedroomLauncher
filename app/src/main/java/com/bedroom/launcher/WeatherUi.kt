package com.bedroom.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val RainBlue = Color(0xFF8FC8FF)

/** Top-right weather: place, now, and a 5-day strip with highs/lows and chance of precipitation. */
@Composable
fun WeatherPanel(weather: Weather?, placeSet: Boolean, p: Palette) {
    Column(horizontalAlignment = Alignment.End) {
        if (weather == null) {
            Text("WEATHER", color = p.subtext, fontSize = 13.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (placeSet) "Loading forecast…" else "Set your city to see the forecast",
                color = p.subtext,
                fontSize = 15.sp,
            )
            return@Column
        }

        Text(
            weather.place.uppercase(Locale.getDefault()),
            color = p.subtext,
            fontSize = 13.sp,
            letterSpacing = 3.sp,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(skyFor(weather.code), weather.isDay, 44.dp, p, animated = true)
            Spacer(Modifier.width(10.dp))
            Text("${weather.temp}°", color = p.text, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(describe(weather.code), color = p.text, fontSize = 15.sp, maxLines = 1)
                val today = weather.days.firstOrNull()
                val parts = buildList {
                    today?.precipPct?.let { add("Precip $it%") }
                    add("Wind ${weather.windMph} mph")
                }
                Text(parts.joinToString(" · "), color = p.subtext, fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            weather.days.take(5).forEachIndexed { i, d -> DayCell(d, i, p) }
        }
    }
}

@Composable
private fun DayCell(d: DayForecast, index: Int, p: Palette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dayLabel(d.date, index), color = p.subtext, fontSize = 11.sp)
        WeatherIcon(skyFor(d.code), true, 22.dp, p)
        Row {
            Text("${d.hi}°", color = p.text, fontSize = 12.sp)
            Text(" ${d.lo}°", color = p.subtext, fontSize = 12.sp)
        }
        Text("${d.precipPct ?: 0}%", color = RainBlue.copy(alpha = p.text.alpha), fontSize = 11.sp)
    }
}

/**
 * Simple drawn weather glyphs, so there are no font or emoji surprises on the TV.
 * [animated]: slow, gentle motion (turning sun, drifting clouds, falling rain/snow...). It redraws about
 * 20 times a second rather than 60 to stay light on the TV's chip, and stops when the home screen is hidden.
 */
@Composable
fun WeatherIcon(sky: Sky, isDay: Boolean, size: Dp, p: Palette, animated: Boolean = false) {
    val sun = Color(0xFFFFCF6B)
    val moon = Color(0xFFE8E4D8)
    val cloud = Color(0xFFE3E8EF)
    var t by remember { mutableFloatStateOf(0f) }
    if (animated) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                t = ((System.nanoTime() - start) / 1_000_000_000.0 % 3600.0).toFloat()
                delay(50)
            }
        }
    }
    Canvas(
        Modifier
            .size(size)
            .graphicsLayer(alpha = p.text.alpha), // dims with the night palette; opaque shapes don't double-up
    ) {
        val s = this.size.minDimension
        val drift = if (animated) sin(t * 0.5f) * s * 0.035f else 0f
        val spin = if (animated) t * 8f else 0f
        val pulse = if (animated) 1f + 0.06f * sin(t * 1.6f) else 1f
        when (sky) {
            Sky.Clear ->
                if (isDay) {
                    if (animated) drawCircle(sun.copy(alpha = 0.10f + 0.06f * sin(t * 1.2f)), s * 0.42f, Offset(s / 2, s / 2))
                    sunShape(sun, Offset(s / 2, s / 2), s * 0.2f, s, spin, pulse)
                } else {
                    val bob = if (animated) sin(t * 0.8f) * s * 0.03f else 0f
                    moonShape(moon, Offset(s / 2, s / 2 + bob), s * 0.32f)
                    if (animated) {
                        drawCircle(Color.White.copy(alpha = 0.25f + 0.6f * ((sin(t * 1.7f) + 1f) / 2f)), s * 0.03f, Offset(s * 0.86f, s * 0.2f))
                        drawCircle(Color.White.copy(alpha = 0.25f + 0.6f * ((sin(t * 1.1f + 2f) + 1f) / 2f)), s * 0.022f, Offset(s * 0.14f, s * 0.78f))
                    }
                }

            Sky.PartlyCloudy -> {
                if (isDay) sunShape(sun, Offset(s * 0.36f, s * 0.36f), s * 0.14f, s, spin, pulse)
                else moonShape(moon, Offset(s * 0.36f, s * 0.34f), s * 0.22f)
                cloudShape(cloud, Offset(s * 0.58f + drift, s * 0.62f), s * 0.68f)
            }

            Sky.Cloudy -> {
                cloudShape(cloud.copy(alpha = 0.55f), Offset(s * 0.36f - drift * 0.6f, s * 0.36f), s * 0.5f)
                cloudShape(cloud, Offset(s * 0.5f + drift, s * 0.56f), s * 0.86f)
            }

            Sky.Fog -> for (i in 0..2) {
                val y = s * (0.32f + 0.18f * i)
                val dx = if (animated) sin(t * 0.6f + i * 1.3f) * s * 0.07f else 0f
                drawLine(cloud, Offset(s * 0.15f + dx, y), Offset(s * 0.85f + dx, y), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
            }

            Sky.Drizzle, Sky.Rain -> {
                val n = if (sky == Sky.Rain) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.32f + 0.18f * i + if (n == 2) 0.09f else 0f)
                    if (animated) {
                        // each drop falls from under the cloud and fades out, then starts again
                        val ph = ((t * (if (sky == Sky.Rain) 1.1f else 0.7f)) + i * 0.37f) % 1f
                        val y0 = s * (0.62f + 0.3f * ph)
                        drawLine(
                            RainBlue.copy(alpha = 1f - ph * 0.8f),
                            Offset(x - s * 0.03f * ph, y0), Offset(x - s * 0.03f * ph - s * 0.05f, y0 + s * 0.16f),
                            strokeWidth = s * 0.07f, cap = StrokeCap.Round,
                        )
                    } else {
                        drawLine(RainBlue, Offset(x, s * 0.72f), Offset(x - s * 0.06f, s * 0.92f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
                    }
                }
                cloudShape(cloud, Offset(s * 0.5f + drift * 0.5f, s * 0.4f), s * 0.8f)
            }

            Sky.Snow -> {
                for (i in 0..2) {
                    if (animated) {
                        val ph = (t * 0.35f + i * 0.33f) % 1f
                        val sway = sin(t * 2f + i * 2f) * s * 0.04f
                        drawCircle(Color.White.copy(alpha = 1f - ph * 0.7f), s * 0.055f, Offset(s * (0.3f + 0.2f * i) + sway, s * (0.66f + 0.3f * ph)))
                    } else {
                        drawCircle(Color.White, s * 0.055f, Offset(s * (0.3f + 0.2f * i), s * (0.8f + if (i == 1) 0.08f else 0f)))
                    }
                }
                cloudShape(cloud, Offset(s * 0.5f + drift * 0.5f, s * 0.4f), s * 0.8f)
            }

            Sky.Storm -> {
                cloudShape(cloud, Offset(s * 0.5f + drift * 0.5f, s * 0.38f), s * 0.8f)
                // a quick double flicker every ~4 seconds
                val cyc = t % 4f
                val flash = if (animated && (cyc in 3.0f..3.12f || cyc in 3.25f..3.35f)) 0.25f else 1f
                val bolt = Path().apply {
                    moveTo(s * 0.56f, s * 0.56f)
                    lineTo(s * 0.40f, s * 0.80f)
                    lineTo(s * 0.52f, s * 0.80f)
                    lineTo(s * 0.44f, s * 0.98f)
                    lineTo(s * 0.67f, s * 0.71f)
                    lineTo(s * 0.55f, s * 0.71f)
                    close()
                }
                drawPath(bolt, sun.copy(alpha = flash))
            }
        }
    }
}

private fun DrawScope.sunShape(c: Color, center: Offset, r: Float, s: Float, spinDeg: Float = 0f, rayScale: Float = 1f) {
    drawCircle(c, r, center)
    rotate(spinDeg, center) {
        for (i in 0 until 8) {
            val a = (Math.PI * 2 * i / 8).toFloat()
            drawLine(
                c,
                Offset(center.x + cos(a) * r * 1.45f, center.y + sin(a) * r * 1.45f),
                Offset(center.x + cos(a) * r * 1.95f * rayScale, center.y + sin(a) * r * 1.95f * rayScale),
                strokeWidth = s * 0.06f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.moonShape(c: Color, center: Offset, r: Float) {
    val disc = Path().apply { addOval(Rect(center, r)) }
    val bite = Path().apply { addOval(Rect(Offset(center.x + r * 0.45f, center.y - r * 0.35f), r * 0.85f)) }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    drawPath(crescent, c)
}

private fun DrawScope.cloudShape(c: Color, center: Offset, w: Float) {
    val base = center.y + w * 0.12f
    drawRoundRect(
        c,
        topLeft = Offset(center.x - w * 0.45f, base - w * 0.18f),
        size = Size(w * 0.9f, w * 0.3f),
        cornerRadius = CornerRadius(w * 0.15f),
    )
    drawCircle(c, w * 0.2f, Offset(center.x - w * 0.18f, base - w * 0.15f))
    drawCircle(c, w * 0.26f, Offset(center.x + w * 0.08f, base - w * 0.24f))
}
