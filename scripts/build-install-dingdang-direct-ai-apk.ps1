param(
  [string]$Serial = "YM00FCF3NW0031",
  [int]$WaitSeconds = 30,
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-ops-ai-apk.ps1"
$installScript = Join-Path $repoRoot "scripts\install-and-verify-dingdang-ops-ai.ps1"

function Read-SecretOrEnv([string]$Name, [string]$PathValue, [switch]$Required) {
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ($value -and $value.Trim().Length -gt 0) {
    return $value.Trim()
  }
  if (Test-Path -LiteralPath $PathValue) {
    $fileValue = (Get-Content -LiteralPath $PathValue -Raw).Trim()
    if ($fileValue.Length -gt 0) {
      return $fileValue
    }
  }
  if ($Required) {
    throw "$Name missing. Set env:$Name or create $PathValue"
  }
  return ""
}

function Read-ConfigOrEnv([string]$Name, [string]$PathValue, [string]$DefaultValue = "") {
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ($value -and $value.Trim().Length -gt 0) {
    return $value.Trim()
  }
  if (Test-Path -LiteralPath $PathValue) {
    $fileValue = (Get-Content -LiteralPath $PathValue -Raw).Trim()
    if ($fileValue.Length -gt 0) {
      return $fileValue
    }
  }
  return $DefaultValue
}

$directGptKey = Read-SecretOrEnv `
  -Name "DIRECT_GPT_API_KEY" `
  -PathValue (Join-Path $repoRoot "tmp\direct_gpt_api_key.local") `
  -Required
$directAsrKey = Read-SecretOrEnv `
  -Name "DIRECT_ASR_API_KEY" `
  -PathValue (Join-Path $repoRoot "tmp\direct_asr_api_key.local") `
  -Required
$directAsrEndpoint = Read-ConfigOrEnv `
  -Name "DIRECT_ASR_ENDPOINT" `
  -PathValue (Join-Path $repoRoot "tmp\direct_asr_endpoint.local")
if ($directAsrEndpoint.Length -eq 0) {
  throw "DIRECT_ASR_ENDPOINT missing. Set env:DIRECT_ASR_ENDPOINT or create tmp\direct_asr_endpoint.local"
}

$directGptBaseUrl = Read-ConfigOrEnv `
  -Name "DIRECT_GPT_BASE_URL" `
  -PathValue (Join-Path $repoRoot "tmp\direct_gpt_base_url.local") `
  -DefaultValue "https://api.openai.com/v1"
$directGptModel = Read-ConfigOrEnv `
  -Name "DIRECT_GPT_MODEL" `
  -PathValue (Join-Path $repoRoot "tmp\direct_gpt_model.local") `
  -DefaultValue "gpt-4.1-mini"

$oldDirect = $env:AIR3_APK_DIRECT_GPT
$oldGptKey = $env:DIRECT_GPT_API_KEY
$oldGptBase = $env:DIRECT_GPT_BASE_URL
$oldGptModel = $env:DIRECT_GPT_MODEL
$oldAsrKey = $env:DIRECT_ASR_API_KEY
$oldAsrEndpoint = $env:DIRECT_ASR_ENDPOINT
$oldOpsKey = $env:OPS_GLASSES_API_KEY
$oldBackend = $env:DINGDANG_BACKEND_BASE_URL

try {
  $env:AIR3_APK_DIRECT_GPT = "1"
  $env:DIRECT_GPT_API_KEY = $directGptKey
  $env:DIRECT_GPT_BASE_URL = $directGptBaseUrl
  $env:DIRECT_GPT_MODEL = $directGptModel
  $env:DIRECT_ASR_API_KEY = $directAsrKey
  $env:DIRECT_ASR_ENDPOINT = $directAsrEndpoint
  $env:OPS_GLASSES_API_KEY = ""
  Remove-Item Env:DINGDANG_BACKEND_BASE_URL -ErrorAction SilentlyContinue

  Write-Output "Building direct AI APK. Key values are loaded but will not be printed."
  Write-Output "Direct GPT base URL: $directGptBaseUrl"
  Write-Output "Direct GPT model: $directGptModel"
  Write-Output "Direct ASR endpoint: $directAsrEndpoint"
  powershell -ExecutionPolicy Bypass -File $buildScript

  if (-not $SkipInstall) {
    powershell -ExecutionPolicy Bypass -File $installScript -Serial $Serial -WaitSeconds $WaitSeconds
  }
} finally {
  $env:AIR3_APK_DIRECT_GPT = $oldDirect
  $env:DIRECT_GPT_API_KEY = $oldGptKey
  $env:DIRECT_GPT_BASE_URL = $oldGptBase
  $env:DIRECT_GPT_MODEL = $oldGptModel
  $env:DIRECT_ASR_API_KEY = $oldAsrKey
  $env:DIRECT_ASR_ENDPOINT = $oldAsrEndpoint
  $env:OPS_GLASSES_API_KEY = $oldOpsKey
  $env:DINGDANG_BACKEND_BASE_URL = $oldBackend
}
