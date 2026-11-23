package com.bedroom.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- the sounds (made on the TV, no audio files)

/**
 * Sleep sounds, generated live from random noise shaped into rain, waves, a fan... No recordings, so
 * nothing to download and nothing to run out. Plays alongside other apps without pausing them
 * (it doesn't take "audio focus"), so it never interrupts YouTube.
 */
object SleepSoundEngine {
    data class Sound(val id: String, val title: String, val blurb: String, val color: Long)

    val SOUNDS = listOf(
        Sound("rain", "Rain", "Steady rain on the window", 0xFF5C8FD6),
        Sound("ocean", "Ocean", "Slow waves rolling in", 0xFF3FA7A8),
        Sound("fan", "Fan", "A box fan on low", 0xFF8C93A8),
        Sound("brown", "Deep noise", "Low, soft rumble (brown noise)", 0xFF9A6B4F),
        Sound("pink", "Soft noise", "Balanced hush (pink noise)", 0xFFC98BB0),
        Sound("white", "White noise", "Bright, even hiss", 0xFFD9D9E3),
        Sound("storm", "Thunderstorm", "Rain with distant thunder", 0xFF7A6FD0),
        Sound("fire", "Fireplace", "Crackling logs", 0xFFE0782E),
        Sound("train", "Night train", "Steady rhythm on the rails", 0xFFB09A7A),
        Sound("crickets", "Crickets", "A warm summer night", 0xFF7FBF6A),
        Sound("brook", "Brook", "Water over stones", 0xFF4FB3D9),
    )
    val TIMERS = listOf(0, 15, 30, 60, 90, 120, 240) // minutes, 0 = until stopped

    /** What's playing (null = nothing), the volume (10..100) and when the timer ends (0 = no timer). */
    val playing = mutableStateOf<String?>(null)
    val volume = mutableStateOf(60)
    val endsAt = mutableLongStateOf(0L)

    private const val RATE = 44_100
    private const val FADE_IN_S = 3.0
    private const val FADE_OUT_S = 60.0 // the last minute of the timer fades out gently
    @Volatile private var want: String? = null
    @Volatile private var gainTarget = 0.6
    @Volatile private var endAtMs = 0L
    @Volatile private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun play(id: String) {
        want = id
        gainTarget = volume.value / 100.0
        main.post { playing.value = id }
        if (thread?.isAlive != true) thread = Thread(::loop, "sleep-sounds").apply { isDaemon = true; start() }
        Log.i(TAG, "Sleep sound: $id")
    }

    fun stop() {
        want = null
        endAtMs = 0
        timerIdx = 0
        main.post { playing.value = null; endsAt.longValue = 0 }
    }

    fun setVolume(v: Int) {
        val c = v.coerceIn(10, 100)
        volume.value = c
        gainTarget = c / 100.0
    }

    /** [minutes] 0 = no timer. */
    fun setTimer(minutes: Int) {
        endAtMs = if (minutes <= 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
        endsAt.longValue = endAtMs
    }

    private var timerIdx = 0

    /** Next timer length (off, 15, 30 ... 240 min); returns the minutes set. */
    fun cycleTimer(): Int {
        timerIdx = (timerIdx + 1) % TIMERS.size
        setTimer(TIMERS[timerIdx])
        return TIMERS[timerIdx]
    }

    fun minutesLeft(): Int? = endAtMs.takeIf { it > 0 }?.let { ((it - SystemClock.elapsedRealtime()) / 60_000L + 1).toInt().coerceAtLeast(0) }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf, RATE)) // ~0.25 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val n = 2048
        val buf = ShortArray(n * 2)
        val gen = Gen()
        var gain = 0.0
        var current: String? = null
        try {
            track.play()
            while (true) {
                val target = want
                // Timer: fade out over the last minute, then stop.
                var timerScale = 1.0
                val end = endAtMs
                if (end > 0) {
                    val left = (end - SystemClock.elapsedRealtime()) / 1000.0
                    if (left <= 0) {
                        stop()
                        appCtx?.let { Health.event(it, "Sleep sounds turned off by their timer") }
                    } else if (left < FADE_OUT_S) timerScale = left / FADE_OUT_S
                }
                // Switching sounds: the old one fades to silence first, then the new one fades in.
                val goal = if (target != null && target == current) gainTarget * timerScale else 0.0
                val step = 1.0 / (RATE * FADE_IN_S)
                for (i in 0 until n) {
                    gain += (goal - gain).coerceIn(-step * 3, step)
                    gen.next(current)
                    val l = gen.l
                    val r = gen.r
                    buf[i * 2] = (l * gain * 32767 * 0.9).toInt().coerceIn(-32767, 32767).toShort()
                    buf[i * 2 + 1] = (r * gain * 32767 * 0.9).toInt().coerceIn(-32767, 32767).toShort()
                }
                track.write(buf, 0, buf.size)
                if (gain < 0.0005) {
                    if (target == null) break
                    if (target != current) current = target
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sleep sounds stopped", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            thread = null
            if (want != null) main.post { playing.value = null } // ended by an error
            want = null
        }
    }

    @Volatile var appCtx: android.content.Context? = null

    /** Noise shapers. Everything is per-sample math on the audio thread; nothing is allocated per sample. */
    private val DROP_DECAY = exp(-1.0 / (RATE * 0.004))
    private val CRACK_DECAY = exp(-1.0 / (RATE * 0.0025))
    private val CLACK_DECAY = exp(-1.0 / (RATE * 0.02))
    private val BUBBLE_DECAY = exp(-1.0 / (RATE * 0.03))

    private class Gen {
        private val rnd = Random(SystemClock.elapsedRealtime())
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0
        private var brownL = 0.0; private var brownR = 0.0
        private var lpL = 0.0; private var lpR = 0.0
        private var t = 0L
        private var drop = 0.0; private var dropPan = 0.5
        private var swellPhase = rnd.nextDouble() * 2 * PI
        private var swellPeriod = 9.0

        private fun white() = rnd.nextDouble() * 2 - 1

        /** Paul Kellet's pink noise filter. */
        private fun pink(): Double {
            val w = white()
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            return p * 0.11
        }

        private fun brown(prev: Double): Double = ((prev + 0.02 * white()) / 1.02)

        /** Output of the last [next] call (left / right). */
        var l = 0.0
        var r = 0.0
        private fun sec() = t.toDouble() / RATE

        // ---- thunderstorm: rain, plus a rumble every 20-50 s that swells in and rolls away
        private var rumble = 0.0; private var rumbleTarget = 0.0; private var nextThunder = RATE * 8L; private var thunderPan = 0.5
        private var rumbleLp = 0.0; private var rumbleLp2 = 0.0
        private fun storm(): Pair<Double, Double> {
            val (rl, rr) = rainSample(0.0016)
            if (t >= nextThunder) { rumbleTarget = 0.5 + rnd.nextDouble() * 0.5; thunderPan = 0.2 + rnd.nextDouble() * 0.6; nextThunder = t + RATE * (20L + rnd.nextInt(30)) }
            rumble += (rumbleTarget - rumble) * (if (rumbleTarget > rumble) 1.0 / (RATE * 0.4) else 1.0 / (RATE * 2.5))
            if (rumble >= rumbleTarget * 0.98) rumbleTarget = 0.0
            rumbleLp += 0.004 * (white() - rumbleLp); rumbleLp2 += 0.004 * (rumbleLp - rumbleLp2)
            val th = rumbleLp2 * 9.0 * rumble * (1 + 0.4 * sin(sec() * 13.0) * sin(sec() * 7.3))
            return Pair(rl + th * (1.2 - thunderPan), rr + th * (0.2 + thunderPan))
        }

        private fun rainSample(dropRate: Double): Pair<Double, Double> {
            brownL = brown(brownL); brownR = brown(brownR)
            lpL += 0.35 * (pink() - lpL); lpR += 0.35 * (pink() - lpR)
            if (rnd.nextDouble() < dropRate) { drop = 0.25 + rnd.nextDouble() * 0.5; dropPan = rnd.nextDouble() }
            val d = if (drop > 0.001) { val v = white() * drop; drop *= DROP_DECAY; v } else 0.0
            return Pair(lpL * 1.4 + brownL * 1.2 + d * (1 - dropPan), lpR * 1.4 + brownR * 1.2 + d * dropPan)
        }

        // ---- fireplace: a low roar with crackles and the odd pop
        private var crack = 0.0; private var crackPan = 0.5; private var crackHp = 0.0; private var crackPrev = 0.0
        private var roar = 0.0; private var roar2 = 0.0
        private fun fire(): Pair<Double, Double> {
            roar += 0.02 * (white() - roar); roar2 += 0.02 * (roar - roar2)
            val flutter = 0.7 + 0.3 * sin(sec() * 2.1) * sin(sec() * 0.7 + 1)
            val p = rnd.nextDouble()
            if (p < 0.00022) { crack = 0.9 + rnd.nextDouble() * 0.6; crackPan = rnd.nextDouble() }       // pop
            else if (p < 0.0022) { crack = maxOf(crack, 0.15 + rnd.nextDouble() * 0.35); crackPan = rnd.nextDouble() } // crackle
            var c = 0.0
            if (crack > 0.002) {
                val w = white()
                crackHp = 0.6 * (crackHp + w - crackPrev); crackPrev = w   // bright, snappy
                c = crackHp * crack
                crack *= CRACK_DECAY
            }
            val base = roar2 * 6.0 * flutter + pink() * 0.05
            return Pair(base + c * (1 - crackPan), base + c * crackPan)
        }

        // ---- night train: rumble and the "clack-clack" of wheels over rail joints
        private var clack = 0.0; private var clackLp = 0.0; private var trainLp = 0.0; private var trainLp2 = 0.0
        private val beat = (RATE * 1.15).toLong()
        private fun train(): Pair<Double, Double> {
            trainLp += 0.01 * (white() - trainLp); trainLp2 += 0.01 * (trainLp - trainLp2)
            val ph = t % beat
            if (ph == 0L || ph == (RATE * 0.13).toLong() || ph == (RATE * 0.58).toLong() || ph == (RATE * 0.71).toLong()) clack = 0.6 + rnd.nextDouble() * 0.25
            var c = 0.0
            if (clack > 0.002) { clackLp += 0.25 * (white() - clackLp); c = clackLp * clack * 2.2; clack *= CLACK_DECAY }
            val sway = 1 + 0.15 * sin(sec() * 0.9)
            val base = trainLp2 * 14.0 * sway + pink() * 0.06
            return Pair(base + c * 0.9, base + c * 0.75)
        }

        // ---- crickets: a few crickets chirping at their own pitch and pace
        private val crPitch = doubleArrayOf(4300.0, 4700.0, 5100.0)
        private val crEvery = longArrayOf((RATE * 0.82).toLong(), (RATE * 1.07).toLong(), (RATE * 0.95).toLong())
        private val crOff = longArrayOf(0L, (RATE * 0.31).toLong(), (RATE * 0.57).toLong())
        private val crPan = doubleArrayOf(0.25, 0.75, 0.5)
        private val crLevel = doubleArrayOf(0.10, 0.07, 0.05)
        private var nightLp = 0.0
        private fun crickets(): Pair<Double, Double> {
            var cl = 0.0; var cr = 0.0
            for (k in 0 until 3) {
                val ph = (t + crOff[k]) % crEvery[k]
                val pulses = 4
                val pulseLen = RATE * 0.03
                if (ph < pulseLen * pulses * 1.6) {
                    val inPulse = (ph % (pulseLen * 1.6).toLong()) < pulseLen
                    if (inPulse) {
                        val v = sin(2 * PI * crPitch[k] * t / RATE) * crLevel[k]
                        cl += v * (1 - crPan[k]); cr += v * crPan[k]
                    }
                }
            }
            nightLp += 0.05 * (pink() - nightLp)
            return Pair(cl + nightLp * 0.5, cr + nightLp * 0.5)
        }

        // ---- brook: gentle water hiss with little bubbling "bloops"
        private var bub = 0.0; private var bubF = 500.0; private var bubPh = 0.0; private var bubPan = 0.5; private var waterLp = 0.0
        private fun brook(): Pair<Double, Double> {
            val w = pink()
            waterLp += 0.25 * (w - waterLp)
            val water = (w - waterLp) * 1.6 * (0.8 + 0.2 * sin(sec() * 1.3) * sin(sec() * 0.4))
            if (rnd.nextDouble() < 0.0004) { bub = 0.12 + rnd.nextDouble() * 0.12; bubF = 350.0 + rnd.nextDouble() * 500; bubPan = rnd.nextDouble() }
            var b = 0.0
            if (bub > 0.001) { bubF *= 1.00004; bubPh += 2 * PI * bubF / RATE; b = sin(bubPh) * bub; bub *= BUBBLE_DECAY }
            return Pair(water + b * (1 - bubPan), water + b * bubPan)
        }

        fun next(id: String?) {
            t++
            val (a, b) = when (id) {
                "white" -> Pair(white() * 0.35, white() * 0.35)
                "pink" -> { val a = pink(); Pair(a * 0.9, (a * 0.6 + pink() * 0.4) * 0.9) }
                "brown" -> { brownL = brown(brownL); brownR = brown(brownR); Pair(brownL * 3.2, brownR * 3.2) }
                "fan" -> {
                    brownL = brown(brownL); brownR = brown(brownR)
                    lpL += 0.08 * (pink() - lpL); lpR += 0.08 * (pink() - lpR)
                    val hum = sin(2 * PI * 118.0 * t / RATE) * 0.012 * (1 + 0.2 * sin(2 * PI * 0.3 * t / RATE))
                    Pair(brownL * 2.2 + lpL * 1.6 + hum, brownR * 2.2 + lpR * 1.6 + hum)
                }
                "ocean" -> {
                    val sec = t.toDouble() / RATE
                    val s = 0.5 + 0.5 * sin(2 * PI * sec / swellPeriod + swellPhase)
                    if (t % (RATE * 30) == 0L) swellPeriod = 7.5 + rnd.nextDouble() * 4
                    val swell = 0.18 + 0.82 * s * s
                    brownL = brown(brownL); brownR = brown(brownR)
                    val hissL = pink() * 0.45 * s * s * s
                    val hissR = pink() * 0.45 * s * s * s
                    Pair((brownL * 3.0 + hissL) * swell, (brownR * 3.0 + hissR) * swell)
                }
                "rain" -> {
                    // A bed of soft hiss + low rumble, with little drops scattered left and right.
                    brownL = brown(brownL); brownR = brown(brownR)
                    lpL += 0.35 * (pink() - lpL); lpR += 0.35 * (pink() - lpR)
                    if (rnd.nextDouble() < 0.0012) { drop = 0.25 + rnd.nextDouble() * 0.5; dropPan = rnd.nextDouble() }
                    val d = if (drop > 0.001) { val v = white() * drop; drop *= exp(-1.0 / (RATE * 0.004)); v } else 0.0
                    Pair(lpL * 1.4 + brownL * 1.2 + d * (1 - dropPan), lpR * 1.4 + brownR * 1.2 + d * dropPan)
                }
                "storm" -> storm()
                "fire" -> fire()
                "train" -> train()
                "crickets" -> crickets()
                "brook" -> brook()
                else -> Pair(0.0, 0.0)
            }
            l = a
            r = b
        }
    }
}

// ---------------------------------------------------------------- the "Sleep Sounds" app on the TV

/** Its own entry in the TV's app list ("Sleep Sounds"). The sound keeps playing after you leave. */
class SleepSoundsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SleepSoundEngine.appCtx = applicationContext
        setContent { SleepSoundsScreen(onScreenOff = { ScreenOff.off(this) }) }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        Idle.poke()
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun SleepSoundsScreen(onScreenOff: () -> Unit) {
    val playing = SleepSoundEngine.playing.value
    val vol = SleepSoundEngine.volume.value
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } } // refresh the minutes left
    val left = remember(tick, SleepSoundEngine.endsAt.longValue) { SleepSoundEngine.minutesLeft() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070912), Color(0xFF141A2E)))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
            Text("SLEEP SOUNDS", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 3.sp)
            Text(
                if (playing == null) "Pick a sound" else "Playing: " + (SleepSoundEngine.SOUNDS.firstOrNull { it.id == playing }?.title ?: ""),
                color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light,
            )
            Text(
                "Plays alongside other apps (never pauses YouTube) and keeps going after you leave. OK on a sound to play or stop it.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            val rows = SleepSoundEngine.SOUNDS.chunked(4)
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEachIndexed { c, s ->
                        SoundCard(
                            s, on = playing == s.id,
                            modifier = if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = { if (playing == s.id) SleepSoundEngine.stop() else SleepSoundEngine.play(s.id) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val timerText = when (left) { null -> "Timer: off"; else -> "Stops in $left min" }
                Pill(timerText) { SleepSoundEngine.cycleTimer() }
                Pill("Quieter") { SleepSoundEngine.setVolume(vol - 10) }
                Text("$vol%", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.width(44.dp))
                Pill("Louder") { SleepSoundEngine.setVolume(vol + 10) }
                Pill("Screen off") { onScreenOff() }
                if (playing != null) Pill("Stop") { SleepSoundEngine.stop() }
            }
        }
    }
}

@Composable
private fun SoundCard(s: SleepSoundEngine.Sound, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = Color(s.color)
    Box(
        modifier
            .width(200.dp).height(80.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) accent.copy(alpha = 0.32f) else Color.White.copy(alpha = if (focused) 0.16f else 0.07f))
            .border(2.dp, if (focused) Color.White.copy(alpha = 0.85f) else Color.Transparent, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { onClick(); true } else false }
            .focusable()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoundGlyph(s.id, accent)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(s.title, color = Color.White, fontSize = 17.sp)
                Text(if (on) "Playing · OK to stop" else s.blurb, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(21.dp))
            .background(Color.White.copy(alpha = if (focused) 0.9f else 0.1f))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { onClick(); true } else false }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (focused) Color(0xFF0B0D16) else Color.White, fontSize = 15.sp)
    }
}

/** Little drawn pictures for each sound. */
@Composable
private fun SoundGlyph(id: String, c: Color) {
    Canvas(Modifier.size(34.dp)) {
        val s = size.minDimension
        when (id) {
            "rain" -> for (i in 0..3) {
                val x = s * (0.2f + 0.2f * i)
                drawLine(c, Offset(x, s * (0.2f + 0.1f * (i % 2))), Offset(x - s * 0.08f, s * (0.55f + 0.1f * (i % 2))), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
            }
            "ocean" -> for (i in 0..2) {
                val y = s * (0.3f + 0.2f * i)
                var prev = Offset(0f, y)
                for (k in 1..20) {
                    val x = s * k / 20f
                    val p = Offset(x, y + sin(k / 20f * 2 * PI.toFloat() * 1.5f) * s * 0.06f)
                    drawLine(c, prev, p, strokeWidth = s * 0.06f, cap = StrokeCap.Round); prev = p
                }
            }
            "fan" -> {
                val ctr = Offset(s / 2, s / 2)
                for (i in 0..2) {
                    val a = i * 2 * PI.toFloat() / 3
                    drawCircle(c, s * 0.16f, Offset(ctr.x + kotlin.math.cos(a) * s * 0.2f, ctr.y + sin(a) * s * 0.2f))
                }
                drawCircle(Color(0xFF141A2E), s * 0.07f, ctr)
            }
            else -> {
                val amp = when (id) { "white" -> 0.32f; "pink" -> 0.22f; else -> 0.14f }
                var prev = Offset(0f, s / 2)
                for (k in 1..24) {
                    val x = s * k / 24f
                    val p = Offset(x, s / 2 + (Random(k * 13 + id.length).nextFloat() * 2 - 1) * s * amp)
                    drawLine(c, prev, p, strokeWidth = s * 0.05f, cap = StrokeCap.Round); prev = p
                }
            }
        }
    }
}
