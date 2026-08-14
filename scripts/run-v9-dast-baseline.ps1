param(
  [string]$Target = $env:V9_DAST_BASE_URL,
  [string]$OutputDirectory = "output\v9-dast"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if ([string]::IsNullOrWhiteSpace($Target) -or -not $Target.StartsWith("https://", [StringComparison]::OrdinalIgnoreCase)) {
  throw "V9_DAST_BASE_URL must be an explicitly authorized HTTPS target; no local or mock target is accepted."
}
$output = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) { throw "Docker with OWASP ZAP is required for the external DAST gate." }
docker run --rm -v "${output}:/zap/wrk/:rw" ghcr.io/zaproxy/zaproxy:stable zap-baseline.py `
  -t $Target -r v9-zap-baseline.html -J v9-zap-baseline.json
if ($LASTEXITCODE -ne 0) {
  throw "OWASP ZAP baseline failed. Review $output and do not mark V9 market GA."
}
Write-Output "V9 DAST baseline completed for explicitly supplied target: $Target"
