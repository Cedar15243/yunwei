param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,
  [string]$CollabServerUrl = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-integrated-preview.ps1"
$apk = Join-Path $repoRoot "air3-dingdang-expert-integrated-app\app\build\outputs\apk\debug\app-debug.apk"
$sdk = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$aapt = Join-Path $sdk "build-tools\34.0.0\aapt.exe"

$originalPackage = "com.codex.air3nativecamera.dingdangexpert.follow"
$collabPackage = "com.codex.air3nativecamera.dingdangexpert.collab"
$previewPackage = "com.codex.air3nativecamera.dingdangexpert.follow.preview"

function Invoke-Adb([string[]]$Arguments) {
  $output = & $adb -s $Serial @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "adb failed: $($Arguments -join ' ')`n$output"
  }
  return $output
}

function Get-VersionCode([string]$Package) {
  $packages = Invoke-Adb @("shell", "pm", "list", "packages", $Package)
  if (($packages -join "`n") -notmatch "package:$([regex]::Escape($Package))(\s|$)") {
    return $null
  }
  $details = Invoke-Adb @("shell", "dumpsys", "package", $Package)
  $match = [regex]::Match(($details -join "`n"), "versionCode=(\d+)")
  if (-not $match.Success) {
    throw "Unable to read versionCode for $Package"
  }
  return [int]$match.Groups[1].Value
}

function Assert-Version([string]$Package, [int]$Expected, [string]$Label) {
  $actual = Get-VersionCode $Package
  if ($null -eq $actual -or $actual -ne $Expected) {
    throw "$Label must remain versionCode=$Expected; actual=$actual"
  }
}

if (-not (Test-Path -LiteralPath $adb) -or -not (Test-Path -LiteralPath $aapt)) {
  throw "Android SDK tools are missing"
}

$connected = & $adb devices
if (($connected -join "`n") -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
  throw "Air3 $Serial is not connected"
}

Assert-Version $originalPackage 626 "Original 626 package"
Assert-Version $collabPackage 801 "Independent collaboration package"
$previewBefore = Get-VersionCode $previewPackage
if ($null -ne $previewBefore -and $previewBefore -ne 627) {
  throw "Existing preview package must be versionCode=627; actual=$previewBefore"
}

& $buildScript -CollabServerUrl $CollabServerUrl
if ($LASTEXITCODE -ne 0) {
  throw "Integrated preview build script failed"
}

$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) {
  throw "Unable to inspect integrated preview APK"
}
$packageMatch = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)'")
if (-not $packageMatch.Success
    -or $packageMatch.Groups[1].Value -ne $previewPackage
    -or [int]$packageMatch.Groups[2].Value -ne 627) {
  throw "Refusing installation because APK is not $previewPackage versionCode=627"
}

Invoke-Adb @("install", "-r", $apk) | Out-Null
Invoke-Adb @("shell", "pm", "grant", $previewPackage, "android.permission.CAMERA") | Out-Null
Invoke-Adb @("shell", "pm", "grant", $previewPackage, "android.permission.RECORD_AUDIO") | Out-Null

Assert-Version $originalPackage 626 "Original 626 package after preview install"
Assert-Version $collabPackage 801 "Independent collaboration package after preview install"
Assert-Version $previewPackage 627 "Integrated preview package"

$activity = (Invoke-Adb @("shell", "cmd", "package", "resolve-activity", "--brief", $previewPackage) | Select-Object -Last 1).Trim()
if (-not $activity -or $activity -notmatch "/") {
  throw "Unable to resolve integrated preview Activity"
}
Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null

Write-Output "Verified original package versionCode=626."
Write-Output "Verified independent collaboration package versionCode=801."
Write-Output "Installed integrated preview package versionCode=627 without replacing existing APKs."
