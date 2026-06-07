$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$unityAndroid = "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer"
$sdk = Join-Path $unityAndroid "SDK"
$jdk = Join-Path $unityAndroid "OpenJDK"
$buildTools = Join-Path $sdk "build-tools\34.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
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
$compiledRes = Join-Path $build "compiled-res"
$generatedSrc = Join-Path $build "generated-src"
$classes = Join-Path $build "classes"
$dex = Join-Path $build "dex"
$unsigned = Join-Path $build "Air3NativeCameraTest-unsigned.apk"
$unaligned = Join-Path $build "Air3NativeCameraTest-unaligned.apk"
$aligned = Join-Path $build "Air3NativeCameraTest-aligned.apk"
$signed = Join-Path $build "Air3NativeCameraTest.apk"
$keystore = Join-Path $build "debug.keystore"
$repoRoot = Split-Path -Parent $root
$versionCode = if ($env:AIR3_APK_VERSION_CODE) { [int]$env:AIR3_APK_VERSION_CODE } else { 212 }
$versionName = if ($env:AIR3_APK_VERSION_NAME) { $env:AIR3_APK_VERSION_NAME } else { "2.0.12" }
$localOpsKeyPath = Join-Path $repoRoot "tmp\ops_glasses_api_key.local"
$gitSha = "nogit"
$gitOutput = & git -C $repoRoot rev-parse --short HEAD 2>$null
if ($LASTEXITCODE -eq 0 -and $gitOutput) {
  $gitSha = ($gitOutput | Select-Object -First 1).Trim()
}
$versionedSigned = Join-Path $build "Air3NativeCameraTest-v$versionName-$gitSha.apk"

$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk "bin") + ";" + $env:PATH

Remove-Item -LiteralPath $compiledRes, $generatedSrc, $classes, $dex -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $compiledRes, $generatedSrc, $classes, $dex | Out-Null

& $aapt2 compile --dir $res -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$flatFiles = Get-ChildItem -LiteralPath $compiledRes -Filter "*.flat" | ForEach-Object { $_.FullName }
& $aapt2 link `
  -I $androidJar `
  --manifest $manifest `
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
if ($opsKey.Length -eq 0) {
  throw "OPS_GLASSES_API_KEY missing. Set env:OPS_GLASSES_API_KEY or create tmp/ops_glasses_api_key.local."
}
$escapedEndpoint = $opsEndpoint.Replace("\", "\\").Replace('"', '\"')
$escapedKey = $opsKey.Replace("\", "\\").Replace('"', '\"')
@"
package com.codex.air3nativecamera;

final class GeneratedConfig {
    static final String EVENTS_ENDPOINT = "$escapedEndpoint";
    static final String OPS_GLASSES_API_KEY = "$escapedKey";

    private GeneratedConfig() {
    }
}
"@ | Set-Content -LiteralPath $generatedConfig -Encoding ASCII
$javaFiles = @(
  Get-ChildItem -LiteralPath $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
  Get-ChildItem -LiteralPath $generatedSrc -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
)
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classFiles = Get-ChildItem -LiteralPath $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $d8 --lib $androidJar --output $dex $classFiles
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
Write-Output $signed
Write-Output $versionedSigned
