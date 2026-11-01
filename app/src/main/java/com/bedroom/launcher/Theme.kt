package com.bedroom.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Phase { Dawn, Day, Golden, Evening, Night }

/** Recomputed from the live clock every time, never cached at startup. */
fun phaseFor(nowMillis: Long): Phase {
    val hour = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..7 -> Phase.Dawn
        in 8..16 -> Phase.Day
        in 17..18 -> Phase.Golden
        in 19..21 -> Phase.Evening
        else -> Phase.Night // 10 PM – 5 AM: dim, low-glare
    }
}

data class Palette(
    val top: Color,
    val bottom: Color,
    val glow: Color,
    val text: Color,
    val subtext: Color,
    val card: Color,
    val cardFocused: Color,
    /** Black overlay on app banners that aren't focused (bright white banners glare at night). */
    val tileDim: Float = 0.22f,
    /** Overlay even on the focused banner at night, so nothing is blinding in a dark room. */
    val focusedDim: Float = 0f,
)

fun paletteFor(phase: Phase): Palette = when (phase) {
    Phase.Dawn -> Palette(Color(0xFF1B2140), Color(0xFF4A3550), Color(0x55FFB38A), Color(0xFFFFFFFF), Color(0xB3FFFFFF), Color(0x22FFFFFF), Color(0x38FFFFFF), tileDim = 0.28f)
    Phase.Day -> Palette(Color(0xFF0E2233), Color(0xFF1D4A5E), Color(0x4488D8FF), Color(0xFFFFFFFF), Color(0xB3FFFFFF), Color(0x22FFFFFF), Color(0x38FFFFFF), tileDim = 0.22f)
    Phase.Golden -> Palette(Color(0xFF2A1A2E), Color(0xFF7A4326), Color(0x66FFB45A), Color(0xFFFFFFFF), Color(0xB3FFFFFF), Color(0x22FFFFFF), Color(0x38FFFFFF), tileDim = 0.28f)
    Phase.Evening -> Palette(Color(0xFF0D1024), Color(0xFF26193A), Color(0x447A6CFF), Color(0xF0FFFFFF), Color(0x99FFFFFF), Color(0x1CFFFFFF), Color(0x30FFFFFF), tileDim = 0.40f, focusedDim = 0.10f)
    Phase.Night -> Palette(Color(0xFF04050A), Color(0xFF0A0C14), Color(0x22303C70), Color(0xA6FFFFFF), Color(0x5CFFFFFF), Color(0x12FFFFFF), Color(0x22FFFFFF), tileDim = 0.55f, focusedDim = 0.25f)
}

/** Smoothly fades between palettes when the phase changes. */
@Composable
fun animatedPalette(target: Palette): Palette {
    val spec = tween<Color>(4000)
    val top by animateColorAsState(target.top, spec, label = "top")
    val bottom by animateColorAsState(target.bottom, spec, label = "bottom")
    val glow by animateColorAsState(target.glow, spec, label = "glow")
    val text by animateColorAsState(target.text, spec, label = "text")
    val subtext by animateColorAsState(target.subtext, spec, label = "subtext")
    val card by animateColorAsState(target.card, spec, label = "card")
    val cardFocused by animateColorAsState(target.cardFocused, spec, label = "cardFocused")
    val tileDim by animateFloatAsState(target.tileDim, tween(4000), label = "tileDim")
    val focusedDim by animateFloatAsState(target.focusedDim, tween(4000), label = "focusedDim")
    return Palette(top, bottom, glow, text, subtext, card, cardFocused, tileDim, focusedDim)
}

/** Ticks once per second, aligned to the wall clock. */
@Composable
fun rememberNowMillis(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000L - value % 1000L)
    }
}

/**
 * Soft light behind everything. Drawn as a still image (v0.6): the old 90-second drifting
 * animation redrew the full screen every frame, which cost memory and CPU this TV needs for Hulu.
 * It still fades between day/evening/night palettes.
 */
@Composable
fun Backdrop(p: Palette) {
    val phase = 0.15f // fixed position of the two glows
    Canvas(Modifier.fillMaxSize()) {
        val tau = 2f * PI.toFloat()
        drawRect(Brush.verticalGradient(listOf(p.top, p.bottom)))

        val r1 = size.height * 0.95f
        val c1 = Offset(
            size.width * (0.78f + 0.07f * sin(tau * phase)),
            size.height * (0.28f + 0.06f * cos(tau * phase)),
        )
        drawCircle(Brush.radialGradient(listOf(p.glow, Color.Transparent), center = c1, radius = r1), r1, c1)

        val r2 = size.height * 0.7f
        val c2 = Offset(
            size.width * (0.18f + 0.05f * cos(tau * 2f * phase)),
            size.height * (0.88f + 0.04f * sin(tau * phase)),
        )
        drawCircle(
            Brush.radialGradient(listOf(p.glow.copy(alpha = p.glow.alpha * 0.6f), Color.Transparent), center = c2, radius = r2),
            r2, c2,
        )

        // Gentle darkening toward the bottom so tile labels stay readable.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                startY = size.height * 0.4f,
                endY = size.height,
            ),
        )
    }
}
