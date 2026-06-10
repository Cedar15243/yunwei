param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$ApkPath = "tmp\sherpa-onnx\sherpa-onnx-1.13.1-arm64-v8a-asr-zh-small_zipformer_14M_2023_02_23.apk",
  [string]$Package = "com.k2fsa.sherpa.onnx",
  [string]$Activity = "com.k2fsa.sherpa.onnx.MainActivity",
  [string]$EvidencePrefix = "tmp\sherpa-onnx\air3-sherpa-official"
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

$evidenceDir = Split-Path -Parent (Join-Path $root $EvidencePrefix)
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$devices = (& $adb devices -l) -join "`n"
if ($devices -notmatch [regex]::Escape($Serial)) {
  & $adb devices -l
  throw "Air3 device not found by adb: $Serial"
}

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
  throw "adb install failed"
}

$packages = (& $adb -s $Serial shell pm list packages) -join "`n"
foreach ($required in @(
  "com.codex.air3nativecamera",
  "com.codex.air3nativecamera.fast",
  "com.codex.air3nativecamera.delivery",
  $Package
)) {
  if ($packages -notmatch [regex]::Escape("package:$required")) {
    throw "Required package missing after install: $required"
  }
}

& $adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null

$dump = (& $adb -s $Serial shell dumpsys package $Package) -join "`n"
if ($dump -notmatch "versionCode=20260508") {
  throw "Unexpected sherpa-onnx versionCode"
}
if ($dump -notmatch "versionName=1.13.1") {
  throw "Unexpected sherpa-onnx versionName"
}
if ($dump -notmatch "android.permission.RECORD_AUDIO: granted=true") {
  throw "RECORD_AUDIO was not granted"
}

$resolved = (& $adb -s $Serial shell cmd package resolve-activity --brief $Package) -join "`n"
$shortActivity = $Activity.Replace($Package, "")
if ($resolved -notmatch [regex]::Escape("$Package/$Activity") -and
    $resolved -notmatch [regex]::Escape("$Package/$shortActivity")) {
  throw "Activity did not resolve: $resolved"
}

& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial shell am start -n "$Package/$Activity" | Out-Null
Start-Sleep -Seconds 3

$uiPath = Join-Path $root "$EvidencePrefix-ui.xml"
$pngPath = Join-Path $root "$EvidencePrefix-ui.png"
$batteryPath = Join-Path $root "$EvidencePrefix-battery.txt"

& $adb -s $Serial exec-out uiautomator dump /dev/tty > $uiPath
& $adb -s $Serial exec-out screencap -p > $pngPath
& $adb -s $Serial shell dumpsys battery > $batteryPath

Write-Output "Installed $Package 20260508/1.13.1"
Write-Output "Coexistence verified with com.codex.air3nativecamera, com.codex.air3nativecamera.fast, com.codex.air3nativecamera.delivery"
Write-Output "RECORD_AUDIO granted=true"
Write-Output "Evidence:"
Write-Output "  $uiPath"
Write-Output "  $pngPath"
Write-Output "  $batteryPath"
