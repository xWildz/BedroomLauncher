package com.bedroom.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import dadb.Dadb
import java.io.ByteArrayOutputStream

/**
 * Live view of the TV screen for the phone: a screenshot through the TV's own adb, shrunk to a small JPEG.
 * About 1-2 frames a second. Protected video (Hulu, Netflix...) shows as black: that's the apps' copy protection.
 */
object ScreenGrab {
    @Volatile private var last: ByteArray? = null
    @Volatile private var lastAt = 0L
    private var dadb: Dadb? = null

    @Synchronized
    fun jpeg(ctx: Context, width: Int): ByteArray? {
        // Several viewers at once share one capture.
        if (SystemClock.elapsedRealtime() - lastAt < 300) last?.let { return it }
        return try {
            val d = dadb ?: Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).also { dadb = it }
            val png = d.open("exec:screencap -p").use { s -> s.source.readByteArray() }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val full = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return null
            val w = width.coerceIn(320, 1280)
            val h = (full.height.toLong() * w / full.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(full, w, h, true)
            if (small !== full) full.recycle()
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            small.recycle()
            out.toByteArray().also {
                last = it
                lastAt = SystemClock.elapsedRealtime()
            }
        } catch (e: Throwable) {
            runCatching { dadb?.close() }
            dadb = null
            null
        }
    }
}
