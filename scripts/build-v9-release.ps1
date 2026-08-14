param(
  [string]$OutputDirectory = "output\v9.0.0-formal-release"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$project = Join-Path $repoRoot "air3-dingdang-expert-integrated-app"
$gradlew = Join-Path $project "gradlew.bat"
$applicationId = "com.codex.air3nativecamera.dingdangexpert.v9"
$versionCode = 900000
$versionName = "9.0.0"
$expectedAiModel = "qwen3-vl-plus"
$expectedAsrModel = "fun-asr-realtime"
$expectedVoiceprintService = "s1aa729d0"

function Require-EnvironmentValue([string]$Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw "$Name is required for the formal V9 release build"
  }
  return $value.Trim()
}

function Resolve-JavaHome {
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    return $env:JAVA_HOME
  }
  $bundled = Get-ChildItem -LiteralPath (Join-Path $repoRoot "tmp\jdk17-temurin") `
    -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($bundled -and (Test-Path -LiteralPath (Join-Path $bundled.FullName "bin\java.exe"))) {
    return $bundled.FullName
  }
  throw "JDK 17 is required. Set JAVA_HOME or restore tmp\jdk17-temurin."
}

function Resolve-AndroidSdk {
  foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME,
      "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK")) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate "platforms\android-34\android.jar"))) {
      return $candidate
    }
  }
  throw "Android SDK 34 is required. Set ANDROID_SDK_ROOT."
}

function Assert-ApkContents([string]$ApkPath, [array]$ForbiddenItems, [string]$AsrNeedle) {
  $ascii = [Text.Encoding]::ASCII
  $needleLengths = @($AsrNeedle.Length) + @($ForbiddenItems | ForEach-Object { $_.Needle.Length })
  $overlapSize = [Math]::Max(0, ($needleLengths | Measure-Object -Maximum).Maximum - 1)
  $asrMatches = 0
  $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
  try {
    foreach ($entry in $archive.Entries) {
      if ($entry.Length -le 0) { continue }
      $entryStream = $entry.Open()
      try {
        $buffer = New-Object byte[] 65536
        $tail = ""
        while (($read = $entryStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
          $tailLength = $tail.Length
          $content = $tail + $ascii.GetString($buffer, 0, $read)
          foreach ($item in $ForbiddenItems) {
            if ($content.IndexOf($item.Needle, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
              throw "APK contains forbidden model or credential: $($item.Label)"
            }
          }
          $searchFrom = 0
          while (($match = $content.IndexOf($AsrNeedle, $searchFrom, [StringComparison]::Ordinal)) -ge 0) {
            if ($match + $AsrNeedle.Length -gt $tailLength) {
              $asrMatches += 1
            }
            $searchFrom = $match + [Math]::Max(1, $AsrNeedle.Length)
          }
          $kept = [Math]::Min($overlapSize, $content.Length)
          $tail = if ($kept -gt 0) { $content.Substring($content.Length - $kept) } else { "" }
        }
      } finally {
        $entryStream.Dispose()
      }
    }
  } finally {
    $archive.Dispose()
  }
  return $asrMatches
}

if (-not (Test-Path -LiteralPath $gradlew)) {
  throw "Gradle wrapper is missing: $gradlew"
}

$keystore = Require-EnvironmentValue "V9_RELEASE_KEYSTORE"
$null = Require-EnvironmentValue "V9_RELEASE_KEY_ALIAS"
$null = Require-EnvironmentValue "V9_RELEASE_STORE_PASSWORD"
$null = Require-EnvironmentValue "V9_RELEASE_KEY_PASSWORD"
$iflytekAikitRoot = Require-EnvironmentValue "IFLYTEK_AIKIT_ROOT"
$sherpaOnnxRoot = Require-EnvironmentValue "SHERPA_ONNX_ROOT"
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
  throw "V9_RELEASE_KEYSTORE does not exist"
}
if (-not (Test-Path -LiteralPath (Join-Path $iflytekAikitRoot "SDK\AIKit.aar") -PathType Leaf)) {
  throw "The previous iFlytek AIKit SDK is missing"
}
if (-not (Test-Path -LiteralPath (Join-Path $sherpaOnnxRoot "assets") -PathType Container) -or
    -not (Test-Path -LiteralPath (Join-Path $sherpaOnnxRoot "jniLibs") -PathType Container)) {
  throw "The previous Sherpa fallback package is incomplete"
}

$javaHome = Resolve-JavaHome
$androidSdk = Resolve-AndroidSdk
$buildTools = Join-Path $androidSdk "build-tools\34.0.0"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
if (-not (Test-Path -LiteralPath $aapt2) -or -not (Test-Path -LiteralPath $apksigner)) {
  throw "Android build-tools 34.0.0 are required"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH
$env:AIR3_APK_IFLYTEK_OFFLINE_WAKE = "1"
$env:AIR3_APK_LOCAL_ASR_FALLBACK = "1"
$env:AIR3_APK_SECURE_RUNTIME = "1"
$env:AIR3_APK_DIRECT_GPT = "0"

Write-Output "Building formal V9 release $applicationId $versionCode/$versionName"
& $gradlew --project-dir $project --no-daemon `
  "-PformalRelease=true" `
  "-PsecureRuntime=true" `
  "-PdebugPrivateProvisioning=false" `
  assembleRelease
if ($LASTEXITCODE -ne 0) {
  throw "Formal V9 release build failed"
}

$builtApk = Join-Path $project "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) {
  throw "Release APK was not generated"
}

$badging = (& $aapt2 dump badging $builtApk | Select-Object -First 1)
if ($badging -notmatch "name='$([regex]::Escape($applicationId))'" -or
    $badging -notmatch "versionCode='$versionCode'" -or
    $badging -notmatch "versionName='$([regex]::Escape($versionName))'") {
  throw "Formal V9 APK identity check failed: $badging"
}
& $apksigner verify --verbose --print-certs $builtApk
if ($LASTEXITCODE -ne 0) {
  throw "Formal V9 APK signature verification failed"
}

$resolvedOutput = Join-Path $repoRoot $OutputDirectory
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$finalApk = Join-Path $resolvedOutput "DingdangAI-V9-$versionName-release.apk"
Copy-Item -LiteralPath $builtApk -Destination $finalApk -Force

[Reflection.Assembly]::LoadWithPartialName("System.IO.Compression.FileSystem") | Out-Null
$forbiddenItems = @()
foreach ($forbiddenModel in @(
    $expectedAiModel,
    $expectedVoiceprintService,
    "OpenClaw",
    "gpt-5.5",
    "gpt-5.6",
    "deepseek",
    "claude",
    "qwen-max")) {
  $forbiddenItems += [pscustomobject]@{
    Needle = $forbiddenModel
    Label = "forbidden model $forbiddenModel"
  }
}

foreach ($secretName in @(
    "DINGDANG_BACKEND_API_KEY",
    "OPS_GLASSES_API_KEY",
    "DIRECT_GPT_API_KEY",
    "DIRECT_ASR_API_KEY",
    "IFLYTEK_APP_ID",
    "IFLYTEK_API_KEY",
    "IFLYTEK_API_SECRET")) {
  $secretValue = [Environment]::GetEnvironmentVariable($secretName)
  if ($secretValue) {
    $forbiddenItems += [pscustomobject]@{
      Needle = $secretValue.Trim()
      Label = "managed credential $secretName"
    }
  }
}

$asrMatches = Assert-ApkContents $finalApk $forbiddenItems $expectedAsrModel
if ($asrMatches -gt 1) {
  throw "APK contains the managed ASR protocol constant more than once"
}

$hash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash
$manifest = [ordered]@{
  applicationId = $applicationId
  versionCode = $versionCode
  versionName = $versionName
  sha256 = $hash
  aiModel = $expectedAiModel
  asrModel = $expectedAsrModel
  voiceprintService = $expectedVoiceprintService
  secureRuntime = $true
  directGptEnabled = $false
  builtAt = [DateTimeOffset]::UtcNow.ToString("o")
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath `
  (Join-Path $resolvedOutput "release-manifest.json") -Encoding utf8

Write-Output "APK=$finalApk"
Write-Output "SHA256=$hash"
Write-Output "Formal V9 release validation passed"
