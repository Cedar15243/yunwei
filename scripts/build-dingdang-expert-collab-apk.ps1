$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$project = Join-Path $repoRoot "air3-expert-collab-app"
$gradlew = Join-Path $project "gradlew.bat"
$package = "com.codex.air3nativecamera.dingdangexpert.collab"
$versionCode = 801
$versionName = "8.0.1-expert-collab-demo"

if (-not (Test-Path -LiteralPath $gradlew)) {
  throw "Expert collaboration Gradle wrapper is not ready: $gradlew"
}

Write-Output "Building $package $versionCode/$versionName"
& $gradlew --project-dir $project assembleDebug
if ($LASTEXITCODE -ne 0) {
  throw "Expert collaboration APK build failed"
}
