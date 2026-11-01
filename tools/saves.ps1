# RetroArch save files (game saves + save states) between the TV and this PC.
#   -List                  show the saves on the TV
#   -Backup [-To <folder>] copy every save to the PC (default: Documents\RetroArch Saves\<date-time>)
#   -Push <files>          send save files (or a backup .zip) to the TV
#       -StopRetroArch     close RetroArch first if it's open (unsaved game progress is lost)
#   -ListReplaced          UNDO list: old copies kept when a send replaced a save
#   -Restore <file>        UNDO: put one of those old copies back (the current one is kept too)
# A save with the same name already on the TV is replaced in place; the old copy is kept in
# RetroArch's files\_replaced folder.
# Examples:
#   powershell -ExecutionPolicy Bypass -File tools\saves.ps1 -Backup
#   powershell -ExecutionPolicy Bypass -File tools\saves.ps1 -Push "C:\Users\you\Desktop\My Game.srm"

param(
    [switch]$List,
    [switch]$Backup,
    [string]$To,
    [string[]]$Push,
    [switch]$StopRetroArch,
    [switch]$ListReplaced,
    [string]$Restore,
    [string]$Tv = "",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$More
)
# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

if ($More) { $Push = @($Push) + $More }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$ra = "com.retroarch"
$root = "/sdcard/Android/data/$ra/files"
$oldRoot = "/sdcard/RetroArch"
$saveExt = @("srm", "sav", "rtc", "dsv", "mcr", "mcd", "eep", "fla", "sra", "mpk", "nv", "ldci")
$stateRe = '\.state(\d+|\.auto)?$'

function IsSave([string]$name) {
    $ext = [IO.Path]::GetExtension($name).TrimStart('.').ToLower()
    return ($saveExt -contains $ext) -or ($name -match $stateRe)
}

function Q([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }

function Sh([string]$cmd) {
    return ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim()
}

function Get-TvSaves {
    $names = (($saveExt | ForEach-Object { "-iname '*.$_'" }) -join " -o ") + " -o -iname '*.state' -o -iname '*.state[0-9]*' -o -iname '*.state.auto'"
    $cmd = "for d in $root $oldRoot; do [ -d `$d ] && find `$d -type f \( $names \) ! -path '*/_replaced/*' ! -iname '*.png' -exec stat -c '%s|%Y|%n' {} + ; done 2>/dev/null"
    $out = Sh $cmd
    $list = @()
    foreach ($line in ($out -split "`n")) {
        $p = $line.Trim().Split('|', 3)
        if ($p.Count -lt 3) { continue }
        $path = $p[2]
        $rel = if ($path.StartsWith("$root/")) { $path.Substring($root.Length + 1) }
               elseif ($path.StartsWith("$oldRoot/")) { "RetroArch/" + $path.Substring($oldRoot.Length + 1) }
               else { [IO.Path]::GetFileName($path) }
        $list += [pscustomobject]@{
            Path     = $path
            Rel      = $rel
            Name     = $path.Substring($path.LastIndexOf('/') + 1)
            Size     = [long]$p[0]
            Modified = [DateTimeOffset]::FromUnixTimeSeconds([long]$p[1]).LocalDateTime
            State    = ($path -match $stateRe)
        }
    }
    return $list | Sort-Object Modified -Descending
}

& $adb connect $Tv | Out-Null

if ($List) {
    $s = @(Get-TvSaves)
    if (-not $s) { Write-Host "No RetroArch saves on the TV yet (play a game and save in it first)." }
    else {
        Write-Host "$($s.Count) save file(s) on the TV:"
        foreach ($f in $s) {
            $kind = if ($f.State) { "state" } else { "save " }
            Write-Host ("  [{0}] {1,-44} {2,8:N0} KB  {3:yyyy-MM-dd HH:mm}" -f $kind, $f.Rel, [math]::Ceiling($f.Size / 1KB), $f.Modified)
        }
    }
}

if ($Backup) {
    if (-not $To) { $To = Join-Path ([Environment]::GetFolderPath("MyDocuments")) ("RetroArch Saves\" + (Get-Date -Format "yyyy-MM-dd_HHmm")) }
    $s = @(Get-TvSaves)
    if (-not $s) { Write-Host "No RetroArch saves on the TV to back up." }
    else {
        New-Item -ItemType Directory -Force -Path $To | Out-Null
        foreach ($f in $s) {
            $dest = Join-Path $To ($f.Rel -replace '/', '\')
            New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
            & $adb -s $Tv pull $f.Path $dest 2>&1 | Out-Null
        }
        Write-Host "Backed up $($s.Count) save file(s) to:"
        Write-Host "  $To"
    }
}

if ($Push) {
    # Collect the files to send (unpacking any .zip, e.g. a backup made by the phone app).
    $items = @()
    $tmp = Join-Path $env:TEMP ("ra-saves-" + [guid]::NewGuid().ToString("N"))
    foreach ($f in $Push) {
        if (-not (Test-Path -LiteralPath $f)) { Write-Host "Not found: $f"; continue }
        if ([IO.Path]::GetExtension($f).ToLower() -eq ".zip") {
            $dir = Join-Path $tmp ([IO.Path]::GetFileNameWithoutExtension($f))
            Expand-Archive -LiteralPath $f -DestinationPath $dir -Force
            Get-ChildItem -LiteralPath $dir -Recurse -File | Where-Object { IsSave $_.Name } | ForEach-Object { $items += $_.FullName }
        } elseif (IsSave ([IO.Path]::GetFileName($f))) {
            $items += (Resolve-Path -LiteralPath $f).Path
        } else {
            Write-Host "Skipped $([IO.Path]::GetFileName($f)): not a RetroArch save file (.srm, .sav, .state, ...)."
        }
    }
    if (-not $items) { Write-Host "Nothing to send."; return }

    if (Sh "pidof $ra") {
        if (-not $StopRetroArch) {
            Write-Host "RetroArch is open on the TV. Quit it first (it would overwrite the saves when it closes),"
            Write-Host "or run again with -StopRetroArch to close it (unsaved game progress is lost)."
            return
        }
        Sh "am force-stop $ra" | Out-Null
        Write-Host "Closed RetroArch."
    }

    $existing = @(Get-TvSaves)
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    foreach ($local in $items) {
        $name = [IO.Path]::GetFileName($local)
        $state = $name -match $stateRe
        $match = $existing | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if ($match) {
            $target = $match.Path
            Sh ("mkdir -p $root/_replaced && cp " + (Q $match.Path) + " " + (Q "$root/_replaced/$stamp-$name")) | Out-Null
            $what = "replaced"
        } else {
            $same = $existing | Where-Object { $_.State -eq $state -and $_.Path.StartsWith("$root/") } | Select-Object -First 1
            $dir = if ($same) { $same.Path.Substring(0, $same.Path.LastIndexOf('/')) } elseif ($state) { "$root/states" } else { "$root/saves" }
            $target = "$dir/$name"
            $what = "added"
        }
        Sh ("mkdir -p " + (Q ($target.Substring(0, $target.LastIndexOf('/'))))) | Out-Null
        $r = (& $adb -s $Tv push $local $target 2>&1 | ForEach-Object { "$_" }) -join " "
        if ($LASTEXITCODE -eq 0) { Write-Host "  $name -> $what $($target.Substring($root.Length + 1))" }
        else { Write-Host "  $name -> failed: $r" }
    }
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Write-Host "Done. Old copies of replaced saves are in RetroArch's files\_replaced folder on the TV."
}

if ($ListReplaced) {
    $l = (Sh "ls -1t $root/_replaced 2>/dev/null") -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    if (-not $l) { Write-Host "No replaced saves - nothing to undo." }
    else { $l | ForEach-Object { Write-Host "REPLACED|$_" } }
}

if ($Restore) {
    $file = [IO.Path]::GetFileName($Restore)
    if ($file -notmatch '^\d{8}-\d{6}-(.+)$') { Write-Host "Not a replaced-save name: $file"; return }
    $name = $Matches[1]
    if (Sh "pidof $ra") {
        if (-not $StopRetroArch) { Write-Host "RetroArch is open on the TV - quit it first (or add -StopRetroArch)."; return }
        Sh "am force-stop $ra" | Out-Null
    }
    $existing = @(Get-TvSaves) | Where-Object { $_.Name -eq $name } | Select-Object -First 1
    $state = $name -match $stateRe
    $target = if ($existing) { $existing.Path } elseif ($state) { "$root/states/$name" } else { "$root/saves/$name" }
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    if ($existing) { Sh ("cp " + (Q $existing.Path) + " " + (Q "$root/_replaced/$stamp-$name")) | Out-Null }
    Sh ("mkdir -p " + (Q ($target.Substring(0, $target.LastIndexOf('/')))) + " && cp " + (Q "$root/_replaced/$file") + " " + (Q $target)) | Out-Null
    Write-Host "Restored $name from $file (the save it replaced is kept in _replaced too)."
}
