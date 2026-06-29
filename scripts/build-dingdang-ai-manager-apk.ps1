$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$localOpenAiKeyPath = Join-Path $repoRoot "tmp\openai_api_key.local"
$localDirectAsrEndpointPath = Join-Path $repoRoot "tmp\direct_asr_endpoint.local"

$managerLabel = -join @(
  [char]0x53EE, [char]0x5F53, "AI",
  [char]0x8FD0, [char]0x7EF4,
  [char]0x7BA1, [char]0x5BB6
)

if (-not $env:DIRECT_GPT_API_KEY -or $env:DIRECT_GPT_API_KEY.Trim().Length -eq 0) {
  if ($env:OPENAI_API_KEY -and $env:OPENAI_API_KEY.Trim().Length -gt 0) {
    $env:DIRECT_GPT_API_KEY = $env:OPENAI_API_KEY.Trim()
  } elseif (Test-Path -LiteralPath $localOpenAiKeyPath) {
    $env:DIRECT_GPT_API_KEY = (Get-Content -LiteralPath $localOpenAiKeyPath -Raw).Trim()
  }
}

if (-not $env:DIRECT_ASR_ENDPOINT -or $env:DIRECT_ASR_ENDPOINT.Trim().Length -eq 0) {
  if (Test-Path -LiteralPath $localDirectAsrEndpointPath) {
    $env:DIRECT_ASR_ENDPOINT = (Get-Content -LiteralPath $localDirectAsrEndpointPath -Raw).Trim()
  }
}

$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangmanager"
$env:AIR3_APK_APP_LABEL = $managerLabel
$env:AIR3_APK_OUTPUT_NAME = "DingdangAiOpsManager"
$env:AIR3_APK_VERSION_CODE = "720"
$env:AIR3_APK_VERSION_NAME = "7.2.0-demo-stable-stream"
$env:AIR3_APK_FAST_UPLOAD = "1"
$env:AIR3_APK_DIRECT_GPT = "1"
$env:DIRECT_GPT_BASE_URL = "https://api.xje96.uk"
if (-not $env:DIRECT_GPT_MODEL -or $env:DIRECT_GPT_MODEL.Trim().Length -eq 0) {
  $env:DIRECT_GPT_MODEL = "gpt-5.5"
}
$env:DIRECT_GPT_REASONING_EFFORT = "low"

powershell -ExecutionPolicy Bypass -File (Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1")
