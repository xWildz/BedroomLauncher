package com.bedroom.remote

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/**
 * "Bedroom TV" in the phone's Quick Settings (pull down twice, then add it with the pencil).
 * Lit = the TV is awake, dim = resting. Tap to rest / wake it; long-press opens the app.
 */
class TvTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        show(null) // keep the last state while we ask the TV
        thread { show(TvClient.callBlocking(applicationContext, "status")?.optBoolean("resting")?.let { !it }, asked = true) }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val wasAwake = tile.state == Tile.STATE_ACTIVE
        // Show the change straight away; the TV confirms a moment later.
        update(!wasAwake, if (wasAwake) "Resting…" else "Waking…")
        thread {
            val res = TvClient.callBlocking(applicationContext, if (wasAwake) "rest" else "wake")
            if (res == null) show(null, asked = true)
            else {
                Thread.sleep(1200)
                show(TvClient.callBlocking(applicationContext, "status")?.optBoolean("resting")?.let { !it }, asked = true)
            }
        }
    }

    /** awake: true / false, or null when unknown ([asked] = we tried and the TV didn't answer). */
    private fun show(awake: Boolean?, asked: Boolean = false) {
        when {
            !TvClient.isPaired(applicationContext) -> update(null, "Pair in the app")
            awake == true -> update(true, "Awake · tap to rest")
            awake == false -> update(false, "Resting · tap to wake")
            asked -> update(null, "Can't reach the TV")
        }
    }

    private fun update(awake: Boolean?, sub: String) {
        val tile = qsTile ?: return
        tile.label = "Bedroom TV"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_tv)
        tile.state = when (awake) { true -> Tile.STATE_ACTIVE; false -> Tile.STATE_INACTIVE; null -> Tile.STATE_UNAVAILABLE }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "Bedroom TV: $sub"
        tile.updateTile()
    }
}
