# Changelog

Bedroom TV is three parts that are released together: **Bedroom Home** (the TV app), **TV Remote** (the phone app)
and the **PC tools** (control panel and scripts).

Made for and tested on: **Philips 4K UHD Android TV, made by Funai** (Android TV 9 / API 28, device codename
`freesia`, build `PTM5.200218.905`, 1920×1080 UI at 320 dpi, 1.3 GB RAM). Phone app: Android 8.0 or newer.

| Current versions | |
|---|---|
| Bedroom Home (TV) | **1.8.0** (versionCode 37) |
| TV Remote (phone) | **3.4 "Sharing Shark"** (versionCode 24) |
| PC control panel | **v4** (Material look) |

> Older entries were reconstructed from the project notes and the APKs built along the way, so very early
> releases are summarised rather than listed one by one.

---

## Bedroom Home (TV app)

### 1.8.0 — 2026-11-01
- **Share to TV:** links shared from the phone open on the TV (YouTube links in the TV's YouTube app, YouTube
  Music links in YouTube Music, other links in whatever app opens them). Shared photos show full screen right away
  and go into a "Shared from phone" album. The TV wakes first if it's resting.
- **Play time:** counts time played per RetroArch game (merged with RetroArch's own runtime logs when it keeps
  them). Shown on the Continue card ("X h played") and in the phone's Games tab.
- Photo frame can open on a specific photo (paused) and jump to a new one while it's already showing.
- Removed unused code.

### 1.7.4
- Fixed: moving the brightness slider quickly could leave the extra-dim layer stuck on.

### 1.7.3
- **Continue card** in the space between the clock and the weather: the last RetroArch game with a picture of
  where you left off (RetroArch's save-state screenshot, or its box art/title picture), the game name and
  "your spot · 2 h ago". Hold OK on it to turn on RetroArch's save-on-quit / load-on-start (keeps a backup of
  RetroArch's settings; only while RetroArch is closed).

### 1.7.2
- **Brightness:** more range at the dim end. 25–100 is the backlight on a curve; below 25 the backlight stays at
  its lowest and a see-through black layer makes the picture up to ~85 % darker. The layer comes back after a reboot.

### 1.7.1
- Photo frame: "← Home" now works with touch / PC clicks; the photo counter moved to the top-right so it no longer
  covers the date.
- More space between the top buttons and the Watch row.

### 1.7.0
- **Tidier top row:** one row of compact buttons under the clock (All apps, Photos, Continue, Sleep, Phone, Settings).
- **Photos button** shows a small thumbnail that cycles through your photos.
- **Photo frame controls:** ← Home, previous / pause / next, a counter; tap the left / middle / right of the
  screen; remote arrows and OK work too.
- **Albums:** each subfolder of the PC's `TV Photo Frame` folder is an album; the phone picks which one shows.
- Frame options from the phone: album, shuffle, slow zoom, fill screen, clock, temperature, time per photo.
- Backlight API for the phone and PC brightness sliders.

### 1.6.x
- **Bedroom features:** bedtime button (night mode, bedtime volume, sleep music, then rest), night mode (dimmer
  backlight + optional warm tint), wake-up alarm with volume ramp, screensaver replacement (rest or photo frame),
  photo frame with two-way PC folder sync and uploads from the phone, storage check and cache cleaner,
  second player gamepad, Continue last game, voice search support.
- 1.6.2: phone key presses are de-duplicated per sender (no double presses).
- 1.6.3: gently animated weather icons.
- 1.6.4: touch support: a tap or click in the PC's live view presses on-screen buttons.
- 1.6.5: touch from the phone's live view (tap, hold, swipe).

### 1.0 – 1.5 — 2026-10-25 to 2026-10-31
- **Phone remote server** (port 8765), every request signed with the pairing key; QR code pairing from the
  TV's Phone screen.
- Remote keys through the TV's own adb connection ("Allow full remote"); fast key presses over UDP (port 8766).
- Virtual **gamepad** for the phone (shows up as "Bedroom Phone Gamepad").
- **Sleep timer** and **Rest** (looks off but stays online so the phone can wake it); screen off with sound on.
- RetroArch helpers: save files and save states to/from the phone, sending games, a file browser limited to
  RetroArch's folders.
- **Live view** of the TV screen for the phone: hardware H.264 video, with snapshots as a fallback.
- What's playing (now playing card, media controls, close apps).

### 0.3.0 — 2026-10-25
- Full-width responsive layout (TV-safe margins); All apps and Settings as top-bar buttons.
- Made Bedroom Home the TV's home screen (step by step, with an undo timer; only the stock launcher was disabled).

### 0.2.0 — 2026-10-25
- Weather panel (Open-Meteo) with a 5-day strip; city set from the PC.
- Favorites row with an Add / remove picker.

### 0.1.1 — 2026-10-25
- Unfocused banners are dimmed (bright white banners glared), more so at night.

### 0.1.0 — 2026-10-25
- First version: clock, greeting and date; Watch row (Hulu, YouTube, YouTube Music); RetroArch, All apps and
  Settings; focus glow and scale; dims automatically at night.

---

## TV Remote (phone app)

### 3.4 "Sharing Shark" — 2026-11-01
- **Bedroom TV in the Share menu:** share a link or photo from any app to the TV.
- **Play time** card in the Games tab (hours per game, bars, last played).

### 3.3 "Gamepad Gecko" — 2026-11-01
- **New look** matching the PC panel: cards with coloured badges and a main card on each tab
  (Remote, Apps & Sleep, Games).
- **Live view gamepad:** Remote | Gamepad switch under the picture; in full screen the pad sits over the picture.
- Brightness slider shows "extra dim" below 25.

### 3.2 "Bright Badger"
- Photo frame options screen (album, shuffle, slow zoom, fill screen, clock, temperature, time per photo, rest after).
- TV brightness slider on the Remote and Bedroom tabs.

### 3.1 "Tappy Tapir"
- Touch the TV from the live view (tap, hold, swipe); full screen no longer exits on tap.

### 3.0 "Night Owl"
- **Bedroom tab:** bedtime, night mode, wake-up alarm, what to do instead of the screensaver, photo frame
  (add / remove photos), TV storage and cache clean-up.
- Gamepad Player 1 / Player 2, Continue last game, voice search.

### 2.8 "Smooth Sloth"
- While a video plays full screen on the TV, the live view shows what's playing instead of mirroring it
  (mirroring made the TV stutter); "Show live video anyway" button.

### 2.7 "Sleepy Walrus"
- Rest the TV / wake the TV, with a banner when it's resting.

### 2.5 "Swift Heron" – 2.6 "Feather Fox"
- Live view quality choice: Light 360p, Smooth 480p, Sharp 720p.

### 2.3 "Neon Falcon"
- **Live video** of the TV screen (hardware H.264), snapshots as a fallback.

### 2.0 "Iron Badger"
- **Secure pairing:** scan the QR code on the TV; replies from the TV are signed, so a fake reply is blocked.
- Full screen live view.

### 1.9 "Cartridge Yeti"
- **Games & saves tab:** send games and saves, back up all saves, saves listed under each game.
- File browser (RetroArch folders): move, delete, new folder, copy to phone.
- First TV screen viewer (snapshots).

### 1.7 "Disco Badger"
- **Apps & Sleep tab:** what's playing with controls, open apps, close an app, skip to the next episode.

### 1.6 "Turbo Raccoon"
- Screen off (sound stays on).

### 1.5 "Pixel Possum"
- Reconnect button; TV Home and RetroArch buttons on the gamepad; keeps a background connection.

### 1.4 "Floppy Goblin"
- RetroArch saves: copy to the phone, send to the TV, back up all.

### 1.2
- **Phone gamepad** for RetroArch.

### 1.1
- TV controls in the notification (volume, play/pause) with the app closed.

### 1.0
- First version: D-pad remote, Back / Home, volume, play/pause, keyboard, apps.

Versions 1.3, 1.8, 2.1, 2.2 and 2.4 were fix releases with no visible changes.

---

## PC tools

### Control panel v4
- Material Dashboard look (cards, stat cards, dark sidebar).
- **Smooth live view** with scrcpy (real video, quality setting), plus a pop-up remote window.
- Mouse clicks in the live view touch the TV; keyboard control.
- Fast key presses (same signed UDP method as the phone app).
- TV brightness slider in the main window and the pop-up remote (they stay in step).
- Photo frame sync button, weekly backup on/off, bedtime / night mode buttons.

### Scripts
- `photos.ps1`: two-way photo frame sync with albums (subfolders); fixed photos being reported as skipped and
  album photos landing in the wrong folder.
- `saves.ps1`, `roms.ps1`, `weekly.ps1`: RetroArch saves and games, weekly backup (keeps the newest 8).
- `home-step.ps1`, `optimize.ps1`, `tune2.ps1`: home screen setup and reversible tune-ups, each with an undo.
- The TV's address now comes from `tools\tv-address.txt` (kept out of git).
