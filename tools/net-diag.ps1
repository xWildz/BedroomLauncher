# READ-ONLY network check of the TV (VPN, DNS, internet, Bedroom Home's Wi-Fi repair). Writes net-diag.txt.
param([string]$Tv = "")
if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "net-diag.txt"
function Sh([string]$cmd) { ((& $adb -s $Tv shell $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n") }
function Section([string]$t, [string]$c) { "`n===== $t =====`n" + (Sh $c) | Add-Content -LiteralPath $out -Encoding UTF8 }
& $adb connect $Tv 2>&1 | Out-Null
"Network check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -LiteralPath $out -Encoding UTF8
Section "On screen" "dumpsys activity activities | grep -m1 mResumedActivity"
Section "Networks Android sees" "dumpsys connectivity | grep -E 'NetworkAgentInfo|Active default network|VALIDATED|PARTIAL|CAPTIVE' | head -n 20"
Section "VPN" "dumpsys connectivity | grep -iE 'vpn|tun0|Tailscale' | head -n 15; ip -4 addr show tun0 2>&1 | head -n 3"
Section "DNS" "getprop net.dns1; getprop net.dns2; dumpsys connectivity | grep -iE 'DnsAddresses|dns' | head -n 10"
Section "Internet test (ping)" "ping -c 2 -W 3 8.8.8.8 2>&1 | tail -n 3"
Section "Name lookup test" "ping -c 1 -W 3 www.youtube.com 2>&1 | head -n 2"
Section "Wi-Fi" "dumpsys wifi | grep -m 3 -E 'mWifiInfo|curState|mNetworkInfo'"
Section "Bedroom Home Wi-Fi repair / events" "logcat -d -t 3000 2>/dev/null | grep -iE 'Wi-Fi down|restarting Wi-Fi|Wi-Fi dropped|Event:' | tail -n 20"
Section "Recent Wi-Fi switch offs" "logcat -d -t 3000 2>/dev/null | grep -iE 'setWifiEnabled|svc wifi' | tail -n 10"
"`nDone." | Add-Content -LiteralPath $out -Encoding UTF8
