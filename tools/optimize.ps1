# Reversible TV tune-up. Logs everything (including the old values) to optimize-log.txt.
#   Apply: powershell -ExecutionPolicy Bypass -File tools\optimize.ps1 -Apply
#   Undo:  powershell -ExecutionPolicy Bypass -File tools\optimize.ps1 -Undo
# Nothing is uninstalled; disabled apps come back with -Undo (or Settings > Apps > [app] > Enable).

param(
    [switch]$Apply,
    [switch]$Undo,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

if (-not ($Apply -xor $Undo)) { Write-Error "Use exactly one of -Apply or -Undo"; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$log = Join-Path $root "optimize-log.txt"

# Approved 2026-10-25. Chromecast (com.google.android.apps.mediashell) intentionally KEPT.
$disable = @(
    "air.com.vudu.air.DownloaderTablet",        # Vudu (preinstalled)
    "com.google.android.play.games",            # Play Games
    "com.google.android.videos",                # Play Movies & TV
    "com.mstar.android.tv.retail.demo",         # store demo mode
    "com.google.android.tvrecommendations",     # fed the old home screen's rows
    "com.google.android.katniss",               # voice search / mic button
    "com.spotify.tv.android",                   # Spotify
    "tv.pluto.android",                         # Pluto TV
    "tv.twitch.android.app"                     # Twitch
)
$anim = @("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
$sleepMs = 4 * 60 * 60 * 1000  # 4 hours

function Log([string]$t) {
    $line = "[$(Get-Date -Format HH:mm:ss)] $t"
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}
function Sh([string]$cmd) {
    Log "> adb shell $cmd"
    $o = (& $adb -s $Tv shell $cmd 2>&1 | Out-String).Trim()
    if ($o) { Log $o }
}

& $adb connect $Tv | Out-Null

if ($Apply) {
    Log "===== APPLY ====="
    Log "Before:"
    foreach ($k in $anim) { Sh "settings get global $k" }
    Sh "settings get secure sleep_timeout"
    Sh "df -h /data"
    Sh "head -3 /proc/meminfo"

    foreach ($k in $anim) { Sh "settings put global $k 0.5" }
    Sh "settings put secure sleep_timeout $sleepMs"   # screensaver after 5 min stays as is
    foreach ($p in $disable) { Sh "pm disable-user --user 0 $p" }
    Sh "pm trim-caches 999G"
    Start-Sleep -Seconds 3

    Log "After:"
    Sh "pm list packages -d"
    Sh "df -h /data"
    Sh "head -3 /proc/meminfo"
    Log "UNDO: tools\optimize.ps1 -Undo"
}

if ($Undo) {
    Log "===== UNDO ====="
    Sh "settings put global window_animation_scale 1.0"
    Sh "settings put global transition_animation_scale 1.0"
    Sh "settings delete global animator_duration_scale"   # was unset (null) before
    Sh "settings put secure sleep_timeout -1"             # was 'never'
    foreach ($p in $disable) { Sh "pm enable $p" }
    Sh "pm list packages -d"
}
Log "===== done ====="
