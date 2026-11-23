package com.bedroom.launcher

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Low-lag game picture mode: when RetroArch is opened from Bedroom Home, the TV switches to its "Game"
 * picture style (less processing = less delay), and back to your usual style on the home screen.
 *
 * This TV numbers its picture styles without names, so Bedroom Home learns which one is Game: the first time,
 * pick the Game style with the remote while RetroArch is open. Coming back home, Bedroom Home notices the
 * change, remembers it, and puts your usual style back. From then on it switches by itself.
 * (On TVs whose picture modes have names, it picks the one called Game straight away.)
 */
object GameMode {
    @Volatile var active = false
        private set
    @Volatile private var before: String? = null
    @Volatile private var learning = false

    fun enabled(ctx: Context) = BedroomPrefs.get(ctx).getBoolean("gameMode", true)
    fun learned(ctx: Context): String? = BedroomPrefs.get(ctx).getString("gameStyle", null)

    fun enter(ctx: Context) {
        if (active || !enabled(ctx)) return
        val app = ctx.applicationContext
        active = true
        thread(name = "game-mode") {
            val known = learned(app)
            val r = if (known != null) ScreenOff.request(app, "game:on:$known") else ScreenOff.request(app, "game:on")
            when {
                r.startsWith("ok") && r != "ok bool" -> { before = r.removePrefix("ok ").trim(); learning = false }
                else -> {
                    // No named game mode: remember the current style, and learn the one you pick in RetroArch.
                    before = ScreenOff.request(app, "pmode").removePrefix("ok ").trim().takeIf { it != "unknown" }
                    learning = known == null && before != null
                }
            }
            Log.i(TAG, "Game mode: was $before, learning=$learning ($r)")
        }
    }

    fun exit(ctx: Context) {
        if (!active) return
        active = false
        val app = ctx.applicationContext
        val back = before ?: return
        thread(name = "game-mode-off") {
            if (learning) {
                val now = ScreenOff.request(app, "pmode").removePrefix("ok ").trim()
                if (now != back && now != "unknown" && !now.startsWith("error")) {
                    BedroomPrefs.get(app).edit().putString("gameStyle", now).apply()
                    Health.event(app, "Learned your game picture style (it switches by itself from now on)")
                }
                learning = false
            }
            val r = ScreenOff.request(app, "pmode:$back")
            Log.i(TAG, "Game mode off: back to $back ($r)")
        }
    }

    /** Forget the learned style (phone switch "Learn again"). */
    fun forget(ctx: Context) = BedroomPrefs.get(ctx).edit().remove("gameStyle").apply()
}
