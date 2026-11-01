package com.bedroom.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.WeakHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The TV's pairing key, stored encrypted with a key that lives in the phone's hardware keystore
 * (it can't be copied off the phone, and it isn't included in backups).
 */
object SecureKey {
    private const val ALIAS = "bedroom_tv_pairing"
    private const val PREFS = "tv"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(ctx: Context, key: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
        val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key_enc", enc).remove("key").commit()
    }

    fun load(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Older versions kept the key in plain text: move it into the keystore once.
        p.getString("key", null)?.takeIf { it.isNotBlank() }?.let { plain ->
            runCatching { save(ctx, plain) }
            return plain
        }
        val enc = p.getString("key_enc", null) ?: return ""
        return try {
            val (iv, ct) = enc.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun has(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        !it.getString("key_enc", null).isNullOrBlank() || !it.getString("key", null).isNullOrBlank()
    }
}

/**
 * Signs every request to the TV and checks every reply (see Security.kt on the TV for the format).
 * The key itself is never sent.
 */
object Signer {
    private val KEY_RE = Regex("^[a-z2-7]{32}$")
    private val nonces = WeakHashMap<HttpURLConnection, String>()
    private val rnd = SecureRandom()

    /** A real pairing key from this TV (32 characters). */
    fun validKey(k: String) = KEY_RE.matches(k)

    /** Only addresses on a home network: 10.x, 172.16-31.x, 192.168.x. */
    fun validHost(h: String): Boolean {
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun mac(key: String): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Opens a signed connection to http://<host>:8765/api/<action>?params. */
    fun open(host: String, key: String, action: String, params: List<Pair<String, String>>, method: String = "GET"): HttpURLConnection {
        val query = params.joinToString("&") { (a, b) -> a + "=" + URLEncoder.encode(b, "UTF-8") }
        val target = "/api/$action" + if (query.isNotEmpty()) "?$query" else ""
        val ts = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }.let { hex(it) }
        val sig = hex(mac(key).doFinal("$method\n$target\n$ts\n$nonce".toByteArray(Charsets.UTF_8)))
        val conn = URL("http://$host:8765$target").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("X-TS", ts)
        conn.setRequestProperty("X-Nonce", nonce)
        conn.setRequestProperty("X-Sig", sig)
        synchronized(nonces) { nonces[conn] = nonce }
        return conn
    }

    private fun expectedSig(key: String, conn: HttpURLConnection, bodyHash: String): String {
        val nonce = synchronized(nonces) { nonces[conn] } ?: throw SecurityException("unsigned connection")
        return hex(mac(key).doFinal("resp\n$nonce\n$bodyHash".toByteArray(Charsets.UTF_8)))
    }

    /** For the endless video stream: checks the TV signed the reply headers (the video itself is just pictures). */
    fun checkStream(key: String, conn: HttpURLConnection) {
        val got = conn.getHeaderField("X-Sig") ?: throw SecurityException("reply not signed")
        if (!same(expectedSig(key, conn, "stream"), got.lowercase())) throw SecurityException("stream signature mismatch")
    }

    /** Reads the whole reply and checks the TV signed it. Throws SecurityException if not. */
    fun readVerified(key: String, conn: HttpURLConnection): ByteArray {
        val body = conn.inputStream.use { it.readBytes() }
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(body))
        val got = conn.getHeaderField("X-Sig") ?: throw SecurityException("reply not signed")
        if (!same(expectedSig(key, conn, hash), got.lowercase())) throw SecurityException("reply signature mismatch")
        return body
    }

    /** Streams a download into [out], then checks the TV's signature over it. */
    fun copyVerified(key: String, conn: HttpURLConnection, out: OutputStream) {
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { i ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                md.update(buf, 0, n)
            }
        }
        val got = conn.getHeaderField("X-Sig") ?: throw SecurityException("reply not signed")
        if (!same(expectedSig(key, conn, hex(md.digest())), got.lowercase())) throw SecurityException("download signature mismatch")
    }

    /**
     * Upload body: the data, then 64 hex chars of HMAC(key, "body\nnonce\n" + data), so the TV can tell
     * nothing was changed on the way. Set Content-Length to length + 64.
     */
    fun writeSignedBody(key: String, conn: HttpURLConnection, input: InputStream, out: OutputStream, onProgress: (Long) -> Unit = {}) {
        val nonce = synchronized(nonces) { nonces[conn] } ?: throw SecurityException("unsigned connection")
        val m = mac(key)
        m.update("body\n$nonce\n".toByteArray(Charsets.UTF_8))
        val buf = ByteArray(256 * 1024)
        var sent = 0L
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            m.update(buf, 0, n)
            sent += n
            if (sent - last > 512 * 1024) {
                last = sent
                onProgress(sent)
            }
        }
        onProgress(sent)
        out.write(hex(m.doFinal()).toByteArray(Charsets.US_ASCII))
    }
}

/**
 * Remote button presses over UDP (port 8766, same as the gamepad): no connection to set up and no reply to
 * wait for, so a press reaches the TV almost instantly. Signed like everything else; each press is sent
 * twice (the TV ignores the repeat) so a dropped Wi-Fi packet doesn't lose it.
 */
object KeySender {
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var socket: java.net.DatagramSocket? = null
    private var addr: java.net.InetAddress? = null
    private var addrHost = ""
    private var seq = System.currentTimeMillis() * 1000

    fun press(host: String, key: String, name: String) {
        if (host.isBlank() || key.isBlank()) return
        io.execute {
            try {
                val s = socket ?: java.net.DatagramSocket().also { socket = it }
                if (addr == null || addrHost != host) {
                    addr = java.net.InetAddress.getByName(host)
                    addrHost = host
                }
                val body = "BRK1 ${++seq} $name"
                val mac = Signer.hex(Signer.mac(key).doFinal(body.toByteArray(Charsets.US_ASCII))).take(32)
                val bytes = "$body $mac".toByteArray(Charsets.US_ASCII)
                val pkt = java.net.DatagramPacket(bytes, bytes.size, addr, 8766)
                s.send(pkt)
                Thread.sleep(12)
                s.send(pkt)
            } catch (e: Exception) {
                socket = null
            }
        }
    }
}
