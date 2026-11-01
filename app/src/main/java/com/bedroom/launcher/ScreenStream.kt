package com.bedroom.launcher

import android.content.Context
import android.util.Log
import dadb.AdbStream
import dadb.Dadb
import java.io.OutputStream

/**
 * Live video of the TV screen for the phone: the TV's own hardware H.264 encoder (screenrecord), streamed
 * straight to the phone, which decodes it in hardware. About 20-30 frames a second at 720p.
 *
 * screenrecord stops by itself after 3 minutes; the phone then simply asks again (a short blip).
 * Only one viewer at a time: a new one takes over. Protected video (Hulu, Netflix...) is black, as always.
 */
object ScreenStream {
    private var current: AdbStream? = null
    private var dadb: Dadb? = null
    private val lock = Any()

    /** Streams raw H.264 (Annex-B) into [out] until the phone disconnects or the 3-minute segment ends. */
    fun pump(ctx: Context, out: OutputStream, width: Int, height: Int, bitrate: Int) {
        val w = (width.coerceIn(320, 1920) / 16) * 16
        val h = (height.coerceIn(180, 1080) / 16) * 16
        val br = bitrate.coerceIn(500_000, 8_000_000)
        val s: AdbStream
        synchronized(lock) {
            runCatching { current?.close() } // new viewer takes over
            val d = dadb ?: Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).also { dadb = it }
            s = try {
                d.open("exec:screenrecord --output-format=h264 --size ${w}x$h --bit-rate $br -")
            } catch (e: Throwable) {
                runCatching { dadb?.close() }
                dadb = null
                throw e
            }
            current = s
        }
        try {
            val buf = okio.Buffer()
            while (true) {
                val n = s.source.read(buf, 64 * 1024)
                if (n < 0) break
                out.write(buf.readByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            // phone went away, or the segment ended
        } finally {
            runCatching { s.close() }
            synchronized(lock) { if (current === s) current = null }
            Log.i(TAG, "Screen stream ended")
        }
    }
}
