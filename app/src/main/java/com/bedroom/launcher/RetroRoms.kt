package com.bedroom.launcher

import android.content.Context
import android.os.Environment
import android.os.StatFs
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Games (ROMs) for RetroArch, sent from the phone. Same rules as tools\roms.ps1 on the PC:
 * files are sorted into a folder per system inside RetroArch's own folder (the only place the Play Store
 * RetroArch can read). The file streams to a temp file on the TV first, so big games don't use RAM.
 */
object RetroRoms {
    const val ROMS = "/sdcard/Android/data/com.retroarch/files/ROMs"

    private val MAP = linkedMapOf(
        "NES" to listOf("nes", "fds", "unf"),
        "SNES" to listOf("sfc", "smc", "fig", "swc"),
        "N64" to listOf("n64", "z64", "v64"),
        "GB" to listOf("gb"),
        "GBC" to listOf("gbc"),
        "GBA" to listOf("gba"),
        "NDS" to listOf("nds"),
        "Genesis" to listOf("md", "gen", "smd", "32x"),
        "Master System" to listOf("sms", "gg"),
        "PS1" to listOf("cue", "chd", "pbp", "m3u"),
        "Atari" to listOf("a26", "a78", "lnx"),
        "Patches" to listOf("ips", "bps", "ups"),
    )
    private val SAFE_NAME = Regex("""^[\p{L}\p{N} ._()\[\]!&,'+#~-]{1,200}$""")

    private fun folderForExt(ext: String): String? = MAP.entries.firstOrNull { ext in it.value }?.key

    /** Which system folder a file goes in. A .zip is opened to see what's inside. */
    private fun folderFor(name: String, file: File): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "zip") {
            try {
                ZipFile(file).use { z ->
                    for (e in z.entries()) {
                        folderForExt(e.name.substringAfterLast('.', "").lowercase())?.let { return it }
                    }
                }
            } catch (e: Exception) {
            }
            return "Other"
        }
        return folderForExt(ext) ?: "Other"
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        -1
    }

    /**
     * A USB drive plugged into the TV (used as normal "portable" storage), or null. New games go there:
     * into RetroArch's own folder on the drive, which RetroArch can always read without extra permissions.
     */
    private fun usbRoot(d: Dadb): String? =
        d.shell("for x in /storage/*; do case \"\$x\" in */emulated|*/self) ;; *) [ -d \"\$x\" ] && [ -w \"\$x\" ] && echo \"\$x\";; esac; done | head -n 1")
            .output.trim().ifBlank { null }

    private fun usbRoms(usb: String) = "$usb/Android/data/com.retroarch/files/ROMs"

    private fun usbFree(d: Dadb, usb: String): Long =
        d.shell("df -k ${q(usb)} 2>/dev/null | tail -n 1").output.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.times(1024) ?: -1

    private fun listIn(d: Dadb, root: String, suffix: String, into: MutableMap<String, JSONArray>) {
        val out = d.shell("[ -d ${q(root)} ] && cd ${q(root)} && find . -type f -exec stat -c '%s|%n' {} + 2>/dev/null").output
        out.lines().forEach { line ->
            val p = line.trim().split("|", limit = 2)
            if (p.size < 2) return@forEach
            val rel = p[1].removePrefix("./")
            val system = (if (rel.contains('/')) rel.substringBefore('/') else "Other") + suffix
            into.getOrPut(system) { JSONArray() }
                .put(JSONObject().put("name", rel.substringAfterLast('/')).put("size", p[0].toLongOrNull() ?: 0))
        }
    }

    /** Every game on the TV (and on a USB drive, if one is plugged in), grouped by system, plus free space. */
    fun list(ctx: Context): String = try {
        val bySystem = sortedMapOf<String, JSONArray>(String.CASE_INSENSITIVE_ORDER)
        var usbFreeBytes = -1L
        val usb = Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
            listIn(d, ROMS, "", bySystem)
            usbRoot(d)?.also { u ->
                listIn(d, usbRoms(u), " (USB drive)", bySystem)
                usbFreeBytes = usbFree(d, u)
            }
        }
        val arr = JSONArray()
        bySystem.forEach { (sys, files) -> arr.put(JSONObject().put("system", sys).put("files", files)) }
        JSONObject().put("ok", true).put("systems", arr).put("free", freeBytes())
            .put("usb", if (usb != null) JSONObject().put("free", usbFreeBytes) else JSONObject.NULL).toString()
    } catch (e: Throwable) {
        JSONObject().put("ok", false)
            .put("error", "Can't reach RetroArch's folder. Press \"Allow full remote\" on the TV's Phone screen.").toString()
    }

    /** Put one received game ([file], already checked) in the right folder. One at a time. */
    @Synchronized
    fun upload(ctx: Context, rawName: String, file: File): String {
        fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
        val name = rawName.substringAfterLast('/').trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (!SAFE_NAME.matches(name)) return fail("That file name has characters the TV can't use. Rename it and try again.")
        if (ext == "rar" || ext == "7z") return fail("RetroArch can't open .$ext files. Unzip it first (or re-zip it as .zip).")
        return try {
            val folder = folderFor(name, file)
            Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
                // A USB drive, if plugged in and it has room, takes new games (the TV itself has little space).
                val usb = usbRoot(d)?.takeIf { usbFree(d, it) > file.length() + 20L * 1024 * 1024 }
                val root = if (usb != null) usbRoms(usb) else ROMS
                if (usb == null) {
                    val free = freeBytes()
                    if (free in 0 until file.length() + 50L * 1024 * 1024) return fail("Not enough space on the TV. Plug in a USB drive for more room.")
                }
                val target = "$root/$folder/$name"
                val existed = d.shell("[ -e ${q(target)} ] && echo yes").output.trim() == "yes"
                d.shell("mkdir -p ${q("$root/$folder")}")
                d.push(file, target, 420, System.currentTimeMillis())
                JSONObject().put("ok", true).put("folder", folder + if (usb != null) " (USB drive)" else "")
                    .put("replaced", existed).put("usb", usb != null).toString()
            }
        } catch (e: Throwable) {
            fail("Couldn't save it on the TV (${e.javaClass.simpleName}). Is \"Allow full remote\" turned on?")
        }
    }
}
