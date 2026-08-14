param(
  [string]$ProjectRef = "",
  [string]$SecretsEnvFile = "supabase\.env.production.local",
  [string]$GatewayOverlayEnvFile = "v9-ops-gateway\deploy\dingdang-v9.env.local",
  [string]$VoiceprintOverlayEnvFile = "v9-ops-gateway\deploy\dingdang-v9-voiceprint.env.local",
  [string]$Serial = "YM00FCF3NW0031",
  [int]$WaitSeconds = 30,
  [switch]$DryRun,
  [switch]$PreflightOnly,
  [switch]$SkipLink,
  [switch]$SkipSecretsSet,
  [switch]$SkipDeploy,
  [switch]$SkipApkBuild,
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $repoRoot "scripts\build-v9-release.ps1"
$installScript = Join-Path $repoRoot "scripts\install-and-verify-v9-release.ps1"
$formalOutputRelative = "output\v9.0.0-formal-release-current"
$formalApkRelative = "$formalOutputRelative\DingdangAI-V9-9.0.0-release.apk"
$formalManifestRelative = "$formalOutputRelative\release-manifest.json"
$projectRefPath = Join-Path $repoRoot ".supabase\project-ref"
$linkedProjectPath = Join-Path $repoRoot "supabase\.temp\linked-project.json"

function Resolve-RepoPath {
  param([string]$PathValue)
  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return $PathValue
  }
  return Join-Path $repoRoot $PathValue
}

function Assert-SupabaseAuth {
  $localAccessToken = Join-Path $HOME ".supabase\access-token"
  $localConfig = Join-Path $HOME ".supabase\config.toml"
  if (-not $env:SUPABASE_ACCESS_TOKEN -and
      -not (Test-Path -LiteralPath $localAccessToken) -and
      -not (Test-Path -LiteralPath $localConfig)) {
    throw "Supabase auth is missing. Run 'npx supabase login' or set SUPABASE_ACCESS_TOKEN first."
  }
}

function Assert-IgnoredSecretsFile {
  param([string]$PathValue)
  $relative = Resolve-Path -LiteralPath $PathValue -Relative
  & git -C $repoRoot check-ignore -q -- $relative
  if ($LASTEXITCODE -ne 0) {
    throw "Secrets env file must be git-ignored before use: $relative"
  }
}

function Invoke-Supabase {
  param([string[]]$Arguments)
  & npx supabase @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Supabase CLI command failed: supabase $($Arguments -join ' ')"
  }
}

function Assert-NoReservedSupabaseSecretNames {
  param([string]$PathValue)
  foreach ($line in Get-Content -LiteralPath $PathValue -Encoding UTF8) {
    if ($line -match '^\s*#' -or -not $line.Trim()) {
      continue
    }
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=') {
      throw "Invalid secrets env line. Only NAME=VALUE entries are allowed."
    }
    if ($Matches[1].StartsWith("SUPABASE_", [StringComparison]::OrdinalIgnoreCase)) {
      throw "Reserved Supabase secret prefix is not allowed in the upload env file. Supabase injects SUPABASE_* defaults automatically."
    }
  }
}

function Read-EnvironmentFile {
  param([string]$PathValue)

  $environment = @{}
  foreach ($line in Get-Content -LiteralPath $PathValue -Encoding UTF8) {
    if ($line -match '^\s*#' -or -not $line.Trim()) {
      continue
    }
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
      throw "Invalid environment line. Only NAME=VALUE entries are allowed."
    }
    $name = $Matches[1]
    $value = $Matches[2].Trim()
    if ($value.Length -ge 2 -and
        (($value.StartsWith('"') -and $value.EndsWith('"')) -or
         ($value.StartsWith("'") -and $value.EndsWith("'")))) {
      $value = $value.Substring(1, $value.Length - 2)
    }
    $environment[$name] = $value
  }
  return $environment
}

function Get-RequiredEnvironmentValue {
  param(
    [hashtable]$Environment,
    [string]$Name
  )

  $value = [string]$Environment[$Name]
  if (-not $value.Trim()) {
    throw "Required production environment value is missing: $Name"
  }
  return $value.Trim()
}

function Get-Sha256Hex {
  param([string]$Value)

  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = $sha256.ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
  } finally {
    $sha256.Dispose()
  }
}

function Assert-CrossServiceSecretConsistency {
  param(
    [string]$SupabaseSecretsPath,
    [string]$GatewayOverlayPath,
    [string]$VoiceprintOverlayPath
  )

  $supabase = Read-EnvironmentFile -PathValue $SupabaseSecretsPath
  $gateway = Read-EnvironmentFile -PathValue $GatewayOverlayPath
  $voiceprint = Read-EnvironmentFile -PathValue $VoiceprintOverlayPath
  $manifestToken = Get-RequiredEnvironmentValue -Environment $gateway -Name "V9_CONTENT_MANIFEST_SYNC_TOKEN"
  $manifestHash = Get-RequiredEnvironmentValue -Environment $supabase -Name "V9_GATEWAY_SYNC_TOKEN_SHA256"
  if ((Get-Sha256Hex -Value $manifestToken) -ne $manifestHash.ToLowerInvariant()) {
    throw "Content manifest sync token hash mismatch between Supabase secrets and gateway overlay."
  }

  $voiceprintToken = Get-RequiredEnvironmentValue -Environment $supabase -Name "V9_VOICEPRINT_ADMIN_TOKEN"
  $voiceprintHash = Get-RequiredEnvironmentValue -Environment $voiceprint -Name "V9_VOICEPRINT_ADMIN_TOKEN_SHA256"
  if ((Get-Sha256Hex -Value $voiceprintToken) -ne $voiceprintHash.ToLowerInvariant()) {
    throw "Voiceprint admin token hash mismatch between Supabase secrets and gateway overlay."
  }

  $knowledgeToken = Get-RequiredEnvironmentValue -Environment $supabase -Name "V9_KNOWLEDGE_PARSER_TOKEN"
  $knowledgeHash = Get-RequiredEnvironmentValue -Environment $gateway -Name "V9_KNOWLEDGE_PARSER_TOKEN_SHA256"
  if ((Get-Sha256Hex -Value $knowledgeToken) -ne $knowledgeHash.ToLowerInvariant()) {
    throw "Knowledge parser token hash mismatch between Supabase secrets and gateway overlay."
  }
}

function Assert-ProductionRecoveryRedirectReachable {
  param([string]$SupabaseSecretsPath)

  $supabase = Read-EnvironmentFile -PathValue $SupabaseSecretsPath
  $redirectValue = Get-RequiredEnvironmentValue `
    -Environment $supabase `
    -Name "OPS_ACCOUNT_RECOVERY_REDIRECT_URL"
  $redirectUri = $null
  if (-not [Uri]::TryCreate($redirectValue, [UriKind]::Absolute, [ref]$redirectUri) -or
      $redirectUri.Scheme -ne "https" -or
      -not $redirectUri.DnsSafeHost -or
      $redirectUri.UserInfo -or
      $redirectUri.Query -or
      $redirectUri.Fragment) {
    throw "Production account recovery redirect must be a plain HTTPS URL."
  }

  try {
    $ipv4Addresses = [System.Net.Dns]::GetHostAddresses($redirectUri.DnsSafeHost) |
      Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork }
  } catch {
    throw "Production account recovery redirect DNS lookup failed."
  }
  if (-not $ipv4Addresses) {
    throw "Production account recovery redirect requires a reachable IPv4 A record."
  }

  try {
    $response = Invoke-WebRequest `
      -UseBasicParsing `
      -Method Get `
      -Uri $redirectUri.AbsoluteUri `
      -TimeoutSec 15
  } catch {
    throw "Production account recovery redirect HTTPS probe failed."
  }
  if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
    throw "Production account recovery redirect did not return a usable HTTPS response."
  }
}

function Resolve-LinkedProjectRef {
  if (Test-Path -LiteralPath $projectRefPath -PathType Leaf) {
    $legacyRef = (Get-Content -Raw -Encoding UTF8 $projectRefPath).Trim()
    if ($legacyRef) {
      return $legacyRef
    }
  }

  if (Test-Path -LiteralPath $linkedProjectPath -PathType Leaf) {
    try {
      $linkedProject = Get-Content -Raw -Encoding UTF8 $linkedProjectPath |
        ConvertFrom-Json -ErrorAction Stop
      if ($linkedProject.ref -is [string] -and $linkedProject.ref.Trim()) {
        return $linkedProject.ref.Trim()
      }
    } catch {
      return ""
    }
  }

  return ""
}

if (-not $ProjectRef) {
  $ProjectRef = Resolve-LinkedProjectRef
}
if (-not $ProjectRef) {
  throw "ProjectRef is required. Pass -ProjectRef <project-ref> or link the project first."
}

$secretsPath = Resolve-RepoPath -PathValue $SecretsEnvFile
$gatewayOverlayPath = Resolve-RepoPath -PathValue $GatewayOverlayEnvFile
$voiceprintOverlayPath = Resolve-RepoPath -PathValue $VoiceprintOverlayEnvFile
if ($DryRun) {
  Write-Output "Dry run for Supabase project $ProjectRef."
  Write-Output "Would use secrets env file path: $secretsPath"
  Write-Output "Would use gateway overlay env file path: $gatewayOverlayPath"
  Write-Output "Would use voiceprint overlay env file path: $voiceprintOverlayPath"
  Write-Output "Would run: npx supabase link --project-ref <project-ref>"
  Write-Output "Would run: npx supabase secrets set --env-file <ignored-env-file> --project-ref <project-ref>"
  Write-Output "Would run: npx supabase functions deploy ops-glasses --project-ref <project-ref>"
  Write-Output "Would build the formal V9 secure-runtime APK with public backend and activation URLs."
  Write-Output "Would not read or embed OPS_GLASSES_API_KEY in the APK."
  Write-Output "Would run install-and-verify-v9-release.ps1."
  return
}

if (-not $SkipSecretsSet) {
  if (-not (Test-Path -LiteralPath $secretsPath)) {
    throw "Secrets env file not found: $secretsPath"
  }
  Assert-IgnoredSecretsFile -PathValue $secretsPath
  Assert-NoReservedSupabaseSecretNames -PathValue $secretsPath
  if (-not (Test-Path -LiteralPath $gatewayOverlayPath)) {
    throw "Gateway overlay env file not found: $gatewayOverlayPath"
  }
  Assert-IgnoredSecretsFile -PathValue $gatewayOverlayPath
  if (-not (Test-Path -LiteralPath $voiceprintOverlayPath)) {
    throw "Voiceprint overlay env file not found: $voiceprintOverlayPath"
  }
  Assert-IgnoredSecretsFile -PathValue $voiceprintOverlayPath
  Assert-CrossServiceSecretConsistency `
    -SupabaseSecretsPath $secretsPath `
    -GatewayOverlayPath $gatewayOverlayPath `
    -VoiceprintOverlayPath $voiceprintOverlayPath
}

if ($PreflightOnly) {
  if ($SkipSecretsSet) {
    throw "PreflightOnly requires a secrets env file."
  }
  Write-Output "Supabase production secrets preflight passed."
  return
}

if (-not $SkipSecretsSet) {
  Assert-ProductionRecoveryRedirectReachable -SupabaseSecretsPath $secretsPath
}
Assert-SupabaseAuth

if (-not $SkipLink) {
  Write-Output "Linking Supabase project $ProjectRef..."
  Invoke-Supabase -Arguments @("link", "--project-ref", $ProjectRef)
}

if (-not $SkipSecretsSet) {
  Write-Output "Setting Supabase Function secrets from ignored env file..."
  Invoke-Supabase -Arguments @("secrets", "set", "--env-file", $secretsPath, "--project-ref", $ProjectRef)
}

if (-not $SkipDeploy) {
  Write-Output "Deploying Supabase Edge Function ops-glasses..."
  Invoke-Supabase -Arguments @("functions", "deploy", "ops-glasses", "--project-ref", $ProjectRef)
}

$backendBaseUrl = "https://$ProjectRef.supabase.co/functions/v1/ops-glasses"
Write-Output "Checking Edge Function health at $backendBaseUrl/health..."
$health = Invoke-RestMethod -Method Get -Uri "$backendBaseUrl/health"
if (-not $health.ok) {
  throw "Edge Function health check did not return ok=true."
}

if (-not $SkipApkBuild) {
  Write-Output "Building formal V9 APK with secure runtime and managed device sessions..."
  $oldBackendBaseUrl = $env:DINGDANG_BACKEND_BASE_URL
  $oldEventsEndpoint = $env:OPS_GLASSES_EVENTS_ENDPOINT
  $oldActivationBaseUrl = $env:V9_DEVICE_ACTIVATION_BASE_URL
  try {
    $env:DINGDANG_BACKEND_BASE_URL = $backendBaseUrl
    $env:OPS_GLASSES_EVENTS_ENDPOINT = "$backendBaseUrl/sessions/events"
    $env:V9_DEVICE_ACTIVATION_BASE_URL = $backendBaseUrl
    powershell -ExecutionPolicy Bypass -File $buildScript -OutputDirectory $formalOutputRelative
    if ($LASTEXITCODE -ne 0) {
      throw "Formal V9 release build failed."
    }
  } finally {
    $env:DINGDANG_BACKEND_BASE_URL = $oldBackendBaseUrl
    $env:OPS_GLASSES_EVENTS_ENDPOINT = $oldEventsEndpoint
    $env:V9_DEVICE_ACTIVATION_BASE_URL = $oldActivationBaseUrl
  }
}

if (-not $SkipInstall) {
  Write-Output "Installing and verifying formal V9 APK on Air3..."
  powershell -ExecutionPolicy Bypass -File $installScript `
    -Serial $Serial `
    -WaitSeconds $WaitSeconds `
    -ApkPath $formalApkRelative `
    -ReleaseManifestPath $formalManifestRelative
  if ($LASTEXITCODE -ne 0) {
    throw "Formal V9 installation verification failed."
  }
}

Write-Output "Supabase production deploy helper completed. Real provider live smoke can now validate the installed formal V9 APK."
