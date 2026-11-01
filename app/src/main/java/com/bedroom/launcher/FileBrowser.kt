package com.bedroom.launcher

import android.content.Context
import android.os.Environment
import android.os.StatFs
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Games & saves browser for the phone app: list, rename/move, new folder, delete, download, upload.
 *
 * Works through the TV's own adb, limited to RetroArch's folder (games, saves, save states). The top-level
 * folders themselves and everything RetroArch needs to run (its settings, cores, system files) are locked;
 * files inside ROMs, saves, states and _replaced can be sorted and deleted.
 */
object FileBrowser {
    const val ROOT = "/sdcard/Android/data/com.retroarch/files"
    private val OPEN_FOLDERS = setOf("roms", "saves", "states", "_replaced")
    private val SAFE_NAME = Regex("""^[\p{L}\p{N} ._()\[\]!&,'+#~@=-]{1,200}$""")

    /** Normalise and check a path: must stay inside /sdcard, no "..". Returns null if not allowed. */
    fun clean(raw: String?): String? {
        var p = (raw ?: ROOT).trim().replace("/storage/emulated/0", "/sdcard")
        if (!p.startsWith("/")) p = "$ROOT/$p"
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val out = "/" + parts.joinToString("/")
        return if (out == ROOT || out.startsWith("$ROOT/")) out else null
    }

    /** Locked: the top level itself, and anything outside ROMs / saves / states / _replaced. */
    fun isProtected(path: String): Boolean {
        val rel = path.removePrefix(ROOT).trim('/')
        if (rel.isEmpty()) return true
        val parts = rel.split('/')
        if (parts.size == 1) return true
        return parts[0].lowercase() !in OPEN_FOLDERS
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun <T> adb(ctx: Context, block: (Dadb) -> T): T =
        Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use(block)
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
    private fun noAccess() = err("Can't reach the TV's files. Press \"Allow full remote\" on the TV's Phone screen.")

    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        -1
    }

    fun list(ctx: Context, raw: String?): String {
        val path = clean(raw) ?: return err("That folder is outside RetroArch's games and saves.")
        return try {
            val out = adb(ctx) { d ->
                d.shell("[ -d ${q(path)} ] || echo @@missing; find ${q(path)} -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null").output
            }
            if (out.contains("@@missing")) return err("That folder doesn't exist any more.")
            val arr = JSONArray()
            out.lines().forEach { line ->
                val p = line.trim().split("|", limit = 4)
                if (p.size < 4) return@forEach
                val full = p[3]
                arr.put(
                    JSONObject()
                        .put("name", full.substringAfterLast('/'))
                        .put("path", full)
                        .put("dir", p[0].startsWith("directory"))
                        .put("size", p[1].toLongOrNull() ?: 0)
                        .put("modified", (p[2].toLongOrNull() ?: 0) * 1000)
                        .put("locked", isProtected(full)),
                )
            }
            JSONObject().put("ok", true).put("path", path).put("root", ROOT).put("free", freeBytes()).put("items", arr).toString()
        } catch (e: Throwable) {
            noAccess()
        }
    }

    /** delete | rename (to = new name, or a path relative to the same folder to move it) | mkdir (to = name). */
    fun op(ctx: Context, op: String, raw: String?, to: String?): String {
        val path = clean(raw) ?: return err("That's outside RetroArch's games and saves.")
        return try {
            when (op) {
                "delete" -> {
                    if (isProtected(path)) return err("That folder is locked (RetroArch needs it). Files inside ROMs, saves and states can be deleted.")
                    adb(ctx) { it.shell("rm -rf ${q(path)}") }
                    JSONObject().put("ok", true).put("done", "Deleted ${path.substringAfterLast('/')}").toString()
                }
                "rename" -> {
                    if (isProtected(path)) return err("That folder is locked (RetroArch needs it).")
                    val t = to?.trim().orEmpty()
                    if (t.isEmpty()) return err("Type a new name.")
                    val parent = path.substringBeforeLast('/')
                    val dest = clean(if (t.startsWith("/")) t else "$parent/$t") ?: return err("That's outside RetroArch's games and saves.")
                    if (dest.split('/').any { it.isNotEmpty() && !SAFE_NAME.matches(it) }) return err("That name has characters the TV can't use.")
                    if (isProtected(dest)) return err("Files can only go inside ROMs, saves, states or _replaced.")
                    val r = adb(ctx) {
                        it.shell("if [ -e ${q(dest)} ]; then echo @@exists; else mkdir -p ${q(dest.substringBeforeLast('/'))} && mv ${q(path)} ${q(dest)} && echo @@ok; fi").output
                    }
                    when {
                        r.contains("@@exists") -> err("Something with that name is already there.")
                        r.contains("@@ok") -> JSONObject().put("ok", true).put("done", "Moved to ${dest.removePrefix("$ROOT/")}").toString()
                        else -> err("Couldn't rename it.")
                    }
                }
                "mkdir" -> {
                    val name = to?.trim().orEmpty()
                    if (!SAFE_NAME.matches(name)) return err("That folder name has characters the TV can't use.")
                    val dest = clean("$path/$name") ?: return err("That's outside RetroArch's games and saves.")
                    if (isProtected(dest)) return err("New folders can only go inside ROMs, saves or states.")
                    adb(ctx) { it.shell("mkdir -p ${q(dest)}") }
                    JSONObject().put("ok", true).put("done", "Made folder $name").toString()
                }
                else -> err("Unknown action")
            }
        } catch (e: Throwable) {
            noAccess()
        }
    }

    /** Copy a TV file to a temp file for download. */
    fun pull(ctx: Context, raw: String?): File? {
        val path = clean(raw) ?: return null
        val out = File(ctx.cacheDir, "fdl-" + path.substringAfterLast('/'))
        return try {
            adb(ctx) { it.pull(out, path) }
            out
        } catch (e: Throwable) {
            out.delete()
            null
        }
    }

    /** Put a received file ([file], already checked) into a folder. */
    @Synchronized
    fun upload(ctx: Context, rawDir: String?, rawName: String, file: File): String {
        val dir = clean(rawDir) ?: return err("That folder is outside RetroArch's games and saves.")
        val name = rawName.substringAfterLast('/').trim()
        if (!SAFE_NAME.matches(name)) return err("That file name has characters the TV can't use. Rename it and try again.")
        if (isProtected("$dir/$name")) return err("Open a folder inside ROMs, saves or states first.")
        val free = freeBytes()
        if (free in 0 until file.length() + 50L * 1024 * 1024) return err("Not enough space on the TV.")
        return try {
            adb(ctx) { d ->
                d.shell("mkdir -p ${q(dir)}")
                d.push(file, "$dir/$name", 420, System.currentTimeMillis())
            }
            JSONObject().put("ok", true).put("done", "Sent $name").toString()
        } catch (e: Throwable) {
            noAccess()
        }
    }
}
