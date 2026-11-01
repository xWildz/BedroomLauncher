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
        LinkService.start(this)    // keeps all of the above alive while other apps are on screen
    }
}
