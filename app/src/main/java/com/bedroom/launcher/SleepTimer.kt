package com.bedroom.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Media keys and volume for whatever app is playing (no special permission needed). */
object Media {
    fun key(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun volume(ctx: Context, direction: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}

/**
 * "Turn the TV off in N minutes". Survives the launcher being in the background (AlarmManager)
 * and reboots (restored from prefs on start). Turning the TV off uses Device Admin lockNow(),
 * which puts the TV into standby; without that permission it pauses playback and returns Home.
 */
object SleepTimer {
    val CHOICES = listOf(30, 60, 90, 120)
    const val ACTION_FIRE = "com.bedroom.launcher.SLEEP_FIRE"
    private const val PREFS = "sleep"

    /** Wall-clock time the TV will sleep, or null. Compose state, so the home screen updates live. */
    var endsAt by mutableStateOf<Long?>(null)
        private set

    /** The last option picked (30/60/90/120), 0 when off. */
    var lastChoice by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restore(ctx: Context) {
        val p = prefs(ctx)
        val end = p.getLong("endsAt", 0L)
        lastChoice = p.getInt("lastChoice", 0)
        if (end > System.currentTimeMillis()) {
            endsAt = end
            schedule(ctx, end)
            Log.i(TAG, "Sleep timer restored: ${minutesLeft()} min left")
        } else {
            endsAt = null
            if (end != 0L) p.edit().remove("endsAt").apply()
        }
    }

    fun minutesLeft(now: Long = System.currentTimeMillis()): Int? {
        val end = endsAt ?: return null
        val ms = end - now
        return if (ms <= 0) 0 else ((ms + 59_999L) / 60_000L).toInt()
    }

    /** minutes <= 0 cancels. */
    fun set(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.cancel(pending(ctx))
        if (minutes <= 0) {
            endsAt = null
            lastChoice = 0
            prefs(ctx).edit().remove("endsAt").putInt("lastChoice", 0).apply()
            Log.i(TAG, "Sleep timer off")
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        endsAt = end
        lastChoice = minutes
        prefs(ctx).edit().putLong("endsAt", end).putInt("lastChoice", minutes).apply()
        schedule(ctx, end)
        Log.i(TAG, "Sleep timer set: $minutes min")
    }

    /** Off -> 30 -> 60 -> 90 -> 120 -> Off. Returns the new setting (0 = off). */
    fun cycle(ctx: Context): Int {
        val next = if (endsAt == null) {
            CHOICES.first()
        } else {
            CHOICES.getOrNull(CHOICES.indexOf(lastChoice) + 1) ?: 0
        }
        set(ctx, next)
        return next
    }

    private fun schedule(ctx: Context, end: Long) {
        ctx.getSystemService(AlarmManager::class.java)
            ?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        1,
        Intent(ctx, SleepReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun fire(ctx: Context) {
        Log.i(TAG, "Sleep timer fired")
        endsAt = null
        prefs(ctx).edit().remove("endsAt").apply()
        // Rest instead of a real standby, so the phone can still turn the TV back on.
        kotlin.concurrent.thread { Rest.enter(ctx) }
    }

    fun adminComponent(ctx: Context) = ComponentName(ctx, SleepAdmin::class.java)

    /** True once tools\remote-setup.ps1 -Enable has granted the "turn screen off" permission. */
    fun canTurnOff(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(ctx)) == true

    fun removeAdmin(ctx: Context) {
        ctx.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(adminComponent(ctx))
        Log.i(TAG, "Turn-off permission removed")
    }
}

/** Fired by the alarm when the sleep timer runs out. Not exported. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SleepTimer.ACTION_FIRE) SleepTimer.fire(context)
    }
}

/**
 * adb-only switch (protected by the DUMP permission, which only the adb shell has):
 *   adb shell am broadcast -a com.bedroom.launcher.REMOVE_ADMIN -n com.bedroom.launcher/.AdminControlReceiver
 */
class AdminControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.bedroom.launcher.REMOVE_ADMIN") SleepTimer.removeAdmin(context)
    }
}

/** Only asks for one power: turning the screen off (force-lock). */
class SleepAdmin : DeviceAdminReceiver()
