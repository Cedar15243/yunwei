param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.fast",
  [string]$OriginalPackage = "com.codex.air3nativecamera",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$ApkPath = "air3-native-camera-test\build\Air3NativeCameraFast.apk",
  [int]$ExpectedVersionCode = 222,
  [string]$ExpectedVersionName = "2.1.6-fast",
  [int]$WaitSeconds = 120,
  [string]$EvidencePrefix = "tmp\air3-fast-222"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$adb = Join-Path $root "tmp\tools\platform-tools\adb.exe"
$apk = Join-Path $root $ApkPath

if (-not (Test-Path -LiteralPath $adb)) {
  throw "adb not found: $adb"
}
if (-not (Test-Path -LiteralPath $apk)) {
  throw "APK not found: $apk"
}

New-Item -ItemType Directory -Force -Path (Join-Path $root "tmp") | Out-Null

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

$packages = (& $adb -s $Serial shell pm list packages com.codex.air3nativecamera) -join "`n"
if ($packages -notmatch [regex]::Escape("package:$OriginalPackage")) {
  throw "Original package missing after install: $OriginalPackage"
}
if ($packages -notmatch [regex]::Escape("package:$Package")) {
  throw "Fast package missing after install: $Package"
}

$dump = (& $adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($dump -notmatch "versionCode=$ExpectedVersionCode") {
  throw "Fast package versionCode mismatch; expected $ExpectedVersionCode"
}
if ($dump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Fast package versionName mismatch; expected $ExpectedVersionName"
}

$resolved = (& $adb -s $Serial shell cmd package resolve-activity --brief $Package) -join "`n"
if ($resolved -notmatch [regex]::Escape("$Package/$Activity")) {
  throw "Fast package activity did not resolve: $resolved"
}

Write-Output "Launching $Package/$Activity"
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial shell am start -n "$Package/$Activity" | Out-Null
Start-Sleep -Seconds 3

$uiPath = Join-Path $root "$EvidencePrefix-ui.xml"
$pngPath = Join-Path $root "$EvidencePrefix-ui.png"
$voicePath = Join-Path $root "$EvidencePrefix-last-voice-response.json"
$diagPath = Join-Path $root "$EvidencePrefix-last-voice-diagnostics.json"
$opsPath = Join-Path $root "$EvidencePrefix-last-ops-response.json"

& $adb -s $Serial exec-out uiautomator dump /dev/tty > $uiPath
& $adb -s $Serial exec-out screencap -p > $pngPath
& $adb -s $Serial exec-out run-as $Package cat files/last_voice_response.json > $voicePath 2>$null
& $adb -s $Serial exec-out run-as $Package cat files/last_voice_diagnostics.json > $diagPath 2>$null
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

Write-Output "Installed and verified $Package $ExpectedVersionCode/$ExpectedVersionName"
Write-Output "Evidence:"
Write-Output "  $uiPath"
Write-Output "  $pngPath"
Write-Output "  $voicePath"
Write-Output "  $diagPath"
Write-Output "  $opsPath"
