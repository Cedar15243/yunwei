param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangmanager",
  [string[]]$CoexistPackages = @(
    "com.codex.air3nativecamera.dingdangexpert"
  ),
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$ApkPath = "air3-native-camera-test\build\DingdangAiOpsManager.apk",
  [int]$ExpectedVersionCode = 711,
  [string]$ExpectedVersionName = "7.1.1-voice-commands",
  [string]$ExpectedLabel = (-join @(
    [char]0x53EE, [char]0x5F53, "AI",
    [char]0x8FD0, [char]0x7EF4,
    [char]0x7BA1, [char]0x5BB6
  )),
  [int]$WaitSeconds = 120,
  [string]$EvidencePrefix = "tmp\dingdang-ai-ops-manager-711"
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

if (-not (Test-Path -LiteralPath $adb)) {
  throw "adb not found: $adb"
}
if (-not (Test-Path -LiteralPath $apk)) {
  throw "APK not found: $apk"
}
if (-not (Test-Path -LiteralPath $aapt2)) {
  throw "aapt2 not found: $aapt2"
}

New-Item -ItemType Directory -Force -Path (Join-Path $root "tmp") | Out-Null

$badging = (& $aapt2 dump badging $apk) -join "`n"
if ($badging -notmatch [regex]::Escape("package: name='$Package'")) {
  throw "manager package id mismatch in APK badging"
}
if ($badging -notmatch "versionCode='$ExpectedVersionCode'") {
  throw "manager versionCode mismatch in APK badging"
}
if ($badging -notmatch [regex]::Escape("versionName='$ExpectedVersionName'")) {
  throw "manager versionName mismatch in APK badging"
}
if ($badging -notmatch [regex]::Escape("application-label:'$ExpectedLabel'")) {
  throw "manager app label mismatch in APK badging"
}

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
if ($LASTEXITCODE -ne 0) {
  throw "adb install failed"
}
& $adb -s $Serial shell pm grant $Package android.permission.CAMERA 2>$null
& $adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null

$packages = (& $adb -s $Serial shell pm list packages com.codex.air3nativecamera) -join "`n"
foreach ($requiredPackage in @($CoexistPackages + $Package)) {
  if ($packages -notmatch [regex]::Escape("package:$requiredPackage")) {
    throw "Expected coexist package missing after manager install: $requiredPackage"
  }
}

$dump = (& $adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($dump -notmatch "versionCode=$ExpectedVersionCode") {
  throw "Manager package versionCode mismatch; expected $ExpectedVersionCode"
}
if ($dump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Manager package versionName mismatch; expected $ExpectedVersionName"
}

$resolved = (& $adb -s $Serial shell cmd package resolve-activity --brief $Package) -join "`n"
if ($resolved -notmatch [regex]::Escape("$Package/$Activity")) {
  throw "Manager package activity did not resolve: $resolved"
}

Write-Output "Launching $Package/$Activity"
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial shell am start --display 0 -n "$Package/$Activity" | Out-Null
Start-Sleep -Seconds 3

$uiPath = Join-Path $root "$EvidencePrefix-ui.xml"
$pngPath = Join-Path $root "$EvidencePrefix-ui.png"
& $adb -s $Serial exec-out uiautomator dump /dev/tty > $uiPath
& $adb -s $Serial exec-out screencap -p > $pngPath

$ui = Get-Content -LiteralPath $uiPath -Raw -Encoding UTF8
$photoMarker = -join @([char]0x70B9, [char]0x6211, [char]0x62CD, [char]0x7167)
$voiceMarker = -join @([char]0x70B9, [char]0x6211, [char]0x8BF4, [char]0x8BDD)
$listeningMarker = -join @([char]0x7ED3, [char]0x675F, [char]0x63D0, [char]0x95EE)
foreach ($marker in @($ExpectedLabel, $photoMarker)) {
  if ($ui -notmatch [regex]::Escape($marker)) {
    throw "UI marker missing after launch: $marker"
  }
}
if ($ui -notmatch [regex]::Escape($voiceMarker) -and $ui -notmatch [regex]::Escape($listeningMarker)) {
  throw "UI voice marker missing after launch: expected $voiceMarker or $listeningMarker"
}

Write-Output "Installed and verified $Package $ExpectedVersionCode/$ExpectedVersionName label=$ExpectedLabel"
foreach ($coexistPackage in $CoexistPackages) {
  Write-Output "Coexist package still present: $coexistPackage"
}
Write-Output "Evidence:"
Write-Output "  $uiPath"
Write-Output "  $pngPath"
