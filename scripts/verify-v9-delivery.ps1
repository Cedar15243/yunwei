param(
  [string]$DeliveryRoot = ""
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
  throw "V9 delivery self-verification failed: $Message"
}

function Normalize-RelativePath([string]$Value) {
  $text = if ($null -eq $Value) { "" } else { [string]$Value }
  $normalized = $text.Trim().Replace("\", "/")
  if (-not $normalized) { Fail "empty relative path" }
  if ([IO.Path]::IsPathRooted($normalized)) { Fail "rooted path is forbidden: $normalized" }
  $segments = @($normalized.Split("/"))
  $invalidSegments = @($segments | Where-Object { -not $_ -or $_ -eq "." -or $_ -eq ".." })
  if ($segments.Count -eq 0 -or $invalidSegments.Count -gt 0) {
    Fail "path traversal or empty segment is forbidden: $normalized"
  }
  return ($segments -join "/")
}

function Resolve-DeliveryFile([string]$Root, [string]$Relative) {
  $normalized = Normalize-RelativePath $Relative
  $candidate = [IO.Path]::GetFullPath((Join-Path $Root $normalized.Replace("/", [IO.Path]::DirectorySeparatorChar)))
  $prefix = $Root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
    Fail "path escapes delivery root: $normalized"
  }
  return $candidate
}

function Require-FiniteNumber([object]$Value, [string]$Name) {
  if ($null -eq $Value) { Fail "$Name is missing" }
  try {
    $number = [double]$Value
  } catch {
    Fail "$Name is not numeric"
  }
  if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) {
    Fail "$Name is not finite"
  }
  return $number
}

function Test-V9OpsUri([string]$Uri, [string]$Target) {
  try {
    $parsed = [Uri]$Uri
    $expected = [Uri]$Target
  } catch {
    return $false
  }
  if ($parsed.Scheme -ne $expected.Scheme -or
      $parsed.Host -ne $expected.Host -or
      $parsed.Port -ne $expected.Port) {
    return $false
  }
  return $parsed.AbsolutePath -eq "/v9-ops" -or $parsed.AbsolutePath.StartsWith("/v9-ops/", [StringComparison]::Ordinal)
}

function Verify-V9DastReport([string]$Root) {
  $reportPath = Resolve-DeliveryFile $Root "security/dast/v9-zap-baseline.json"
  $readmePath = Resolve-DeliveryFile $Root "security/dast/README.md"
  if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { Fail "DAST JSON report is missing" }
  if (-not (Test-Path -LiteralPath $readmePath -PathType Leaf)) { Fail "DAST README is missing" }
  $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $reportPath | ConvertFrom-Json
  if ($report.'@programName' -ne "ZAP") { Fail "DAST program must be ZAP" }
  if ($report.'@version' -ne "2.17.0") { Fail "DAST version must be 2.17.0" }
  $target = "https://bb.chinacedar.top:2305"
  $sites = @($report.site)
  if ($sites.Count -ne 1 -or $sites[0].'@name' -ne $target) { Fail "DAST target site mismatch" }
  $high = 0
  $medium = 0
  $low = 0
  $instanceCount = 0
  foreach ($alert in @($sites[0].alerts)) {
    try { $risk = [int]$alert.riskcode } catch { Fail "DAST alert riskcode is invalid" }
    if ($risk -lt 0 -or $risk -gt 3) { Fail "DAST alert riskcode is invalid" }
    if ($risk -eq 3) { $high++ }
    elseif ($risk -eq 2) { $medium++ }
    elseif ($risk -eq 1) { $low++ }
    $instances = @($alert.instances)
    if ($instances.Count -eq 0) { Fail "DAST alert instances must not be empty" }
    foreach ($instance in $instances) {
      if (-not (Test-V9OpsUri ([string]$instance.uri) $target)) {
        Fail "DAST alert instance is outside /v9-ops: $($instance.uri)"
      }
      $instanceCount++
    }
  }
  if ($instanceCount -eq 0) { Fail "DAST report contains no alert instances" }
  if ($high -ne 0 -or $medium -ne 0 -or $low -ne 0) {
    Fail "DAST risk counts must be zero: high=$high medium=$medium low=$low"
  }
  $readme = Get-Content -Raw -Encoding UTF8 -LiteralPath $readmePath
  foreach ($marker in @("development-team authorized passive DAST", "third-party penetration", "pending")) {
    if ($readme.IndexOf($marker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
      Fail "DAST README is missing boundary marker: $marker"
    }
  }
}

if (-not $DeliveryRoot) {
  $DeliveryRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
$root = [IO.Path]::GetFullPath($DeliveryRoot)
if (-not (Test-Path -LiteralPath $root -PathType Container)) { Fail "delivery root is missing: $root" }

$checksumPath = Join-Path $root "SHA256SUMS.txt"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { Fail "SHA256SUMS.txt is missing" }

$expected = @{}
foreach ($line in Get-Content -Encoding ASCII -LiteralPath $checksumPath) {
  if (-not $line.Trim()) { continue }
  if ($line -notmatch '^([A-Fa-f0-9]{64})\s{2,}(.+)$') { Fail "invalid checksum line: $line" }
  $relative = Normalize-RelativePath $Matches[2]
  if ($expected.ContainsKey($relative)) { Fail "duplicate checksum path: $relative" }
  $absolute = Resolve-DeliveryFile $root $relative
  if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) { Fail "checksummed file is missing: $relative" }
  $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash.ToUpperInvariant()
  $expectedHash = $Matches[1].ToUpperInvariant()
  if ($actualHash -ne $expectedHash) { Fail "hash mismatch: $relative" }
  $expected[$relative] = $expectedHash
}
if ($expected.Count -eq 0) { Fail "checksum list is empty" }

$actualFiles = @(
  Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object { $_.FullName -ne $checksumPath } |
    ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace("\", "/") } |
    Sort-Object
)
$expectedFiles = @($expected.Keys | Sort-Object)
if ($actualFiles.Count -ne $expectedFiles.Count) {
  Fail "file count mismatch expected=$($expectedFiles.Count) actual=$($actualFiles.Count)"
}
foreach ($relative in $actualFiles) {
  if (-not $expected.ContainsKey($relative)) { Fail "unexpected file: $relative" }
}

$deliveryManifestPath = Join-Path $root "DELIVERY_MANIFEST.json"
if (-not (Test-Path -LiteralPath $deliveryManifestPath -PathType Leaf)) { Fail "DELIVERY_MANIFEST.json is missing" }
$deliveryManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $deliveryManifestPath | ConvertFrom-Json
if ($deliveryManifest.product -ne "Dingdang AI Operations Glasses") { Fail "unexpected product identifier" }
if ($deliveryManifest.release -ne "V9.0.0") { Fail "unexpected release" }
if ($deliveryManifest.applicationId -ne "com.codex.air3nativecamera.dingdangexpert.v9") { Fail "unexpected applicationId" }
if ([int64]$deliveryManifest.versionCode -ne 900000) { Fail "unexpected versionCode" }
if ($deliveryManifest.secureRuntime -ne $true) { Fail "secureRuntime must be true" }
if ($deliveryManifest.externalPrerequisitesRequired -ne $true) { Fail "external prerequisite gate is missing" }
if ($deliveryManifest.productionDeploymentStatus -ne "pending_external_credentials_and_hardware_acceptance") {
  Fail "production deployment status is not fail-closed"
}
$modelContract = $deliveryManifest.modelContract
if ($modelContract.ai -ne "qwen3-vl-plus" -or
    $modelContract.asr -ne "fun-asr-realtime" -or
    $modelContract.wake -ne "previous-iflytek-aikit" -or
    $modelContract.voiceprint -ne "s1aa729d0") {
  Fail "fixed model contract mismatch"
}
Verify-V9DastReport $root

$manifestArtifacts = @($deliveryManifest.artifacts)
$artifactPaths = @{}
foreach ($artifact in $manifestArtifacts) {
  $relative = Normalize-RelativePath ([string]$artifact.path)
  if ($artifactPaths.ContainsKey($relative)) { Fail "duplicate manifest artifact: $relative" }
  $absolute = Resolve-DeliveryFile $root $relative
  if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) { Fail "manifest artifact is missing: $relative" }
  $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash.ToUpperInvariant()
  if ($actualHash -ne ([string]$artifact.sha256).ToUpperInvariant()) { Fail "manifest hash mismatch: $relative" }
  if ((Get-Item -LiteralPath $absolute).Length -ne [int64]$artifact.bytes) { Fail "manifest size mismatch: $relative" }
  $artifactPaths[$relative] = $true
}
$expectedArtifactPaths = @($actualFiles | Where-Object { $_ -ne "DELIVERY_MANIFEST.json" } | Sort-Object)
if ($manifestArtifacts.Count -ne $expectedArtifactPaths.Count) {
  Fail "manifest artifact count mismatch expected=$($expectedArtifactPaths.Count) actual=$($manifestArtifacts.Count)"
}
foreach ($relative in $expectedArtifactPaths) {
  if (-not $artifactPaths.ContainsKey($relative)) { Fail "file is missing from delivery manifest: $relative" }
}

$releaseManifestPath = Join-Path $root "android\release-manifest.json"
$apkPath = Join-Path $root "android\DingdangAI-V9-9.0.0-release.apk"
if (-not (Test-Path -LiteralPath $releaseManifestPath -PathType Leaf)) { Fail "Android release manifest is missing" }
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { Fail "formal APK is missing" }
$releaseManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $releaseManifestPath | ConvertFrom-Json
if ($releaseManifest.applicationId -ne $deliveryManifest.applicationId -or
    [int64]$releaseManifest.versionCode -ne [int64]$deliveryManifest.versionCode -or
    $releaseManifest.versionName -ne "9.0.0" -or
    $releaseManifest.aiModel -ne $modelContract.ai -or
    $releaseManifest.asrModel -ne $modelContract.asr -or
    $releaseManifest.voiceprintService -ne $modelContract.voiceprint -or
    $releaseManifest.secureRuntime -ne $true -or
    $releaseManifest.directGptEnabled -ne $false) {
  Fail "Android release manifest contract mismatch"
}
$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash.ToUpperInvariant()
if ($apkHash -ne ([string]$releaseManifest.sha256).ToUpperInvariant()) { Fail "formal APK hash mismatch" }

$air3SoakRelative = "verification/air3-soak/summary.json"
$air3SoakPath = Resolve-DeliveryFile $root $air3SoakRelative
if (-not (Test-Path -LiteralPath $air3SoakPath -PathType Leaf)) {
  Fail "Air3 soak summary is missing"
}
$air3SoakContent = Get-Content -Raw -Encoding UTF8 -LiteralPath $air3SoakPath
$air3Soak = $air3SoakContent | ConvertFrom-Json
if ([int]$air3Soak.schemaVersion -ne 1 -or
    $air3Soak.evidenceType -ne "local_air3_hardware_soak") {
  Fail "Air3 soak contract mismatch"
}
if ($air3Soak.release.applicationId -ne $releaseManifest.applicationId -or
    [int64]$air3Soak.release.versionCode -ne [int64]$releaseManifest.versionCode -or
    $air3Soak.release.versionName -ne $releaseManifest.versionName -or
    ([string]$air3Soak.release.apkSha256).ToUpperInvariant() -ne $apkHash) {
  Fail "Air3 soak release binding mismatch"
}
try {
  $soakCapturedAt = [DateTimeOffset]::Parse([string]$air3Soak.capturedAt)
  $releaseBuiltAt = [DateTimeOffset]::Parse([string]$releaseManifest.builtAt)
} catch {
  Fail "Air3 soak or release timestamp is invalid"
}
if ($soakCapturedAt -lt $releaseBuiltAt) { Fail "Air3 soak predates the formal release" }
if ($air3Soak.device.model -ne "IMA301" -or
    ([string]$air3Soak.device.serialMasked) -notmatch '^YM00\.\.\.0031$') {
  Fail "Air3 soak device identity is invalid or not masked"
}

$soak = $air3Soak.measurements
$durationRequested = Require-FiniteNumber $soak.durationSecondsRequested "Air3 soak duration requested"
$durationObserved = Require-FiniteNumber $soak.durationSecondsObserved "Air3 soak duration observed"
if ($durationRequested -lt 1200 -or $durationObserved -lt $durationRequested) {
  Fail "Air3 soak duration is below the 20-minute release contract"
}
$sampleCount = Require-FiniteNumber $soak.sampleCount "Air3 soak sample count"
$cameraCycles = Require-FiniteNumber $soak.cameraCyclesRequested "Air3 soak camera cycle count"
if ($sampleCount -lt 41) { Fail "Air3 soak sample count is incomplete" }
if ($cameraCycles -lt 3 -or $soak.cameraCyclesPassed -ne $true) {
  Fail "Air3 soak camera cycle evidence is incomplete"
}

$batteryTemperature = Require-FiniteNumber $soak.maxBatteryTemperatureC "Air3 soak battery temperature"
$thermalStatus = Require-FiniteNumber $soak.maxThermalStatus "Air3 soak thermal status"
if ($batteryTemperature -gt 45) { Fail "Air3 soak battery temperature exceeds budget" }
if ($thermalStatus -gt 2) { Fail "Air3 soak thermal status exceeds budget" }
$null = Require-FiniteNumber $soak.thermalSensorPeaksC.cpu "Air3 soak CPU thermal sensor peak"
$null = Require-FiniteNumber $soak.thermalSensorPeaksC.gpu "Air3 soak GPU thermal sensor peak"
$null = Require-FiniteNumber $soak.thermalSensorPeaksC.skin "Air3 soak skin thermal sensor peak"
$thermalSensorSampleCount = Require-FiniteNumber $soak.thermalSensorSampleCount "Air3 soak thermal sensor sample count"
if ($thermalSensorSampleCount -ne $sampleCount) {
  Fail "Air3 soak thermal sensor sample count does not match sampleCount"
}

$baselinePssKb = Require-FiniteNumber $soak.baselinePssKb "Air3 soak baseline PSS"
$maxPssKb = Require-FiniteNumber $soak.maxPssKb "Air3 soak maximum PSS"
$pssDeltaKb = Require-FiniteNumber $soak.pssDeltaKb "Air3 soak PSS delta"
if ($pssDeltaKb -lt 0 -or $pssDeltaKb -gt 30000 -or
    [Math]::Abs(($maxPssKb - $baselinePssKb) - $pssDeltaKb) -gt 0.5) {
  Fail "Air3 soak PSS delta exceeds budget or is inconsistent"
}
$jankPercent = Require-FiniteNumber $soak.finalJankPercent "Air3 soak jank"
if ($jankPercent -lt 0 -or $jankPercent -gt 10) { Fail "Air3 soak jank exceeds budget" }

if ($soak.externalPowerConnected -isnot [bool] -or
    $soak.powerMeasurementValid -isnot [bool] -or
    $soak.powerMeasurementValid -ne (-not $soak.externalPowerConnected)) {
  Fail "Air3 soak power measurement validity contradicts external power state"
}
if ($soak.cameraLeak -ne $false) { Fail "Air3 soak camera leak must be false" }
if ($soak.audioLeak -ne $false) { Fail "Air3 soak audio leak must be false" }
if ($soak.crashBufferEmpty -ne $true) { Fail "Air3 soak crash buffer must be empty" }
if ($soak.anrObserved -ne $false) { Fail "Air3 soak ANR must not be observed" }
if ($soak.foregroundFinal -ne $true) { Fail "Air3 soak final foreground state is invalid" }
if ($soak.protectedV8Present -ne $true -or $soak.protectedV8Unchanged -ne $true) {
  Fail "Air3 soak protected V8 package is missing or changed"
}
if ($soak.passed -ne $true) { Fail "Air3 soak passed flag must be true" }

$limitations = @($air3Soak.limitations)
foreach ($requiredLimitation in @(
    "usb_powered_power_measurement_invalid",
    "managed_services_unavailable",
    "production_voice_voiceprint_not_accepted")) {
  if ($limitations -notcontains $requiredLimitation) {
    Fail "Air3 soak limitation is missing: $requiredLimitation"
  }
}
foreach ($rawName in @("snapshots.json", "logcat-all.txt", "logcat-crash.txt", "logcat-filtered.txt")) {
  $rawPath = Resolve-DeliveryFile $root "verification/air3-soak/$rawName"
  if (Test-Path -LiteralPath $rawPath) { Fail "raw Air3 soak evidence is forbidden: $rawName" }
}
if ($air3SoakContent -match '(?i)\bYM00(?!\.\.\.)[A-Z0-9]{8,}\b|[A-Z]:\\Users\\|\.worktrees') {
  Fail "Air3 soak summary contains private device or workstation identifiers"
}

Write-Output "V9 delivery self-verification passed"
Write-Output "VerifiedFiles=$($expected.Count)"
Write-Output "ManifestArtifacts=$($manifestArtifacts.Count)"
Write-Output "APK_SHA256=$apkHash"
Write-Output "Air3Soak=passed"
