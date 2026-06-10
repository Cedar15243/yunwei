param(
  [string]$ProjectRef = "",
  [string]$SecretsEnvFile = "supabase\.env.production.local",
  [string]$Serial = "YM00FCF3NW0031",
  [int]$WaitSeconds = 30,
  [switch]$DryRun,
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
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-ops-ai-apk.ps1"
$installScript = Join-Path $repoRoot "scripts\install-and-verify-dingdang-ops-ai.ps1"
$projectRefPath = Join-Path $repoRoot ".supabase\project-ref"

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

function Read-EnvFileValue {
  param([string]$PathValue, [string]$Name)
  foreach ($line in Get-Content -LiteralPath $PathValue -Encoding UTF8) {
    if ($line -match "^\s*$([regex]::Escape($Name))\s*=\s*(.+?)\s*$") {
      $value = $matches[1].Trim()
      if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
          ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
      }
      return $value
    }
  }
  return ""
}

function Invoke-Supabase {
  param([string[]]$Arguments)
  & npx supabase @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Supabase CLI command failed: supabase $($Arguments -join ' ')"
  }
}

if (-not $ProjectRef -and (Test-Path -LiteralPath $projectRefPath)) {
  $ProjectRef = (Get-Content -Raw -Encoding UTF8 $projectRefPath).Trim()
}
if (-not $ProjectRef) {
  throw "ProjectRef is required. Pass -ProjectRef <project-ref> or link the project first."
}

$secretsPath = Resolve-RepoPath -PathValue $SecretsEnvFile
if ($DryRun) {
  Write-Output "Dry run for Supabase project $ProjectRef."
  Write-Output "Would use secrets env file path: $secretsPath"
  Write-Output "Would run: npx supabase link --project-ref <project-ref>"
  Write-Output "Would run: npx supabase secrets set --env-file <ignored-env-file> --project-ref <project-ref>"
  Write-Output "Would run: npx supabase functions deploy ops-glasses --project-ref <project-ref>"
  Write-Output "Would set DINGDANG_BACKEND_BASE_URL for APK build."
  Write-Output "Would read OPS_GLASSES_API_KEY from ignored env file or process env without printing it."
  Write-Output "Would run install-and-verify-dingdang-ops-ai.ps1."
  return
}

if (-not $SkipSecretsSet) {
  if (-not (Test-Path -LiteralPath $secretsPath)) {
    throw "Secrets env file not found: $secretsPath"
  }
  Assert-IgnoredSecretsFile -PathValue $secretsPath
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

$opsGlassesApiKey = $env:OPS_GLASSES_API_KEY
if (-not $opsGlassesApiKey -and (Test-Path -LiteralPath $secretsPath)) {
  $opsGlassesApiKey = Read-EnvFileValue -PathValue $secretsPath -Name "OPS_GLASSES_API_KEY"
}
if (-not $opsGlassesApiKey) {
  throw "OPS_GLASSES_API_KEY is required in the ignored env file or process environment."
}

if (-not $SkipApkBuild) {
  Write-Output "Building Dingdang APK with DINGDANG_BACKEND_BASE_URL and OPS_GLASSES_API_KEY..."
  $oldBackendBaseUrl = $env:DINGDANG_BACKEND_BASE_URL
  $oldBackendApiKey = $env:DINGDANG_BACKEND_API_KEY
  try {
    $env:DINGDANG_BACKEND_BASE_URL = $backendBaseUrl
    $env:DINGDANG_BACKEND_API_KEY = $opsGlassesApiKey
    powershell -ExecutionPolicy Bypass -File $buildScript
  } finally {
    $env:DINGDANG_BACKEND_BASE_URL = $oldBackendBaseUrl
    $env:DINGDANG_BACKEND_API_KEY = $oldBackendApiKey
  }
}

if (-not $SkipInstall) {
  Write-Output "Installing and verifying Dingdang APK on Air3..."
  powershell -ExecutionPolicy Bypass -File $installScript -Serial $Serial -WaitSeconds $WaitSeconds
}

Write-Output "Supabase production deploy helper completed. Real provider live smoke can now use the installed backend APK."
