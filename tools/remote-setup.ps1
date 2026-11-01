# Sleep timer + phone remote setup.
#   -Enable   let Bedroom Home turn the TV off when the sleep timer ends (Device Admin "lock screen" only)
#   -Disable  take that permission away again (the timer then just pauses playback and goes Home)
#   -Link     print the phone-remote link (same as the QR code on the TV's Phone screen)
# Usage: powershell -ExecutionPolicy Bypass -File tools\remote-setup.ps1 -Enable

param(
    [switch]$Enable,
    [switch]$Disable,
    [switch]$Link,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.bedroom.launcher"
$admin = "$pkg/.SleepAdmin"

function Sh([string]$cmd) {
    Write-Host "> adb shell $cmd"
    $o = ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim()
    if ($o) { Write-Host $o }
    return $o
}

& $adb connect $Tv | Out-Null

if ($Enable) {
    $feature = Sh "pm list features | grep -i device_admin"
    if (-not $feature) { Write-Host "This TV doesn't support Device Admin; the timer will pause + go Home instead."; exit 0 }
    Sh "dpm set-active-admin --user 0 $admin" | Out-Null
    $active = Sh "dumpsys device_policy | grep -i $pkg"
    if ($active) { Write-Host "OK: the sleep timer can now turn the TV off." } else { Write-Host "Couldn't activate - see output above." }
    Write-Host "UNDO: tools\remote-setup.ps1 -Disable"
}

if ($Disable) {
    Sh "am broadcast -a com.bedroom.launcher.REMOVE_ADMIN -n $pkg/.AdminControlReceiver" | Out-Null
    Start-Sleep -Seconds 1
    $still = Sh "dumpsys device_policy | grep -i $pkg"
    if ($still) { Write-Host "Still active - see output above." } else { Write-Host "Removed." }
}

if ($Link -or $Enable) {
    $xml = Sh "run-as $pkg cat shared_prefs/remote.xml"
    $key = [regex]::Match($xml, 'name="key">([a-z0-9]+)<').Groups[1].Value
    $ip = $Tv.Split(':')[0]
    if ($key) { Write-Host "`nPhone pairing link (open on the phone): http://${ip}:8765/#k=$key`n" } else { Write-Host "Open Bedroom Home once, then run -Link again." }
}
