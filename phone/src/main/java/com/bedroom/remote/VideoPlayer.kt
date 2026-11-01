package com.bedroom.remote

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Plays the TV's live H.264 stream on a Surface using the phone's hardware decoder, with as little buffering
 * as possible. Reconnects by itself when the TV ends a 3-minute segment.
 */
class VideoPlayer(
    private val tv: TvClient,
    private val surface: Surface,
    private val width: Int = 848,
    private val height: Int = 480,
    private val bitrate: Int = 2_000_000,
    private val onState: (String) -> Unit,
    private val onFrame: () -> Unit,
    /** Called for every video frame that arrives from the TV (before decoding). */
    private val onPacket: () -> Unit = {},
) {
    @Volatile private var running = true
    @Volatile private var conn: java.net.HttpURLConnection? = null

    fun start() {
        thread(name = "tv-video", isDaemon = true) {
            var fails = 0
            while (running) {
                val c = tv.openStream(width, height, bitrate)
                if (c == null) {
                    fails++
                    onState(if (fails >= 3) "failed" else "connecting")
                    Thread.sleep(1500)
                    continue
                }
                conn = c
                onState("playing")
                val ok = try {
                    c.inputStream.use { decode(it) }
                } catch (e: Exception) {
                    false
                } finally {
                    runCatching { c.disconnect() }
                    conn = null
                }
                fails = if (ok) 0 else fails + 1
                if (running) onState(if (fails >= 3) "failed" else "connecting")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
    }

    /** Splits the Annex-B stream into NAL units and feeds the decoder. Returns true if any frame was shown. */
    private fun decode(input: InputStream): Boolean {
        val codec = MediaCodec.createDecoderByType("video/avc")
        val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        codec.configure(fmt, surface, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var shown = false
        var config: ByteArray? = null
        try {
            val nals = NalReader(input)
            while (running) {
                val nal = nals.next() ?: break
                val type = nal[nal.startCodeLength()].toInt() and 0x1F
                when (type) {
                    7 -> { config = nal; continue }                 // SPS: wait for PPS
                    8 -> {                                          // PPS: send SPS+PPS as codec config
                        val cfg = (config ?: ByteArray(0)) + nal
                        queue(codec, cfg, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        config = null
                        continue
                    }
                    1, 5 -> {
                        onPacket()
                        queue(codec, nal, if (type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                    else -> continue                                // SEI, AUD...: not needed
                }
                // Show every decoded frame right away (no buffering for smoothness: lowest delay wins).
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    codec.releaseOutputBuffer(out, true)
                    shown = true
                    onFrame()
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return shown
    }

    private fun queue(codec: MediaCodec, data: ByteArray, flags: Int) {
        // Wait for room rather than dropping: a dropped frame smears the picture until the next full frame.
        var i = codec.dequeueInputBuffer(50_000)
        var waited = 0
        while (i < 0 && running && waited < 20) {
            drain(codec)
            i = codec.dequeueInputBuffer(50_000)
            waited++
        }
        if (i < 0) return
        val buf = codec.getInputBuffer(i) ?: return
        buf.clear()
        buf.put(data)
        codec.queueInputBuffer(i, 0, data.size, System.nanoTime() / 1000, flags)
        drain(codec)
    }

    /** Show everything already decoded, right away. */
    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            codec.releaseOutputBuffer(out, true)
            onFrame()
        }
    }

    private fun ByteArray.startCodeLength() = if (size > 3 && this[2].toInt() == 1) 3 else 4
}

/** Reads H.264 Annex-B NAL units (each returned with its 00 00 01 / 00 00 00 01 start code). */
private class NalReader(private val input: InputStream) {
    private val buf = ByteArray(2 * 1024 * 1024)
    private var len = 0
    private var pos = 0
    private var eof = false

    private fun fill(): Boolean {
        if (eof) return false
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
            pos = 0
        }
        if (len == buf.size) return false
        val n = input.read(buf, len, buf.size - len)
        if (n < 0) { eof = true; return false }
        len += n
        return true
    }

    /** Index of the next start code at or after [from], or -1. */
    private fun findStart(from: Int): Int {
        var i = from
        while (i + 3 <= len) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 &&
                (buf[i + 2].toInt() == 1 || (i + 3 < len && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1))
            ) return i
            i++
        }
        return -1
    }

    fun next(): ByteArray? {
        // Find the start of this NAL.
        var start = findStart(pos)
        while (start < 0) {
            pos = maxOf(pos, len - 3).coerceAtLeast(0)
            if (!fill()) return null
            start = findStart(pos)
        }
        pos = start
        // Find the start of the following NAL (that's where this one ends).
        var end = findStart(pos + 3)
        while (end < 0) {
            val before = len
            if (!fill()) {
                if (len > pos) { val nal = buf.copyOfRange(pos, len); pos = len; return nal }
                return null
            }
            end = findStart(pos + 3)
            if (end < 0 && len == before) return null
        }
        val nal = buf.copyOfRange(pos, end)
        pos = end
        return nal
    }
}
