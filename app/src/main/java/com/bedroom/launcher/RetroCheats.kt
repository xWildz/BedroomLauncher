package com.bedroom.launcher

import android.content.Context
import android.util.Log
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cheat codes per game, managed from the phone. RetroArch keeps each game's cheats in
 * cheats/<emulator>/<game file name>.cht (the emulator's short name, e.g. "mGBA", and the game's file name
 * without its extension) and, with "auto-apply cheats" on, turns them on when the game starts.
 * Files are only changed while RetroArch is closed (it saves its own copy when a game closes).
 */
object RetroCheats {
    private const val PKG = "com.retroarch"
    private const val BASE = "/sdcard/Android/data/$PKG/files"
    private const val DB = "$BASE/cheats"

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    data class Game(val label: String, val path: String, val core: String)

    /** "Nintendo - Game Boy Advance (mGBA)" -> "mGBA" (the emulator's own short name). */
    private fun library(coreName: String): String =
        Regex("""\(([^()]+)\)\s*$""").find(coreName)?.groupValues?.get(1)?.substringBefore(",")?.trim() ?: coreName.trim()

    /** Games from RetroArch's history (each one knows which emulator it was played with). */
    fun games(ctx: Context): List<Game> = try {
        val txt = TvShell.run(ctx, "cat $BASE/playlists/builtin/content_history.lpl 2>/dev/null || cat $BASE/playlists/content_history.lpl 2>/dev/null")
        val items = JSONObject(txt.substring(txt.indexOf('{'))).optJSONArray("items") ?: JSONArray()
        (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("path").isNotBlank() && it.optString("core_name").isNotBlank() && !it.optString("core_name").equals("DETECT", true) }
            .map {
                val path = it.getString("path").substringBefore('#')
                Game(it.optString("label").ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }, path, library(it.getString("core_name")))
            }
            .filter { it.path.contains('.') }      // a folder isn't a game
            .distinctBy { it.path }
    } catch (e: Throwable) {
        Log.w(TAG, "cheat games failed", e)
        emptyList()
    }

    fun gamesJson(ctx: Context): String {
        val arr = JSONArray()
        games(ctx).forEach { arr.put(JSONObject().put("label", it.label).put("path", it.path).put("core", it.core)) }
        return JSONObject().put("ok", true).put("games", arr).toString()
    }

    private fun fileFor(g: Game) = "$DB/${g.core}/${g.path.substringAfterLast('/').substringBeforeLast('.')}.cht"

    private fun find(ctx: Context, path: String?): Game? = games(ctx).firstOrNull { it.path == path }

    /** Parsed cheats: index -> every key of that cheat (desc, code, enable and any others RetroArch wrote). */
    private fun parse(txt: String): List<LinkedHashMap<String, String>> {
        val map = sortedMapOf<Int, LinkedHashMap<String, String>>()
        val re = Regex("""^cheat(\d+)_(\w+)\s*=\s*(.*)$""")
        for (line in txt.lines()) {
            val m = re.find(line.trim()) ?: continue
            map.getOrPut(m.groupValues[1].toInt()) { LinkedHashMap() }[m.groupValues[2]] = m.groupValues[3].trim()
        }
        return map.values.toList()
    }

    private fun unq(v: String?) = v.orEmpty().removeSurrounding("\"")

    fun list(ctx: Context, path: String?): String {
        val g = find(ctx, path) ?: return err("Play the game once in RetroArch first, so it's in its history")
        return try {
            val txt = TvShell.run(ctx, "cat ${q(fileFor(g))} 2>/dev/null")
            val arr = JSONArray()
            parse(txt).forEach { c ->
                arr.put(JSONObject().put("desc", unq(c["desc"])).put("code", unq(c["code"])).put("enable", unq(c["enable"]) == "true"))
            }
            val auto = TvShell.run(ctx, "grep '^apply_cheats_after_load ' $BASE/retroarch.cfg").contains("\"true\"")
            JSONObject().put("ok", true).put("game", g.label).put("core", g.core).put("cheats", arr).put("autoApply", auto).toString()
        } catch (e: Throwable) {
            err("Can't reach RetroArch's folder. Is \"Allow full remote\" on?")
        }
    }

    private val SAFE_CODE = Regex("""^[0-9A-Za-z+:\-\s]{1,600}$""")

    /**
     * Saves the game's cheat list from the phone: [data] = JSON array of {desc, code, enable}.
     * Keeps any extra details RetroArch stored for cheats that are kept, and turns on auto-apply.
     */
    @Synchronized
    fun save(ctx: Context, path: String?, data: String?): String {
        val g = find(ctx, path) ?: return err("Play the game once in RetroArch first")
        val wanted = runCatching { JSONArray(data) }.getOrNull() ?: return err("Bad cheat list")
        return try {
            if (TvShell.run(ctx, "pidof $PKG").trim().isNotEmpty()) return err("Close RetroArch first (Quit in its menu). It saves its own copy of the cheats when a game closes.")
            val file = fileFor(g)
            val old = parse(TvShell.run(ctx, "cat ${q(file)} 2>/dev/null"))
            val sb = StringBuilder()
            sb.append("cheats = ").append(wanted.length()).append("\n\n")
            for (i in 0 until wanted.length()) {
                val c = wanted.getJSONObject(i)
                val desc = c.optString("desc").replace("\"", "'").replace("\n", " ").take(120)
                val code = c.optString("code").trim().replace(Regex("""\s+"""), "+")
                if (!SAFE_CODE.matches(code)) return err("Cheat \"$desc\" has characters a code can't have")
                // Keep RetroArch's extra details if this exact code was already there.
                val prev = old.firstOrNull { unq(it["code"]) == code }
                val keys = LinkedHashMap<String, String>()
                prev?.forEach { (k, v) -> keys[k] = v }
                keys["desc"] = "\"$desc\""
                keys["code"] = "\"$code\""
                keys["enable"] = if (c.optBoolean("enable")) "\"true\"" else "\"false\""
                for ((k, v) in keys) sb.append("cheat").append(i).append('_').append(k).append(" = ").append(v).append('\n')
                sb.append('\n')
            }
            val tmp = File(ctx.cacheDir, "cheat.cht").apply { writeText(sb.toString()) }
            Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(ctx)).use { d ->
                d.shell("mkdir -p ${q("$DB/${g.core}")}; [ -f ${q(file)} ] && cp ${q(file)} ${q("$file.bak")}")
                d.push(tmp, file, 432, System.currentTimeMillis())
            }
            tmp.delete()
            val auto = enableAutoApply(ctx)
            JSONObject().put("ok", true).put("saved", wanted.length()).put("autoApply", auto).toString()
        } catch (e: Throwable) {
            Log.w(TAG, "cheat save failed", e)
            err("Couldn't save the cheats (${e.javaClass.simpleName})")
        }
    }

    /** RetroArch's "Auto-Apply Cheats During Game Load" on (keeps a copy of its settings first). */
    private fun enableAutoApply(ctx: Context): Boolean {
        val cfg = "$BASE/retroarch.cfg"
        val cmd = "[ -f ${q(cfg)} ] || exit 3; [ -f ${q("$cfg.bedroom-backup")} ] || cp ${q(cfg)} ${q("$cfg.bedroom-backup")}; " +
            "if grep -q '^apply_cheats_after_load ' ${q(cfg)}; then sed -i 's/^apply_cheats_after_load = .*/apply_cheats_after_load = \"true\"/' ${q(cfg)}; " +
            "else echo 'apply_cheats_after_load = \"true\"' >> ${q(cfg)}; fi; grep -c '^apply_cheats_after_load = \"true\"' ${q(cfg)}"
        return runCatching { TvShell.run(ctx, cmd).trim().endsWith("1") }.getOrDefault(false)
    }
}
