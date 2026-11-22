# READ-ONLY: why did the TV restart? Collects the TV's boot reason, crash/watchdog records and memory
# events into restart-diag.txt (next to the project, not tracked by git). Changes nothing on the TV.
#   powershell -ExecutionPolicy Bypass -File tools\restart-diag.ps1

param([string]$Tv = "")

if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "restart-diag.txt"
function Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }
function Section([string]$title, [string]$cmd) { "`n===== $title =====`n" + (Sh $cmd) | Add-Content -LiteralPath $out -Encoding UTF8 }

& $adb connect $Tv 2>&1 | Out-Null
"Restart check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') (PC time)" | Set-Content -LiteralPath $out -Encoding UTF8

Section "TV time and uptime" "date; cat /proc/uptime; uptime"
Section "Bedroom Home's own report (restarts, memory, what it did overnight)" "am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op health 2>/dev/null | grep -o 'data=.*'"
Section "Boot reason" "for p in sys.boot.reason ro.boot.bootreason persist.sys.boot.reason ro.boot.wakeup_reason sys.boot_completed; do echo `$p=`$(getprop `$p); done"
Section "Dropbox: list of recorded events (crashes, watchdogs, restarts)" "dumpsys dropbox 2>/dev/null | grep -E '^[0-9]{4}-' | tail -n 120"
Section "Dropbox: restart / watchdog / kernel details" "for t in SYSTEM_RESTART system_server_watchdog system_server_crash system_server_anr SYSTEM_LAST_KMSG SYSTEM_TOMBSTONE SYSTEM_BOOT SYSTEM_FSCK kernel_panic; do echo ---- `$t; dumpsys dropbox --print `$t 2>/dev/null | tail -n 60; done"
Section "Dropbox: our apps' crashes and freezes" "dumpsys dropbox --print data_app_crash 2>/dev/null | grep -A25 -E 'com.bedroom|Process:' | tail -n 120; dumpsys dropbox --print data_app_anr 2>/dev/null | grep -A15 'com.bedroom' | tail -n 60"
Section "Events since this boot (boot, low memory, kills, crashes)" "logcat -b events -d 2>/dev/null | grep -E 'boot_progress_start|boot_progress_enable_screen|am_low_memory|am_proc_died|am_kill|am_crash|am_anr|watchdog|power_sleep_requested|power_screen_state|sysui' | tail -n 150"
Section "Memory now" "cat /proc/meminfo | head -n 8; dumpsys meminfo 2>/dev/null | grep -A14 'Total PSS by process' | head -n 16"
Section "Sleep / standby settings" "echo sleep_timeout=`$(settings get secure sleep_timeout); echo screen_off_timeout=`$(settings get system screen_off_timeout); echo stay_on=`$(settings get global stay_on_while_plugged_in); dumpsys power 2>/dev/null | grep -iE 'mWakefulness=|mLastWakeTime|mLastSleepTime|mLastSleepReason|mLastWakeReason|mSleepTimeoutSetting' "
Section "Scheduled reboots / updates" "dumpsys alarm 2>/dev/null | grep -iE 'reboot|update|ota|maintenance|com.bedroom' | head -n 40"

Section "System warnings (logged just before each restart)" "dumpsys dropbox --print system_server_wtf 2>/dev/null | grep -vE '^\s*at |^\s*\.\.\.' | tail -n 140"
Section "Which system apps froze or crashed" "dumpsys dropbox --print system_app_anr 2>/dev/null | grep -E '^20[0-9-]+ |^Process:|^Subject:|^Reason:' | tail -n 60; dumpsys dropbox --print system_app_crash 2>/dev/null | grep -vE '^\s*at ' | tail -n 40"
Section "Wi-Fi now" "ip -4 addr show wlan0 2>/dev/null | grep inet; dumpsys wifi 2>/dev/null | grep -m3 -iE 'mWifiInfo|curState'"

Section "CPU now (busiest first)" "top -b -n 1 -m 15 2>/dev/null | head -n 25; echo; dumpsys cpuinfo 2>/dev/null | head -n 20"

"`nDone. Saved to $out"
