param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.delivery",
  [string]$OriginalPackage = "com.codex.air3nativecamera",
  [string]$FastPackage = "com.codex.air3nativecamera.fast",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$ApkPath = "air3-native-camera-test\build\Air3NativeCameraDelivery.apk",
  [int]$ExpectedVersionCode = 300,
  [string]$ExpectedVersionName = "3.0.0-delivery",
  [string]$ExpectedLabel = (-join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI")),
  [int]$WaitSeconds = 120,
  [string]$EvidencePrefix = "tmp\air3-delivery-300"
)

$ErrorActionPreference = "Stop"

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
  throw "delivery package id mismatch in APK badging"
}
if ($badging -notmatch "versionCode='$ExpectedVersionCode'") {
  throw "delivery versionCode mismatch in APK badging"
}
if ($badging -notmatch [regex]::Escape("versionName='$ExpectedVersionName'")) {
  throw "delivery versionName mismatch in APK badging"
}
if ($badging -notmatch [regex]::Escape("application-label:'$ExpectedLabel'")) {
  throw "delivery app label mismatch in APK badging"
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

Write-Output "Installing $apk"
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
  throw "adb install failed"
}
& $adb -s $Serial shell pm grant $Package android.permission.CAMERA 2>$null
& $adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null

$packages = (& $adb -s $Serial shell pm list packages com.codex.air3nativecamera) -join "`n"
foreach ($expectedPackage in @($OriginalPackage, $FastPackage, $Package)) {
  if ($packages -notmatch [regex]::Escape("package:$expectedPackage")) {
    throw "Expected package missing after install: $expectedPackage"
  }
}

$dump = (& $adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($dump -notmatch "versionCode=$ExpectedVersionCode") {
  throw "Delivery package versionCode mismatch; expected $ExpectedVersionCode"
}
if ($dump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Delivery package versionName mismatch; expected $ExpectedVersionName"
}

$resolved = (& $adb -s $Serial shell cmd package resolve-activity --brief $Package) -join "`n"
if ($resolved -notmatch [regex]::Escape("$Package/$Activity")) {
  throw "Delivery package activity did not resolve: $resolved"
}

Write-Output "Launching $Package/$Activity"
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial shell am start --display 0 -n "$Package/$Activity" | Out-Null
Start-Sleep -Seconds 3

$uiPath = Join-Path $root "$EvidencePrefix-ui.xml"
$pngPath = Join-Path $root "$EvidencePrefix-ui.png"
$voicePath = Join-Path $root "$EvidencePrefix-last-voice-response.json"
$diagPath = Join-Path $root "$EvidencePrefix-last-voice-diagnostics.json"
$voiceWavPath = Join-Path $root "$EvidencePrefix-last-voice-upload.wav"
$opsPath = Join-Path $root "$EvidencePrefix-last-ops-response.json"

& $adb -s $Serial exec-out uiautomator dump /dev/tty > $uiPath
& $adb -s $Serial exec-out screencap -p > $pngPath
& $adb -s $Serial exec-out run-as $Package cat files/last_voice_response.json > $voicePath 2>$null
& $adb -s $Serial exec-out run-as $Package cat files/last_voice_diagnostics.json > $diagPath 2>$null
& $adb -s $Serial exec-out run-as $Package cat files/last_voice_upload.wav > $voiceWavPath 2>$null
& $adb -s $Serial exec-out run-as $Package cat files/last_ops_response.json > $opsPath 2>$null

$ui = Get-Content -LiteralPath $uiPath -Raw -Encoding UTF8
$captureNextMarker = -join @([char]0x62CD, [char]0x7167, " / ", [char]0x4E0B, [char]0x4E00, [char]0x9875)
$voiceMarker = -join @([char]0x8BED, [char]0x97F3, [char]0x63D0, [char]0x95EE)
$backRetakeMarker = -join @([char]0x4E0A, [char]0x4E00, [char]0x9875, " / ", [char]0x91CD, [char]0x62CD)
foreach ($marker in @($captureNextMarker, $voiceMarker, $backRetakeMarker)) {
  if ($ui -notmatch [regex]::Escape($marker)) {
    throw "UI marker missing after launch: $marker"
  }
}

Write-Output "Installed and verified $Package $ExpectedVersionCode/$ExpectedVersionName label=$ExpectedLabel"
Write-Output "Evidence:"
Write-Output "  $uiPath"
Write-Output "  $pngPath"
Write-Output "  $voicePath"
Write-Output "  $diagPath"
Write-Output "  $voiceWavPath"
Write-Output "  $opsPath"
