$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "air3-native-camera-test\build-native-apk.ps1"
$deliveryLabel = -join @([char]0x53EE, [char]0x5F53, [char]0x4FDD, "AI")

$env:AIR3_APK_APP_ID = "com.codex.air3nativecamera.delivery"
$env:AIR3_APK_APP_LABEL = $deliveryLabel
$env:AIR3_APK_OUTPUT_NAME = "Air3NativeCameraDelivery"
$env:AIR3_APK_VERSION_CODE = "300"
$env:AIR3_APK_VERSION_NAME = "3.0.0-delivery"
$env:AIR3_APK_FAST_UPLOAD = "1"

& powershell -ExecutionPolicy Bypass -File $buildScript
if ($LASTEXITCODE -ne 0) {
  throw "delivery APK build failed"
}
