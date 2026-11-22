package com.bedroom.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Game covers for the Games tab, from RetroArch's public thumbnail library (thumbnails.libretro.com).
 * Looked up by the game's file name, which matches for normally named games ("Super Mario World (USA).sfc").
 * Covers are kept on the phone, so each one is downloaded once; a game with no cover is asked again after a week.
 * Only the game's name goes to the library, nothing else.
 */
object BoxArt {
    private val SYSTEMS = mapOf(
        "NES" to "Nintendo - Nintendo Entertainment System",
        "SNES" to "Nintendo - Super Nintendo Entertainment System",
        "N64" to "Nintendo - Nintendo 64",
        "GB" to "Nintendo - Game Boy",
        "GBC" to "Nintendo - Game Boy Color",
        "GBA" to "Nintendo - Game Boy Advance",
        "NDS" to "Nintendo - Nintendo DS",
        "Genesis" to "Sega - Mega Drive - Genesis",
        "Master System" to "Sega - Master System - Mark III",
        "PS1" to "Sony - PlayStation",
    )
    private val BY_EXT = mapOf(
        "gg" to "Sega - Game Gear", "32x" to "Sega - 32X",
        "a26" to "Atari - 2600", "a78" to "Atari - 7800", "lnx" to "Atari - Lynx",
        "gbc" to "Nintendo - Game Boy Color", "gba" to "Nintendo - Game Boy Advance",
    )
    private val mem = object : LruCache<String, ImageBitmap>(60) {}
    private val gate = Semaphore(3) // a few downloads at a time
    private const val RETRY_MS = 7L * 24 * 3600_000L

    /** The library's folder for this game, or null if we don't know the system. */
    fun librarySystem(system: String, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return BY_EXT[ext] ?: SYSTEMS[system.removeSuffix(" (USB drive)").trim()]
    }

    fun cached(system: String, fileName: String): ImageBitmap? = mem.get("$system/$fileName")

    suspend fun get(ctx: Context, system: String, fileName: String): ImageBitmap? {
        val key = "$system/$fileName"
        mem.get(key)?.let { return it }
        val lib = librarySystem(system, fileName) ?: return null
        val base = fileName.substringBeforeLast('.')
        // The library swaps these characters for "_" in its file names.
        fun clean(s: String) = s.replace(Regex("""[&*/:`<>?\\|"]"""), "_").trim()
        val names = listOf(clean(base), clean(base.replace(Regex("""\s*\[[^\]]*]"""), ""))).distinct()
        return withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "boxart").apply { mkdirs() }
            val id = Integer.toHexString(key.hashCode())
            val file = File(dir, "$id.png")
            val none = File(dir, "$id.none")
            if (!file.exists() && !(none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS)) {
                gate.withPermit {
                    var got = false
                    for (n in names) {
                        if (download("https://thumbnails.libretro.com/${enc(lib)}/Named_Boxarts/${enc(n)}.png", file)) { got = true; break }
                    }
                    if (got) none.delete() else none.writeText("")
                }
            }
            if (!file.exists()) return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 300) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { mem.put(key, it) }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun download(url: String, to: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "BedroomTVRemote")
        try {
            if (c.responseCode != 200) false
            else {
                val tmp = File(to.path + ".part")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.length() > 200 && tmp.renameTo(to)
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        false
    }
}
