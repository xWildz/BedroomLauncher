package com.bedroom.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An installed app we can show and launch. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

/** A pinned tile on the home screen. The first installed package in [packages] wins. */
data class Pinned(
    val title: String,
    val subtitle: String,
    val packages: List<String>,
    val accent: Color,
)

val WATCH = listOf(
    Pinned("Hulu", "Shows & movies", listOf("com.hulu.livingroomplus", "com.hulu.plus"), Color(0xFF1CE783)),
    Pinned("YouTube", "Videos", listOf("com.google.android.youtube.tv"), Color(0xFFFF3B30)),
    Pinned("YouTube Music", "Music", listOf("com.google.android.youtube.tvmusic"), Color(0xFFFF6A5F)),
)

val RETROARCH = Pinned(
    "RetroArch", "Retro games",
    listOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"),
    Color(0xFF9B8CFF),
)

data class AppData(val loaded: Boolean, val all: List<AppEntry>, val byPkg: Map<String, AppEntry>) {
    fun resolve(pin: Pinned): AppEntry? = pin.packages.firstNotNullOfOrNull { byPkg[it] }

    companion object {
        val EMPTY = AppData(false, emptyList(), emptyMap())
    }
}

object Apps {

    /**
     * Decoded banners are kept between reloads (keyed by the APK path, which changes when an app updates),
     * so coming back to the home screen doesn't re-decode every image. This TV has ~1.3 GB of RAM.
     */
    private class Cached(val stamp: String, val entry: AppEntry, val decoded: Boolean)

    private val cache = HashMap<String, Cached>()

    /**
     * Lists every app that shows up on a TV launcher. Run off the main thread.
     *
     * To keep memory low (this TV kills Hulu when memory runs out), banners are only decoded for
     * [wanted] packages (Watch row + Favorites) unless [decodeAll] is set (All apps / picker screens).
     */
    @Synchronized
    fun load(ctx: Context, decodeAll: Boolean = false, wanted: Set<String> = emptySet()): AppData {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        var decoded = 0
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
            val want = decodeAll || pkg in wanted
            val stamp = ai.applicationInfo?.sourceDir ?: ""
            val hit = cache[pkg]
            if (hit != null && hit.stamp == stamp && (hit.decoded || !want)) {
                seen[pkg] = hit.entry
                continue
            }
            val label = ri.loadLabel(pm).toString()
            val entry = if (want) {
                // Cap at the biggest tile's real pixel size (272x153 dp = 544x306 px). RGB_565 = half the memory.
                val banner = (safe { ai.loadBanner(pm) } ?: safe { ai.applicationInfo.loadBanner(pm) })
                    ?.toImage(maxW = 544, maxH = 306, config = Bitmap.Config.RGB_565)
                // Only keep an icon when there's no banner to show (icons need transparency).
                val icon = if (banner == null) safe { ai.loadIcon(pm) }?.toImage(maxW = 128, maxH = 128) else null
                decoded++
                AppEntry(pkg, label, banner, icon)
            } else {
                AppEntry(pkg, label, null, null)
            }
            cache[pkg] = Cached(stamp, entry, want)
            seen[pkg] = entry
        }
        cache.keys.retainAll(seen.keys) // forget uninstalled/disabled apps
        val all = seen.values.sortedBy { it.label.lowercase() }
        Log.i(TAG, "Loaded ${all.size} TV apps ($decoded decoded, ${all.size - decoded} from cache)")
        return AppData(true, all, seen)
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "Launched $pkg")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for $pkg", e)
            false
        }
    }

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "Couldn't open Settings")
        }
    }

    fun openStore(ctx: Context, pin: Pinned) = openStore(ctx, pin.packages.first(), pin.title)

    fun openStore(ctx: Context, pkg: String, title: String) {
        val uris = listOf("market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg")
        for (u in uris) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        toast(ctx, "$title isn't installed. Get it from the Play Store.")
    }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private inline fun <T> safe(block: () -> T?): T? = try {
    block()
} catch (e: PackageManager.NameNotFoundException) {
    null
} catch (e: RuntimeException) {
    null
}

/** Converts a drawable to a bitmap, capped in size so 30+ banners don't eat the TV's memory. */
private fun Drawable.toImage(
    maxW: Int,
    maxH: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): ImageBitmap? = try {
    val iw = if (intrinsicWidth > 0) intrinsicWidth else maxW
    val ih = if (intrinsicHeight > 0) intrinsicHeight else maxH
    val scale = minOf(1f, maxW.toFloat() / iw, maxH.toFloat() / ih)
    toBitmap((iw * scale).toInt().coerceAtLeast(1), (ih * scale).toInt().coerceAtLeast(1), config).asImageBitmap()
} catch (e: Exception) {
    null
}

/** Packages whose banners the home screen shows: the Watch row + Favorites (+ every RetroArch variant). */
fun homePackages(favorites: List<String>): Set<String> {
    val set = HashSet<String>()
    WATCH.forEach { set.addAll(it.packages) }
    set.addAll(favorites)
    if (favorites.any { it in RETROARCH.packages }) set.addAll(RETROARCH.packages)
    return set
}
