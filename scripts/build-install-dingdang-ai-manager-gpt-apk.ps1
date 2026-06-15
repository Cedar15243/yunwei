param(
  [string]$Serial = "YM00FCF3NW0031",
  [int]$WaitSeconds = 45,
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-ai-manager-apk.ps1"
$installScript = Join-Path $repoRoot "scripts\install-and-verify-dingdang-ai-manager.ps1"

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

$openAiKey = Read-SecretOrEnv `
  -Name "OPENAI_API_KEY" `
  -PathValue (Join-Path $repoRoot "tmp\openai_api_key.local") `
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

$gptModel = Read-ConfigOrEnv `
  -Name "DINGDANG_MANAGER_GPT_MODEL" `
  -PathValue (Join-Path $repoRoot "tmp\dingdang_manager_gpt_model.local") `
  -DefaultValue "gpt-5.5"

$oldDirect = $env:AIR3_APK_DIRECT_GPT
$oldGptKey = $env:DIRECT_GPT_API_KEY
$oldOpenAiKey = $env:OPENAI_API_KEY
$oldGptBase = $env:DIRECT_GPT_BASE_URL
$oldGptModel = $env:DIRECT_GPT_MODEL
$oldGptReasoningEffort = $env:DIRECT_GPT_REASONING_EFFORT
$oldAsrKey = $env:DIRECT_ASR_API_KEY
$oldAsrEndpoint = $env:DIRECT_ASR_ENDPOINT
$oldOpsKey = $env:OPS_GLASSES_API_KEY
$oldBackend = $env:DINGDANG_BACKEND_BASE_URL

try {
  $env:AIR3_APK_DIRECT_GPT = "1"
  $env:OPENAI_API_KEY = $openAiKey
  $env:DIRECT_GPT_API_KEY = $openAiKey
  $env:DIRECT_GPT_BASE_URL = "https://api.xje96.uk"
  $env:DIRECT_GPT_MODEL = $gptModel
  $env:DIRECT_GPT_REASONING_EFFORT = "high"
  $env:DIRECT_ASR_API_KEY = $directAsrKey
  $env:DIRECT_ASR_ENDPOINT = $directAsrEndpoint
  $env:OPS_GLASSES_API_KEY = ""
  Remove-Item Env:DINGDANG_BACKEND_BASE_URL -ErrorAction SilentlyContinue

  Write-Output "Building Dingdang AI manager GPT APK. Key values are loaded but will not be printed."
  Write-Output "GPT base URL: https://api.xje96.uk"
  Write-Output "GPT model: $gptModel"
  Write-Output "GPT reasoning effort: high"
  Write-Output "Direct ASR endpoint: $directAsrEndpoint"
  powershell -ExecutionPolicy Bypass -File $buildScript

  if (-not $SkipInstall) {
    powershell -ExecutionPolicy Bypass -File $installScript -Serial $Serial -WaitSeconds $WaitSeconds
  }
} finally {
  $env:AIR3_APK_DIRECT_GPT = $oldDirect
  $env:DIRECT_GPT_API_KEY = $oldGptKey
  $env:OPENAI_API_KEY = $oldOpenAiKey
  $env:DIRECT_GPT_BASE_URL = $oldGptBase
  $env:DIRECT_GPT_MODEL = $oldGptModel
  $env:DIRECT_GPT_REASONING_EFFORT = $oldGptReasoningEffort
  $env:DIRECT_ASR_API_KEY = $oldAsrKey
  $env:DIRECT_ASR_ENDPOINT = $oldAsrEndpoint
  $env:OPS_GLASSES_API_KEY = $oldOpsKey
  $env:DINGDANG_BACKEND_BASE_URL = $oldBackend
}
