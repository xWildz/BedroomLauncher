# One-click "update everything": builds release versions of the TV app and the phone app, installs the
# TV app on the TV over the current one (same signing key, so nothing is lost), and puts the phone APK in
# releases\. Writes everything it does to update-log.txt (not tracked by git).
#   powershell -ExecutionPolicy Bypass -File tools\update.ps1            (both apps)
#   powershell -ExecutionPolicy Bypass -File tools\update.ps1 -Only tv   (or -Only phone)
#   -NoInstall builds without touching the TV.

param([ValidateSet("all", "tv", "phone")][string]$Only = "all", [switch]$NoInstall, [string]$Tv = "")

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$log = Join-Path $root "update-log.txt"
"Update $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  (-Only $Only)" | Set-Content -LiteralPath $log -Encoding UTF8
function Log([string]$s) { $s | Add-Content -LiteralPath $log -Encoding UTF8; Write-Host $s }
function Fail([string]$s) { Log "FAILED: $s"; Log "RESULT: FAILED"; exit 1 }

# ---- Java: the same one Android Studio uses for this project (.gradle\config.properties), then fallbacks.
# Gradle 8.11 can't run on very new Java (Studio's own jbr may be newer), so a JDK 17-21 is preferred.
$studioJdk = $null
$cfg = Join-Path $root ".gradle\config.properties"
if (Test-Path $cfg) {
    $m = [regex]::Match((Get-Content $cfg -Raw), "java\.home=(.+)")
    if ($m.Success) { $studioJdk = $m.Groups[1].Value.Trim() -replace '\\:', ':' -replace '\\\\', '\' }
}
$userJdks = Get-ChildItem (Join-Path $env:USERPROFILE ".jdks") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '(17|21)\.' } | Sort-Object Name -Descending | ForEach-Object { $_.FullName }
$jdk = @($studioJdk) + @($userJdks) + @($env:JAVA_HOME, "C:\Program Files\Android\Android Studio\jbr") |
    Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } | Select-Object -First 1
if (-not $jdk) { Fail "Couldn't find Java (Android Studio's jbr folder)." }
$env:JAVA_HOME = $jdk
Log "Java:   $jdk"

$wrapper = Get-Content (Join-Path $root "gradle\wrapper\gradle-wrapper.properties") -Raw
$ver = [regex]::Match($wrapper, "gradle-([0-9.]+)-(bin|all)\.zip").Groups[1].Value
$gHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
$gradle = Get-ChildItem -Path (Join-Path $gHome "wrapper\dists") -Filter "gradle.bat" -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like "*gradle-$ver*" } | Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { Fail "Couldn't find Gradle $ver. Open the project in Android Studio once so it downloads it." }
Log "Gradle: $gradle"

# ---- Build
$tasks = switch ($Only) { "tv" { @(":app:assembleRelease") } "phone" { @(":phone:assembleRelease") } default { @(":app:assembleRelease", ":phone:assembleRelease") } }
Log "`nBuilding $($tasks -join ', ') ..."
Push-Location $root
$t0 = Get-Date
& $gradle @tasks --console=plain --warning-mode=none 2>&1 | ForEach-Object {
    $l = "$_"
    if ($l -eq "System.Management.Automation.RemoteException" -or $l -match "^WARNING: .*(restricted method|System::load|native access|Restricted methods)") { return }
    if ($l -match "^e: |error:|FAILURE|BUILD (SUCCESSFUL|FAILED)|What went wrong|^> ") { Log $l } else { $l | Add-Content -LiteralPath $log -Encoding UTF8 }
}
$code = $LASTEXITCODE
Pop-Location
Log ("Build took {0:n0} s" -f ((Get-Date) - $t0).TotalSeconds)
if ($code -ne 0) { Fail "Build failed (details above)." }

function Version([string]$gradleFile) {
    $g = Get-Content $gradleFile -Raw
    $vc = [regex]::Match($g, "versionCode\s*=\s*(\d+)").Groups[1].Value
    $vn = [regex]::Match($g, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    return @($vc, $vn)
}

# ---- Phone APK -> releases\
if ($Only -ne "tv") {
    $apk = Join-Path $root "phone\build\outputs\apk\release\phone-release.apk"
    if (-not (Test-Path $apk)) { Fail "Phone APK missing: $apk" }
    $vc, $vn = Version (Join-Path $root "phone\build.gradle.kts")
    $rel = Join-Path $root "releases"
    New-Item -ItemType Directory -Force -Path $rel | Out-Null
    $name = "TV-Remote-" + ($vn -replace '[^A-Za-z0-9.]+', '-') + ".apk"
    Copy-Item $apk (Join-Path $rel $name) -Force
    Log "`nPhone app $vn (code $vc): releases\$name ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB)"
}

# ---- TV: install over the current version
if ($Only -ne "phone") {
    $apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { Fail "TV APK missing: $apk" }
    $vc, $vn = Version (Join-Path $root "app\build.gradle.kts")
    Log "`nTV app $vn (code $vc) built."
    if ($NoInstall) { Log "Not installing (-NoInstall)." }
    else {
        if (-not $Tv) { $tvFile = Join-Path $PSScriptRoot "tv-address.txt"; if (Test-Path $tvFile) { $Tv = (Get-Content $tvFile -TotalCount 1).Trim() } }
        if (-not $Tv) { Fail "No TV address in tools\tv-address.txt" }
        $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        & $adb connect $Tv 2>&1 | Out-Null
        $front = (& $adb -s $Tv shell "dumpsys activity activities | grep -m1 mResumedActivity" 2>&1) -join ""
        Log "Installing on the TV ..."
        $out = (& $adb -s $Tv install -r $apk 2>&1) -join " "
        Log "  $out"
        if ($out -notmatch "Success") { Fail "Install on the TV failed." }
        # Only bring the home screen back if it was the thing on screen (never interrupt YouTube or anything else).
        if ($front -match "com\.bedroom\.launcher") {
            & $adb -s $Tv shell "am start -n com.bedroom.launcher/.MainActivity" 2>&1 | Out-Null
        }
        $now = (& $adb -s $Tv shell "dumpsys package com.bedroom.launcher | grep -m1 versionName" 2>&1) -join ""
        Log "  TV now has: $($now.Trim())"
    }
}

Log "`nRESULT: OK"
