# Tune-up #2: whole-TV ad/tracker blocking (Private DNS) + extra cleanup. Fully reversible.
#   Apply: powershell -ExecutionPolicy Bypass -File tools\tune2.ps1 -Apply
#   Undo:  powershell -ExecutionPolicy Bypass -File tools\tune2.ps1 -Undo
#
# Safety net: before changing anything, a 5-minute auto-undo timer is started ON THE TV.
# The script then tests the TV's internet. If the test passes, it cancels the timer.
# If the test fails, it undoes everything right away (and the timer is a backup if the PC loses the TV).

param(
    [switch]$Apply,
    [switch]$Undo,
    [string]$Tv = "",
    [string]$Dns = "dns.adguard-dns.com"   # AdGuard DNS (free, blocks ads + trackers)
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

if (-not ($Apply -xor $Undo)) { Write-Error "Use exactly one of -Apply or -Undo"; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$log = Join-Path $root "tune2-log.txt"

# Conservative extra cleanup: nothing here handles Wi-Fi, inputs, the picture, the remote, or app installs.
$disable = @(
    "com.google.android.marvin.talkback",                   # screen reader (accessibility)
    "com.google.android.feedback",                          # Google feedback reporter
    "com.google.android.tv.bugreportsender",                # bug report sender
    "com.android.printspooler",                             # printing (on a TV)
    "com.google.android.leanbacklauncher.recommendations",  # old launcher's recommendation rows
    "com.google.android.speech.pumpkin",                    # offline voice commands (voice is already off)
    "com.android.dreams.basic",                             # unused "colors" screensaver (photos one stays)
    "com.google.android.syncadapters.calendar",             # Google Calendar sync
    "android.autoinstalls.config.mstar.androidtv"           # pushes preinstalled apps (Pluto, Twitch...) back
)

function Log([string]$t) {
    $line = "[$(Get-Date -Format HH:mm:ss)] $t"
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}
function Sh([string]$cmd) {
    Log "> adb shell $cmd"
    $o = (& $adb -s $Tv shell $cmd 2>&1 | Out-String).Trim()
    if ($o) { Log $o }
    return $o
}

$restoreDns = "settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier"
$enableAll = ($disable | ForEach-Object { "pm enable $_" }) -join "; "

function Disarm {
    # Kill the waiting shell first; killing only 'sleep' would make it run the undo immediately.
    Sh "pkill -9 -f '[s]leep 300; settings put global private_dns_mode'" | Out-Null
    Sh "pkill -9 -f '^[s]leep 300$'" | Out-Null
    $left = Sh "ps -A -o PID,ARGS | grep -E '[s]leep 300'"
    if ($left) { Log "WARNING: timer still listed above" } else { Log "Auto-undo timer cancelled." }
}

function UndoAll {
    Sh $restoreDns | Out-Null
    foreach ($p in $disable) { Sh "pm enable $p" | Out-Null }
}

& $adb connect $Tv | Out-Null

if ($Apply) {
    Log "===== APPLY tune2 ====="
    Sh "settings get global private_dns_mode" | Out-Null
    Sh "settings get global private_dns_specifier" | Out-Null

    Log "Arming 5-minute auto-undo on the TV..."
    Sh "nohup sh -c 'sleep 300; $restoreDns; $enableAll' > /dev/null 2>&1 < /dev/null &" | Out-Null
    Start-Sleep -Seconds 2
    $armed = Sh "ps -A -o PID,ARGS | grep -E '[s]leep 300'"
    if (-not $armed) { Log "Timer did not start - stopping without changing anything."; exit 1 }

    Sh "settings put global private_dns_specifier $Dns" | Out-Null
    Sh "settings put global private_dns_mode hostname" | Out-Null
    foreach ($p in $disable) { Sh "pm disable-user --user 0 $p" | Out-Null }

    Log "Waiting 15 s, then testing the TV's internet through the new DNS..."
    Start-Sleep -Seconds 15
    $ping = Sh "ping -c 2 -W 3 www.google.com"
    if ($ping -match "bytes from") {
        Log "Internet OK through $Dns."
        Disarm
        Log "Done. UNDO anytime: tools\tune2.ps1 -Undo"
    } else {
        Log "Internet test FAILED - undoing everything now."
        UndoAll
        Disarm
    }
    Sh "pm list packages -d" | Out-Null
}

if ($Undo) {
    Log "===== UNDO tune2 ====="
    UndoAll
    Sh "settings get global private_dns_mode" | Out-Null
    Sh "pm list packages -d" | Out-Null
}
Log "===== done ====="
