$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1"
$assistantLabel = -join @(
  [char]0x53EE, [char]0x5F53, "AI",
  [char]0x8FD0, [char]0x7EF4,
  [char]0x4E13, [char]0x5BB6
)

$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangexpert"
$env:AIR3_APK_APP_LABEL = $assistantLabel
$env:AIR3_APK_OUTPUT_NAME = "DingdangAiOpsExpert"
$env:AIR3_APK_VERSION_CODE = "621"
$env:AIR3_APK_VERSION_NAME = "6.2.1-ai-ops-expert"
$env:AIR3_APK_FAST_UPLOAD = "1"
if (-not $env:AIR3_APK_DIRECT_GPT) {
  $env:AIR3_APK_DIRECT_GPT = "0"
}

& powershell -ExecutionPolicy Bypass -File $buildScript
if ($LASTEXITCODE -ne 0) {
  throw "dingdang ai assistant APK build failed"
}
