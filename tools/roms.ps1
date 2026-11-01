# RetroArch ROM folder on the TV.
#   -Setup            create /sdcard/ROMs/<system> folders, let RetroArch read them, make it RetroArch's start folder
#   -Push <files>     copy ROM files from the PC into the right system folder (sorted by file type)
#   -List             show what's on the TV and how much space is left
#   -RestoreConfig    UNDO: put back RetroArch's settings from before -Setup (retroarch.cfg.bak)
# Examples:
#   powershell -ExecutionPolicy Bypass -File tools\roms.ps1 -Setup
#   powershell -ExecutionPolicy Bypass -File tools\roms.ps1 -Push "C:\Users\you\Downloads\game.gba"
# Use games you own.

param(
    [switch]$Setup,
    [string[]]$Push,
    [switch]$List,
    [switch]$RestoreConfig,
    [string]$Tv = "",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$More   # extra files after -Push a.gba b.gba
)
# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

if ($More) { $Push = @($Push) + $More }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
# The Play Store RetroArch can only read its OWN folder (it never asks for storage access),
# so the ROMs live inside it. Note: this folder is deleted if RetroArch is uninstalled.
$base = "/sdcard/Android/data/com.retroarch/files/ROMs"
$baseForRetroArch = "/storage/emulated/0/Android/data/com.retroarch/files/ROMs"
$oldBase = "/sdcard/ROMs"   # first attempt; RetroArch can't see it
$ra = "com.retroarch"

# File type -> folder (RetroArch picks the core when you load the game).
$map = [ordered]@{
    "NES"     = @(".nes", ".fds", ".unf")
    "SNES"    = @(".sfc", ".smc", ".fig", ".swc")
    "N64"     = @(".n64", ".z64", ".v64")
    "GB"      = @(".gb")
    "GBC"     = @(".gbc")
    "GBA"     = @(".gba")
    "NDS"     = @(".nds")
    "Genesis" = @(".md", ".gen", ".smd", ".32x")
    "Master System" = @(".sms", ".gg")
    "PS1"     = @(".cue", ".chd", ".pbp", ".m3u")
    "Atari"   = @(".a26", ".a78", ".lnx")
    "Patches" = @(".ips", ".bps", ".ups")
}

function Sh([string]$cmd) {
    $o = ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim()
    if ($o) { Write-Host $o }
    return $o
}

function FolderFor([string]$path) {
    $ext = [IO.Path]::GetExtension($path).ToLower()
    if ($ext -eq ".zip") {
        # Look inside the zip to see what system it's for.
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $z = [IO.Compression.ZipFile]::OpenRead($path)
            $inner = $z.Entries | ForEach-Object { [IO.Path]::GetExtension($_.FullName).ToLower() } | Where-Object { $_ }
            $z.Dispose()
            foreach ($k in $map.Keys) { if ($inner | Where-Object { $map[$k] -contains $_ }) { return $k } }
        } catch {}
        return "Other"
    }
    foreach ($k in $map.Keys) { if ($map[$k] -contains $ext) { return $k } }
    return "Other"
}

& $adb connect $Tv | Out-Null

if ($Setup) {
    Write-Host "Creating $base and system folders..."
    $folders = @($map.Keys) + @("Other")
    $mk = ($folders | ForEach-Object { "mkdir -p '$base/$_'" }) -join "; "
    Sh $mk | Out-Null
    # Bring over anything sent to the old, invisible location (copy only; the old folder is left alone).
    $old = Sh "ls -A $oldBase 2>/dev/null"
    if ($old) {
        Write-Host "Copying games from the old ROMs folder into RetroArch's folder..."
        Sh "cp -r $oldBase/. '$base/'" | Out-Null
    }

    # Point RetroArch's file browser at the ROMs folder (only while RetroArch is closed, so it doesn't overwrite it).
    $cfg = "/storage/emulated/0/Android/data/$ra/files/retroarch.cfg"
    $exists = Sh "ls $cfg 2>/dev/null"
    if ($exists) {
        Sh "am force-stop $ra" | Out-Null
        Sh "cp $cfg $cfg.bak" | Out-Null
        $tmp = Join-Path $env:TEMP "retroarch.cfg"
        & $adb -s $Tv pull $cfg $tmp | Out-Null
        $line = 'rgui_browser_directory = "' + $baseForRetroArch + '"'
        $lines = @(Get-Content -LiteralPath $tmp)
        if ($lines -match '^rgui_browser_directory') {
            $lines = $lines | ForEach-Object { if ($_ -match '^rgui_browser_directory') { $line } else { $_ } }
        } else {
            $lines += $line
        }
        [IO.File]::WriteAllText($tmp, (($lines -join "`n") + "`n"))   # UTF-8, no BOM
        & $adb -s $Tv push $tmp $cfg | Out-Null
        Write-Host "RetroArch will open in the ROMs folder (its old settings are saved as retroarch.cfg.bak)."
    } else {
        Write-Host "Open RetroArch once, then run -Setup again to make ROMs its start folder."
        Write-Host "(Or in RetroArch: Settings > Directory > File Browser > Android/data/com.retroarch/files/ROMs)"
    }
    Sh "ls $base" | Out-Null
    Write-Host "Done."
}

if ($RestoreConfig) {
    $cfg = "/storage/emulated/0/Android/data/$ra/files/retroarch.cfg"
    if (-not (Sh "ls $cfg.bak 2>/dev/null")) { Write-Host "No backup found (retroarch.cfg.bak) - nothing to restore." }
    else {
        Sh "am force-stop $ra" | Out-Null
        Sh "cp $cfg $cfg.before-restore && cp $cfg.bak $cfg" | Out-Null
        Write-Host "RetroArch settings restored from before ROM setup (current ones kept as retroarch.cfg.before-restore)."
    }
}

if ($Push) {
    foreach ($f in $Push) {
        if (-not (Test-Path -LiteralPath $f)) { Write-Host "Not found: $f"; continue }
        $ext = [IO.Path]::GetExtension($f).ToLower()
        if ($ext -in @(".rar", ".7z")) {
            Write-Host "Skipped $([IO.Path]::GetFileName($f)): unzip it on the PC first (RetroArch can't open .rar)."
            continue
        }
        $folder = FolderFor $f
        $name = [IO.Path]::GetFileName($f)
        Write-Host "Sending $name -> ROMs/$folder ..."
        & $adb -s $Tv shell "mkdir -p '$base/$folder'" | Out-Null
        & $adb -s $Tv push $f "$base/$folder/$name" 2>&1 | ForEach-Object { "$_" } | Select-Object -Last 1 | Write-Host
    }
}

if ($List -or $Push) {
    Write-Host "`nOn the TV (ROMs folder):"
    $dirs = (& $adb -s $Tv shell "ls -1 $base 2>/dev/null" | ForEach-Object { "$_".Trim() }) | Where-Object { $_ }
    foreach ($d in $dirs) {
        $n = [int]((& $adb -s $Tv shell "ls -1 '$base/$d' 2>/dev/null | wc -l" | Select-Object -First 1) -as [int])
        if ($n -gt 0) { Write-Host ("  {0}: {1} file(s)" -f $d, $n) }
    }
    $df = (& $adb -s $Tv shell "df -h /data | tail -1" | Select-Object -First 1)
    $free = ("$df" -split '\s+')[3]
    Write-Host "  Free space on the TV: $free"
}
