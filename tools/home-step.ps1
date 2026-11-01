# Makes Bedroom Home the TV's home screen, ONE SAFE STEP AT A TIME. Every step logs to home-log.txt.
#
#   -Step status   read-only: what's disabled, what Home points to, whether the undo timer is running
#   -Step arm      start a 10-minute auto-undo timer ON THE TV (re-enables the stock launcher by itself)
#   -Step disable  disable the stock launcher + point Home at Bedroom Home + press Home
#   -Step check    read-only: confirm Home, network, resumed app, take a screenshot
#   -Step disarm   cancel the undo timer (only after 'check' looks good)
#   -Step reboot   restart the TV to test it comes back to Bedroom Home
#   -Step undo     put the normal Android TV home back right now
#
# Undo at any time:  powershell -ExecutionPolicy Bypass -File tools\home-step.ps1 -Step undo
# Undo with only the remote: Settings -> Apps -> See all apps -> Show system apps -> Android TV Home -> Enable

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("status", "arm", "disable", "check", "disarm", "reboot", "undo")]
    [string]$Step,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$log = Join-Path $root "home-log.txt"
$stock = "com.google.android.tvlauncher"
$ours = "com.bedroom.launcher/.MainActivity"
$stockHome = "$stock/.MainActivity"

function Log([string]$t) {
    $line = "[$(Get-Date -Format HH:mm:ss)] $t"
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding utf8
}

function Sh([string]$cmd) {
    Log "> adb shell $cmd"
    $out = (& $adb -s $Tv shell $cmd 2>&1 | Out-String).Trim()
    if ($out) { Log $out }
}

function Status {
    Sh "pm list packages -d"
    Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    Sh "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'"
    # [s]leep trick: the pattern doesn't match this grep's own command line.
    Sh "ps -A -o PID,PPID,ARGS | grep -E '[s]leep 600|[p]m enable'"
}

Log "===== STEP: $Step ====="
& $adb connect $Tv | Out-Null

switch ($Step) {
    "status" { Status }

    "arm" {
        # Runs entirely on the TV, so it fires even if Wi-Fi drops after we disable the launcher.
        Sh "nohup sh -c 'sleep 600; pm enable $stock; cmd package set-home-activity $stockHome; input keyevent KEYCODE_HOME' > /dev/null 2>&1 < /dev/null &"
        Start-Sleep -Seconds 2
        Log "Timer should be visible below (sleep 600). If nothing shows, do NOT run 'disable'."
        Sh "ps -A -o PID,PPID,ARGS | grep -E '[s]leep 600|[p]m enable'"
    }

    "disable" {
        Log "UNDO: tools\home-step.ps1 -Step undo   (or wait: the timer re-enables it within 10 minutes)"
        Sh "pm disable-user --user 0 $stock"
        Sh "cmd package set-home-activity $ours"
        Sh "input keyevent KEYCODE_HOME"
        Start-Sleep -Seconds 3
        Status
    }

    "check" {
        Status
        Sh "dumpsys connectivity | grep -m 3 -E 'NetworkAgentInfo|state: CONNECTED'"
        Sh "settings get global always_finish_activities"
        $shots = Join-Path $root "shots"
        New-Item -ItemType Directory -Force $shots | Out-Null
        $f = Join-Path $shots ("home-{0}.png" -f (Get-Date -Format "HHmmss"))
        & $adb -s $Tv shell screencap -p /sdcard/launcher-shot.png
        & $adb -s $Tv pull /sdcard/launcher-shot.png $f | Out-Null
        & $adb -s $Tv shell rm /sdcard/launcher-shot.png
        Log "Screenshot: $f"
    }

    "disarm" {
        # Kill the waiting shell FIRST (killing only 'sleep' would make it run 'pm enable' immediately).
        Sh "pkill -9 -f '[p]m enable $stock'"
        Sh "pkill -9 -f '[s]leep 600'"
        Start-Sleep -Seconds 1
        Log "Nothing should be listed below (timer gone):"
        Sh "ps -A -o PID,PPID,ARGS | grep -E '[s]leep 600|[p]m enable'"
    }

    "reboot" {
        Log "Rebooting TV. Wait until it's fully back (2-3 min), then run -Step check."
        & $adb -s $Tv reboot
    }

    "undo" {
        Sh "pm enable $stock"
        Sh "cmd package set-home-activity $stockHome"
        Sh "input keyevent KEYCODE_HOME"
        Start-Sleep -Seconds 2
        Status
    }
}
Log "===== done: $Step ====="
