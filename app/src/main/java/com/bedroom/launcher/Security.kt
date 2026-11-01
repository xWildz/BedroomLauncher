package com.bedroom.launcher

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The shared secret between the TV and the phone app (shown only as the TV's pairing QR code).
 * 32 random characters (160 bits). It never travels over Wi-Fi: requests are signed with it instead.
 */
object RemoteKey {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    const val LENGTH = 32

    @Synchronized
    fun get(ctx: Context): String {
        val p = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
        p.getString("key", null)?.takeIf { it.length >= LENGTH }?.let { return it }
        return newKey(ctx)
    }

    /** Make a fresh key; every paired phone has to scan the QR code again. */
    @Synchronized
    fun newKey(ctx: Context): String {
        val r = SecureRandom()
        val k = (1..LENGTH).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("key", k).commit()
        return k
    }
}

/**
 * Request signing (HMAC-SHA256), replay protection and lock-out.
 *
 * Every phone request carries X-TS (time, ms), X-Nonce (random, used once) and
 * X-Sig = HMAC(key, "METHOD\npath?query\nts\nnonce"). Uploads end with a 64-character HMAC of the body,
 * so the content can't be swapped on the way. Replies carry X-Sig = HMAC(key, "resp\nnonce\nsha256(body)")
 * so the phone can tell they really came from this TV.
 */
object Auth {
    private const val WINDOW_MS = 5 * 60 * 1000L       // requests older/newer than 5 min are refused
    private const val MAX_FAILS = 30                    // forged requests from one address ...
    private const val FAIL_WINDOW_MS = 60 * 1000L       // ... within a minute ...
    private const val BLOCK_MS = 5 * 60 * 1000L         // ... block it for 5 minutes

    private val seen = LinkedHashMap<String, Long>()    // nonce -> expiry (elapsedRealtime)
    private val fails = HashMap<String, Pair<Int, Long>>()
    private val blocked = HashMap<String, Long>()

    fun hmac(key: String, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    fun newMac(key: String): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sha256Hex(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    /** Constant-time compare (no timing hints). */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Only devices on the home network (and the TV itself). */
    fun isLocal(a: InetAddress?) = a != null && (a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress)

    @Synchronized
    fun isBlocked(ip: String): Boolean {
        val until = blocked[ip] ?: return false
        if (SystemClock.elapsedRealtime() > until) {
            blocked.remove(ip)
            return false
        }
        return true
    }

    @Synchronized
    fun fail(ip: String) {
        val now = SystemClock.elapsedRealtime()
        val (n, start) = fails[ip]?.takeIf { now - it.second < FAIL_WINDOW_MS } ?: (0 to now)
        if (n + 1 >= MAX_FAILS) {
            blocked[ip] = now + BLOCK_MS
            fails.remove(ip)
            android.util.Log.w(TAG, "Blocked $ip for 5 minutes after repeated forged requests")
        } else {
            fails[ip] = (n + 1) to start
        }
    }

    /**
     * Checks a request's signature. Returns its nonce if it's genuine and new, else null.
     * [target] = path + query exactly as sent.
     */
    @Synchronized
    fun verify(key: String, method: String, target: String, headers: Map<String, String>): String? {
        val ts = headers["x-ts"]?.toLongOrNull() ?: return null
        val nonce = headers["x-nonce"]?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        val sig = headers["x-sig"] ?: return null
        if (Math.abs(System.currentTimeMillis() - ts) > WINDOW_MS) return null
        val expected = hex(hmac(key, "$method\n$target\n$ts\n$nonce".toByteArray(Charsets.UTF_8)))
        if (!same(expected, sig.lowercase())) return null
        val now = SystemClock.elapsedRealtime()
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value < now) it.remove() else break
        if (seen.containsKey(nonce)) return null // replayed
        seen[nonce] = now + 2 * WINDOW_MS
        return nonce
    }

    fun responseSig(key: String, nonce: String, bodySha256Hex: String) =
        hex(hmac(key, "resp\n$nonce\n$bodySha256Hex".toByteArray(Charsets.UTF_8)))

    /**
     * Reads an upload body: [length] bytes = the file, then 64 hex chars of HMAC(key, "body\nnonce\n" + file).
     * Saves it to [dest] and returns true only if the HMAC matches.
     */
    fun receiveVerified(key: String, nonce: String, input: InputStream, length: Long, dest: File): Boolean {
        if (length <= 64) return false
        val dataLen = length - 64
        val mac = newMac(key)
        mac.update("body\n$nonce\n".toByteArray(Charsets.UTF_8))
        var got = 0L
        dest.outputStream().buffered(256 * 1024).use { out ->
            val buf = ByteArray(256 * 1024)
            while (got < dataLen) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), dataLen - got).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                mac.update(buf, 0, n)
                got += n
            }
        }
        if (got != dataLen) return false
        val tail = ByteArray(64)
        var t = 0
        while (t < 64) {
            val n = input.read(tail, t, 64 - t)
            if (n < 0) return false
            t += n
        }
        return same(hex(mac.doFinal()), String(tail, Charsets.US_ASCII).lowercase())
    }
}
