$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"
$sdk = Join-Path $unityAndroid "SDK"
$jdk = Join-Path $unityAndroid "OpenJDK"
$buildTools = Join-Path $sdk "build-tools\34.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
$jar = Join-Path $jdk "bin\jar.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"

$app = Join-Path $root "app\src\main"
$manifest = Join-Path $app "AndroidManifest.xml"
$res = Join-Path $app "res"
$src = Join-Path $app "java"
$build = Join-Path $root "build"
$buildRes = Join-Path $build "res"
$compiledRes = Join-Path $build "compiled-res"
$generatedSrc = Join-Path $build "generated-src"
$classes = Join-Path $build "classes"
$classesJar = Join-Path $build "classes.jar"
$dex = Join-Path $build "dex"
$repoRoot = Split-Path -Parent $root
$keystore = if ($env:AIR3_APK_KEYSTORE) { $env:AIR3_APK_KEYSTORE } else { Join-Path $repoRoot "tmp\air3-debug.keystore" }
$appId = if ($env:AIR3_APK_APP_ID) { $env:AIR3_APK_APP_ID } else { "com.codex.air3nativecamera" }
$appLabel = if ($env:AIR3_APK_APP_LABEL) { $env:AIR3_APK_APP_LABEL } else { "叮当X" }
$outputName = if ($env:AIR3_APK_OUTPUT_NAME) { $env:AIR3_APK_OUTPUT_NAME } else { "Air3NativeCameraTest" }
$versionCode = if ($env:AIR3_APK_VERSION_CODE) { [int]$env:AIR3_APK_VERSION_CODE } else { 215 }
$versionName = if ($env:AIR3_APK_VERSION_NAME) { $env:AIR3_APK_VERSION_NAME } else { "2.0.15" }
$unsigned = Join-Path $build "$outputName-unsigned.apk"
$unaligned = Join-Path $build "$outputName-unaligned.apk"
$aligned = Join-Path $build "$outputName-aligned.apk"
$signed = Join-Path $build "$outputName.apk"
$manifestForBuild = Join-Path $build "AndroidManifest.xml"
$stringsForBuild = Join-Path $build "res\values\strings.xml"
$localOpsKeyPath = Join-Path $repoRoot "tmp\ops_glasses_api_key.local"
$localDirectGptKeyPath = Join-Path $repoRoot "tmp\direct_gpt_api_key.local"
$localDirectAsrKeyPath = Join-Path $repoRoot "tmp\direct_asr_api_key.local"
$gitSha = "nogit"
$gitOutput = & git -C $repoRoot rev-parse --short HEAD 2>$null
if ($LASTEXITCODE -eq 0 -and $gitOutput) {
  $gitSha = ($gitOutput | Select-Object -First 1).Trim()
}
$versionedSigned = Join-Path $build "$outputName-v$versionName-$gitSha.apk"

$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk "bin") + ";" + $env:PATH

Remove-Item -LiteralPath $buildRes, $compiledRes, $generatedSrc, $classes, $dex -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $buildRes, $compiledRes, $generatedSrc, $classes, $dex | Out-Null
Get-ChildItem -LiteralPath $res -Force | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination $buildRes -Recurse -Force
}

(Get-Content -LiteralPath $manifest -Raw -Encoding UTF8).
  Replace('package="com.codex.air3nativecamera"', "package=`"$appId`"").
  Replace('android:name=".MainActivity"', 'android:name="com.codex.air3nativecamera.MainActivity"') |
  Set-Content -LiteralPath $manifestForBuild -Encoding UTF8

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $stringsForBuild) | Out-Null
$escapedAppLabelXml = [System.Security.SecurityElement]::Escape($appLabel)
@"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$escapedAppLabelXml</string>
</resources>
"@ | Set-Content -LiteralPath $stringsForBuild -Encoding UTF8

& $aapt2 compile --dir $buildRes -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$flatFiles = Get-ChildItem -LiteralPath $compiledRes -Filter "*.flat" | ForEach-Object { $_.FullName }
& $aapt2 link `
  -I $androidJar `
  --manifest $manifestForBuild `
  --min-sdk-version 34 `
  --target-sdk-version 34 `
  --version-code $versionCode `
  --version-name $versionName `
  -o $unsigned `
  $flatFiles
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$generatedDir = Join-Path $generatedSrc "com\codex\air3nativecamera"
$generatedConfig = Join-Path $generatedDir "GeneratedConfig.java"
New-Item -ItemType Directory -Force -Path $generatedDir | Out-Null
$opsEndpoint = if ($env:OPS_GLASSES_EVENTS_ENDPOINT) { $env:OPS_GLASSES_EVENTS_ENDPOINT } else { "https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses/sessions/events" }
$opsKeySource = "missing"
if ($env:OPS_GLASSES_API_KEY) {
  $opsKey = $env:OPS_GLASSES_API_KEY.Trim()
  $opsKeySource = "env"
} elseif (Test-Path -LiteralPath $localOpsKeyPath) {
  $opsKey = (Get-Content -LiteralPath $localOpsKeyPath -Raw).Trim()
  $opsKeySource = "tmp/ops_glasses_api_key.local"
} else {
  $opsKey = ""
}
if ($env:AIR3_APK_DIRECT_GPT -ne "1" -and $opsKey.Length -eq 0) {
  throw "OPS_GLASSES_API_KEY missing. Set env:OPS_GLASSES_API_KEY or create tmp/ops_glasses_api_key.local."
}
$directGptKeySource = "missing"
if ($env:DIRECT_GPT_API_KEY) {
  $directGptKey = $env:DIRECT_GPT_API_KEY.Trim()
  $directGptKeySource = "env"
} elseif (Test-Path -LiteralPath $localDirectGptKeyPath) {
  $directGptKey = (Get-Content -LiteralPath $localDirectGptKeyPath -Raw).Trim()
  $directGptKeySource = "tmp/direct_gpt_api_key.local"
} else {
  $directGptKey = ""
}
if ($env:AIR3_APK_DIRECT_GPT -eq "1" -and $directGptKey.Length -eq 0) {
  throw "DIRECT_GPT_API_KEY missing. Set env:DIRECT_GPT_API_KEY or create tmp/direct_gpt_api_key.local."
}
$directAsrKeySource = "missing"
if ($env:DIRECT_ASR_API_KEY) {
  $directAsrKey = $env:DIRECT_ASR_API_KEY.Trim()
  $directAsrKeySource = "env"
} elseif (Test-Path -LiteralPath $localDirectAsrKeyPath) {
  $directAsrKey = (Get-Content -LiteralPath $localDirectAsrKeyPath -Raw).Trim()
  $directAsrKeySource = "tmp/direct_asr_api_key.local"
} else {
  $directAsrKey = ""
}
$directGptBaseUrl = if ($env:DIRECT_GPT_BASE_URL) { $env:DIRECT_GPT_BASE_URL } else { "https://api.openai.com/v1" }
$directGptModel = if ($env:DIRECT_GPT_MODEL) { $env:DIRECT_GPT_MODEL } else { "gpt-4.1-mini" }
$directGptReasoningEffort = if ($env:DIRECT_GPT_REASONING_EFFORT) { $env:DIRECT_GPT_REASONING_EFFORT } else { "" }
$directAsrEndpoint = if ($env:DIRECT_ASR_ENDPOINT) { $env:DIRECT_ASR_ENDPOINT } else { "" }
$dingdangBackendBaseUrl = if ($env:DINGDANG_BACKEND_BASE_URL) { $env:DINGDANG_BACKEND_BASE_URL } else { $opsEndpoint -replace "/sessions/events$", "" }
$dingdangBackendApiKey = $opsKey
$escapedEndpoint = $opsEndpoint.Replace("\", "\\").Replace('"', '\"')
$escapedKey = $opsKey.Replace("\", "\\").Replace('"', '\"')
$escapedAppLabel = $appLabel.Replace("\", "\\").Replace('"', '\"')
$escapedDingdangBackendBaseUrl = $dingdangBackendBaseUrl.Replace("\", "\\").Replace('"', '\"')
$escapedDingdangBackendApiKey = $dingdangBackendApiKey.Replace("\", "\\").Replace('"', '\"')
$escapedDirectGptBaseUrl = $directGptBaseUrl.Replace("\", "\\").Replace('"', '\"')
$escapedDirectGptModel = $directGptModel.Replace("\", "\\").Replace('"', '\"')
$escapedDirectGptReasoningEffort = $directGptReasoningEffort.Replace("\", "\\").Replace('"', '\"')
$escapedDirectGptKey = $directGptKey.Replace("\", "\\").Replace('"', '\"')
$escapedDirectAsrEndpoint = $directAsrEndpoint.Replace("\", "\\").Replace('"', '\"')
$escapedDirectAsrKey = $directAsrKey.Replace("\", "\\").Replace('"', '\"')
$fastUpload = if ($env:AIR3_APK_FAST_UPLOAD -eq "1") { "true" } else { "false" }
$directGptEnabled = if ($env:AIR3_APK_DIRECT_GPT -eq "1") { "true" } else { "false" }
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$generatedConfigSource = @"
package com.codex.air3nativecamera;

final class GeneratedConfig {
    static final String APP_ID = "$appId";
    static final String APP_LABEL = "$escapedAppLabel";
    static final String EVENTS_ENDPOINT = "$escapedEndpoint";
    static final String OPS_GLASSES_API_KEY = "$escapedKey";
    static final String DINGDANG_BACKEND_BASE_URL = "$escapedDingdangBackendBaseUrl";
    static final String DINGDANG_BACKEND_API_KEY = "$escapedDingdangBackendApiKey";
    static final boolean FAST_UPLOAD = $fastUpload;
    static final boolean DIRECT_GPT_ENABLED = $directGptEnabled;
    static final String DIRECT_GPT_BASE_URL = "$escapedDirectGptBaseUrl";
    static final String DIRECT_GPT_MODEL = "$escapedDirectGptModel";
    static final String DIRECT_GPT_REASONING_EFFORT = "$escapedDirectGptReasoningEffort";
    static final String DIRECT_GPT_API_KEY = "$escapedDirectGptKey";
    static final String DIRECT_ASR_ENDPOINT = "$escapedDirectAsrEndpoint";
    static final String DIRECT_ASR_API_KEY = "$escapedDirectAsrKey";

    private GeneratedConfig() {
    }
}
"@
[System.IO.File]::WriteAllText($generatedConfig, $generatedConfigSource, $utf8NoBom)
$javaFiles = @(
  Get-ChildItem -LiteralPath $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
  Get-ChildItem -LiteralPath $generatedSrc -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
)
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Remove-Item -LiteralPath $classesJar -Force -ErrorAction SilentlyContinue
& $jar --create --file $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& $d8 --lib $androidJar --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item -LiteralPath $unsigned -Destination $unaligned -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkZip = [System.IO.Compression.ZipFile]::Open($unaligned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $existingDex = $apkZip.GetEntry("classes.dex")
  if ($existingDex -ne $null) {
    $existingDex.Delete()
  }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $apkZip,
    (Join-Path $dex "classes.dex"),
    "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
  $apkZip.Dispose()
}

& $zipalign -f 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path -LiteralPath $keystore)) {
  & $keytool `
    -genkeypair `
    -keystore $keystore `
    -storepass android `
    -keypass android `
    -alias androiddebugkey `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -dname "CN=Android Debug,O=Android,C=US"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

& $apksigner sign `
  --ks $keystore `
  --ks-pass pass:android `
  --key-pass pass:android `
  --out $signed `
  $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --verbose $signed
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Copy-Item -LiteralPath $signed -Destination $versionedSigned -Force
Write-Output "VersionCode=$versionCode VersionName=$versionName Git=$gitSha"
Write-Output "OpsKeySource=$opsKeySource"
Write-Output "DirectGptKeySource=$directGptKeySource"
Write-Output "DirectAsrKeySource=$directAsrKeySource"
Write-Output $signed
Write-Output $versionedSigned
