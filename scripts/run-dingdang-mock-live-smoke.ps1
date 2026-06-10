param(
  [string]$Serial = "YM00FCF3NW0031",
  [int]$Port = 18080,
  [int]$WaitSeconds = 30,
  [string]$OutDir = "",
  [int]$CameraUiMaxMs = 5000,
  [int]$PhotoReturnMaxMs = 8500,
  [int]$AsrPartialMaxMs = 3000,
  [int]$GptFirstDeltaMaxMs = 3000,
  [int]$TotalInteractionMaxMs = 30000,
  [switch]$SkipRestoreStandard
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "tmp\mock-live-smoke-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$node = (Get-Command node).Source
$serverScript = Join-Path $repoRoot "scripts\mock-dingdang-backend.mjs"
$buildScript = Join-Path $repoRoot "scripts\build-dingdang-ops-ai-apk.ps1"
$installScript = Join-Path $repoRoot "scripts\install-and-verify-dingdang-ops-ai.ps1"
$adb = Join-Path $repoRoot "tmp\tools\platform-tools\adb.exe"
$package = "com.codex.air3nativecamera.dingdangops"
$activity = "com.codex.air3nativecamera.MainActivity"
if (-not (Test-Path -LiteralPath $adb)) {
  $adb = "adb"
}

function Invoke-AdbQuiet {
  param([string[]]$Arguments)
  try {
    & $adb @Arguments *> $null
  } catch {
  }
}

function Wait-ForMockBackend {
  param([int]$Port, [Diagnostics.Process]$Process)
  for ($i = 0; $i -lt 50; $i++) {
    Start-Sleep -Milliseconds 200
    try {
      $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -Method Get
      if ($health.ok) {
        return
      }
    } catch {
    }
    if ($Process.HasExited) {
      break
    }
  }
  throw "mock backend did not start on port $Port"
}

function Dump-Ui {
  param([string]$Name)
  & $adb -s $Serial exec-out uiautomator dump /dev/tty > (Join-Path $OutDir "$Name-ui.xml")
}

function Get-ElapsedMs {
  param([datetime]$Start)
  return [int][Math]::Round(((Get-Date) - $Start).TotalMilliseconds)
}

function Convert-LogTimestamp {
  param([string]$Value)
  try {
    $year = (Get-Date).Year
    return [datetime]::ParseExact(
      "$year-$Value",
      "yyyy-MM-dd HH:mm:ss.fff",
      [Globalization.CultureInfo]::InvariantCulture
    )
  } catch {
    return $null
  }
}

function Get-AsrPartialLatencyMs {
  param([string]$LogText)
  $startTime = $null
  $partialTime = $null
  foreach ($line in ($LogText -split "`r?`n")) {
    if (-not $startTime -and $line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*Realtime ASR start') {
      $startTime = Convert-LogTimestamp -Value $matches[1]
      continue
    }
    if ($startTime -and -not $partialTime -and $line -match '^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*Realtime ASR partial') {
      $partialTime = Convert-LogTimestamp -Value $matches[1]
      break
    }
  }
  if ($startTime -and $partialTime) {
    return [int][Math]::Round(($partialTime - $startTime).TotalMilliseconds)
  }
  return $null
}

function Get-GptFirstDeltaLatencyMs {
  param([string]$LogText)
  $match = [regex]::Match($LogText, "GPT stream first delta latencyMs=(\d+)")
  if ($match.Success) {
    return [int]$match.Groups[1].Value
  }
  return $null
}

function New-Text {
  param([int[]]$Codes)
  return -join ($Codes | ForEach-Object { [char]$_ })
}

$cameraPromptMarker = New-Text @(0x5BF9, 0x51C6, 0x73B0, 0x573A, 0x540E, 0x62CD, 0x7167)
$returnChatMarker = New-Text @(0x8FD4, 0x56DE, 0x804A, 0x5929)
$chatHistoryMarker = New-Text @(0x4F1A, 0x8BDD, 0x8BB0, 0x5F55)
$dingdangLabelMarker = (New-Text @(0x53EE, 0x5F53, 0x8FD0, 0x7EF4)) + "AI"
$photoActionMarker = New-Text @(0x62CD, 0x7167, 0x8BC6, 0x522B)
$voiceActionMarker = New-Text @(0x8BED, 0x97F3, 0x63D0, 0x95EE)
$composerPhotoReadyMarker = New-Text @(0x7167, 0x7247, 0x5DF2, 0x6DFB, 0x52A0)
$sceneImageMarker = New-Text @(0x73B0, 0x573A, 0x56FE, 0x7247)
$imageMarker = New-Text @(0x56FE, 0x7247)
$photoMarker = New-Text @(0x7167, 0x7247)
$pumpPartialMarker = New-Text @(0x8FD9, 0x4E2A, 0x6C34, 0x6CF5)
$listeningMarker = New-Text @(0x6B63, 0x5728, 0x542C)
$finishVoiceMarker = New-Text @(0x70B9, 0x4E00, 0x4E0B, 0x7ED3, 0x675F)
$pumpFinalMarker = New-Text @(0x8FD9, 0x4E2A, 0x6C34, 0x6CF5, 0x4E3A, 0x4EC0, 0x4E48, 0x4E00, 0x76F4, 0x62A5, 0x8B66)
$pressureGaugeMarker = New-Text @(0x538B, 0x529B, 0x8868)
$alarmLightMarker = New-Text @(0x62A5, 0x8B66, 0x706F)
$inletValveMarker = New-Text @(0x8FDB, 0x6C34, 0x9600)
$filterMarker = New-Text @(0x8FC7, 0x6EE4, 0x5668)

$serverStdout = Join-Path $OutDir "server.out.log"
$serverStderr = Join-Path $OutDir "server.err.log"
$serverArgs = '"' + $serverScript + '" --port=' + $Port
$serverProc = $null
$oldBackendBaseUrl = $env:DINGDANG_BACKEND_BASE_URL

try {
  $serverProc = Start-Process `
    -FilePath $node `
    -ArgumentList $serverArgs `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $serverStdout `
    -RedirectStandardError $serverStderr

  Wait-ForMockBackend -Port $Port -Process $serverProc

  $env:DINGDANG_BACKEND_BASE_URL = "http://127.0.0.1:$Port"
  powershell -ExecutionPolicy Bypass -File $buildScript |
    Tee-Object -FilePath (Join-Path $OutDir "build-mock.txt") | Out-Null

  Invoke-AdbQuiet -Arguments @("-s", $Serial, "reverse", "--remove", "tcp:$Port")
  & $adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null

  powershell -ExecutionPolicy Bypass -File $installScript -Serial $Serial -WaitSeconds $WaitSeconds |
    Tee-Object -FilePath (Join-Path $OutDir "install-mock.txt") | Out-Null

  & $adb -s $Serial shell pm clear $package | Out-Null
  & $adb -s $Serial shell pm grant $package android.permission.CAMERA 2>$null
  & $adb -s $Serial shell pm grant $package android.permission.RECORD_AUDIO 2>$null
  & $adb -s $Serial shell am start --display 0 -n "$package/$activity" | Out-Null
  Start-Sleep -Milliseconds 1200

  & $adb -s $Serial logcat -c
  Start-Sleep -Milliseconds 900

  $interactionStart = Get-Date
  $cameraStart = Get-Date
  & $adb -s $Serial shell input keyevent 80
  Start-Sleep -Milliseconds 2500
  Dump-Ui "01-camera"
  $cameraUiMs = Get-ElapsedMs -Start $cameraStart

  $photoStart = Get-Date
  & $adb -s $Serial shell input keyevent 66
  Start-Sleep -Milliseconds 5500
  Dump-Ui "02-after-photo"
  $photoReturnMs = Get-ElapsedMs -Start $photoStart

  $asrStart = Get-Date
  & $adb -s $Serial shell input keyevent 66
  Start-Sleep -Milliseconds 1400
  Dump-Ui "03-asr-partial"
  $asrPartialUiMs = Get-ElapsedMs -Start $asrStart

  $gptStart = Get-Date
  # ASR final now stops recording and sends to GPT automatically; do not press ENTER
  # again here, otherwise the smoke can start a second voice turn and pollute the UI.
  Start-Sleep -Milliseconds 6500
  Dump-Ui "04-final"
  $gptFirstDeltaMs = Get-ElapsedMs -Start $gptStart
  $totalInteractionMs = Get-ElapsedMs -Start $interactionStart

  & $adb -s $Serial shell dumpsys activity activities > (Join-Path $OutDir "activity.txt")
  & $adb -s $Serial logcat -d -s DingdangKey AndroidRuntime > (Join-Path $OutDir "logcat.txt")

  $cameraUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "01-camera-ui.xml")
  $photoUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "02-after-photo-ui.xml")
  $partialUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "03-asr-partial-ui.xml")
  $finalUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "04-final-ui.xml")
  $activity = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "activity.txt")
  $logcat = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "logcat.txt")

  $asrPartialLogMs = Get-AsrPartialLatencyMs -LogText $logcat
  $asrPartialMs = if ($null -ne $asrPartialLogMs) { $asrPartialLogMs } else { $asrPartialUiMs }
  $gptFirstDeltaLogMs = Get-GptFirstDeltaLatencyMs -LogText $logcat
  $gptFirstDeltaMeasuredMs = if ($null -ne $gptFirstDeltaLogMs) { $gptFirstDeltaLogMs } else { $gptFirstDeltaMs }
  $latencyBudgets = [pscustomobject]@{
    cameraUi = $CameraUiMaxMs
    photoReturn = $PhotoReturnMaxMs
    asrPartial = $AsrPartialMaxMs
    gptFirstDelta = $GptFirstDeltaMaxMs
    totalInteraction = $TotalInteractionMaxMs
  }
  $latency = [pscustomobject]@{
    cameraUi = $cameraUiMs
    photoReturn = $photoReturnMs
    asrPartial = $asrPartialMs
    gptFirstDelta = $gptFirstDeltaMeasuredMs
    totalInteraction = $totalInteractionMs
    gptUiObserved = $gptFirstDeltaMs
    gptFirstDeltaSource = if ($null -ne $gptFirstDeltaLogMs) { "logcat" } else { "ui" }
  }
  $latencyWithinBudget = (
    $latency.cameraUi -le $latencyBudgets.cameraUi -and
    $latency.photoReturn -le $latencyBudgets.photoReturn -and
    $latency.asrPartial -le $latencyBudgets.asrPartial -and
    $latency.gptFirstDelta -le $latencyBudgets.gptFirstDelta -and
    $latency.totalInteraction -le $latencyBudgets.totalInteraction
  )

  $summary = [pscustomobject]@{
    mockBackend = $true
    adbReverse = $true
    cameraUiVisible = ($cameraUi.Contains($cameraPromptMarker) -or $cameraUi.Contains($returnChatMarker))
    returnedToChatAfterPhoto = ($photoUi.Contains($dingdangLabelMarker) -and (($photoUi.Contains($photoActionMarker) -and $photoUi.Contains($voiceActionMarker)) -or $photoUi.Contains($composerPhotoReadyMarker)))
    imageAttached = ($photoUi.Contains($sceneImageMarker) -or $photoUi.Contains($imageMarker) -or $photoUi.Contains($photoMarker))
    asrPartialVisible = ($partialUi.Contains($pumpPartialMarker) -or $partialUi.Contains($listeningMarker) -or $partialUi.Contains($finishVoiceMarker))
    finalTranscriptVisible = ($finalUi.Contains($pumpFinalMarker))
    gptStreamVisible = ($finalUi.Contains($pressureGaugeMarker) -or $finalUi.Contains($alarmLightMarker) -or $finalUi.Contains($inletValveMarker) -or $finalUi.Contains($filterMarker))
    inDingdang = ($activity -match "topResumedActivity=.*com\.codex\.air3nativecamera\.dingdangops")
    latencyMs = $latency
    latencyBudgetsMs = $latencyBudgets
    latencyWithinBudget = [bool]$latencyWithinBudget
    restoredStandard = $false
    evidenceDir = $OutDir
  }

  $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $OutDir "summary.json")

  $requiredSmokeChecks = @(
    "mockBackend",
    "adbReverse",
    "cameraUiVisible",
    "returnedToChatAfterPhoto",
    "imageAttached",
    "asrPartialVisible",
    "finalTranscriptVisible",
    "gptStreamVisible",
    "inDingdang",
    "latencyWithinBudget"
  )
  $failedSmokeChecks = $requiredSmokeChecks | Where-Object { -not $summary.$_ }
  if ($failedSmokeChecks.Count -gt 0) {
    throw "mock live smoke failed checks: $($failedSmokeChecks -join ', ')"
  }
} finally {
  Invoke-AdbQuiet -Arguments @("-s", $Serial, "reverse", "--remove", "tcp:$Port")
  if ($serverProc -and -not $serverProc.HasExited) {
    Stop-Process -Id $serverProc.Id -Force
  }
  $env:DINGDANG_BACKEND_BASE_URL = $oldBackendBaseUrl

  if (-not $SkipRestoreStandard) {
    powershell -ExecutionPolicy Bypass -File $buildScript |
      Tee-Object -FilePath (Join-Path $OutDir "build-standard.txt") | Out-Null
    powershell -ExecutionPolicy Bypass -File $installScript -Serial $Serial -WaitSeconds $WaitSeconds |
      Tee-Object -FilePath (Join-Path $OutDir "install-standard.txt") | Out-Null

    $summaryPath = Join-Path $OutDir "summary.json"
    if (Test-Path -LiteralPath $summaryPath) {
      $summary = Get-Content -Raw -Encoding UTF8 $summaryPath | ConvertFrom-Json
      $summary.restoredStandard = $true
      $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $summaryPath
    }
  }
}

$finalSummaryPath = Join-Path $OutDir "summary.json"
if (Test-Path -LiteralPath $finalSummaryPath) {
  Get-Content -Raw -Encoding UTF8 $finalSummaryPath
}
