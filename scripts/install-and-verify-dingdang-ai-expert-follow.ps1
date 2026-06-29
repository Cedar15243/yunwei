param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangexpert.follow",
  [string[]]$RequiredCoexistPackages = @(
    "com.codex.air3nativecamera.dingdangexpert",
    "com.codex.air3nativecamera.dingdangmanager",
    "com.codex.air3nativecamera.dingdangmanager.butler"
  ),
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$ApkPath = "air3-native-camera-test\build\DingdangAiOpsExpertFollow.apk",
  [int]$ExpectedVersionCode = 626,
  [string]$ExpectedVersionName = "6.2.6-chat-follow-new-app",
  [string]$ExpectedLabel = (-join @(
    [char]0x53EE, [char]0x5F53, "AI",
    [char]0x8FD0, [char]0x7EF4,
    [char]0x4E13, [char]0x5BB6
  )),
  [int]$WaitSeconds = 120,
  [string]$EvidencePrefix = "tmp\dingdang-ai-ops-expert-follow-626"
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$adb = Join-Path $root "tmp\tools\platform-tools\adb.exe"
$apk = Join-Path $root $ApkPath
$aapt2 = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\34.0.0\aapt2.exe"

if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
if (-not (Test-Path -LiteralPath $aapt2)) { throw "aapt2 not found: $aapt2" }

New-Item -ItemType Directory -Force -Path (Join-Path $root "tmp") | Out-Null

$badging = (& $aapt2 dump badging $apk) -join "`n"
if ($badging -notmatch [regex]::Escape("package: name='$Package'")) { throw "expert follow package id mismatch in APK badging" }
if ($badging -notmatch "versionCode='$ExpectedVersionCode'") { throw "expert follow versionCode mismatch in APK badging" }
if ($badging -notmatch [regex]::Escape("versionName='$ExpectedVersionName'")) { throw "expert follow versionName mismatch in APK badging" }
if ($badging -notmatch [regex]::Escape("application-label:'$ExpectedLabel'")) { throw "expert follow app label mismatch in APK badging" }

Write-Output "Waiting for Air3 device serial=$Serial timeout=${WaitSeconds}s"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$deviceFound = $false
while ((Get-Date) -lt $deadline) {
  $devices = & $adb devices -l
  if ($devices -match [regex]::Escape($Serial)) {
    $deviceFound = $true
    break
  }
  Start-Sleep -Seconds 3
}
if (-not $deviceFound) {
  & $adb devices -l
  throw "Air3 device not found by adb: $Serial"
}

Write-Output "Installing $apk without uninstalling existing Dingdang packages"
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
& $adb -s $Serial shell pm grant $Package android.permission.CAMERA 2>$null
& $adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null

$packages = (& $adb -s $Serial shell pm list packages com.codex.air3nativecamera) -join "`n"
foreach ($requiredPackage in @($RequiredCoexistPackages + $Package)) {
  if ($packages -notmatch [regex]::Escape("package:$requiredPackage")) {
    throw "Expected coexist package missing after expert follow install: $requiredPackage"
  }
}

$dump = (& $adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($dump -notmatch "versionCode=$ExpectedVersionCode") { throw "Expert follow package versionCode mismatch; expected $ExpectedVersionCode" }
if ($dump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") { throw "Expert follow package versionName mismatch; expected $ExpectedVersionName" }

$resolved = (& $adb -s $Serial shell cmd package resolve-activity --brief $Package) -join "`n"
if ($resolved -notmatch [regex]::Escape("$Package/$Activity")) { throw "Expert follow package activity did not resolve: $resolved" }

Write-Output "Launching $Package/$Activity"
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial shell am start --display 0 -n "$Package/$Activity" | Out-Null
Start-Sleep -Seconds 3

$uiPath = Join-Path $root "$EvidencePrefix-ui.xml"
$pngPath = Join-Path $root "$EvidencePrefix-ui.png"
& $adb -s $Serial exec-out uiautomator dump /dev/tty > $uiPath
& $adb -s $Serial exec-out screencap -p > $pngPath

$activityDump = (& $adb -s $Serial shell dumpsys activity activities) -join "`n"
if ($activityDump -notmatch [regex]::Escape("$Package/$Activity")) {
  throw "Expert follow package not found in activity stack after launch"
}

Write-Output "Installed and verified $Package $ExpectedVersionCode/$ExpectedVersionName label=$ExpectedLabel"
foreach ($coexistPackage in $RequiredCoexistPackages) {
  Write-Output "Coexist package still present: $coexistPackage"
}
Write-Output "Evidence:"
Write-Output "  $uiPath"
Write-Output "  $pngPath"
