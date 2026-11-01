package com.bedroom.launcher

import android.content.Context
import android.util.Log
import dadb.AdbStream
import dadb.Dadb
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Phone gamepad -> virtual controller on the TV.
 *
 * Each phone sends signed UDP packets (BGP2 = player 1, BGP3 = player 1 or 2) to port 8766 on every press/release
 * (plus a heartbeat). We forward the state to [UhidHelper], started once through the TV's own adb
 * (same "Always allow" approval as the full remote). If packets stop, all buttons are released.
 */
object GamepadBridge {
    const val PORT = 8766

    /** "off", "starting", "ready", or an error (player 1; player 2 is in [state2]). Shown in the phone app. */
    val state: String get() = pads[0].state
    val state2: String get() = pads[1].state

    /** One virtual controller per player. Player 2 is only created when a second phone uses it. */
    private class Pad(val player: Int) {
        val worker = Executors.newSingleThreadExecutor()
        @Volatile var state: String = "off"
        var dadb: Dadb? = null
        var stream: AdbStream? = null
        var curButtons = 0
        var curHat = 8
        @Volatile var lastPacketAt = 0L
        var lastSeq = 0L
    }

    private val pads = arrayOf(Pad(1), Pad(2))
    @Volatile
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext

        thread(name = "gamepad-udp", isDaemon = true) {
            try {
                DatagramSocket(PORT).use { sock ->
                    val buf = ByteArray(256)
                    Log.i(TAG, "Gamepad listening on UDP $PORT")
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        if (!Auth.isLocal(pkt.address)) continue
                        handle(app, String(pkt.data, 0, pkt.length, Charsets.US_ASCII), pkt.address)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gamepad UDP stopped", e)
                pads[0].state = "error: ${e.javaClass.simpleName}"
                started = false
            }
        }

        // Safety: if a phone goes quiet (screen off, Wi-Fi drop) let go of every button on its pad.
        thread(name = "gamepad-watchdog", isDaemon = true) {
            while (true) {
                Thread.sleep(400)
                for (pad in pads) {
                    val quiet = pad.lastPacketAt > 0 && System.currentTimeMillis() - pad.lastPacketAt > 1200
                    if (quiet) apply(app, pad, 0, 8)
                }
            }
        }
    }

    /** Newest key-press number seen from each sender (phone, PC...), so they don't block each other. */
    private val lastKeySeq = HashMap<java.net.InetAddress, Long>()

    /**
     * Remote buttons from the phone: "BRK1 <seq> <key name> <mac>", mac = first 32 hex of
     * HMAC(key, "BRK1 seq name"). The phone sends each press twice; the repeat (same seq) is ignored.
     */
    private fun handleKey(ctx: Context, p: List<String>, from: java.net.InetAddress) {
        if (p.size != 4) return
        val seq = p[1].toLongOrNull() ?: return
        val expected = Auth.hex(Auth.hmac(RemoteKey.get(ctx), "BRK1 ${p[1]} ${p[2]}".toByteArray(Charsets.US_ASCII))).take(32)
        if (!Auth.same(expected, p[3].lowercase())) return
        synchronized(lastKeySeq) {
            if (seq <= (lastKeySeq[from] ?: 0L)) return
            if (lastKeySeq.size > 16) lastKeySeq.clear()
            lastKeySeq[from] = seq
        }
        KeyInjector.key(p[2])
    }

    /**
     * Player 1 (older apps): "BGP2 <seq> <buttons hex> <hat> <mac>", mac = first 32 hex of HMAC(key, "BGP2 seq buttons hat").
     * Any player:             "BGP3 <player> <seq> <buttons hex> <hat> <mac>", mac over "BGP3 player seq buttons hat".
     * Only packets signed with the pairing key, and only newer than the last one for that player, are used.
     */
    private fun handle(ctx: Context, msg: String, from: java.net.InetAddress) {
        val p = msg.trim().split(" ")
        when (p.firstOrNull()) {
            "BRK1" -> return handleKey(ctx, p, from)
            "BGP2" -> {
                if (p.size != 5) return
                if (!signed(ctx, p.subList(0, 4).joinToString(" "), p[4])) return
                use(ctx, pads[0], p[1], p[2], p[3])
            }
            "BGP3" -> {
                if (p.size != 6) return
                val player = p[1].toIntOrNull() ?: return
                if (player !in 1..2) return
                if (!signed(ctx, p.subList(0, 5).joinToString(" "), p[5])) return
                use(ctx, pads[player - 1], p[2], p[3], p[4])
            }
        }
    }

    private fun signed(ctx: Context, body: String, mac: String): Boolean {
        val expected = Auth.hex(Auth.hmac(RemoteKey.get(ctx), body.toByteArray(Charsets.US_ASCII))).take(32)
        return Auth.same(expected, mac.lowercase())
    }

    private fun use(ctx: Context, pad: Pad, seqS: String, buttonsS: String, hatS: String) {
        val seq = seqS.toLongOrNull() ?: return
        synchronized(pad) {
            if (seq <= pad.lastSeq) return
            pad.lastSeq = seq
        }
        pad.lastPacketAt = System.currentTimeMillis()
        val buttons = buttonsS.toIntOrNull(16) ?: return
        val hat = (hatS.toIntOrNull() ?: 8).coerceIn(0, 8)
        apply(ctx, pad, buttons and 0xFFFF, hat)
    }

    private fun apply(ctx: Context, pad: Pad, buttons: Int, hat: Int) {
        pad.worker.execute {
            if (buttons == pad.curButtons && hat == pad.curHat && pad.stream != null) return@execute
            if (pad.stream == null && buttons == 0 && hat == 8) return@execute // nothing to release
            try {
                val s = ensure(ctx, pad)
                s.sink.writeUtf8("S %04x %d\n".format(buttons, hat))
                s.sink.flush()
                pad.curButtons = buttons
                pad.curHat = hat
            } catch (e: Throwable) {
                Log.w(TAG, "Gamepad ${pad.player} write failed", e)
                pad.state = "error: ${e.javaClass.simpleName}"
                close(pad)
            }
        }
    }

    private fun ensure(ctx: Context, pad: Pad): AdbStream {
        pad.stream?.let { return it }
        pad.state = "starting"
        val d = pad.dadb ?: Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).also { pad.dadb = it }
        val apk = ctx.applicationInfo.sourceDir
        val arg = if (pad.player == 1) "" else " pad${pad.player}"
        val s = d.open("exec:CLASSPATH=$apk app_process /system/bin com.bedroom.launcher.UhidHelper$arg 2>&1")
        pad.stream = s
        pad.curButtons = -1
        pad.curHat = -1
        // Watch the helper's first line ("ready" or an error).
        thread(isDaemon = true) {
            try {
                val line = s.source.readUtf8Line() ?: "error: helper exited"
                pad.state = if (line.trim() == "ready") "ready" else line.trim().take(80)
                Log.i(TAG, "Gamepad ${pad.player} helper: $line")
            } catch (e: Exception) {
                pad.state = "error: ${e.javaClass.simpleName}"
            }
        }
        return s
    }

    private fun close(pad: Pad) {
        runCatching { pad.stream?.close() }
        pad.stream = null
        runCatching { pad.dadb?.close() }
        pad.dadb = null
    }
}
