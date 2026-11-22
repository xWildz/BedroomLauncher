# READ-ONLY probe of a few TV features (temperature sensors, "Watch next" list). Writes probe-diag.txt
# (not tracked by git). Changes nothing on the TV.
param([string]$Tv = "")
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "probe-diag.txt"
function Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }
function Section([string]$title, [string]$cmd) { "`n===== $title =====`n" + (Sh $cmd) | Add-Content -LiteralPath $out -Encoding UTF8 }
& $adb connect $Tv 2>&1 | Out-Null
"Probe $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -LiteralPath $out -Encoding UTF8
Section "Thermal zones" "for z in /sys/class/thermal/thermal_zone*; do echo `$z `$(cat `$z/type 2>&1) `$(cat `$z/temp 2>&1); ls -l `$z/temp; done; ls /sys/class/thermal/"
Section "Other temperature files" "ls /sys/devices/virtual/thermal/ 2>&1; cat /proc/mstar_thermal 2>&1 | head; getprop | grep -i -E 'therm|temp' | head; dumpsys thermalservice 2>&1 | head -n 30"
Section "Watch next (all apps)" "content query --uri content://android.media.tv/watch_next_program --projection package_name:title:episode_title:watch_next_type:last_engagement_time_utc_millis:poster_art_uri:intent_uri 2>&1 | tail -n 40"
Section "Preview programs count" "content query --uri content://android.media.tv/preview_program --projection package_name:title 2>&1 | head -n 15"
Section "TV provider permissions" "dumpsys package com.android.providers.tv 2>&1 | grep -i -E 'permission|EPG' | head -n 30"
Section "Recent tasks" "dumpsys activity recents 2>&1 | grep -E 'Recent #|realActivity' | head -n 30"
Section "RetroArch thumbnails" "ls /sdcard/Android/data/com.retroarch/files/thumbnails/ 2>&1 | head; ls /sdcard/RetroArch/thumbnails 2>&1 | head; find /sdcard -maxdepth 6 -type d -name 'Named_Boxarts' 2>/dev/null | head"
Section "RetroArch playlists" "ls /sdcard/Android/data/com.retroarch/files/playlists/ 2>&1 | head -n 20"
Section "Bedroom Home version" "dumpsys package com.bedroom.launcher | grep -m2 -E 'versionName|versionCode'"
Section "On screen" "dumpsys activity activities | grep -m1 mResumedActivity"
Section "Bedroom Home crashes since it started" "logcat -b crash -d 2>/dev/null | grep -A12 com.bedroom | tail -n 40"
Section "Bedroom Home log (last lines)" "logcat -d -t 400 2>/dev/null | grep -E 'BedroomLauncher|AndroidRuntime' | tail -n 40"
# A picture of the screen, only when the home screen is what's showing (never anything you're watching).
$front = Sh "dumpsys activity activities | grep -m1 mResumedActivity"
if ($front -match "com\.bedroom\.launcher") {
    $shots = Join-Path (Split-Path $PSScriptRoot -Parent) "shots"
    New-Item -ItemType Directory -Force $shots | Out-Null
    $png = Join-Path $shots "probe-home.png"
    & cmd /c "`"$adb`" -s $Tv exec-out screencap -p > `"$png`""
    "`nHome screen picture: shots\probe-home.png" | Add-Content -LiteralPath $out -Encoding UTF8
}
"`nDone." | Add-Content -LiteralPath $out -Encoding UTF8
