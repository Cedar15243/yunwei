$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pidFile = Join-Path $repoRoot "tmp\expert-collab-runtime\processes.json"
if (-not (Test-Path -LiteralPath $pidFile)) {
  Write-Output "No expert collaboration process record was found."
  exit 0
}

$record = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
$processIds = @($record.serverListenerPid, $record.webListenerPid, $record.serverPid, $record.webPid) |
  Where-Object { $_ -is [int] -or $_ -is [long] } |
  Select-Object -Unique
foreach ($processId in $processIds) {
  $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
  if ($process) {
    $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue).CommandLine
    if ($commandLine -and $commandLine -notmatch "expert-collab|npm(\.cmd)?\s+(start|run dev)") {
      Write-Warning "Skipped PID $processId because it no longer belongs to the expert collaboration demo."
      continue
    }
    Stop-Process -Id $processId -ErrorAction SilentlyContinue
    Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
  }
}
Remove-Item -LiteralPath $pidFile -Force
Write-Output "Expert collaboration demo processes stopped."
