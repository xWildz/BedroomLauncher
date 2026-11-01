# Device notes

Personal details (the TV's address and network ID, home town) are in `DEVICES.local.md`, which git ignores.

## Philips 4K TV (bedroom)
- Maker: Funai. Google TV / Android TV with Chromecast built in.
- Chromecast name: "PHILIPS 4K TV". Address: see DEVICES.local.md (tools\find-tv.ps1 finds it).
- Wi-Fi only, no Ethernet fallback. Recovery is via remote (see README).
- OS: **Android TV 9 (API 28)**, not Google TV. Build Funai/freesia PTM5.200218.905. 1920x1080 @ 320dpi (960x540 dp).
- ADB: USB debugging ON; adb over Wi-Fi works on port 5555 (needed a TV restart after enabling).
  PC authorized ("Always allow"). No "Wireless debugging" option on this build.
- HOME candidates: com.google.android.tvlauncher/.MainActivity (stock, priority 2),
  com.android.tv.settings/.system.FallbackHome (priority -1000).
- Other launcher-ish packages present: com.google.android.tungsten.setupwraith,
  com.google.android.leanbacklauncher, com.google.android.leanbacklauncher.recommendations.
- Undo for anything we disable: `adb shell pm enable com.google.android.tvlauncher`
- Packages disabled: **com.google.android.tvlauncher** (since 2026-10-25). Tolerated fine: Wi-Fi, Settings, reboot all OK.
  setupwraith and leanbacklauncher left alone (not needed on this TV).
- Preferred HOME: com.bedroom.launcher/.MainActivity (set-home-activity).
- Hardware: 1.3 GB usable RAM (MemTotal 1,358,996 kB); /data 4.0 GB.
- Tune-up 2026-10-25 (tools\optimize.ps1 -Apply; undo: -Undo):
  animations 0.5x (were 1.0/1.0/unset), sleep_timeout 4h (was -1 never), screensaver Backdrop after 5 min unchanged.
  Disabled: vudu, play.games, videos, retail.demo, tvrecommendations, katniss (voice/mic), spotify, pluto, twitch.
  KEPT: mediashell (Chromecast). Result: MemAvailable ~145 MB -> ~440 MB; /data free 784 MB -> 920 MB.
- Controllers: **DualShock 4 does NOT work (BT or USB)**. Kernel sees it (HID 054C:09CC via uhid and via USB),
  BT HidHost state=2 (connected), but no input device is created: kernel HID drivers are only
  hid-generic, hid-steam, microsoft, nintendo — no hid-sony, and hid-generic skips Sony IDs. Not fixable without root.
  Should work: Xbox One/Series controller over Bluetooth (hid-microsoft), Switch Pro / 8BitDo in Switch mode (hid-nintendo),
  Steam Controller (hid-steam). Generic HID gamepads may work via hid-generic.
- "Don't keep activities": off.
- Installed: YouTube (com.google.android.youtube.tv), YouTube Music (com.google.android.youtube.tvmusic).
  Hulu installed 2026-10-25 via launcher tile. RetroArch installed 2026-10-25.
- Weather place: set via the panel (Tools > Weather city) or tools\set-weather.ps1 -> /sdcard/Android/data/com.bedroom.launcher/files/config.json
