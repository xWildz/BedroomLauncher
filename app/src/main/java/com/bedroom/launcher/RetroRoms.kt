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

    /** Every game on the TV, grouped by system, plus free space. */
    fun list(ctx: Context): String = try {
        val out = Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
            d.shell("[ -d $ROMS ] && cd $ROMS && find . -type f -exec stat -c '%s|%n' {} + 2>/dev/null").output
        }
        val bySystem = sortedMapOf<String, JSONArray>(String.CASE_INSENSITIVE_ORDER)
        out.lines().forEach { line ->
            val p = line.trim().split("|", limit = 2)
            if (p.size < 2) return@forEach
            val rel = p[1].removePrefix("./")
            val system = if (rel.contains('/')) rel.substringBefore('/') else "Other"
            bySystem.getOrPut(system) { JSONArray() }
                .put(JSONObject().put("name", rel.substringAfterLast('/')).put("size", p[0].toLongOrNull() ?: 0))
        }
        val arr = JSONArray()
        bySystem.forEach { (sys, files) -> arr.put(JSONObject().put("system", sys).put("files", files)) }
        JSONObject().put("ok", true).put("systems", arr).put("free", freeBytes()).toString()
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
        val free = freeBytes()
        if (free in 0 until file.length() + 50L * 1024 * 1024) return fail("Not enough space on the TV.")
        return try {
            val folder = folderFor(name, file)
            val target = "$ROMS/$folder/$name"
            Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
                val existed = d.shell("[ -e ${q(target)} ] && echo yes").output.trim() == "yes"
                d.shell("mkdir -p ${q("$ROMS/$folder")}")
                d.push(file, target, 420, System.currentTimeMillis())
                JSONObject().put("ok", true).put("folder", folder).put("replaced", existed).toString()
            }
        } catch (e: Throwable) {
            fail("Couldn't save it on the TV (${e.javaClass.simpleName}). Is \"Allow full remote\" turned on?")
        }
    }
}
