package com.bedroom.launcher

import android.content.Context
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * RetroArch save files (in-game saves + save states) for the phone app.
 *
 * RetroArch keeps them inside its own app folder, which only the TV's adb shell can reach, so this goes
 * through the same local adb connection as the full remote. Files that get replaced by an upload are
 * copied to files/_replaced/ first, so nothing is lost.
 */
object RetroSaves {
    private const val RA = "com.retroarch"
    const val ROOT = "/sdcard/Android/data/$RA/files"
    private const val OLD_ROOT = "/sdcard/RetroArch"
    private const val REPLACED = "$ROOT/_replaced"

    private val SAVE_EXT = listOf("srm", "sav", "rtc", "dsv", "mcr", "mcd", "eep", "fla", "sra", "mpk", "nv", "ldci")
    private val STATE_RE = Regex("""\.state(\d+|\.auto)?$""", RegexOption.IGNORE_CASE)
    private val SAFE_NAME = Regex("""^[A-Za-z0-9 ._()\[\]!&,'+#~-]{1,180}$""")

    data class Entry(val path: String, val size: Long, val modified: Long) {
        val name get() = path.substringAfterLast('/')
        val isState get() = STATE_RE.containsMatchIn(name)
        val rel get() = when {
            path.startsWith("$ROOT/") -> path.removePrefix("$ROOT/")
            path.startsWith("$OLD_ROOT/") -> "RetroArch/" + path.removePrefix("$OLD_ROOT/")
            else -> name
        }
    }

    fun isSaveName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SAVE_EXT || STATE_RE.containsMatchIn(name)
    }

    private fun <T> withAdb(ctx: Context, block: (Dadb) -> T): T {
        val d = Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx))
        try {
            return block(d)
        } finally {
            runCatching { d.close() }
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun listWith(d: Dadb): List<Entry> {
        val names = SAVE_EXT.joinToString(" -o ") { "-iname '*.$it'" } + " -o -iname '*.state' -o -iname '*.state[0-9]*' -o -iname '*.state.auto'"
        val cmd = "for d in $ROOT $OLD_ROOT; do [ -d \$d ] && find \$d -type f \\( $names \\) ! -path '*/_replaced/*' ! -iname '*.png' -exec stat -c '%s|%Y|%n' {} + ; done 2>/dev/null"
        return d.shell(cmd).output.lines().mapNotNull { line ->
            val p = line.trim().split("|", limit = 3)
            if (p.size < 3) null else Entry(p[2], p[0].toLongOrNull() ?: 0, (p[1].toLongOrNull() ?: 0) * 1000)
        }.sortedByDescending { it.modified }
    }

    fun list(ctx: Context): String = try {
        val entries = withAdb(ctx) { listWith(it) }
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject().put("path", it.path).put("name", it.name).put("rel", it.rel)
                    .put("size", it.size).put("modified", it.modified).put("state", it.isState),
            )
        }
        JSONObject().put("ok", true).put("saves", arr).toString()
    } catch (e: Throwable) {
        err(e)
    }

    private fun err(e: Throwable) = JSONObject().put("ok", false)
        .put("error", "Can't reach RetroArch's folder (${e.javaClass.simpleName}). Press \"Allow full remote\" on the TV's Phone screen.")
        .toString()

    private fun validPath(p: String) =
        (p.startsWith("$ROOT/") || p.startsWith("$OLD_ROOT/")) && !p.contains("..") && !p.contains("/_replaced/") && isSaveName(p.substringAfterLast('/'))

    /** Copy one save to a temp file for download. */
    fun pull(ctx: Context, path: String): File? {
        if (!validPath(path)) return null
        val out = File(ctx.cacheDir, "dl-" + path.substringAfterLast('/'))
        withAdb(ctx) { it.pull(out, path) }
        return out
    }

    /** Zip of every save, keeping folders (saves/..., states/...). */
    fun backupZip(ctx: Context): File {
        val zip = File(ctx.cacheDir, "retroarch-saves.zip")
        val tmp = File(ctx.cacheDir, "zip-part")
        withAdb(ctx) { d ->
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                for (e in listWith(d)) {
                    d.pull(tmp, e.path)
                    z.putNextEntry(ZipEntry(e.rel).apply { time = e.modified })
                    tmp.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        tmp.delete()
        return zip
    }

    /**
     * Put uploaded save files where RetroArch will find them. A .zip (like a backup from this app)
     * is unpacked. Same file name already on the TV -> replaced in place (old copy kept in _replaced).
     */
    fun upload(ctx: Context, fileName: String, body: ByteArray, stopRetroArch: Boolean): String = try {
        withAdb(ctx) { d ->
            val running = d.shell("pidof $RA").output.trim().isNotEmpty()
            if (running && !stopRetroArch) {
                return@withAdb JSONObject().put("ok", false).put("running", true)
                    .put("error", "RetroArch is open. Close it first, or let the app close it (unsaved progress is lost).")
                    .toString()
            }
            if (running) d.shell("am force-stop $RA")

            val files = mutableListOf<Pair<String, ByteArray>>()
            if (fileName.lowercase().endsWith(".zip")) {
                ZipInputStream(ByteArrayInputStream(body)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name.substringAfterLast('/')
                        if (!e.isDirectory && isSaveName(n)) files += n to z.readBytes()
                    }
                }
            } else {
                files += fileName.substringAfterLast('/') to body
            }
            if (files.isEmpty()) {
                return@withAdb JSONObject().put("ok", false).put("error", "No save files found in that upload.").toString()
            }

            val existing = listWith(d)
            val cfg = readCfg(d)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val results = JSONArray()
            val tmp = File(ctx.cacheDir, "up-part")
            for ((name, bytes) in files) {
                if (!SAFE_NAME.matches(name) || !isSaveName(name)) {
                    results.put(JSONObject().put("name", name).put("result", "skipped (not a save file name)"))
                    continue
                }
                val match = existing.firstOrNull { it.name == name }
                val target = match?.path ?: (targetDir(name, existing, cfg, d) + "/" + name)
                if (match != null) {
                    d.shell("mkdir -p $REPLACED && cp ${q(match.path)} ${q("$REPLACED/$stamp-$name")}")
                }
                d.shell("mkdir -p ${q(target.substringBeforeLast('/'))}")
                tmp.writeBytes(bytes)
                d.push(tmp, target, 420, System.currentTimeMillis())
                results.put(
                    JSONObject().put("name", name)
                        .put("result", (if (match != null) "replaced " else "added ") + target.removePrefix("$ROOT/")),
                )
            }
            tmp.delete()
            JSONObject().put("ok", true).put("results", results).toString()
        }
    } catch (e: Throwable) {
        err(e)
    }

    private fun readCfg(d: Dadb): Map<String, String> =
        d.shell("cat $ROOT/retroarch.cfg 2>/dev/null").output.lines().mapNotNull { l ->
            val m = Regex("""^\s*([a-z_]+)\s*=\s*"(.*)"\s*$""").find(l) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2]
        }.toMap()

    /** Where a brand-new save goes: next to the newest save of the same kind, else RetroArch's setting. */
    private fun targetDir(name: String, existing: List<Entry>, cfg: Map<String, String>, d: Dadb): String {
        val state = STATE_RE.containsMatchIn(name)
        existing.firstOrNull { it.isState == state && it.path.startsWith("$ROOT/") }?.let {
            return it.path.substringBeforeLast('/')
        }
        val inContent = cfg[if (state) "savestates_in_content_dir" else "savefiles_in_content_dir"] == "true"
        if (inContent) {
            val base = if (state) name.replace(STATE_RE, "") else name.substringBeforeLast('.')
            val rom = d.shell("find $ROOT/ROMs -type f -name ${q("$base.*")} 2>/dev/null | head -1").output.trim()
            if (rom.isNotEmpty()) return rom.substringBeforeLast('/')
        }
        val setting = cfg[if (state) "savestate_directory" else "savefile_directory"].orEmpty()
        val abs = setting.replace("/storage/emulated/0/", "/sdcard/")
        if (abs.startsWith("/sdcard/Android/data/$RA/")) return abs.trimEnd('/')
        return "$ROOT/" + if (state) "states" else "saves"
    }
}
