package com.bedroom.launcher

import android.app.Application

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The backlight helper runs in its own ":picture" process; only the main process runs the services.
        if (android.os.Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != packageName) return

        SleepTimer.restore(this)   // re-arm a sleep timer that was running before a reboot
        Brightness.restore(this)   // the extra-dim layer, if the brightness slider was left at the very bottom
        RemoteKey.get(this)        // make sure the phone-remote key exists (setup script reads it)
        RemoteServer.startOnce(this)
        KeyInjector.start(this)    // idle until the phone presses a remote button
        GamepadBridge.start(this)  // idle until the phone's Gamepad tab sends a press
        PlayTime.start(this)       // counts RetroArch play time per game
        Idle.start()               // pauses home-screen animations when nobody is using it
        Health.start(this)         // restart reason + history for the phone / PC
        NightCleanup.start(this)   // closes idle video apps after an hour of rest (never YouTube)
        WifiRepair.start(this)     // reconnects Wi-Fi if it has been down for 3 minutes
        LinkService.start(this)    // keeps all of the above alive while other apps are on screen
    }

    /** Android is short of memory (this TV has 1.3 GB): give back what can be rebuilt later. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Apps.trim()
            NowPlaying.trim()
        }
    }
}
