package com.bedroom.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Keeps the phone link (remote server, gamepad, key presses) alive while another app is on screen.
 *
 * Without it, Android treats Bedroom Home as a background app as soon as Hulu or YouTube opens, and on this
 * 1.3 GB TV it gets killed to free memory, taking the phone link with it until Home is pressed again.
 * A foreground service tells Android "this is in use", so it is kept. It does no work of its own and
 * only shows a silent, minimum-priority notification (TVs don't display it on screen).
 */
class LinkService : Service() {
    companion object {
        private const val CHANNEL = "phone_link"
        private const val ID = 7

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LinkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the phone link service", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone remote link", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the phone remote connected while other apps are open"
                setShowBadge(false)
            },
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Phone remote ready")
            .setOngoing(true)
            .build()
        try {
            // Android 14+ needs the service type stated; older TVs (this one is Android 9) don't know it.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep the phone link in the foreground", e)
        }

        // Make sure everything the phone talks to is running (no-ops if it already is).
        RemoteServer.startOnce(applicationContext)
        KeyInjector.start(applicationContext)
        GamepadBridge.start(applicationContext)
        IdleWatcher.register(applicationContext) // screensaver -> rest / photo frame (if chosen on the phone)
        Alarm.schedule(applicationContext)       // re-arm the wake-up alarm (after boots and updates too)
        return START_STICKY // if Android ever stops it anyway, it brings it back
    }
}

/** Starts the phone link when the TV boots, even before the home screen is shown. */
class LinkBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkService.start(context.applicationContext)
    }
}
