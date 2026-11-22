package com.bedroom.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * How the home screen background looks, chosen on the phone (Bedroom tab):
 *  - weather background on/off: the backdrop takes on today's weather (rain streaks, stars, clouds...)
 *  - seasonal theme: off, automatic by date, or a fixed one (autumn, winter, holidays, ...)
 * Everything here is a still picture: it's drawn once and only redrawn when the weather, theme or time of
 * day changes, so it costs nothing while the TV sits on the home screen.
 */
object Look {
    val THEMES = listOf("auto", "off", "autumn", "halloween", "holidays", "winter", "spring", "summer")

    val theme = mutableStateOf("auto")
    val weatherBg = mutableStateOf(true)

    fun load(ctx: Context) {
        val p = BedroomPrefs.get(ctx)
        val t = p.getString("theme", "auto") ?: "auto"
        val w = p.getBoolean("weatherBg", true)
        Handler(Looper.getMainLooper()).post {
            theme.value = if (t in THEMES) t else "auto"
            weatherBg.value = w
        }
    }

    /** The theme to draw right now ("auto" picks one from the date). Null = no theme. */
    fun current(now: Long, chosen: String): String? {
        if (chosen == "off") return null
        if (chosen != "auto") return chosen
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val md = (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        return when (md) {
            in 1025..1031 -> "halloween"
            in 1201..1231, in 101..106 -> "holidays"
            in 107..319 -> "winter"
            in 320..620 -> "spring"
            in 621..921 -> "summer"
            else -> "autumn" // Sep 22 - Nov 30, except Halloween week
        }
    }
}

/**
 * Soft light behind everything, tinted by the weather ([sky], null = plain) and decorated for the season
 * ([theme], null = none). Still image; only simple values are passed in so it isn't redrawn every second.
 */
@Composable
fun LookBackdrop(p: Palette, sky: Sky?, isDay: Boolean, theme: String?) {
    // Fixed random layouts so the picture doesn't jump around between redraws.
    val stars = remember { List(70) { Offset(Random(it * 7 + 1).nextFloat(), Random(it * 13 + 5).nextFloat() * 0.55f) } }
    val bits = remember { List(18) { i -> Triple(Random(i * 31 + 3).nextFloat(), Random(i * 17 + 11).nextFloat(), Random(i * 5 + 2).nextFloat()) } }
    // Night: everything decorative is much fainter (the palette's text alpha is low at night).
    val k = (p.text.alpha * 0.9f).coerceIn(0.35f, 1f)

    val tint = when (sky) {
        null -> null
        Sky.Clear -> if (isDay) Color(0x66FFC46B) else Color(0x332A3A8A)
        Sky.PartlyCloudy -> if (isDay) Color(0x55B9D3E8) else Color(0x33465070)
        Sky.Cloudy -> Color(0x557D8796)
        Sky.Fog -> Color(0x55A0A8B0)
        Sky.Drizzle, Sky.Rain -> Color(0x55557FA8)
        Sky.Snow -> Color(0x66D7E6F5)
        Sky.Storm -> Color(0x666A4C9A)
    }
    val themeTint = when (theme) {
        "autumn" -> Color(0x55E07A2E)
        "halloween" -> Color(0x66FF7A1A)
        "holidays" -> Color(0x55C8323C)
        "winter" -> Color(0x5590C8FF)
        "spring" -> Color(0x55F2A2C8)
        "summer" -> Color(0x55FFD25A)
        else -> null
    }
    var glow = p.glow
    if (tint != null) glow = lerp(glow, tint.copy(alpha = tint.alpha * k), 0.6f)
    if (themeTint != null) glow = lerp(glow, themeTint.copy(alpha = themeTint.alpha * k), 0.35f)

    Canvas(Modifier.fillMaxSize()) {
        val tau = 2f * PI.toFloat()
        val phase = 0.15f
        drawRect(Brush.verticalGradient(listOf(p.top, p.bottom)))
        val r1 = size.height * 0.95f
        val c1 = Offset(size.width * (0.78f + 0.07f * sin(tau * phase)), size.height * (0.28f + 0.06f * cos(tau * phase)))
        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center = c1, radius = r1), r1, c1)
        val r2 = size.height * 0.7f
        val c2 = Offset(size.width * (0.18f + 0.05f * cos(tau * 2f * phase)), size.height * (0.88f + 0.04f * sin(tau * phase)))
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.6f), Color.Transparent), center = c2, radius = r2), r2, c2)

        when (sky) {
            Sky.Clear -> if (!isDay) stars.forEachIndexed { i, s ->
                drawCircle(Color.White.copy(alpha = (0.18f + 0.3f * ((i * 37) % 10) / 10f) * k), size.minDimension * (0.0016f + 0.0012f * (i % 3)), Offset(s.x * size.width, s.y * size.height))
            }
            Sky.PartlyCloudy, Sky.Cloudy -> {
                val n = if (sky == Sky.Cloudy) 5 else 3
                for (i in 0 until n) softCloud(Offset(size.width * (0.12f + 0.2f * i), size.height * (0.12f + 0.05f * (i % 2))), size.width * 0.22f, 0.06f * k)
            }
            Sky.Fog -> for (i in 0..3) drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.05f * k), Color.Transparent),
                    startY = size.height * (0.45f + 0.12f * i), endY = size.height * (0.6f + 0.12f * i)),
            )
            Sky.Drizzle, Sky.Rain, Sky.Storm -> {
                val n = if (sky == Sky.Drizzle) 40 else 80
                for (i in 0 until n) {
                    val x = Random(i * 7 + 3).nextFloat() * size.width * 1.1f
                    val y = Random(i * 11 + 9).nextFloat() * size.height
                    val len = size.height * (0.03f + 0.03f * Random(i).nextFloat())
                    drawLine(Color(0xFFAFCBEA).copy(alpha = 0.10f * k), Offset(x, y), Offset(x - len * 0.25f, y + len), strokeWidth = 1.6f, cap = StrokeCap.Round)
                }
            }
            Sky.Snow -> for (i in 0 until 60) {
                val x = Random(i * 7 + 3).nextFloat() * size.width
                val y = Random(i * 11 + 9).nextFloat() * size.height
                drawCircle(Color.White.copy(alpha = 0.16f * k), size.minDimension * (0.002f + 0.002f * (i % 3)), Offset(x, y))
            }
            null -> {}
        }

        when (theme) {
            "autumn" -> bits.forEachIndexed { i, (x, y, r) ->
                val colors = listOf(Color(0xFFE0782E), Color(0xFFC94A2B), Color(0xFFE8B23A), Color(0xFF9C5A2E))
                leaf(edge(x, y), size.minDimension * (0.014f + 0.01f * r), r * 360f, colors[i % 4].copy(alpha = 0.32f * k))
            }
            "halloween" -> {
                bits.take(7).forEach { (x, y, r) -> bat(edge(x, y * 0.5f), size.minDimension * (0.018f + 0.01f * r), Color(0xFF120A18).copy(alpha = 0.55f * k)) }
                drawCircle(Color(0xFFFFE6A8).copy(alpha = 0.10f * k), size.height * 0.09f, Offset(size.width * 0.93f, size.height * 0.12f))
            }
            "holidays" -> lights(k)
            "winter" -> bits.forEach { (x, y, r) -> flake(edge(x, y), size.minDimension * (0.01f + 0.008f * r), Color.White.copy(alpha = 0.22f * k)) }
            "spring" -> bits.forEachIndexed { i, (x, y, r) ->
                val c = if (i % 3 == 0) Color.White else Color(0xFFF6B3D0)
                leaf(edge(x, y), size.minDimension * (0.008f + 0.006f * r), r * 360f, c.copy(alpha = 0.30f * k))
            }
            "summer" -> bits.take(12).forEach { (x, y, _) ->
                val o = edge(x, y)
                drawCircle(Color(0xFFFFE98A).copy(alpha = 0.10f * k), size.minDimension * 0.012f, o)
                drawCircle(Color(0xFFFFF4B0).copy(alpha = 0.35f * k), size.minDimension * 0.003f, o)
            }
        }

        // Gentle darkening toward the bottom so tile labels stay readable.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = size.height * 0.4f, endY = size.height))
    }
}

/** Keeps decorations near the edges (left/right strips and the very top), away from the tiles' middle. */
private fun DrawScope.edge(x: Float, y: Float): Offset {
    val side = if (x < 0.5f) x * 0.16f else 0.84f + (x - 0.5f) * 0.32f
    return Offset(side * size.width, (0.05f + y * 0.9f) * size.height)
}

private fun DrawScope.softCloud(c: Offset, w: Float, a: Float) {
    val col = Color.White.copy(alpha = a)
    drawOval(col, Offset(c.x - w / 2, c.y - w * 0.12f), Size(w, w * 0.28f))
    drawOval(col, Offset(c.x - w * 0.3f, c.y - w * 0.26f), Size(w * 0.5f, w * 0.32f))
}

private fun DrawScope.leaf(c: Offset, r: Float, deg: Float, col: Color) = rotate(deg, c) {
    drawOval(col, Offset(c.x - r, c.y - r * 0.5f), Size(r * 2, r))
    drawLine(col.copy(alpha = col.alpha * 0.8f), Offset(c.x - r * 1.3f, c.y), Offset(c.x + r, c.y), strokeWidth = r * 0.12f)
}

private fun DrawScope.flake(c: Offset, r: Float, col: Color) {
    for (i in 0 until 6) {
        val a = (PI * i / 3).toFloat()
        drawLine(col, c, Offset(c.x + cos(a) * r, c.y + sin(a) * r), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.bat(c: Offset, w: Float, col: Color) {
    val path = Path().apply {
        moveTo(c.x - w, c.y)
        quadraticBezierTo(c.x - w * 0.6f, c.y - w * 0.45f, c.x - w * 0.15f, c.y - w * 0.05f)
        lineTo(c.x, c.y - w * 0.2f)
        lineTo(c.x + w * 0.15f, c.y - w * 0.05f)
        quadraticBezierTo(c.x + w * 0.6f, c.y - w * 0.45f, c.x + w, c.y)
        quadraticBezierTo(c.x + w * 0.5f, c.y - w * 0.1f, c.x + w * 0.3f, c.y + w * 0.15f)
        quadraticBezierTo(c.x, c.y, c.x - w * 0.3f, c.y + w * 0.15f)
        quadraticBezierTo(c.x - w * 0.5f, c.y - w * 0.1f, c.x - w, c.y)
        close()
    }
    drawPath(path, col)
}

/** A string of coloured lights draped along the top edge. */
private fun DrawScope.lights(k: Float) {
    val colors = listOf(Color(0xFFFF5A5A), Color(0xFF6BE38A), Color(0xFFFFD45A), Color(0xFF6BB8FF))
    val n = 26
    val wire = Color(0xFF2A2F25).copy(alpha = 0.6f * k)
    var prev: Offset? = null
    for (i in 0..n) {
        val t = i / n.toFloat()
        val sag = sin((t * 3f % 1f) * PI.toFloat()) * size.height * 0.03f
        val o = Offset(t * size.width, size.height * 0.015f + sag)
        prev?.let { drawLine(wire, it, o, strokeWidth = 1.5f) }
        prev = o
        if (i in 1 until n) {
            val col = colors[i % colors.size]
            drawCircle(col.copy(alpha = 0.12f * k), size.minDimension * 0.014f, Offset(o.x, o.y + size.minDimension * 0.008f))
            drawCircle(col.copy(alpha = 0.6f * k), size.minDimension * 0.005f, Offset(o.x, o.y + size.minDimension * 0.008f))
        }
    }
}
