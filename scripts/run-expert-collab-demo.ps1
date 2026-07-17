param(
  [int]$ServerPort = 8787,
  [int]$WebPort = 5173
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$secretFile = Join-Path $repoRoot "tmp\trtc_sdk_secret.local"
$runtimeDir = Join-Path $repoRoot "tmp\expert-collab-runtime"
$pidFile = Join-Path $runtimeDir "processes.json"

function Test-Port([int]$Port) {
  $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
  try {
    $listener.Start()
    return $true
  } catch {
    return $false
  } finally {
    $listener.Stop()
  }
}

function Wait-Health([string]$Url) {
  for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri $Url -TimeoutSec 2
      if ($response.ok -eq $true) {
        return
      }
    } catch {
      Start-Sleep -Milliseconds 500
    }
  }
  throw "Expert collaboration service did not become healthy: $Url"
}

function Get-ListeningProcessId([int]$Port) {
  $line = netstat -ano | Select-String "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$" | Select-Object -First 1
  if (-not $line -or $line.Matches.Count -eq 0) {
    throw "No process is listening on port $Port"
  }
  return [int]$line.Matches[0].Groups[1].Value
}

if (-not (Test-Path -LiteralPath $secretFile)) {
  throw "TRTC local secret file is missing: $secretFile"
}
$sdkSecret = (Get-Content -LiteralPath $secretFile -Raw).Trim()
if (-not $sdkSecret) {
  throw "TRTC local secret file is empty: $secretFile"
}
if (-not (Test-Port $ServerPort)) {
  throw "Collaboration server port $ServerPort is already occupied. Stop the existing process first."
}
while (-not (Test-Port $WebPort)) {
  $WebPort++
}

$lanAddress = Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -notmatch "^(127\.|169\.254\.)" -and $_.PrefixOrigin -ne "WellKnown" } |
  Sort-Object InterfaceMetric |
  Select-Object -First 1 -ExpandProperty IPAddress
if (-not $lanAddress) {
  $lanAddress = "127.0.0.1"
}

New-Item -ItemType Directory -Force $runtimeDir | Out-Null
$serverOrigin = "http://${lanAddress}:$ServerPort"
$webOrigin = "http://${lanAddress}:$WebPort"
$localWebOrigin = "http://localhost:$WebPort"
$env:TRTC_SDK_APP_ID = "1600152353"
$env:TRTC_SDK_SECRET = $sdkSecret
$env:COLLAB_HOST = "0.0.0.0"
$env:COLLAB_PORT = "$ServerPort"
$env:COLLAB_ORIGIN = "$localWebOrigin,$webOrigin"
$env:COLLAB_FREEZE_DIR = Join-Path $runtimeDir "freezes"
$env:VITE_COLLAB_HTTP_URL = $serverOrigin
$env:VITE_COLLAB_WS_URL = "ws://${lanAddress}:$ServerPort/collab"

$serverProcess = Start-Process -FilePath "npm.cmd" -ArgumentList @("start") `
  -WorkingDirectory (Join-Path $repoRoot "expert-collab-server") -WindowStyle Hidden -PassThru `
  -RedirectStandardOutput (Join-Path $runtimeDir "server.out.log") `
  -RedirectStandardError (Join-Path $runtimeDir "server.err.log")
$webProcess = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--", "--host", "0.0.0.0", "--port", "$WebPort", "--strictPort") `
  -WorkingDirectory (Join-Path $repoRoot "expert-collab-web") -WindowStyle Hidden -PassThru `
  -RedirectStandardOutput (Join-Path $runtimeDir "web.out.log") `
  -RedirectStandardError (Join-Path $runtimeDir "web.err.log")

Wait-Health "http://127.0.0.1:$ServerPort/health"
for ($attempt = 0; $attempt -lt 20; $attempt++) {
  try {
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://127.0.0.1:$WebPort/" | Out-Null
    break
  } catch {
    Start-Sleep -Milliseconds 500
  }
}

@{
  serverPid = $serverProcess.Id
  serverListenerPid = Get-ListeningProcessId $ServerPort
  webPid = $webProcess.Id
  webListenerPid = Get-ListeningProcessId $WebPort
  serverPort = $ServerPort
  webPort = $WebPort
} | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8

Write-Output "Expert collaboration demo is ready."
Write-Output "Primary expert: $localWebOrigin/?expertId=expert-wang&name=%E7%8E%8B%E5%B7%A5"
Write-Output "Observer expert: $localWebOrigin/?expertId=expert-liu&name=%E5%88%98%E5%B7%A5"
Write-Output "LAN expert access: $webOrigin"
Write-Output "Server: $serverOrigin"
Write-Output "Logs: $runtimeDir"
