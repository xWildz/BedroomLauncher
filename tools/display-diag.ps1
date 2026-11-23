# READ-ONLY: which resolutions / refresh rates the TV reports, and its motion settings. Writes display-diag.txt.
param([string]$Tv = "")
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "display-diag.txt"
function Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }
function Section([string]$t, [string]$c) { "`n===== $t =====`n" + (Sh $c) | Add-Content -LiteralPath $out -Encoding UTF8 }
& $adb connect $Tv 2>&1 | Out-Null
"Display check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -LiteralPath $out -Encoding UTF8
Section "Android display modes" "dumpsys display | grep -iE 'mode|refresh|fps|DisplayDeviceInfo' | head -n 40"

Section "Panel / timing properties" "getprop | grep -iE 'panel|frc|memc|refresh|hz|fps|timing|motion|4k2k|ursa|hdmi.*mode|display' | head -n 60"
Section "Panel files" "ls /config/panel 2>&1 | head; ls /vendor/tvconfig/config/panel 2>&1 | head; ls /tvconfig/config/panel 2>&1 | head"
#Section "Panel ini" "for f in /config/panel/*.ini /vendor/tvconfig/config/panel/*.ini /tvconfig/config/panel/*.ini; do [ -f \"`$f\" ] && echo \"--- `$f\" && grep -iE 'name|width|height|vtotal|htotal|dclk|hz|fps|frc|60|120' \"`$f\" | head -n 25; done 2>/dev/null"
Section "Motion app (MStar)" "dumpsys package com.mstar.android.tv.moption | grep -m3 -E 'versionName|Activity'; settings list global | grep -iE 'motion|memc|frc|hz' ; settings list system | grep -iE 'motion|memc|frc|hz'"
Section "Picture modes (Bedroom Home asks the TV's picture service)" "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op picinfo 2>&1 | grep -o 'data=.*'"
"`nDone." | Add-Content -LiteralPath $out -Encoding UTF8
