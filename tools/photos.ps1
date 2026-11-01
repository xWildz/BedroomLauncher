# Photo frame sync between this PC and the TV.
#   PC folder:  Documents\TV Photo Frame   (put photos here; each subfolder becomes an album, one level deep)
#   TV folder:  /sdcard/Android/data/com.bedroom.launcher/files/frame
#
# Two-way:
#   - new photos on the PC are shrunk to 1920 px (rotation fixed) and sent to the TV
#   - photos sent from the phone app are copied into the PC folder
#   - a photo removed on the phone is moved to "TV Photo Frame\Removed" on the PC
#   - a photo deleted from the PC folder is removed from the TV
# Run:  powershell -ExecutionPolicy Bypass -File tools\photos.ps1 [-Open]

param(
    [switch]$Open,
    [string]$Tv = ""
)

# The TV's address lives in tools\tv-address.txt (kept out of git). The control panel passes it with -Tv.
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
if (-not $Tv) { "Put the TV's address (like 192.168.1.50:5555) in tools\tv-address.txt, or pass -Tv."; exit 1 }

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pc = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "TV Photo Frame"
$tvDir = "/sdcard/Android/data/com.bedroom.launcher/files/frame"
$manifest = Join-Path $pc ".synced.txt"
$exts = @(".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".heic")

New-Item -ItemType Directory -Force -Path $pc | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $pc "Removed") | Out-Null
if ($Open) { Start-Process $pc; return }

# adb prints progress ("1 file pushed ...") on stderr; with "Stop" Windows PowerShell treats that as a failure,
# so run adb with errors relaxed and judge success by the result instead.
function Adb { $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { (& $adb -s $Tv @args 2>&1 | ForEach-Object { "$_" }) } finally { $ErrorActionPreference = $old } }
function Sh([string]$cmd) { ((Adb shell $cmd) -join "`n").Trim() }
function Q([string]$s) { "'" + ($s -replace "'", "'\''") + "'" }
function Base([string]$name) { [IO.Path]::GetFileNameWithoutExtension($name).ToLowerInvariant() }

& $adb connect $Tv 2>&1 | Out-Null
if (((& $adb -s $Tv get-state 2>&1) -join "").Trim() -ne "device") { "Can't reach the TV at $Tv - is it on?"; exit 1 }
Sh "mkdir -p $tvDir" | Out-Null

# What's where. A photo is known by "album/name" (album = subfolder, "" for loose photos), so the same
# file name can live in two albums. Only one level of subfolders is used; "Removed" is never synced.
function Key([string]$rel) {
    $d = [IO.Path]::GetDirectoryName($rel.Replace('/', '\'))
    $b = [IO.Path]::GetFileNameWithoutExtension($rel)
    if ($d) { "$d/$b".ToLowerInvariant() } else { $b.ToLowerInvariant() }
}
$tvFiles = @((Sh "cd $tvDir 2>/dev/null && find . -mindepth 1 -maxdepth 2 -type f ! -name '.*' | sed 's|^\./||'") -split "`n" |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch '(^|/)\.' })
$deleted = @((Sh "cat $tvDir/.deleted 2>/dev/null") -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$pcFiles = @(Get-ChildItem -LiteralPath $pc -File | Where-Object { $exts -contains $_.Extension.ToLowerInvariant() } |
    ForEach-Object { [pscustomobject]@{ Rel = $_.Name; File = $_ } })
foreach ($d in (Get-ChildItem -LiteralPath $pc -Directory | Where-Object { $_.Name -ne "Removed" -and -not $_.Name.StartsWith(".") })) {
    $pcFiles += @(Get-ChildItem -LiteralPath $d.FullName -File | Where-Object { $exts -contains $_.Extension.ToLowerInvariant() } |
        ForEach-Object { [pscustomobject]@{ Rel = "$($d.Name)/$($_.Name)"; File = $_ } })
}
$known = if (Test-Path $manifest) { @(Get-Content $manifest | Where-Object { $_ }) } else { @() }

$tvByKey = @{}; foreach ($n in $tvFiles) { $tvByKey[(Key $n)] = $n }
$pcByKey = @{}; foreach ($f in $pcFiles) { $pcByKey[(Key $f.Rel)] = $f }
$delKeys = @{}; foreach ($n in $deleted) { $delKeys[(Key $n)] = $true }
$knownSet = @{}; foreach ($k in $known) { $knownSet[$k.ToLowerInvariant()] = $true }

$sent = 0; $pulled = 0; $removedPc = 0; $removedTv = 0

# 1. Removed on the phone -> move the PC copy to Removed\
foreach ($k in @($delKeys.Keys)) {
    if ($pcByKey.ContainsKey($k)) {
        $f = $pcByKey[$k].File
        $dest = Join-Path (Join-Path $pc "Removed") $f.Name
        if (Test-Path $dest) { $dest = Join-Path (Join-Path $pc "Removed") ("{0} {1}{2}" -f $f.BaseName, (Get-Date -Format "yyyyMMdd-HHmmss"), $f.Extension) }
        Move-Item -LiteralPath $f.FullName -Destination $dest
        $pcByKey.Remove($k); $removedPc++
        "Moved to Removed: $($f.Name)"
    }
}

# 2. Deleted from the PC folder since last time -> remove from the TV
foreach ($k in @($tvByKey.Keys)) {
    if ($knownSet.ContainsKey($k) -and -not $pcByKey.ContainsKey($k)) {
        Sh ("rm -f " + (Q "$tvDir/$($tvByKey[$k])")) | Out-Null
        $tvByKey.Remove($k); $removedTv++
        "Removed from TV: $k"
    }
}

# 2b. Stray copies: a loose photo on the TV whose twin lives in an album on the PC (left by an older sync that
#     didn't keep album folders). The PC original is kept; only the stray TV copy goes, then it's resent to its album.
$albumBases = @{}; foreach ($k in $pcByKey.Keys) { if ($k.Contains('/')) { $albumBases[$k.Substring($k.LastIndexOf('/') + 1)] = $true } }
$tidied = 0
foreach ($k in @($tvByKey.Keys)) {
    if (-not $k.Contains('/') -and -not $pcByKey.ContainsKey($k) -and $albumBases.ContainsKey($k)) {
        Sh ("rm -f " + (Q "$tvDir/$($tvByKey[$k])")) | Out-Null
        $tvByKey.Remove($k); $tidied++
    }
}
if ($tidied) { "Tidied $tidied stray copies on the TV (they belong in albums)" }

# 3. New on the TV (sent from the phone) -> copy to the PC (into the matching album folder)
foreach ($k in @($tvByKey.Keys)) {
    if (-not $pcByKey.ContainsKey($k)) {
        $rel = $tvByKey[$k]
        $dest = Join-Path $pc ($rel.Replace('/', '\'))
        New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
        Adb pull "$tvDir/$rel" "$dest" | Out-Null
        if (Test-Path -LiteralPath $dest) { $pcByKey[$k] = [pscustomobject]@{ Rel = $rel; File = (Get-Item -LiteralPath $dest) }; $pulled++; "From phone: $rel" }
    }
}

# 4. New on the PC -> shrink + fix rotation, then send
Add-Type -AssemblyName System.Drawing
$jpeg = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
$tmp = Join-Path $env:TEMP "tv-frame"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
foreach ($k in @($pcByKey.Keys)) {
    if ($tvByKey.ContainsKey($k) -or $delKeys.ContainsKey($k)) { continue }
    $f = $pcByKey[$k].File
    $album = [IO.Path]::GetDirectoryName($pcByKey[$k].Rel.Replace('/', '\'))
    if ($f.Extension -ieq ".heic") { "Skipped $($f.Name): HEIC isn't supported - save it as JPG first"; continue }
    try {
        $img = [Drawing.Image]::FromFile($f.FullName)
        try {
            if ($img.PropertyIdList -contains 0x0112) {
                switch ([int]$img.GetPropertyItem(0x0112).Value[0]) {
                    3 { $img.RotateFlip("Rotate180FlipNone") }
                    6 { $img.RotateFlip("Rotate90FlipNone") }
                    8 { $img.RotateFlip("Rotate270FlipNone") }
                }
            }
            $scale = [Math]::Min(1.0, [Math]::Min(1920.0 / $img.Width, 1920.0 / $img.Height))
            $w = [int]($img.Width * $scale); $h = [int]($img.Height * $scale)
            $bmp = New-Object Drawing.Bitmap($w, $h)
            $g = [Drawing.Graphics]::FromImage($bmp)
            $g.InterpolationMode = "HighQualityBicubic"
            $g.DrawImage($img, 0, 0, $w, $h)
            $g.Dispose()
        } finally { $img.Dispose() }
        $out = Join-Path $tmp ($f.BaseName + ".jpg")
        $ep = New-Object Drawing.Imaging.EncoderParameters(1)
        $ep.Param[0] = New-Object Drawing.Imaging.EncoderParameter([Drawing.Imaging.Encoder]::Quality, [long]88)
        $bmp.Save($out, $jpeg, $ep)
        $bmp.Dispose()
        $relTv = if ($album) { "$album/$($f.BaseName).jpg" } else { "$($f.BaseName).jpg" }
        if ($album) { Sh ("mkdir -p " + (Q "$tvDir/$album")) | Out-Null }
        $res = (Adb push "$out" "$tvDir/$relTv") -join " "
        Remove-Item -LiteralPath $out -Force
        if ($res -notmatch "1 file pushed") { throw "push failed: $res" }
        $tvByKey[$k] = $relTv; $sent++
        "Sent: $relTv"
    } catch {
        "Skipped $($f.Name): $($_.Exception.Message)"
    }
}

# Remember what's in sync now (so a PC deletion can be told apart from a new phone photo).
if (Test-Path -LiteralPath $manifest) { (Get-Item -LiteralPath $manifest -Force).Attributes = "Normal" }
[string[]]$synced = @($pcByKey.Keys | Where-Object { $tvByKey.ContainsKey($_) })
[IO.File]::WriteAllLines($manifest, $synced)
(Get-Item -LiteralPath $manifest -Force).Attributes = "Hidden"

"Photo frame synced: $sent sent, $pulled from the phone, $removedPc moved to Removed, $removedTv removed from the TV. ($($tvByKey.Count) on the TV)"
