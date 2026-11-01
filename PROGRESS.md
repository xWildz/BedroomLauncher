# Progress

## Phase 1: setup
- [x] Android Studio installed (Rabbit 1, 2026.2.1)
- [x] Google TV emulator created (Television 1080p, API 36, x86_64); Windows Hypervisor Platform enabled
- [x] Project syncs and builds (first build ~5 min, no compile errors)

## Phase 2: emulator prototype (v0.1.0)
- [x] Home screen renders: clock, greeting, date (verified by screenshot 2026-10-25)
- [x] Watch row: Hulu, YouTube, YouTube Music (shows "not installed" on emulator, expected)
- [x] RetroArch / All apps / Settings row
- [x] Focus glow + scale visible (Hulu, All apps, YouTube seen focused)
- [ ] D-pad moves sensibly (needs keyboard/remote test; mouse taps only move focus, by design on TV)
- [ ] All apps grid opens, Back returns and refocuses "All apps"
- [ ] Night palette (10 PM–5 AM) checked by changing emulator clock
- [ ] Returning from another app refocuses the tile that launched it

## Phase 3: real TV (ask before every step)
- [x] adb over Wi-Fi connected + authorized (Android TV 9, stock launcher com.google.android.tvlauncher)
- [x] v0.1.0 installed as a NORMAL app (stock home untouched); renders correctly at 1920x1080 (screenshot verified)
- [x] "Not installed" tile -> Play Store -> Hulu installed -> tile switched to real banner on return
- [x] v0.1.1: dim unfocused banners (white YouTube banners glared), stronger at night
- [x] v0.2.0: weather panel (Open-Meteo, home town via config.json) + 5-day strip renders on TV (screenshot verified)
- [x] v0.2.0: Favorites row (RetroArch default, now installed) + Add/remove tile; layout fits 1080p
- [x] Favorites picker add/remove verified with remote (user added Kick + Netflix)
- [x] v0.3.0: full-width responsive layout (48dp margins, All apps/Settings as top-bar pills); D-pad Up reaches pills (screenshot verified)
- [ ] Night palette checked on the real TV after 10 PM
- [ ] D-pad navigation + Back from All apps verified with real remote
- [x] Made it the home screen 2026-10-25 (tools\home-step.ps1: arm 10-min undo timer -> disable stock -> check -> disarm)
      Only com.google.android.tvlauncher disabled. set-home-activity -> com.bedroom.launcher/.MainActivity.
      Verified: Home key lands on ours, Settings pill opens TV settings, Wi-Fi stays up.

## Phase 4: reboot + overnight test
- [x] Reboot test: TV booted straight into Bedroom Home, Wi-Fi up, no leftover timer (no BOOT_COMPLETED receiver needed on Android 9)
- [ ] Overnight / multi-day: clock + weather keep updating, night palette after 10 PM, still home after standby
- [ ] After any TV system update: run tools\home-step.ps1 -Step status (updates can re-enable the stock launcher)
