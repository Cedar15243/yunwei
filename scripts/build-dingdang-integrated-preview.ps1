param(
  [string]$CollabServerUrl = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$projectRoot = Join-Path $repoRoot "air3-dingdang-expert-integrated-app"
$gradle = Join-Path $projectRoot "gradlew.bat"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"

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

if (-not $env:OPS_GLASSES_API_KEY) {
  $secretCandidates = @(
    (Join-Path $repoRoot "tmp\ops_glasses_api_key.local")
  )
  $gitCommonDir = (& git -C $repoRoot rev-parse --git-common-dir 2>$null | Select-Object -First 1)
  if ($LASTEXITCODE -eq 0 -and $gitCommonDir) {
    $commonPath = $gitCommonDir.Trim().Replace("/", "\")
    if (-not [System.IO.Path]::IsPathRooted($commonPath)) {
      $commonPath = Join-Path $repoRoot $commonPath
    }
    $mainCheckout = Split-Path -Parent ([System.IO.Path]::GetFullPath($commonPath))
    $secretCandidates += Join-Path $mainCheckout "tmp\ops_glasses_api_key.local"
  }
  $secretFile = $secretCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
  if (-not $secretFile) {
    throw "OPS_GLASSES_API_KEY is missing from the environment and local ignored files"
  }
  $env:OPS_GLASSES_API_KEY = (Get-Content -LiteralPath $secretFile -Raw).Trim()
}

if (-not $env:OPS_GLASSES_API_KEY) {
  throw "OPS_GLASSES_API_KEY is empty"
}

$env:JAVA_HOME = Join-Path $unityAndroid "OpenJDK"
$env:ANDROID_HOME = Join-Path $unityAndroid "SDK"
$env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH

Push-Location $projectRoot
try {
  & $gradle ":app:testDebugUnitTest" "assembleDebug" "-PcollabServerUrl=$CollabServerUrl" "--console=plain"
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
Write-Output "Collaboration server: $CollabServerUrl"
Write-Output $apk
