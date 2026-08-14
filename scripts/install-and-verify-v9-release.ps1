param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangexpert.v9",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$ApkPath = "output\v9.0.0-formal-release-current\DingdangAI-V9-9.0.0-release.apk",
  [string]$ReleaseManifestPath = "output\v9.0.0-formal-release-current\release-manifest.json",
  [int]$ExpectedVersionCode = 900000,
  [string]$ExpectedVersionName = "9.0.0",
  [string]$ProtectedV8Package = "com.codex.air3nativecamera.dingdangexpert.follow.preview.investorv8audit48",
  [int]$WaitSeconds = 120,
  [string]$OutDir = "",
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath {
  param([string]$PathValue)
  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return $PathValue
  }
  return Join-Path $repoRoot $PathValue
}

function Resolve-AdbPath {
  $candidates = New-Object System.Collections.Generic.List[string]
  $candidates.Add((Join-Path $repoRoot "tmp\tools\platform-tools\adb.exe"))
  try {
    $gitCommonDirValue = (& git -C $repoRoot rev-parse --git-common-dir 2>$null | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0 -and $gitCommonDirValue) {
      $gitCommonDir = if ([System.IO.Path]::IsPathRooted($gitCommonDirValue)) {
        $gitCommonDirValue
      } else {
        Join-Path $repoRoot $gitCommonDirValue
      }
      $mainRepoRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($gitCommonDir))
      $candidates.Add((Join-Path $mainRepoRoot "tmp\tools\platform-tools\adb.exe"))
    }
  } catch {
  }
  foreach ($sdk in @(
      $env:ANDROID_SDK_ROOT,
      $env:ANDROID_HOME,
      "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK")) {
    if ($sdk) {
      $candidates.Add((Join-Path $sdk "platform-tools\adb.exe"))
    }
  }
  foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
      return $candidate
    }
  }
  $pathCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
  if ($pathCommand) {
    return $pathCommand.Source
  }
  throw "adb.exe was not found in the worktree, main repository, Android SDK, or PATH."
}

function Invoke-AdbText {
  param([string[]]$Arguments)
  return (& $adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
}

function Resolve-AndroidSdk {
  foreach ($candidate in @(
      $env:ANDROID_SDK_ROOT,
      $env:ANDROID_HOME,
      "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK")) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate "build-tools\34.0.0\aapt2.exe"))) {
      return $candidate
    }
  }
  throw "Android SDK build-tools 34.0.0 are required."
}

function Get-PackageVersion {
  param([string]$PackageName)
  $dump = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $PackageName)
  if ($dump -notmatch "versionCode=(\d+)") {
    return $null
  }
  $versionCode = [int64]$matches[1]
  $versionName = ""
  if ($dump -match "versionName=([^\s]+)") {
    $versionName = $matches[1]
  }
  return [pscustomobject]@{
    versionCode = $versionCode
    versionName = $versionName
  }
}

$apk = Resolve-RepoPath -PathValue $ApkPath
$releaseManifestFile = Resolve-RepoPath -PathValue $ReleaseManifestPath
if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "tmp\v9-production-install-$stamp"
} else {
  $OutDir = Resolve-RepoPath -PathValue $OutDir
}

if ($DryRun) {
  Write-Output "Dry run for formal V9 installation."
  Write-Output "Would verify $Package $ExpectedVersionCode/$ExpectedVersionName."
  Write-Output "Would verify APK and release manifest under the repository output directory."
  Write-Output "Would install with adb install -r -g, resolve the launcher activity, and verify V8 coexistence when present."
  Write-Output "Would write non-secret installation evidence to $OutDir."
  return
}

$adb = Resolve-AdbPath
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
  throw "Formal V9 APK not found: $apk"
}
if (-not (Test-Path -LiteralPath $releaseManifestFile -PathType Leaf)) {
  throw "Formal V9 release manifest not found: $releaseManifestFile"
}

$releaseManifest = Get-Content -Raw -Encoding UTF8 $releaseManifestFile | ConvertFrom-Json
if ($releaseManifest.applicationId -ne $Package -or
    [int64]$releaseManifest.versionCode -ne $ExpectedVersionCode -or
    $releaseManifest.versionName -ne $ExpectedVersionName) {
  throw "Formal V9 release manifest identity does not match the requested installation."
}
$formalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToUpperInvariant()
if ($formalHash -ne $releaseManifest.sha256.ToString().ToUpperInvariant()) {
  throw "Formal V9 APK SHA-256 does not match release-manifest.json."
}

$androidSdk = Resolve-AndroidSdk
$aapt2 = Join-Path $androidSdk "build-tools\34.0.0\aapt2.exe"
$badging = (& $aapt2 dump badging $apk | Select-Object -First 1)
if ($badging -notmatch "name='$([regex]::Escape($Package))'" -or
    $badging -notmatch "versionCode='$ExpectedVersionCode'" -or
    $badging -notmatch "versionName='$([regex]::Escape($ExpectedVersionName))'") {
  throw "Formal V9 APK identity check failed: $badging"
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$deviceFound = $false
while ((Get-Date) -lt $deadline) {
  $devices = Invoke-AdbText -Arguments @("devices", "-l")
  if ($devices -match "(?m)^$([regex]::Escape($Serial))\s+device\b") {
    $deviceFound = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $deviceFound) {
  throw "Air3 device not found by adb: $Serial"
}

$protectedBefore = Get-PackageVersion -PackageName $ProtectedV8Package

& $adb -s $Serial install -r -g $apk
if ($LASTEXITCODE -ne 0) {
  throw "Formal V9 adb install failed."
}
& $adb -s $Serial shell pm grant $Package android.permission.CAMERA 2>$null
& $adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null

$installedVersion = Get-PackageVersion -PackageName $Package
if (-not $installedVersion -or
    $installedVersion.versionCode -ne $ExpectedVersionCode -or
    $installedVersion.versionName -ne $ExpectedVersionName) {
  throw "Installed V9 package identity does not match $ExpectedVersionCode/$ExpectedVersionName."
}

$resolvedText = Invoke-AdbText -Arguments @(
  "-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", $Package)
$resolvedRows = @($resolvedText -split "`r?`n" |
  Where-Object { $_ -match "^$([regex]::Escape($Package))/" } |
  Select-Object -Last 1)
$resolvedComponent = if ($resolvedRows.Count -gt 0) { $resolvedRows[0].Trim() } else { "" }
if (-not $resolvedComponent -or $resolvedComponent -ne "$Package/$Activity") {
  throw "Formal V9 launcher activity did not resolve: $resolvedText"
}

$remoteApk = ((Invoke-AdbText -Arguments @("-s", $Serial, "shell", "pm", "path", $Package)) -split "`r?`n" |
  Where-Object { $_ -match "base\.apk$" } |
  Select-Object -First 1) -replace "^package:", ""
if (-not $remoteApk) {
  throw "Installed V9 base.apk path was not reported."
}
$deviceHashLine = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "sha256sum", $remoteApk.Trim())
$deviceHash = (($deviceHashLine -split "\s+")[0]).ToUpperInvariant()
if ($deviceHash -ne $formalHash) {
  throw "Installed V9 APK SHA-256 does not match the formal APK."
}

& $adb -s $Serial logcat -c | Out-Null
& $adb -s $Serial shell am force-stop $Package | Out-Null
$launch = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "am", "start", "-W", "--display", "0", "-n", $resolvedComponent)
if ($launch -notmatch "Status:\s*ok") {
  throw "Formal V9 launcher did not report Status: ok."
}

$foreground = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
  $activityState = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "activity", "activities")
  if ($activityState -match "topResumedActivity=.*$([regex]::Escape($Package))") {
    $foreground = $true
    break
  }
  Start-Sleep -Milliseconds 250
}
if (-not $foreground) {
  throw "Formal V9 did not become the resumed Air3 activity."
}

$protectedAfter = Get-PackageVersion -PackageName $ProtectedV8Package
if ($protectedBefore -and (-not $protectedAfter -or
    $protectedAfter.versionCode -ne $protectedBefore.versionCode -or
    $protectedAfter.versionName -ne $protectedBefore.versionName)) {
  throw "Protected V8 package changed during V9 installation."
}

$crashText = Invoke-AdbText -Arguments @("-s", $Serial, "logcat", "-b", "crash", "-d")
$crashBufferEmpty = [string]::IsNullOrWhiteSpace($crashText)
if (-not $crashBufferEmpty) {
  throw "Android crash buffer is not empty after formal V9 launch."
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = [ordered]@{
  packageName = $Package
  versionCode = $ExpectedVersionCode
  versionName = $ExpectedVersionName
  formalApkSha256 = $formalHash
  installedApkSha256 = $deviceHash
  hashMatches = ($formalHash -eq $deviceHash)
  resolvedComponent = $resolvedComponent
  foreground = $foreground
  crashBufferEmpty = $crashBufferEmpty
  protectedV8Package = $ProtectedV8Package
  protectedV8Present = [bool]$protectedBefore
  protectedV8Unchanged = (-not $protectedBefore) -or (
    $protectedAfter.versionCode -eq $protectedBefore.versionCode -and
    $protectedAfter.versionName -eq $protectedBefore.versionName)
  generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutDir "summary.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 4
