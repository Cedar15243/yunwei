param(
  [string]$OutputDirectory = "output\v9.0.0-formal-delivery"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$outputRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "output"))
$deliveryRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
if (-not $deliveryRoot.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw "OutputDirectory must stay under output/"
}

$apkCandidates = @(
  (Join-Path $repoRoot "output\v9.0.0-formal-release-rerun\DingdangAI-V9-9.0.0-release.apk"),
  (Join-Path $repoRoot "output\v9.0.0-formal-release\DingdangAI-V9-9.0.0-release.apk"),
  (Join-Path $repoRoot "output\v9.0.0-formal-release-current\DingdangAI-V9-9.0.0-release.apk")
)
$apk = $apkCandidates |
  Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
  ForEach-Object {
    $file = Get-Item -LiteralPath $_
    $manifest = Join-Path $file.DirectoryName "release-manifest.json"
    $sortTime = if (Test-Path -LiteralPath $manifest -PathType Leaf) {
      (Get-Item -LiteralPath $manifest).LastWriteTimeUtc
    } else {
      $file.LastWriteTimeUtc
    }
    [pscustomobject]@{ File = $file; SortTime = $sortTime }
  } |
  Sort-Object SortTime -Descending |
  ForEach-Object { $_.File } |
  Select-Object -First 1
if (-not $apk) { throw "Formal V9 APK is missing. Run scripts/build-v9-release.ps1 first." }

$webDist = Join-Path $repoRoot "ops-management-web\dist"
if (-not (Test-Path -LiteralPath (Join-Path $webDist "index.html") -PathType Leaf)) {
  throw "Management Web dist is missing. Run npm --prefix ops-management-web run build first."
}

if (Test-Path -LiteralPath $deliveryRoot) {
  Remove-Item -LiteralPath $deliveryRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $deliveryRoot -Force | Out-Null

function Copy-RequiredFile([string]$Source, [string]$RelativeDestination) {
  if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Required delivery file is missing: $Source" }
  $destination = Join-Path $deliveryRoot $RelativeDestination
  New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
  Copy-Item -LiteralPath $Source -Destination $destination -Force
}

function Copy-RequiredDirectory([string]$Source, [string]$RelativeDestination) {
  if (-not (Test-Path -LiteralPath $Source -PathType Container)) { throw "Required delivery directory is missing: $Source" }
  $destination = Join-Path $deliveryRoot $RelativeDestination
  New-Item -ItemType Directory -Path $destination -Force | Out-Null
  Copy-Item -Path (Join-Path $Source "*") -Destination $destination -Recurse -Force
}

function Resolve-LatestEvidenceDirectory([string]$Pattern, [string[]]$RequiredFiles) {
  $candidates = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "output") -Directory -Filter $Pattern -ErrorAction SilentlyContinue |
    Where-Object {
      $directory = $_
      ($RequiredFiles | ForEach-Object {
        Test-Path -LiteralPath (Join-Path $directory.FullName $_) -PathType Leaf
      }) -notcontains $false
    } |
    Sort-Object LastWriteTimeUtc -Descending)
  if ($candidates.Count -eq 0) {
    throw "No complete V9 evidence directory matched $Pattern."
  }
  return $candidates[0].FullName
}

function Resolve-LatestDastEvidenceDirectory() {
  $candidates = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "output") -Directory -Filter "v9-dast-*" -ErrorAction SilentlyContinue |
    Where-Object {
      (Test-Path -LiteralPath (Join-Path $_.FullName "v9-zap-baseline.json") -PathType Leaf) -and
      (Test-Path -LiteralPath (Join-Path $_.FullName "v9-zap-baseline.html") -PathType Leaf)
    } |
    Sort-Object LastWriteTimeUtc -Descending)
  if ($candidates.Count -eq 0) {
    throw "No complete V9 DAST evidence directory found. Run the authorized HTTPS ZAP baseline first."
  }
  return $candidates[0].FullName
}

Copy-RequiredFile $apk.FullName "android\DingdangAI-V9-9.0.0-release.apk"
Copy-RequiredFile (Join-Path $apk.DirectoryName "release-manifest.json") "android\release-manifest.json"

Copy-RequiredDirectory $webDist "management-web\dist"
foreach ($file in @("Dockerfile", "nginx.conf", "docker-compose.cloud.yml", "Caddyfile.ops-management.example", ".env.cloud.example", ".dockerignore", "package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")) {
  Copy-RequiredFile (Join-Path $repoRoot "ops-management-web\$file") "management-web\$file"
}
Copy-RequiredDirectory (Join-Path $repoRoot "ops-management-web\deploy") "management-web\deploy"
Copy-RequiredDirectory (Join-Path $repoRoot "ops-management-web\src") "management-web\src"
if (Test-Path -LiteralPath (Join-Path $repoRoot "ops-management-web\public") -PathType Container) {
  Copy-RequiredDirectory (Join-Path $repoRoot "ops-management-web\public") "management-web\public"
}

$gatewayDestination = Join-Path $deliveryRoot "v9-ops-gateway"
New-Item -ItemType Directory -Path $gatewayDestination -Force | Out-Null
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repoRoot "v9-ops-gateway") -File) {
  if ($file.Extension -in @(".py", ".md", ".txt") -and $file.Name -notlike "test_*.py") {
    Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $gatewayDestination $file.Name) -Force
  }
}
$gatewayDeployFiles = @(
  "activate-production-overlay.sh",
  "backup.sh",
  "Caddyfile.snippet",
  "dingdang-v9-backup.service",
  "dingdang-v9-backup.timer",
  "dingdang-v9-gateway.env.example",
  "dingdang-v9-gateway.service",
  "dingdang-v9-monitor.env.example",
  "dingdang-v9-monitor.service",
  "dingdang-v9-monitor.timer",
  "dingdang-v9-voiceprint.env.example",
  "health-check.sh",
  "install.sh",
  "install-monitoring.sh",
  "merge_caddy.py",
  "merge_production_overlay.py",
  "restore-drill.sh",
  "rollback-monitoring.sh",
  "rollback-production-overlay.sh",
  "rollback.sh",
  "stage-production-overlay.sh",
  "update-runtime.sh"
)
foreach ($file in $gatewayDeployFiles) {
  Copy-RequiredFile (Join-Path $repoRoot "v9-ops-gateway\deploy\$file") "v9-ops-gateway\deploy\$file"
}
Copy-RequiredFile (Join-Path $repoRoot "v9-ops-gateway\deploy\monitoring\prometheus-rules.yml") "v9-ops-gateway\deploy\monitoring\prometheus-rules.yml"

Copy-RequiredDirectory (Join-Path $repoRoot "supabase\functions\ops-glasses") "supabase\functions\ops-glasses"
Copy-RequiredDirectory (Join-Path $repoRoot "supabase\migrations") "supabase\migrations"
foreach ($file in @("config.toml", "README.md", ".env.example")) {
  Copy-RequiredFile (Join-Path $repoRoot "supabase\$file") "supabase\$file"
}

foreach ($directory in Get-ChildItem -LiteralPath $deliveryRoot -Directory -Recurse | Where-Object { $_.Name -in @("node_modules", "__pycache__", ".git", ".temp") } | Sort-Object FullName -Descending) {
  Remove-Item -LiteralPath $directory.FullName -Recurse -Force
}

$docsRoot = Join-Path $deliveryRoot "docs"
New-Item -ItemType Directory -Path $docsRoot -Force | Out-Null
$releaseNotes = @(
  '# Dingdang AI Operations Glasses V9.0.0 Release',
  '',
  '## Version',
  '',
  '- applicationId: `com.codex.air3nativecamera.dingdangexpert.v9`',
  '- versionCode: `900000`',
  '- versionName: `9.0.0`',
  '- secure runtime: enabled',
  '- stable APK policy: V9 installs beside and never overwrites the stable APK',
  '',
  '## Model contract',
  '',
  '- main AI/vision: `qwen3-vl-plus`',
  '- realtime ASR: `fun-asr-realtime`',
  '- Xiao Dingdang wake: previous iFlytek AIKit',
  '- voiceprint enrollment and 1:1 verification: iFlytek `s1aa729d0`',
  '- forbidden: OpenClaw, GPT-5.x, DeepSeek, Claude, Qwen Max, and unauthorized new models',
  '',
  '## Package contents',
  '',
  '- `android/`: formal V9 APK and build manifest',
  '- `management-web/`: isolated management Web source, dist, immutable Docker release installer, health checks, Caddy isolation and rollback controls',
  '- management Web includes real API-backed account invitation, role, project-scope and system settings entries; it does not expose provider secrets or fake write controls',
  '- management Web includes cloud project records and human-confirmed project memory, with a real task-timeline link and no fake resume action',
  '- account invitation uses Supabase Auth Admin email only; no client password is accepted or generated, and audit failure rolls the account back',
  '- `v9-ops-gateway/`: HTTPS gateway runtime modules, voiceprint proxy, execution context and allowlisted deployment scripts; repository-only `test_*.py`, caches and ignored production `.local` files are excluded',
  '- `supabase/`: Edge Function, migrations and config templates',
  '- `verification/air3/`: sanitized Air3 screenshots, UI trees and a release-bound local verification manifest; raw logcat is excluded',
  '- `verification/air3-soak/`: sanitized release-bound 20-minute Air3 hardware soak summary; raw snapshots, serials and logcat are excluded',
  '- `security/`: CycloneDX 1.5 SBOM, dependency audit evidence and authorized passive OWASP ZAP DAST evidence',
  '- `security/dast/`: raw ZAP 2.17.0 JSON/HTML for the authorized V9 HTTPS target; the report is validated before packaging',
  '- `security/verify-v9-delivery.ps1`: read-only post-extraction verifier for checksums, manifest identity, model contract and APK binding',
  '- `security/v9-external-acceptance.mjs`: release-bound structured attestation and Ed25519 dual-role approval verifier; private keys are never packaged',
  '- `security/init-v9-external-acceptance.mjs`: initializes a release-bound pending evidence workspace without generating trust stores, signatures or pass results',
  '- `security/record-v9-external-attestation.mjs`: validates and atomically records one completed attestation with confirmation, optimistic locking and signed-manifest protection',
  '- `security/attach-v9-external-approval-signature.mjs`: verifies and atomically attaches an offline/HSM/KMS Ed25519 signature without reading or packaging private keys',
  '- `docs/`: deployment, rollback, prerequisites and model contract',
  '',
  '## Boundary',
  '',
  'This package contains no provider long-lived keys, Supabase service role, admin token, raw voiceprint audio or production `.env`. Production deployment must inject secrets on the target server and complete real Supabase, MVS and Air3 acceptance.'
) -join [Environment]::NewLine
$releaseNotes | Set-Content -LiteralPath (Join-Path $docsRoot "RELEASE_NOTES.md") -Encoding UTF8

$deploymentNotes = @(
  '# Deployment checklist',
  '',
  '0. After extracting the ZIP, run `powershell -NoProfile -ExecutionPolicy Bypass -File security/verify-v9-delivery.ps1 -DeliveryRoot .` and stop if any file, manifest or APK check fails.',
  '1. Put the production browser configuration and dedicated domain in root-owned mode-0600 `/etc/dingdang-ops-management-web.env`, then run `management-web/deploy/install.sh <release-id>`; it builds an immutable image, switches the release pointer, validates/reloads Caddy and checks management, expert and V9 health.',
  '2. Inject production variables from `v9-ops-gateway/deploy/*.env.example` with root-only permissions. For a voiceprint token rotation, first run `update-runtime.sh <release-id>` while keeping the current configuration unchanged so the runtime accepts current and previous hashes.',
  '3. Generate and stage a `transition` production overlay, then activate it with `stage-production-overlay.sh` and `activate-production-overlay.sh`. Verify the new voiceprint management token through the public HTTPS gateway before changing Supabase.',
  '4. Apply `supabase/migrations/`, deploy production secrets and `supabase/functions/ops-glasses`, then complete the real Auth/RLS, content synchronization and device-session checks.',
  '5. After Supabase secrets/Edge and the new token pass end-to-end verification, generate and activate a `steady` overlay that removes the previous hash. Do not remove the previous hash before this point.',
  '6. Configure the management Web Supabase URL, public anon key and V9 API URL.',
  '7. Verify the expert health endpoint first, then V9 health, login, RLS, short-lived device sessions, Skill/knowledge publishing and workflow delivery.',
  '8. Review `security/dast/README.md`: the included DAST is a development-team passive scan, not a third-party penetration test or independent security approval.',
  '9. Install V9 beside the stable APK on Air3 and complete voice, voiceprint, Camera2, work-order, expert, crash/ANR and performance acceptance.',
  '',
  'If real accounts, project ref, MVS protocol, activation configuration or Air3 are missing, the system must fail closed instead of using local fake data.'
) -join [Environment]::NewLine
$deploymentNotes | Set-Content -LiteralPath (Join-Path $docsRoot "DEPLOYMENT.md") -Encoding UTF8

$rollbackNotes = @(
  '# Rollback',
  '',
  '- Management Web: run `management-web/deploy/rollback.sh`; it verifies the previous image ID, restores the previous Caddy snapshot and release pointer, and rechecks management, expert and V9 health without restarting the expert or Caddy containers.',
  '- V9 gateway runtime: `update-runtime.sh` restores the original current/previous pointers and restarts only the V9 gateway when activation fails; for an operator rollback, repoint the verified previous runtime and recheck local/public V9 plus expert health.',
  '- V9 gateway production overlay: run `v9-ops-gateway/deploy/rollback-production-overlay.sh`; it restores the previous root-owned gateway and voiceprint configuration, preserves the original release pointers on failure, and does not restart expert collaboration or Caddy.',
  '- V9 gateway full release: use `v9-ops-gateway/deploy/rollback.sh`, restore the previous release, database backup and Caddy backup, then check expert and V9 health.',
  '- Supabase: apply only audited forward migrations; if a migration fails, stop the release and follow the project backup/migration recovery procedure.',
  '- APK: V9 has an independent applicationId; rollback disables or uninstalls V9 without overwriting the stable APK.'
) -join [Environment]::NewLine
$rollbackNotes | Set-Content -LiteralPath (Join-Path $docsRoot "ROLLBACK.md") -Encoding UTF8

$prerequisites = @(
  '# External prerequisites',
  '',
  '- production Supabase project ref, login, Edge Function deploy permission and secrets',
  '- registered real `DEFAULT_ASSET_TAG` and compliant tcp/HTTPS probe',
  '- formal MVS upload, attachment binding, Form schema, operation dictionary and least-privilege protocol',
  '- production management domain, TLS, Caddy/Docker permissions',
  '- engineer accounts, organization/role/device bindings and activation policy',
  '- online Air3, MDM/RestrictionsManager configuration, voiceprint authorization and a real-device acceptance window',
  '- formal usage/security approval for the original iFlytek AIKit AAR',
  '',
  'Without these prerequisites, local builds and automation prove code contracts only and do not prove online market delivery.'
) -join [Environment]::NewLine
$prerequisites | Set-Content -LiteralPath (Join-Path $docsRoot "EXTERNAL_PREREQUISITES.md") -Encoding UTF8

$modelContract = @(
  '# Fixed model contract',
  '',
  '- main AI/vision: `qwen3-vl-plus`',
  '- realtime ASR: `fun-asr-realtime`',
  '- wake: previous iFlytek AIKit',
  '- voiceprint: iFlytek `s1aa729d0`',
  '',
  'V9 does not use OpenClaw or other new models. Voiceprint service identifiers and provider keys remain server-side in the V9 gateway.'
) -join [Environment]::NewLine
$modelContract | Set-Content -LiteralPath (Join-Path $docsRoot "MODEL_CONTRACT.md") -Encoding UTF8
Copy-RequiredFile (Join-Path $repoRoot "docs\verification\2026-08-08-v9-formal-delivery-package.md") "docs\VERIFICATION.md"
Copy-RequiredFile (Join-Path $repoRoot "docs\audits\2026-08-06-v9-current-state-audit.md") "docs\CURRENT_STATE_AUDIT.md"
Copy-RequiredFile (Join-Path $repoRoot "docs\audits\2026-08-06-v9-completion-matrix.md") "docs\COMPLETION_MATRIX.md"
Copy-RequiredFile (Join-Path $repoRoot "docs\releases\v9.0.0-formal-release.md") "docs\FORMAL_RELEASE.md"
Copy-RequiredFile (Join-Path $repoRoot "docs\verification\2026-08-05-v9-model-contract-runtime.md") "docs\MODEL_CONTRACT_RUNTIME.md"
Copy-RequiredFile (Join-Path $repoRoot "docs\verification\2026-08-09-v9-air3-hardware-soak.md") "docs\AIR3_HARDWARE_SOAK.md"
Copy-RequiredDirectory (Join-Path $repoRoot "docs\operations") "docs\operations"
Copy-RequiredDirectory (Join-Path $repoRoot "docs\security") "docs\security"

$air3EvidenceSource = Resolve-LatestEvidenceDirectory `
  -Pattern "air3-v9-operation-detail-*" `
  -RequiredFiles @("inspection.png", "home.png", "capabilities.xml", "inspection.xml", "back-capabilities.xml", "back-home.xml")
$air3EvidenceContract = Get-Content -Encoding UTF8 -LiteralPath (
  Join-Path $repoRoot "docs\verification\2026-08-08-v9-air3-evidence-contract.json"
) | ConvertFrom-Json
$air3EvidenceRelativeRoot = "verification\air3"
$air3EvidenceNames = @(
  "inspection.png",
  "home.png",
  "capabilities.xml",
  "inspection.xml",
  "back-capabilities.xml",
  "back-home.xml"
)
foreach ($name in $air3EvidenceNames) {
  Copy-RequiredFile (Join-Path $air3EvidenceSource $name) (Join-Path $air3EvidenceRelativeRoot $name)
}
$releaseManifest = Get-Content -Encoding UTF8 -LiteralPath (Join-Path $apk.DirectoryName "release-manifest.json") |
  ConvertFrom-Json
$air3EvidenceArtifacts = @($air3EvidenceNames | ForEach-Object {
    $relative = ($air3EvidenceRelativeRoot + "\" + $_).Replace("\", "/")
    $absolute = Join-Path $deliveryRoot ($relative.Replace("/", [IO.Path]::DirectorySeparatorChar))
    [ordered]@{
      name = $_
      path = $relative
      sha256 = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToUpperInvariant()
      bytes = (Get-Item -LiteralPath $absolute).Length
    }
  })
$air3CapturedAt = (Get-ChildItem -LiteralPath $air3EvidenceSource -File |
  Where-Object { $_.Name -in $air3EvidenceNames } |
  Sort-Object LastWriteTimeUtc -Descending |
  Select-Object -First 1).LastWriteTimeUtc.ToString("o")
$air3EvidenceManifest = [ordered]@{
  schemaVersion = $air3EvidenceContract.schemaVersion
  evidenceType = $air3EvidenceContract.evidenceType
  capturedAt = $air3CapturedAt
  release = [ordered]@{
    applicationId = $releaseManifest.applicationId
    versionCode = $releaseManifest.versionCode
    versionName = $releaseManifest.versionName
    apkSha256 = $releaseManifest.sha256
  }
  device = [ordered]@{
    model = $air3EvidenceContract.device.model
    serialMasked = $air3EvidenceContract.device.serialMasked
  }
  observations = [ordered]@{
    installCommand = $air3EvidenceContract.observations.installCommand
    installSucceeded = $air3EvidenceContract.observations.installSucceeded
    coldStartMs = $air3EvidenceContract.observations.coldStartMs
    followPreviewPackageCountBefore = $air3EvidenceContract.observations.followPreviewPackageCountBefore
    followPreviewPackageCountAfter = $air3EvidenceContract.observations.followPreviewPackageCountAfter
    packageSetHashBeforeAvailable = $air3EvidenceContract.observations.packageSetHashBeforeAvailable
    packageSetHashAfter = $air3EvidenceContract.observations.packageSetHashAfter
    inspectionTasks = @($air3EvidenceContract.observations.inspectionTasks)
    placeholderVisible = $air3EvidenceContract.observations.placeholderVisible
    returnChain = @($air3EvidenceContract.observations.returnChain)
    camera = [ordered]@{
      activeClients = $air3EvidenceContract.observations.camera.activeClients
      deviceState = $air3EvidenceContract.observations.camera.deviceState
    }
    crashBufferEmpty = $air3EvidenceContract.observations.crashBufferEmpty
  }
  limitations = @($air3EvidenceContract.limitations)
  artifacts = $air3EvidenceArtifacts
}
$air3EvidenceManifest | ConvertTo-Json -Depth 8 |
  Set-Content -LiteralPath (Join-Path $deliveryRoot "$air3EvidenceRelativeRoot\manifest.json") -Encoding UTF8

$air3SoakDirectory = Resolve-LatestEvidenceDirectory `
  -Pattern "air3-v9-hardware-soak-*-final*" `
  -RequiredFiles @("summary.json")
$air3SoakSource = Join-Path $air3SoakDirectory "summary.json"
& node (Join-Path $repoRoot "scripts\validate-v9-air3-soak-summary.mjs") `
  --summary $air3SoakSource --min-duration 1200 --min-camera-cycles 3
if ($LASTEXITCODE -ne 0) { throw "V9 Air3 hardware soak summary validation failed" }
$air3SoakRaw = Get-Content -Encoding UTF8 -LiteralPath $air3SoakSource | ConvertFrom-Json
$air3SoakSummary = [ordered]@{
  schemaVersion = 1
  evidenceType = "local_air3_hardware_soak"
  capturedAt = $air3SoakRaw.generatedAt
  release = [ordered]@{
    applicationId = $releaseManifest.applicationId
    versionCode = $releaseManifest.versionCode
    versionName = $releaseManifest.versionName
    apkSha256 = $releaseManifest.sha256
  }
  device = [ordered]@{
    model = "IMA301"
    serialMasked = $air3EvidenceContract.device.serialMasked
  }
  measurements = [ordered]@{
    durationSecondsRequested = $air3SoakRaw.durationSecondsRequested
    durationSecondsObserved = $air3SoakRaw.durationSecondsObserved
    sampleIntervalSeconds = $air3SoakRaw.sampleIntervalSeconds
    requiredSampleCount = $air3SoakRaw.requiredSampleCount
    sampleCount = $air3SoakRaw.sampleCount
    sampleCoveragePassed = $air3SoakRaw.sampleCoveragePassed
    cameraCyclesRequested = $air3SoakRaw.cameraCyclesRequested
    cameraCyclesPassed = $air3SoakRaw.cameraCyclesPassed
    maxBatteryTemperatureC = [Math]::Round(([double]$air3SoakRaw.maxBatteryTemperature / 10), 1)
    maxThermalStatus = $air3SoakRaw.maxThermalStatus
    thermalSensorPeaksC = [ordered]@{
      cpu = $air3SoakRaw.thermalSensorPeaksC.cpu
      gpu = $air3SoakRaw.thermalSensorPeaksC.gpu
      skin = $air3SoakRaw.thermalSensorPeaksC.skin
    }
    thermalSensorSampleCount = $air3SoakRaw.thermalSensorSampleCount
    baselinePssKb = $air3SoakRaw.baselinePssKb
    maxPssKb = $air3SoakRaw.maxPssKb
    pssDeltaKb = $air3SoakRaw.pssDeltaKb
    finalJankPercent = $air3SoakRaw.finalJankPercent
    externalPowerConnected = $air3SoakRaw.externalPowerConnected
    powerMeasurementValid = $air3SoakRaw.powerMeasurementValid
    cameraLeak = $air3SoakRaw.cameraLeak
    audioLeak = $air3SoakRaw.audioLeak
    crashBufferEmpty = $air3SoakRaw.crashBufferEmpty
    anrObserved = $air3SoakRaw.anrObserved
    foregroundFinal = $air3SoakRaw.foregroundFinal
    protectedV8Present = $air3SoakRaw.protectedV8Present
    protectedV8Unchanged = $air3SoakRaw.protectedV8Unchanged
    passed = $air3SoakRaw.passed
  }
  limitations = @(
    "usb_powered_power_measurement_invalid",
    "managed_services_unavailable",
    "production_voice_voiceprint_not_accepted"
  )
}
$air3SoakDestination = Join-Path $deliveryRoot "verification\air3-soak\summary.json"
New-Item -ItemType Directory -Path (Split-Path -Parent $air3SoakDestination) -Force | Out-Null
$air3SoakSummary | ConvertTo-Json -Depth 8 |
  Set-Content -LiteralPath $air3SoakDestination -Encoding UTF8

$securityRoot = Join-Path $deliveryRoot "security"
New-Item -ItemType Directory -Path $securityRoot -Force | Out-Null
& node (Join-Path $repoRoot "scripts\generate-v9-sbom.mjs") $repoRoot (Join-Path $securityRoot "SBOM.cdx.json")
if ($LASTEXITCODE -ne 0) { throw "V9 SBOM generation failed" }
& node (Join-Path $repoRoot "scripts\generate-v9-security-audit.mjs") $repoRoot (Join-Path $securityRoot "DEPENDENCY_AUDIT.json")
if ($LASTEXITCODE -ne 0) { throw "V9 dependency audit failed" }
& node (Join-Path $repoRoot "scripts\generate-v9-operational-readiness.mjs") $repoRoot (Join-Path $securityRoot "OPERATIONAL_READINESS.json")
if ($LASTEXITCODE -ne 0) { throw "V9 operational readiness audit failed" }
$dastSource = Resolve-LatestDastEvidenceDirectory
$dastDestination = Join-Path $securityRoot "dast"
New-Item -ItemType Directory -Path $dastDestination -Force | Out-Null
Copy-RequiredFile (Join-Path $dastSource "v9-zap-baseline.json") "security\dast\v9-zap-baseline.json"
Copy-RequiredFile (Join-Path $dastSource "v9-zap-baseline.html") "security\dast\v9-zap-baseline.html"
& node (Join-Path $repoRoot "scripts\validate-v9-dast-report.mjs") --json (Join-Path $dastDestination "v9-zap-baseline.json")
if ($LASTEXITCODE -ne 0) { throw "V9 DAST report validation failed; formal ZIP was not generated" }
@(
  '# V9 DAST evidence',
  '',
  '- Tool: OWASP ZAP `2.17.0` passive baseline.',
  '- Target: `https://bb.chinacedar.top:2305`.',
  '- Scope: only `/v9-ops` and `/v9-ops/*` instances are accepted by the report validator.',
  '- Result: High, Medium and Low alert risk counts are zero; the remaining informational cache-control item records the intentional `no-store` policy.',
  '- This is a development-team authorized passive DAST execution. It is not a third-party penetration test, independent security approval, or market-GA acceptance.',
  '- Third-party penetration, independent security approval and external acceptance gates remain pending and fail closed.'
) | Set-Content -LiteralPath (Join-Path $dastDestination "README.md") -Encoding UTF8
Copy-RequiredFile (Join-Path $repoRoot "scripts\run-v9-dast-baseline.ps1") "security\run-v9-dast-baseline.ps1"
Copy-RequiredFile (Join-Path $repoRoot "scripts\validate-v9-dast-report.mjs") "security\validate-v9-dast-report.mjs"
Copy-RequiredFile (Join-Path $repoRoot "scripts\verify-v9-delivery.ps1") "security\verify-v9-delivery.ps1"
Copy-RequiredFile (Join-Path $repoRoot "scripts\v9-external-acceptance.mjs") "security\v9-external-acceptance.mjs"
Copy-RequiredFile (Join-Path $repoRoot "scripts\init-v9-external-acceptance.mjs") "security\init-v9-external-acceptance.mjs"
Copy-RequiredFile (Join-Path $repoRoot "scripts\refresh-v9-external-acceptance.mjs") "security\refresh-v9-external-acceptance.mjs"
Copy-RequiredFile (Join-Path $repoRoot "scripts\record-v9-external-attestation.mjs") "security\record-v9-external-attestation.mjs"
Copy-RequiredFile (Join-Path $repoRoot "scripts\attach-v9-external-approval-signature.mjs") "security\attach-v9-external-approval-signature.mjs"

$forbiddenDeliveryFilePattern = '(?i)(^|[\\/])(?:\.env|.*\.local|.*\.pem|.*\.key|.*\.p12)$'
foreach ($file in Get-ChildItem -LiteralPath $deliveryRoot -Recurse -File) {
  $relative = $file.FullName.Substring($deliveryRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, "/")
  if ($relative -match $forbiddenDeliveryFilePattern -and -not $file.Name.EndsWith(".example", [StringComparison]::OrdinalIgnoreCase)) {
    throw "Secret-like file must not be shipped in the formal delivery: $relative"
  }
}

$files = Get-ChildItem -LiteralPath $deliveryRoot -Recurse -File | Sort-Object FullName
$checksums = foreach ($file in $files) {
  $relative = $file.FullName.Substring($deliveryRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, "/")
  $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
  "$hash  $relative"
}
$checksums | Set-Content -LiteralPath (Join-Path $deliveryRoot "SHA256SUMS.txt") -Encoding ASCII

$manifest = [ordered]@{
  product = "Dingdang AI Operations Glasses"
  release = "V9.0.0"
  applicationId = "com.codex.air3nativecamera.dingdangexpert.v9"
  versionCode = 900000
  secureRuntime = $true
  modelContract = [ordered]@{
    ai = "qwen3-vl-plus"
    asr = "fun-asr-realtime"
    wake = "previous-iflytek-aikit"
    voiceprint = "s1aa729d0"
  }
  artifactRoot = "."
  generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
  externalPrerequisitesRequired = $true
  productionDeploymentStatus = "pending_external_credentials_and_hardware_acceptance"
  artifacts = @($files | ForEach-Object {
      $relative = $_.FullName.Substring($deliveryRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, "/")
      [ordered]@{ path = $relative; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant(); bytes = $_.Length }
    })
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $deliveryRoot "DELIVERY_MANIFEST.json") -Encoding UTF8

$checksumFiles = Get-ChildItem -LiteralPath $deliveryRoot -Recurse -File | Where-Object { $_.Name -ne "SHA256SUMS.txt" } | Sort-Object FullName
$checksums = foreach ($file in $checksumFiles) {
  $relative = $file.FullName.Substring($deliveryRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, "/")
  $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
  "$hash  $relative"
}
$checksums | Set-Content -LiteralPath (Join-Path $deliveryRoot "SHA256SUMS.txt") -Encoding ASCII

$zip = Join-Path $outputRoot "DingdangAI-V9-9.0.0-formal-delivery.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $deliveryRoot "*") -DestinationPath $zip -CompressionLevel Optimal
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToUpperInvariant()
"$zipHash  $(Split-Path -Leaf $zip)" | Set-Content -LiteralPath (Join-Path $outputRoot "DingdangAI-V9-9.0.0-formal-delivery.zip.sha256") -Encoding ASCII
& node (Join-Path $repoRoot "scripts\refresh-v9-external-acceptance.mjs") $repoRoot "evidence/v9-external-acceptance"
if ($LASTEXITCODE -ne 0) { throw "V9 external acceptance workspace refresh failed; formal ZIP was not released" }

Write-Output "DeliveryDirectory=$deliveryRoot"
Write-Output "DeliveryZip=$zip"
Write-Output "DeliveryZipSHA256=$zipHash"
Write-Output "Formal V9 delivery package created"
