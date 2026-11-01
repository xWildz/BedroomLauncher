package com.bedroom.launcher

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Presses real remote buttons (arrows, OK, Back, typing) for the phone remote.
 *
 * Apps can't inject key presses into other apps, so the launcher talks to the TV's own developer
 * connection (adb on 127.0.0.1:5555, already on for this TV). The first time, the TV shows
 * "Allow USB debugging?" for this app's key: choose "Always allow".
 *
 * Fast path: a virtual remote-control device ([UhidHelper] "keys" mode) kept open the whole time, so a
 * press reaches the TV in a few milliseconds. Anything it can't press (typing, Settings key) uses the
 * slower `input` command, batched so fast taps don't pile up.
 */
object KeyInjector {

    /** Remote-button names the phone may send -> Android key codes. Nothing else is accepted. */
    val KEYS = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "back" to 4, "home" to 3, "menu" to 82, "search" to 84,
        "backspace" to 67, "enter" to 66, "playpause" to 85,
        "settings" to 176, "info" to 165,
        "volup" to 24, "voldown" to 25, "mute" to 164,
    )

    /** Same buttons as HID "consumer" usages for the virtual remote (instant). */
    private val USAGE = mapOf(
        "up" to 0x42, "down" to 0x43, "left" to 0x44, "right" to 0x45, "ok" to 0x41,
        "back" to 0x224, "home" to 0x223, "menu" to 0x40, "search" to 0x221,
        "playpause" to 0xCD, "info" to 0x60, "volup" to 0xE9, "voldown" to 0xEA, "mute" to 0xE2,
    )
    private var fast: dadb.AdbStream? = null

    /** "idle", "connecting", "ok", "waiting for approval on the TV", or an error. Shown in the app. */
    @Volatile
    var state: String = "idle"
        private set

    private val queue = LinkedBlockingQueue<String>()
    private var dadb: Dadb? = null
    @Volatile
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(name = "key-injector", isDaemon = true) {
            // Get the virtual remote ready now (only works once "Allow full remote" was accepted).
            runCatching { fastStream(app) }
            while (true) {
                val first = queue.take()
                val batch = mutableListOf(first)
                queue.drainTo(batch)
                run(app, batch)
            }
        }
    }

    fun key(name: String): Boolean {
        if (name !in KEYS) return false
        queue.offer("K:$name")
        return true
    }

    fun text(t: String) {
        if (t.isNotEmpty()) queue.offer("T:$t")
    }

    /**
     * Touch on the TV screen (from the phone's live view): [kind] = tap, long or swipe; positions are 0..1 of
     * the screen width/height, so they work whatever the phone's picture size is.
     */
    fun touch(ctx: Context, kind: String, nx: Float, ny: Float, nx2: Float = nx, ny2: Float = ny, ms: Int = 250): Boolean {
        val ok = listOf(nx, ny, nx2, ny2).all { it in 0f..1f }
        if (!ok) return false
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        ctx.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(size)
        fun px(v: Float, max: Int) = (v * (max - 1)).toInt().coerceIn(0, max - 1)
        val x = px(nx, size.x); val y = px(ny, size.y)
        val cmd = when (kind) {
            "tap" -> "input tap $x $y"
            "long" -> "input swipe $x $y $x $y 700"
            "swipe" -> "input swipe $x $y ${px(nx2, size.x)} ${px(ny2, size.y)} ${ms.coerceIn(60, 2000)}"
            else -> return false
        }
        queue.offer("C:$cmd")
        return true
    }

    /** Connect now (shows the TV's approval prompt the first time). */
    fun connect() {
        queue.offer("NOOP")
    }

    /** The always-open virtual remote. Started once; its first line says "ready". */
    private fun fastStream(ctx: Context): dadb.AdbStream {
        fast?.let { return it }
        val apk = ctx.applicationInfo.sourceDir
        val s = connection(ctx).open("exec:CLASSPATH=$apk app_process /system/bin com.bedroom.launcher.UhidHelper keys 2>&1")
        val first = s.source.readUtf8Line()?.trim()
        if (first != "ready") {
            runCatching { s.close() }
            throw IllegalStateException("virtual remote: $first")
        }
        fast = s
        state = "ok"
        Log.i(TAG, "Virtual remote ready")
        return s
    }

    /** Press through the virtual remote; false if it isn't available (then the slow path is used). */
    private fun pressFast(ctx: Context, usage: Int): Boolean {
        for (attempt in 1..2) {
            try {
                val s = fastStream(ctx)
                s.sink.writeUtf8("K %x\n".format(usage))
                s.sink.flush()
                return true
            } catch (e: Throwable) {
                runCatching { fast?.close() }
                fast = null
                if (attempt == 2) Log.w(TAG, "Virtual remote unavailable", e)
            }
        }
        return false
    }

    private fun run(ctx: Context, batch: List<String>) {
        // Instant path first; whatever's left becomes one `input keyevent a b c` (one process start).
        val cmds = mutableListOf<String>()
        val keys = mutableListOf<String>()
        fun flushKeys() {
            if (keys.isNotEmpty()) {
                cmds += "input keyevent ${keys.joinToString(" ")}"
                keys.clear()
            }
        }
        for (item in batch) {
            when {
                item.startsWith("K:") -> {
                    val name = item.removePrefix("K:")
                    val usage = USAGE[name]
                    if (usage == null || !pressFast(ctx, usage)) keys += KEYS.getValue(name).toString()
                }
                item.startsWith("T:") -> {
                    flushKeys()
                    cmds += "input text ${shellQuote(item.removePrefix("T:").replace(" ", "%s"))}"
                }
                item.startsWith("C:") -> {
                    flushKeys()
                    cmds += item.removePrefix("C:") // only built by touch(): numbers, no user text
                }
                item == "NOOP" -> runCatching { fastStream(ctx) }
            }
        }
        flushKeys()
        if (cmds.isEmpty()) return
        val cmd = cmds.joinToString(" ; ")

        for (attempt in 1..2) {
            try {
                val d = connection(ctx)
                d.shell(cmd)
                state = "ok"
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Key injection failed (attempt $attempt)", e)
                state = "error: ${e.javaClass.simpleName}"
                runCatching { fast?.close() }
                fast = null
                runCatching { dadb?.close() }
                dadb = null
            }
        }
    }

    private fun connection(ctx: Context): Dadb {
        dadb?.let { return it }
        state = "waiting for approval on the TV"
        val d = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
        dadb = d
        return d
    }

    /** The launcher's adb key (shared with the gamepad, so the TV only asks for approval once). */
    @Synchronized
    fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
