# Bedroom TV

A custom home screen for an Android TV, a phone app that controls it, and a Windows control panel.
Calm home screen with clock and weather, a photo frame, bedtime and night mode, a wake-up alarm,
RetroArch games with a phone gamepad, live view of the TV screen, and Share to TV from the phone.
Nothing is rooted; everything can be undone.

**Made for:** Philips 4K UHD Android TV, made by Funai: Android TV 9 (API 28), device codename `freesia`,
build `PTM5.200218.905`, 1920×1080 UI. Other Android TV 9+ sets may work, but the backlight and screen-off
features use this TV's MStar chip service and may not work elsewhere.

| Folder | What | Version |
|---|---|---|
| `app/` | **Bedroom Home**: the TV's home screen (Kotlin, Compose for TV), Android 7.0+ | **1.8.0** |
| `phone/` | **TV Remote**: Android phone app (remote, gamepad, live view, games, bedroom settings, Share to TV), Android 8.0+ | **3.4 "Sharing Shark"** |
| `tools/` | **TV Control Panel** and PC scripts (Windows, PowerShell) | panel **v4** |

What changed in each version: [`CHANGELOG.md`](CHANGELOG.md).
Device notes (what was changed on the TV and how to undo it): `DEVICES.md`.

### Setting it up on your own TV

1. Turn on USB debugging on the TV (Settings → Device Preferences → About → press Build 7 times, then Developer options).
2. Put the TV's address in `tools\tv-address.txt` (for example `192.168.1.50:5555`).
3. Build and run the `app` configuration on the TV from Android Studio, then follow "Building" below.
4. Pair the phone app by scanning the QR code on the TV's Phone screen.

## Day to day

- **PC control panel:** double-click `TV Control Panel.cmd`.
- **Photo frame:** put photos in `Documents\TV Photo Frame` (a subfolder = an album), then *Sync photos* in the panel.
- **Share to TV:** on the phone, Share a YouTube/web link or a photo → *Bedroom TV*.

## Building

Open this folder in Android Studio.

- **TV app:** run configuration `app`, device *Funai PHILIPS 4K TV*, press Debug/Run.
  Bump `versionCode` in `app/build.gradle.kts` first (every build that goes to the TV).
- **Phone app:** run configuration `phone` (emulator or phone), or *Build → Assemble Project* and use
  `phone/build/outputs/apk/debug/phone-debug.apk`.
- If Run/Debug are greyed out: *File → Sync Project with Gradle Files*.

## How it fits together

- The TV app runs a small server on port **8765** (HTTP, every request signed with the pairing key: HMAC-SHA256)
  and **8766** (UDP, signed key presses and gamepad state). The phone and the PC panel use these.
- Things an app normally can't do (key presses, screenshots, backlight) go through the TV's own adb
  connection on `127.0.0.1:5555` (`TvShell` / `KeyInjector` in the TV app). Nothing is rooted.
- The pairing key only lives on the TV and in the phone's encrypted storage; the PC panel reads it over adb
  and keeps it in memory only.

### TV app (`app/src/main/java/com/bedroom/launcher`)

| File | Does |
|---|---|
| `LauncherUi.kt` | Home screen layout: clock, weather, top buttons, Continue card, Watch and Favorites rows |
| `Photos.kt` | Photo frame (store, albums, slideshow screen) and RetroArch history / Continue |
| `Bedroom.kt` | Bedtime, night mode, alarm, screensaver replacement, brightness (incl. extra-dim layer), storage |
| `Share.kt` | Links and photos shared from the phone; play time per game |
| `RemoteServer.kt` | The phone/PC API (port 8765) |
| `KeyInjector.kt`, `GamepadBridge.kt`, `UhidHelper.kt` | Remote keys and virtual gamepads |
| `ScreenOff.kt` | Screen off / rest, backlight helper, broadcast receiver used by the PC panel |
| `NowPlaying.kt`, `SleepTimer.kt`, `Weather*.kt`, `Apps.kt`, `Favorites.kt`, `Retro*.kt`, `FileBrowser.kt` | as named |

### Phone app (`phone/src/main/java/com/bedroom/remote`)

| File | Does |
|---|---|
| `RemoteUi.kt` | App shell, Remote tab, sleep section, settings/keyboard dialogs |
| `Ui.kt` | Shared look: cards with badges, hero cards, segmented switch |
| `ScreenUi.kt`, `VideoPlayer.kt` | Live view (H.264 from the TV), touch, Remote/Gamepad switch |
| `Gamepad.kt` | Full-screen gamepad and the pad used inside the live view |
| `AppsUi.kt`, `Roms.kt`, `FilesUi.kt`, `BedroomUi.kt` | Apps & Sleep, Games, file browser, Bedroom tabs |
| `ShareActivity.kt` | "Bedroom TV" in the phone's Share menu |
| `TvClient.kt`, `Secure.kt`, `TvNotification.kt` | Talking to the TV, pairing key storage, the notification controls |

### PC tools (`tools/`)

| Script | Does |
|---|---|
| `panel.ps1` | The control panel (started by `TV Control Panel.cmd`); live view uses scrcpy in `tools\scrcpy` |
| `photos.ps1` | Two-way photo frame sync |
| `saves.ps1`, `roms.ps1` | RetroArch saves / games between PC and TV |
| `weekly.ps1` | Weekly save backup + photo sync (Task Scheduler) |
| `home-step.ps1` | Make Bedroom Home the home screen, step by step, **and undo it** |
| `optimize.ps1`, `tune2.ps1` | Reversible TV tune-ups (`-Undo` puts everything back) |
| `remote-setup.ps1`, `set-weather.ps1`, `find-tv.ps1` | Setup helpers |

Old one-off investigation scripts from the setup (and three unused source files) are still on disk but
listed in `.gitignore`, so git doesn't track them.

## Getting the normal Google TV home screen back (keep this!)

Nothing is uninstalled or rooted; re-enabling the stock launcher undoes it.

**With just the remote:** on our home screen choose **Settings** → Apps → See all apps → Show system apps →
**Android TV Home** → **Enable**. Press Home; if asked, pick Android TV Home → Always.

**From the PC:**
```
powershell -ExecutionPolicy Bypass -File tools\home-step.ps1 -Step undo
```

After a TV system update (it can re-enable the stock launcher): `tools\home-step.ps1 -Step status`.

Remove our launcher completely: `adb uninstall com.bedroom.launcher` (after re-enabling the stock one).
