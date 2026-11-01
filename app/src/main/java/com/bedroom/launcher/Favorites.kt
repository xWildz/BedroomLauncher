package com.bedroom.launcher

import android.content.Context
import android.util.Log

/** The user's pinned apps, in the order they were added. Stored on the TV, survives reboots and updates. */
object Favorites {
    private const val PREFS = "launcher"
    private const val KEY = "favorites"

    /** First-run favorites. RetroArch shows a "get it" tile until it's installed. */
    val DEFAULT = listOf("com.retroarch")

    fun load(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return DEFAULT
        return s.split(',').filter { it.isNotBlank() }
    }

    private fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString(",")).apply()
    }

    private fun isRetro(pkg: String) = pkg in RETROARCH.packages

    /** RetroArch ships under several package names; treat them as one app. */
    fun isFavorite(list: List<String>, pkg: String): Boolean =
        pkg in list || (isRetro(pkg) && list.any { isRetro(it) })

    fun toggle(ctx: Context, list: List<String>, pkg: String): List<String> {
        val updated = if (isFavorite(list, pkg)) {
            list.filterNot { it == pkg || (isRetro(pkg) && isRetro(it)) }
        } else {
            list + pkg
        }
        save(ctx, updated)
        Log.i(TAG, "Favorites now: $updated")
        return updated
    }

    /** The installed app for a favorite, or null if it isn't installed (yet). */
    fun resolve(pkg: String, data: AppData): AppEntry? =
        if (isRetro(pkg)) data.resolve(RETROARCH) else data.byPkg[pkg]

    fun nameFor(pkg: String, data: AppData): String =
        resolve(pkg, data)?.label ?: if (isRetro(pkg)) "RetroArch" else pkg.substringAfterLast('.')
}
