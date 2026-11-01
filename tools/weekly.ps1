# Weekly job (Windows Task Scheduler, set up from the panel's Bedroom page):
#   1. back up RetroArch saves to Documents\RetroArch Saves\Weekly\<date>  (keeps the newest 8 weekly ones;
#      your own backups elsewhere are never touched)
#   2. sync the photo frame (Documents\TV Photo Frame <-> TV)
#   -Install / -Uninstall   add or remove the scheduled task (Sundays 3:12 PM; runs later if the PC was off)
# Log: Documents\BedroomLauncher\weekly-log.txt

param(
    [switch]$Install,
    [switch]$Uninstall,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$task = "Bedroom TV weekly"
if ($Install) {
    $act = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Tv $Tv"
    $trig = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Sunday -At "3:12PM"
    $set = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 1)
    Register-ScheduledTask -TaskName $task -Action $act -Trigger $trig -Settings $set -Description "Backs up RetroArch saves from the TV and syncs the photo frame." -Force | Out-Null
    "Weekly backup + photo sync is on (Sundays 3:12 PM; if the PC is off then, it runs next time it's on)."
    return
}
if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
    "Weekly backup + photo sync is off. Existing backups are kept."
    return
}

$log = Join-Path (Split-Path $PSScriptRoot -Parent) "weekly-log.txt"
function Log([string]$s) { "[$(Get-Date -Format 'yyyy-MM-dd HH:mm')] $s" | Add-Content -LiteralPath $log; $s }

Log "Weekly run started"
$weekly = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "RetroArch Saves\Weekly"
$dest = Join-Path $weekly (Get-Date -Format "yyyy-MM-dd_HHmm")
$out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "saves.ps1") -Backup -To $dest -Tv $Tv 2>&1 | ForEach-Object { "$_" }
$out | Select-Object -Last 3 | ForEach-Object { Log "saves: $_" }

# Keep the newest 8 weekly backups (only inside the Weekly folder).
if (Test-Path $weekly) {
    $old = Get-ChildItem -LiteralPath $weekly -Directory | Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}_\d{4}$' } |
        Sort-Object Name -Descending | Select-Object -Skip 8
    foreach ($d in $old) { Remove-Item -LiteralPath $d.FullName -Recurse -Force; Log "Pruned old backup $($d.Name)" }
}

$out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "photos.ps1") -Tv $Tv 2>&1 | ForEach-Object { "$_" }
$out | Select-Object -Last 1 | ForEach-Object { Log "photos: $_" }
Log "Weekly run done"
