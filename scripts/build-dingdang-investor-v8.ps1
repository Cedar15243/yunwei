param(
  [string]$CollabServerUrl = "https://bb.chinacedar.top:2305",
  [switch]$DirectAi,
  [switch]$OfflineWake
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$baseBuild = Join-Path $repoRoot "scripts\build-dingdang-integrated-preview.ps1"
$packageId = "com.codex.air3nativecamera.dingdangexpert.follow.preview.investorv8"
$sourceApk = Join-Path $repoRoot "air3-dingdang-expert-integrated-app\app\build\outputs\apk\debug\app-debug.apk"
$outputDir = Join-Path $repoRoot "output"
$outputApk = Join-Path $outputDir "DingdangAiOpsInvestor-v8.0.0-investor-demo.apk"
$appLabel = [Text.Encoding]::UTF8.GetString(
  [Convert]::FromBase64String("5Y+u5b2TQUnov5Dnu7TkuJPlrrbCt+ihjOS4mua8lOekug=="))

$buildParameters = @{
  ApplicationId = $packageId
  VersionCode = 800
  VersionName = "8.0.0-investor-demo"
  AppLabel = $appLabel
}
if ($CollabServerUrl) { $buildParameters.CollabServerUrl = $CollabServerUrl }
if ($DirectAi) { $buildParameters.DirectAi = $true }
if ($OfflineWake) { $buildParameters.OfflineWake = $true }

& $baseBuild @buildParameters
if ($LASTEXITCODE -ne 0) { throw "Investor v8 build failed" }
if (-not (Test-Path -LiteralPath $sourceApk)) { throw "Investor v8 APK was not generated" }

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $outputApk -Force
Write-Output "Investor v8 build passed: $packageId VersionCode 800 / 8.0.0-investor-demo"
Write-Output $outputApk
