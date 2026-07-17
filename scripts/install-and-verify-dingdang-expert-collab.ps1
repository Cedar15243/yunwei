param(
  [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"
$adb = Join-Path $unityAndroid "SDK\platform-tools\adb.exe"
$apk = Join-Path $repoRoot "air3-expert-collab-app\app\build\outputs\apk\debug\app-debug.apk"
$originalPackage = "com.codex.air3nativecamera.dingdangexpert.follow"
$newPackage = "com.codex.air3nativecamera.dingdangexpert.collab"
$activity = "$newPackage/com.codex.expertcollab.MainActivity"

if (-not (Test-Path -LiteralPath $adb)) {
  throw "ADB is missing: $adb"
}
if (-not (Test-Path -LiteralPath $apk)) {
  throw "Build the expert collaboration APK first: $apk"
}

$adbArgs = @()
if ($Serial) {
  $adbArgs += @("-s", $Serial)
} else {
  $devices = @(& $adb devices | Select-String "\tdevice$")
  if ($devices.Count -ne 1) {
    throw "Expected exactly one connected Air3 device, found $($devices.Count). Pass -Serial when needed."
  }
}

function Invoke-Adb([string[]]$Arguments) {
  $output = & $adb @adbArgs @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "ADB command failed: $($Arguments -join ' ')"
  }
  return $output
}

function Assert-Original626 {
  $info = Invoke-Adb @("shell", "dumpsys", "package", $originalPackage)
  if (($info -join "`n") -notmatch "versionCode=626\b") {
    throw "Original 626 package is missing or its version changed. Installation stopped."
  }
}

Assert-Original626
Invoke-Adb @("install", "-r", $apk) | Out-Null
Invoke-Adb @("shell", "pm", "grant", $newPackage, "android.permission.CAMERA") | Out-Null
Invoke-Adb @("shell", "pm", "grant", $newPackage, "android.permission.RECORD_AUDIO") | Out-Null

$newInfo = Invoke-Adb @("shell", "dumpsys", "package", $newPackage)
$newInfoText = $newInfo -join "`n"
if ($newInfoText -notmatch "versionCode=801\b" -or $newInfoText -notmatch "versionName=8\.0\.1-expert-collab-demo") {
  throw "Installed collaboration package identity is incorrect."
}

Assert-Original626
Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null
$resumed = Invoke-Adb @("shell", "dumpsys", "activity", "activities")
if (($resumed -join "`n") -notmatch [regex]::Escape($activity)) {
  throw "Collaboration activity did not reach the foreground."
}

Write-Output "Verified original package: $originalPackage versionCode=626"
Write-Output "Installed collaboration package: $newPackage versionCode=801 versionName=8.0.1-expert-collab-demo"
