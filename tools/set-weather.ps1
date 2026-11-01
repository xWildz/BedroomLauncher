# Sets the city (or ZIP) the launcher's weather uses. No rebuild needed.
# Usage: powershell -ExecutionPolicy Bypass -File tools\set-weather.ps1 -Place "Springfield, IL"

param(
    [Parameter(Mandatory = $true)][string]$Place,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.bedroom.launcher"
$dir = "/sdcard/Android/data/$pkg/files"

$json = @{ weatherPlace = $Place } | ConvertTo-Json -Compress
$tmp = Join-Path $env:TEMP "bedroom-launcher-config.json"
[IO.File]::WriteAllText($tmp, $json)   # UTF-8 without BOM

& $adb connect $Tv | Out-Null
& $adb -s $Tv shell mkdir -p $dir
& $adb -s $Tv push $tmp "$dir/config.json"
# Restart the launcher so it picks up the new city right away.
& $adb -s $Tv shell am force-stop $pkg
& $adb -s $Tv shell am start -n "$pkg/.MainActivity"
Write-Host "Weather set to '$Place'."
