param(
  [Parameter(Mandatory = $true)]
  [string]$Serial,
  [string]$CollabServerUrl = "",
  [switch]$DirectAi,
  [switch]$OfflineWake
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-investor-v8.ps1"
$apk = Join-Path $repoRoot "output\DingdangAiOpsInvestor-v8.0.0-investor-demo.apk"
$sdk = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$aapt = Join-Path $sdk "build-tools\34.0.0\aapt.exe"
$packageId = "com.codex.air3nativecamera.dingdangexpert.follow.preview.investorv8"

function Invoke-Adb([string[]]$Arguments) {
  $output = & $adb -s $Serial @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$output" }
  return $output
}

function Get-PackageVersion([string]$Package) {
  $details = Invoke-Adb @("shell", "dumpsys", "package", $Package)
  $code = [regex]::Match(($details -join "`n"), "versionCode=(\d+)")
  $name = [regex]::Match(($details -join "`n"), "versionName=([^\r\n]+)")
  if (-not $code.Success) { return "missing" }
  return "$($code.Groups[1].Value)|$($name.Groups[1].Value.Trim())"
}

function Snapshot-ExistingPackages {
  $snapshot = @{}
  $lines = Invoke-Adb @("shell", "pm", "list", "packages", "com.codex.air3nativecamera")
  foreach ($line in $lines) {
    $name = ($line -replace '^package:', '').Trim()
    if ($name -and $name -ne $packageId) { $snapshot[$name] = Get-PackageVersion $name }
  }
  return $snapshot
}

function Assert-ExistingPackagesUnchanged([hashtable]$Before) {
  foreach ($name in $Before.Keys) {
    $after = Get-PackageVersion $name
    if ($after -ne $Before[$name]) {
      throw "Existing package changed: $name before=$($Before[$name]) after=$after"
    }
  }
}

if (-not (Test-Path -LiteralPath $adb) -or -not (Test-Path -LiteralPath $aapt)) {
  throw "Android SDK tools are missing"
}
$connected = & $adb devices
if (($connected -join "`n") -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
  throw "Air3 $Serial is not connected"
}

$before = Snapshot-ExistingPackages
$buildParameters = @{}
if ($CollabServerUrl) { $buildParameters.CollabServerUrl = $CollabServerUrl }
if ($DirectAi) { $buildParameters.DirectAi = $true }
if ($OfflineWake) { $buildParameters.OfflineWake = $true }
& $buildScript @buildParameters
if ($LASTEXITCODE -ne 0) { throw "Investor v8 build script failed" }

$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect investor v8 APK" }
$identity = $badging -join "`n"
if ($identity -notmatch "package: name='$([regex]::Escape($packageId))' versionCode='800'") {
  throw "Refusing because APK identity is not the isolated investor v8 package"
}
if ($identity -notmatch "versionName='8\.0\.0-investor-demo'") {
  throw "Investor v8 versionName mismatch"
}

Invoke-Adb @("install", "-r", $apk) | Out-Null
Invoke-Adb @("shell", "pm", "grant", $packageId, "android.permission.CAMERA") | Out-Null
Invoke-Adb @("shell", "pm", "grant", $packageId, "android.permission.RECORD_AUDIO") | Out-Null

Assert-ExistingPackagesUnchanged $before
if ((Get-PackageVersion $packageId) -notmatch '^800\|8\.0\.0-investor-demo$') {
  throw "Investor v8 package verification failed"
}

$activity = (Invoke-Adb @("shell", "cmd", "package", "resolve-activity", "--brief", $packageId) | Select-Object -Last 1).Trim()
if (-not $activity -or $activity -notmatch "/") { throw "Unable to resolve investor v8 Activity" }
Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null
Write-Output "Investor v8 is running as an independent package; existing packages are unchanged."
