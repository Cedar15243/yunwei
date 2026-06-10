param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangops",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$BackendBaseUrl = "",
  [int]$WaitSeconds = 30,
  [int]$VoiceCaptureSeconds = 8,
  [int]$CameraUiMaxMs = 5000,
  [int]$PhotoReturnMaxMs = 12000,
  [int]$AsrPartialMaxMs = 5000,
  [int]$GptFirstDeltaMaxMs = 5000,
  [int]$TotalInteractionMaxMs = 45000,
  [string]$OutDir = "",
  [switch]$DryRun,
  [switch]$AllowNoRealProviderEvidence
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "tmp\real-live-smoke-$stamp"
}

$adb = Join-Path $repoRoot "tmp\tools\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
  $adb = "adb"
}

function Invoke-AdbText {
  param([string[]]$Arguments)
  return (& $adb @Arguments) -join "`n"
}

function Invoke-AdbQuiet {
  param([string[]]$Arguments)
  & $adb @Arguments *> $null
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

function Get-LatencyBetweenLogLines {
  param([string]$LogText, [string]$StartPattern, [string]$EndPattern)
  $startTime = $null
  foreach ($line in ($LogText -split "`r?`n")) {
    if (-not $startTime -and $line -match "^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*$StartPattern") {
      $startTime = Convert-LogTimestamp -Value $matches[1]
      continue
    }
    if ($startTime -and $line -match "^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*$EndPattern") {
      $endTime = Convert-LogTimestamp -Value $matches[1]
      if ($endTime) {
        return [int][Math]::Round(($endTime - $startTime).TotalMilliseconds)
      }
    }
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

if ($DryRun) {
  Write-Output "Dry run for Dingdang real provider live smoke."
  Write-Output "Would verify adb device $Serial and installed package $Package."
  Write-Output "Would optionally call <backend-base-url>/health when -BackendBaseUrl is provided."
  Write-Output "Would launch $Package/$Activity, use FOCUS/F9 style camera entry, capture photo, then open voice capture."
  Write-Output "Would wait $VoiceCaptureSeconds seconds for a human to speak during real Fun-ASR capture."
  Write-Output "Would collect UI dumps, logcat, activity focus, ASR partial/final evidence, and GPT first delta latency."
  Write-Output "Would write evidence to $OutDir."
  Write-Output "No API key or secret value is read or printed by this script."
  return
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$devices = Invoke-AdbText -Arguments @("devices", "-l")
$devices | Set-Content -LiteralPath (Join-Path $OutDir "devices.txt") -Encoding UTF8
if ($devices -notmatch [regex]::Escape($Serial)) {
  throw "Air3 device not found by adb: $Serial"
}

$packageDump = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $Package)
$packageDump | Set-Content -LiteralPath (Join-Path $OutDir "package.txt") -Encoding UTF8
if ($packageDump -notmatch "versionCode=602") {
  throw "Dingdang package versionCode 602 is not installed."
}

$healthOk = $null
if ($BackendBaseUrl) {
  try {
    $health = Invoke-RestMethod -Method Get -Uri "$($BackendBaseUrl.TrimEnd('/'))/health"
    $healthOk = [bool]$health.ok
  } catch {
    $healthOk = $false
  }
}

Invoke-AdbQuiet -Arguments @("-s", $Serial, "shell", "am", "force-stop", $Package)
Start-Sleep -Milliseconds 300
Invoke-AdbQuiet -Arguments @("-s", $Serial, "shell", "am", "start", "--display", "0", "-n", "$Package/$Activity")
Start-Sleep -Milliseconds 1200
& $adb -s $Serial logcat -c

$cameraPromptMarker = New-Text @(0x5BF9, 0x51C6, 0x73B0, 0x573A, 0x540E, 0x62CD, 0x7167)
$returnChatMarker = New-Text @(0x8FD4, 0x56DE, 0x804A, 0x5929)
$chatHistoryMarker = New-Text @(0x4F1A, 0x8BDD, 0x8BB0, 0x5F55)
$dingdangLabelMarker = (New-Text @(0x53EE, 0x5F53, 0x8FD0, 0x7EF4)) + "AI"
$sceneImageMarker = New-Text @(0x73B0, 0x573A, 0x56FE, 0x7247)
$imageMarker = New-Text @(0x56FE, 0x7247)
$photoMarker = New-Text @(0x7167, 0x7247)
$listeningMarker = New-Text @(0x6B63, 0x5728, 0x542C)
$finishVoiceMarker = New-Text @(0x70B9, 0x4E00, 0x4E0B, 0x7ED3, 0x675F)

$interactionStart = Get-Date

$cameraStart = Get-Date
& $adb -s $Serial shell input keyevent 80
Start-Sleep -Milliseconds 2500
Dump-Ui "01-camera"
$cameraUiMs = Get-ElapsedMs -Start $cameraStart

$photoStart = Get-Date
& $adb -s $Serial shell input keyevent 66
Start-Sleep -Milliseconds 7000
Dump-Ui "02-after-photo"
$photoReturnMs = Get-ElapsedMs -Start $photoStart

Write-Output "Speak the real field question now. Capturing voice for $VoiceCaptureSeconds seconds..."
$asrStart = Get-Date
& $adb -s $Serial shell input keyevent 66
Start-Sleep -Seconds $VoiceCaptureSeconds
Dump-Ui "03-asr-partial"
$asrPartialUiMs = Get-ElapsedMs -Start $asrStart

$gptStart = Get-Date
& $adb -s $Serial shell input keyevent 66
Start-Sleep -Milliseconds 10000
Dump-Ui "04-final"
$gptUiObservedMs = Get-ElapsedMs -Start $gptStart
$totalInteractionMs = Get-ElapsedMs -Start $interactionStart

& $adb -s $Serial shell dumpsys activity activities > (Join-Path $OutDir "activity.txt")
& $adb -s $Serial shell dumpsys window > (Join-Path $OutDir "window.txt")
& $adb -s $Serial logcat -d -s DingdangKey AndroidRuntime > (Join-Path $OutDir "logcat.txt")

$cameraUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "01-camera-ui.xml")
$photoUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "02-after-photo-ui.xml")
$partialUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "03-asr-partial-ui.xml")
$finalUi = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "04-final-ui.xml")
$activity = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "activity.txt")
$logcat = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "logcat.txt")

$asrPartialLogMs = Get-LatencyBetweenLogLines -LogText $logcat -StartPattern "Realtime ASR start" -EndPattern "Realtime ASR partial"
$asrFinalLogMs = Get-LatencyBetweenLogLines -LogText $logcat -StartPattern "Realtime ASR start" -EndPattern "Realtime ASR final"
$gptFirstDeltaLogMs = Get-GptFirstDeltaLatencyMs -LogText $logcat

$asrPartialMs = if ($null -ne $asrPartialLogMs) { $asrPartialLogMs } else { $asrPartialUiMs }
$gptFirstDeltaMs = if ($null -ne $gptFirstDeltaLogMs) { $gptFirstDeltaLogMs } else { $gptUiObservedMs }

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
  asrFinal = $asrFinalLogMs
  gptFirstDelta = $gptFirstDeltaMs
  gptUiObserved = $gptUiObservedMs
  totalInteraction = $totalInteractionMs
  asrPartialSource = if ($null -ne $asrPartialLogMs) { "logcat" } else { "ui" }
  asrFinalSource = if ($null -ne $asrFinalLogMs) { "logcat" } else { "ui" }
  gptFirstDeltaSource = if ($null -ne $gptFirstDeltaLogMs) { "logcat" } else { "ui" }
}

$cameraUiVisible = ($cameraUi.Contains($cameraPromptMarker) -or $cameraUi.Contains($returnChatMarker))
$returnedToChatAfterPhoto = ($photoUi.Contains($chatHistoryMarker) -and $photoUi.Contains($dingdangLabelMarker))
$imageAttached = ($photoUi.Contains($sceneImageMarker) -or $photoUi.Contains($imageMarker) -or $photoUi.Contains($photoMarker))
$asrPartialVisible = ($partialUi.Contains($listeningMarker) -or $partialUi.Contains($finishVoiceMarker) -or $logcat.Contains("Realtime ASR partial"))
$asrFinalVisible = ($finalUi.Contains($finishVoiceMarker) -or $logcat.Contains("Realtime ASR final"))
$gptFirstDeltaObserved = ($null -ne $gptFirstDeltaLogMs)
$inDingdang = ($activity -match "topResumedActivity=.*com\.codex\.air3nativecamera\.dingdangops")
$latencyWithinBudget = (
  $latency.cameraUi -le $latencyBudgets.cameraUi -and
  $latency.photoReturn -le $latencyBudgets.photoReturn -and
  $latency.asrPartial -le $latencyBudgets.asrPartial -and
  $latency.gptFirstDelta -le $latencyBudgets.gptFirstDelta -and
  $latency.totalInteraction -le $latencyBudgets.totalInteraction
)

$summary = [pscustomobject]@{
  provider = "real"
  backendBaseUrlProvided = [bool]$BackendBaseUrl
  healthOk = $healthOk
  cameraUiVisible = [bool]$cameraUiVisible
  returnedToChatAfterPhoto = [bool]$returnedToChatAfterPhoto
  imageAttached = [bool]$imageAttached
  asrPartialVisible = [bool]$asrPartialVisible
  asrFinalVisible = [bool]$asrFinalVisible
  gptFirstDeltaObserved = [bool]$gptFirstDeltaObserved
  inDingdang = [bool]$inDingdang
  latencyMs = $latency
  latencyBudgetsMs = $latencyBudgets
  latencyWithinBudget = [bool]$latencyWithinBudget
  humanVoiceWindowSeconds = $VoiceCaptureSeconds
  evidenceDir = $OutDir
}

$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutDir "summary.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 5

$requiredChecks = @(
  "cameraUiVisible",
  "returnedToChatAfterPhoto",
  "imageAttached",
  "asrPartialVisible",
  "asrFinalVisible",
  "gptFirstDeltaObserved",
  "inDingdang",
  "latencyWithinBudget"
)
if ($BackendBaseUrl) {
  $requiredChecks += "healthOk"
}

$failedChecks = $requiredChecks | Where-Object { -not $summary.$_ }
if ($failedChecks.Count -gt 0 -and -not $AllowNoRealProviderEvidence) {
  throw "Real provider live smoke failed checks: $($failedChecks -join ', '). See $OutDir\summary.json"
}

if ($failedChecks.Count -gt 0) {
  Write-Warning "Real provider evidence incomplete because checks failed: $($failedChecks -join ', ')"
}
