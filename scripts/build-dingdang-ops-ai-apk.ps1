$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1"
$dingdangLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x8FD0, [char]0x7EF4, "AI")

$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.dingdangops"
$env:AIR3_APK_APP_LABEL = $dingdangLabel
$env:AIR3_APK_OUTPUT_NAME = "DingdangOpsAi"
$env:AIR3_APK_VERSION_CODE = "610"
$env:AIR3_APK_VERSION_NAME = "6.1.0-asr-final-autostop"
$env:AIR3_APK_FAST_UPLOAD = "1"
if (-not $env:AIR3_APK_DIRECT_GPT) {
  $env:AIR3_APK_DIRECT_GPT = "0"
}

& powershell -ExecutionPolicy Bypass -File $buildScript
if ($LASTEXITCODE -ne 0) {
  throw "dingdang ops ai APK build failed"
}
