$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1"
$instantChatLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI-", [char]0x5373, [char]0x65F6, [char]0x5BF9, [char]0x8BDD, [char]0x6D4B, [char]0x8BD5)

$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.instantchat"
$env:AIR3_APK_APP_LABEL = $instantChatLabel
$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraInstantChat"
$env:AIR3_APK_VERSION_CODE = "501"
$env:AIR3_APK_VERSION_NAME = "5.0.1-instant-chat"
$env:AIR3_APK_FAST_UPLOAD = "1"

& powershell -ExecutionPolicy Bypass -File $buildScript
if ($LASTEXITCODE -ne 0) {
  throw "instant chat APK build failed"
}
