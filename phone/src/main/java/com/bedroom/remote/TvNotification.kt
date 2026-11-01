package com.bedroom.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

/**
 * The always-there TV controls notification (shade + lock screen).
 *
 * It's owned by [TvService], a tiny foreground service. That matters when the phone is locked:
 * Android cuts network access for background apps in deep sleep, but not for an app with a foreground
 * service, and the service also keeps Wi-Fi awake so button presses go through right away.
 * Nothing polls in the background; it only talks to the TV when a button is pressed.
 */
object TvNotification {
    private const val CHANNEL = "tv_controls"
    const val ID = 1
    const val PREFIX = "com.bedroom.remote.TV_"
    const val EXTRA_LINE = "line"
    val COMMANDS = setOf("voldown", "volup", "playpause", "mute")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("notification", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("notification", on).apply()
        if (on) show(ctx) else cancel(ctx)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun cancel(ctx: Context) {
        ctx.stopService(Intent(ctx, TvService::class.java))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** Start (or refresh) the controls. Safe to call from anywhere; falls back to a plain notification. */
    fun show(ctx: Context, line: String? = null) {
        if (!enabled(ctx) || !TvClient.isPaired(ctx) || !canPost(ctx)) return
        val i = Intent(ctx, TvService::class.java).putExtra(EXTRA_LINE, line)
        try {
            ctx.startForegroundService(i)
        } catch (e: Exception) {
            // Android may refuse to start it while the app is in the background; the next app open will.
            Log.w("TvNotification", "Couldn't start the TV service", e)
            try {
                ctx.startService(i) // works if it's already running
            } catch (e2: Exception) {
                ctx.getSystemService(NotificationManager::class.java)?.notify(ID, build(ctx, line))
            }
        }
    }

    fun build(ctx: Context, line: String?): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "TV controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Volume and play/pause for the bedroom TV"
                setShowBadge(false)
            },
        )

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun action(icon: Int, title: String, cmd: String): Notification.Action {
            val pi = PendingIntent.getForegroundService(
                ctx, cmd.hashCode(),
                Intent(ctx, TvService::class.java).setAction(PREFIX + cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()
        }

        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("Bedroom TV")
            .setContentText(line ?: "Tap to open the remote")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_n_voldown, "Volume down", "voldown"))
            .addAction(action(R.drawable.ic_n_playpause, "Play/Pause", "playpause"))
            .addAction(action(R.drawable.ic_n_volup, "Volume up", "volup"))
            .addAction(action(R.drawable.ic_n_mute, "Mute", "mute"))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}

/** Keeps the TV reachable while the phone is locked, and runs the notification's buttons. */
class TvService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var line: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BedroomRemote:tv")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground right away, even if we're about to stop.
        intent?.getStringExtra(TvNotification.EXTRA_LINE)?.let { line = it }
        goForeground()
        if (!TvNotification.enabled(this) || !TvClient.isPaired(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cmd = intent?.action?.removePrefix(TvNotification.PREFIX)
        if (cmd != null && cmd in TvNotification.COMMANDS) {
            io.execute {
                // One retry: the first packet after the phone wakes can be slow.
                val sent = TvClient.callBlocking(this, cmd) ?: TvClient.callBlocking(this, cmd)
                val status = if (sent != null) TvClient.callBlocking(this, "status") else null
                line = TvClient.statusLine(status)
                goForeground()
            }
        } else if (intent?.getStringExtra(TvNotification.EXTRA_LINE) == null) {
            io.execute {
                line = TvClient.statusLine(TvClient.callBlocking(this, "status"))
                goForeground()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = TvNotification.build(this, line)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(TvNotification.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(TvNotification.ID, n)
            }
        } catch (e: Exception) {
            Log.w("TvService", "startForeground failed", e)
            getSystemService(NotificationManager::class.java)?.notify(TvNotification.ID, n)
        }
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        io.shutdownNow()
        super.onDestroy()
    }
}

/** Kept so notifications posted by older versions still work. */
class TvActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.action?.removePrefix(TvNotification.PREFIX) ?: return
        val i = Intent(context, TvService::class.java).setAction(TvNotification.PREFIX + cmd)
        runCatching { context.startForegroundService(i) }
    }
}

/** Puts the controls back after the phone restarts or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TvNotification.show(context.applicationContext)
    }
}
