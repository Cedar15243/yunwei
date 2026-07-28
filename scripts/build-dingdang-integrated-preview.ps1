param(
  [string]$CollabServerUrl = "",
  [string]$ApplicationId = "com.codex.air3nativecamera.dingdangexpert.follow.preview",
  [int]$VersionCode = 627,
  [string]$VersionName = "6.2.7-expert-preview",
  [string]$AppLabel = "叮当AI运维专家·协同测试",
  [switch]$DirectAi,
  [switch]$OfflineWake
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$projectRoot = Join-Path $repoRoot "air3-dingdang-expert-integrated-app"
$gradle = Join-Path $projectRoot "gradlew.bat"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"

$localConfigRoots = @($repoRoot)
$gitCommonDir = (& git -C $repoRoot rev-parse --git-common-dir 2>$null | Select-Object -First 1)
if ($gitCommonDir) {
  $commonPath = $gitCommonDir.Trim().Replace("/", "\")
  if (-not [System.IO.Path]::IsPathRooted($commonPath)) {
    $commonPath = Join-Path $repoRoot $commonPath
  }
  $localConfigRoots += Split-Path -Parent ([System.IO.Path]::GetFullPath($commonPath))
}
$localConfigRoots = @($localConfigRoots | Select-Object -Unique)

function Get-LocalConfigCandidates([string]$FileName) {
  $candidates = @()
  foreach ($configRoot in $localConfigRoots) {
    $candidates += Join-Path $configRoot "tmp\$FileName"
  }
  return $candidates | Select-Object -Unique
}

function Read-ConfigOrEnv(
  [string]$Name,
  [string]$FileName,
  [string]$DefaultValue = "",
  [switch]$Required
) {
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ($value -and $value.Trim().Length -gt 0) {
    return $value.Trim()
  }
  $configCandidates = @(Get-LocalConfigCandidates $FileName)
  $configFile = $configCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
  if ($configFile) {
    $fileValue = (Get-Content -LiteralPath $configFile -Raw).Trim()
    if ($fileValue.Length -gt 0) {
      return $fileValue
    }
  }
  if ($Required) {
    throw "$Name is missing from the environment and local ignored files"
  }
  return $DefaultValue
}

if (-not $CollabServerUrl) {
  $lanAddress = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notmatch "^(127\.|169\.254\.)" -and $_.PrefixOrigin -ne "WellKnown" } |
    Sort-Object InterfaceMetric |
    Select-Object -First 1 -ExpandProperty IPAddress
  if (-not $lanAddress) {
    throw "A LAN address is required for the Air3 collaboration server"
  }
  $CollabServerUrl = "http://${lanAddress}:8787"
}

if ($CollabServerUrl -notmatch "^https?://[^/]+(?::\d+)?$") {
  throw "CollabServerUrl must be an HTTP(S) origin without a path"
}
if ($ApplicationId -notmatch "^com\.codex\.air3nativecamera\.dingdangexpert\.follow\.preview(?:\.[a-z][a-z0-9]*)*$") {
  throw "ApplicationId must remain in the protected integrated preview namespace"
}
if ($VersionCode -lt 627 -or -not $VersionName.Trim() -or -not $AppLabel.Trim()) {
  throw "Integrated preview version and label are invalid"
}

$env:OPS_GLASSES_API_KEY = Read-ConfigOrEnv `
  -Name "OPS_GLASSES_API_KEY" `
  -FileName "ops_glasses_api_key.local" `
  -Required

if ($DirectAi) {
  $env:AIR3_APK_DIRECT_GPT = "1"
  $env:DIRECT_GPT_API_KEY = Read-ConfigOrEnv `
    -Name "DIRECT_GPT_API_KEY" `
    -FileName "direct_gpt_api_key.local" `
    -Required
  $env:DIRECT_GPT_BASE_URL = Read-ConfigOrEnv `
    -Name "DIRECT_GPT_BASE_URL" `
    -FileName "direct_gpt_base_url.local" `
    -DefaultValue "https://api.openai.com/v1"
  $env:DIRECT_GPT_MODEL = Read-ConfigOrEnv `
    -Name "DIRECT_GPT_MODEL" `
    -FileName "direct_gpt_model.local" `
    -DefaultValue "gpt-4.1-mini"
  $env:DIRECT_ASR_API_KEY = Read-ConfigOrEnv `
    -Name "DIRECT_ASR_API_KEY" `
    -FileName "direct_asr_api_key.local" `
    -Required
  $env:DIRECT_ASR_ENDPOINT = Read-ConfigOrEnv `
    -Name "DIRECT_ASR_ENDPOINT" `
    -FileName "direct_asr_endpoint.local" `
    -Required
} else {
  Remove-Item Env:AIR3_APK_DIRECT_GPT -ErrorAction SilentlyContinue
}

if ($OfflineWake) {
  $env:AIR3_APK_IFLYTEK_OFFLINE_WAKE = "1"
  $env:IFLYTEK_APP_ID = Read-ConfigOrEnv -Name "IFLYTEK_APP_ID" -FileName "iflytek_app_id.local" -Required
  $env:IFLYTEK_API_KEY = Read-ConfigOrEnv -Name "IFLYTEK_API_KEY" -FileName "iflytek_api_key.local" -Required
  $env:IFLYTEK_API_SECRET = Read-ConfigOrEnv -Name "IFLYTEK_API_SECRET" -FileName "iflytek_api_secret.local" -Required
  $env:IFLYTEK_AIKIT_ROOT = Join-Path $repoRoot "tmp\iflytek-aikit-min\AIKit_AEE_Android_IVW_e867a88f2_1.0.44_SDK2.2.17_rc6"
  if (-not (Test-Path -LiteralPath (Join-Path $env:IFLYTEK_AIKIT_ROOT "SDK\AIKit.aar"))) {
    throw "AIKit offline wake SDK is missing from the local ignored directory"
  }
} else {
  Remove-Item Env:AIR3_APK_IFLYTEK_OFFLINE_WAKE -ErrorAction SilentlyContinue
}

$env:JAVA_HOME = Join-Path $unityAndroid "OpenJDK"
$env:ANDROID_HOME = Join-Path $unityAndroid "SDK"
$env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH

Push-Location $projectRoot
try {
  & $gradle ":app:testDebugUnitTest" "assembleDebug" `
    "-PcollabServerUrl=$CollabServerUrl" `
    "-PpreviewApplicationId=$ApplicationId" `
    "-PpreviewVersionCode=$VersionCode" `
    "-PpreviewVersionName=$VersionName" `
    "-PpreviewAppLabel=$AppLabel" `
    "--console=plain"
  if ($LASTEXITCODE -ne 0) {
    throw "Integrated preview Gradle build failed"
  }
} finally {
  Pop-Location
}

if (-not (Test-Path -LiteralPath $apk)) {
  throw "Integrated preview APK was not generated: $apk"
}

Write-Output "Integrated preview build passed."
Write-Output "Package: $ApplicationId ($VersionCode / $VersionName)"
Write-Output "Collaboration server: $CollabServerUrl"
Write-Output "AI mode: $(if ($DirectAi) { 'direct' } else { 'backend' })"
Write-Output $apk
