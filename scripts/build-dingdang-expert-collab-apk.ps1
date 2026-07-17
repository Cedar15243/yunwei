$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$project = Join-Path $repoRoot "air3-expert-collab-app"
$gradlew = Join-Path $project "gradlew.bat"
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"
$jdk = Join-Path $unityAndroid "OpenJDK"
$sdk = Join-Path $unityAndroid "SDK"
$aapt2 = Join-Path $sdk "build-tools\34.0.0\aapt2.exe"
$localProperties = Join-Path $project "local.properties"
$package = "com.codex.air3nativecamera.dingdangexpert.collab"
$versionCode = 801
$versionName = "8.0.1-expert-collab-demo"

if (-not (Test-Path -LiteralPath $gradlew)) {
  throw "Expert collaboration Gradle wrapper is not ready: $gradlew"
}
if (-not (Test-Path -LiteralPath (Join-Path $jdk "bin\java.exe"))) {
  throw "Unity Android JDK is missing: $jdk"
}
if (-not (Test-Path -LiteralPath (Join-Path $sdk "platforms\android-34\android.jar"))) {
  throw "Android 34 SDK is missing: $sdk"
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = (Join-Path $jdk "bin") + ";" + $env:PATH
$internetSettings = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
if ($internetSettings.ProxyEnable -eq 1 -and $internetSettings.ProxyServer -match "^(?<host>[^:;=]+):(?<port>\d+)$") {
  $proxyHost = $Matches.host
  $proxyPort = $Matches.port
  $env:GRADLE_OPTS = "-Dhttp.proxyHost=$proxyHost -Dhttp.proxyPort=$proxyPort -Dhttps.proxyHost=$proxyHost -Dhttps.proxyPort=$proxyPort"
}
$sdkProperty = $sdk.Replace("\", "\\")
[System.IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkProperty`n", (New-Object System.Text.UTF8Encoding $false))

Write-Output "Building $package $versionCode/$versionName"
& $gradlew --project-dir $project --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) {
  throw "Expert collaboration APK build failed"
}

$apk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
  throw "Expert collaboration APK was not generated: $apk"
}
$badging = (& $aapt2 dump badging $apk | Select-Object -First 1)
if ($badging -notmatch "name='$([regex]::Escape($package))'" -or
    $badging -notmatch "versionCode='$versionCode'" -or
    $badging -notmatch "versionName='$([regex]::Escape($versionName))'") {
  throw "Expert collaboration APK identity check failed: $badging"
}
Write-Output $apk
