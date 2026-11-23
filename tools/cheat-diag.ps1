# READ-ONLY: how RetroArch on the TV is set up for cheats. Writes cheat-diag.txt.
param([string]$Tv = "")
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "cheat-diag.txt"
function Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }
function Section([string]$t, [string]$c) { "`n===== $t =====`n" + (Sh $c) | Add-Content -LiteralPath $out -Encoding UTF8 }
& $adb connect $Tv 2>&1 | Out-Null
$b = "/sdcard/Android/data/com.retroarch/files"
"Cheat check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -LiteralPath $out -Encoding UTF8
Section "RetroArch cheat settings" "grep -iE 'cheat|core_assets|libretro_info_path|libretro_directory' $b/retroarch.cfg"
Section "Cheat folders" "ls -la $b/cheats 2>&1 | head -n 30; find $b -maxdepth 3 -iname '*.cht' 2>/dev/null | head -n 20"
Section "History (core names)" "grep -E 'core_name|core_path|\""path\""' $b/playlists/builtin/content_history.lpl 2>/dev/null | head -n 20"
Section "Info files" "ls $b/info 2>&1 | head -n 10; ls /data/data/com.retroarch/info 2>&1 | head -n 5"
Section "Cores" "ls $b/cores 2>&1 | head -n 20; ls /data/data/com.retroarch/cores 2>&1 | head"
Section "One info file" "for f in $b/info/*snes9x*.info $b/info/mgba*.info; do [ -f `$f ] && grep -E '^(corename|display_name|systemname) ' `$f; done 2>/dev/null"
"`nDone." | Add-Content -LiteralPath $out -Encoding UTF8
