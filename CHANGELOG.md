# Changelog

Bedroom TV is three parts that are released together: **Bedroom Home** (the TV app), **TV Remote** (the phone app)
and the **PC tools** (control panel and scripts).

Made for and tested on: **Philips 4K UHD Android TV, made by Funai** (Android TV 9 / API 28, device codename
`freesia`, build `PTM5.200218.905`, 1920×1080 UI at 320 dpi, 1.3 GB RAM). Phone app: Android 8.0 or newer.

| Current versions | |
|---|---|
| Bedroom Home (TV) | **2.0.1** (versionCode 40) |
| TV Remote (phone) | **3.7 "Roaming Raccoon"** (versionCode 27) |
| PC control panel | **v4.2** |

> Older entries were reconstructed from the project notes and the APKs built along the way, so very early
> releases are summarised rather than listed one by one.

---

## Bedroom Home (TV app)

### 2.0.1 — 2026-11-22
- **Control from anywhere with Tailscale:** the phone remote server also accepts your private Tailscale network
  (100.64.0.0/10, only devices signed in to your Tailscale account). Requests still have to be signed with the
  pairing key. The TV tells the phone its Tailscale address.

### 2.0.0 — 2026-11-22
- **Sleep Sounds:** its own app in the TV's app list (and in All apps). Rain, ocean, fan, deep / soft / white
  noise, made live on the TV (no recordings). Plays alongside other apps without pausing them (never interrupts
  YouTube), keeps going after you leave, sleep timer with a one-minute fade-out, volume, and a Screen off button.
- **Up next:** a button in the top row (with a picture of the latest item) opens what you were part-way through
  in YouTube, YouTube Music, Hulu and other apps. YouTube items reopen at the same spot. Hold OK to remove one.
  (Android's own "Watch next" list is locked to system apps on this TV, so Bedroom Home keeps its own.)
- **Weather background:** the home screen backdrop matches the weather: stars on a clear night, rain streaks,
  clouds, snow, fog. A still picture, so it costs nothing while the TV sits on the home screen.
- **Seasonal themes:** autumn leaves, Halloween bats, holiday lights, snowflakes, spring petals, summer fireflies;
  automatic by date or picked on the phone. Kept to the edges of the screen.
- **Sunrise and sunset** times under the weather.
- **Memory watchdog:** if memory runs very low while nobody's watching, Bedroom Home frees its own caches.
- Temperature in the health report and a small reading on the home screen, on TVs that report one (this Philips
  set doesn't, so nothing is shown and it stops checking).
- Release builds: installed by `tools\update.cmd`, not debuggable, same signing key (installs over the old one).

### 1.9.0 — 2026-11-22
- **Fix: the TV restarting by itself at night.** The TV's logs showed it being reset by its own hardware while
  Bedroom Home kept redrawing the animated weather and changing the Photos thumbnail all night (about 20–25 %
  of the processor with nobody watching, and the TV's memory overflow 100 % full). Animations now stop after
  10 minutes without a button press, and whenever the TV is resting or the screen is off; any button brings
  them back. The Photos thumbnail changes every 30 seconds instead of 5, and the play-time check stays quiet
  while the screen is off.
- **Night clean-up:** after an hour of rest / screen off, closes video apps that are open but not playing
  (Hulu, Netflix, …). Never YouTube, YouTube Music, anything playing, the app on screen, or RetroArch.
  Can be turned off on the phone (Bedroom tab).
- **Wi-Fi self-repair:** if Wi-Fi has been fully disconnected for 3 minutes, Bedroom Home switches it off and on
  (at most every 15 minutes). A working connection is never touched.
- **Health report** for the phone and PC: uptime, why the TV last started, recent restarts, free memory, how busy
  it is, and what Bedroom Home did overnight.
- Gives memory back when Android asks (decoded banners and cover art are rebuilt later).
- **USB drive for games:** with a USB drive plugged in, new games go to RetroArch's folder on the drive.

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

### 3.7 "Roaming Raccoon" — 2026-11-22
- **Works away from home:** with Tailscale on the TV and the phone, the app learns the TV's Tailscale address
  while you're home and switches to it by itself when the home address doesn't answer (and back again). The
  notification controls and the Quick Settings tile try both too. A note shows when you're connected from away.

### 3.6 "Cozy Capybara" — 2026-11-22
- **Quick Settings tile:** "Bedroom TV" in the phone's pull-down shade. Lit = awake, tap to rest / wake;
  long-press opens the app. (Add it with the pencil icon in Quick Settings.)
- **Touchpad:** D-pad | Touchpad switch on the Remote tab. Swipe to move (one step per finger-width, with a
  little buzz), tap for OK, hold for Back.
- **Game covers:** the Games tab shows box art for each game (from RetroArch's thumbnail library, saved on the
  phone after the first download). List | Covers switch next to Refresh.
- **Home screen look** card in the Bedroom tab: weather background on/off and the seasonal theme.
- Release build (not debuggable), same signing key, installs over 3.5.
- The phone's volume buttons already control the TV while the app is open (since 2.x).

### 3.5 "Healthy Hedgehog" — 2026-11-22
- **TV health** card in the Bedroom tab (restarts, memory, what Bedroom Home did overnight) with the
  "Close idle video apps overnight" switch.
- **Clearer "can't reach the TV":** says whether the TV is restarting/starting up, or off the network, and what
  to do; after a restart it says so ("The TV restarted 3 min ago").
- Games tab shows a USB drive's free space when one is plugged into the TV.

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

### Control panel v4.2 — 2026-11-22
- **Lives in the tray:** closing the window hides it next to the clock. Double-click to open; right-click for
  Bedtime, Rest / Wake, Screen off / on, Back up saves now, and Exit. Optional "Start with Windows (in the tray)".
- **Notifications** when the TV restarts by itself and when game saves are backed up.
- **Saves backed up after playing:** about 90 seconds after you leave RetroArch, new saves are copied to
  Documents\RetroArch Saves\After playing (keeps the last 10). On/off on the Games page or in the tray menu.
- `tools\update.cmd`: one click builds release versions of both apps, installs the TV app over the current one
  and puts the phone APK in `releases\` (log: update-log.txt).
- `tools\probe.cmd`: read-only check of TV features (temperature sensors, Watch next access).

### Control panel v4.1 — 2026-11-22
- **TV health** card on the Bedroom page (refreshes every 2 minutes).
- **"Why did it restart?"** button: runs the read-only restart check (`tools\restart-diag.ps1`) and opens the report.
- `roms.ps1` sends games to a USB drive plugged into the TV when there is one.

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
