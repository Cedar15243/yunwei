param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangexpert.v9",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [int]$ExpectedVersionCode = 900000,
  [string]$ExpectedVersionName = "9.0.0",
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

function Resolve-AdbPath {
  $candidates = New-Object System.Collections.Generic.List[string]
  $candidates.Add((Join-Path $repoRoot "tmp\tools\platform-tools\adb.exe"))
  try {
    $gitCommonDirValue = (& git -C $repoRoot rev-parse --git-common-dir 2>$null | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0 -and $gitCommonDirValue) {
      $gitCommonDir = if ([System.IO.Path]::IsPathRooted($gitCommonDirValue)) {
        $gitCommonDirValue
      } else {
        Join-Path $repoRoot $gitCommonDirValue
      }
      $mainRepoRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($gitCommonDir))
      $candidates.Add((Join-Path $mainRepoRoot "tmp\tools\platform-tools\adb.exe"))
    }
  } catch {
  }
  foreach ($sdk in @(
      $env:ANDROID_SDK_ROOT,
      $env:ANDROID_HOME,
      "C:\Users\59979\UnityEditors\2022.3.62f3c1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK")) {
    if ($sdk) {
      $candidates.Add((Join-Path $sdk "platform-tools\adb.exe"))
    }
  }
  foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
      return $candidate
    }
  }
  $pathCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
  if ($pathCommand) {
    return $pathCommand.Source
  }
  throw "adb.exe was not found in the worktree, main repository, Android SDK, or PATH."
}

if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "tmp\real-live-smoke-$stamp"
}

$adb = Resolve-AdbPath

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

function Get-VoiceStageLatencyMs {
  param([string]$LogText, [string]$Stage)
  $pattern = "Voice latency stage=$([regex]::Escape($Stage))[^`r`n]*asrElapsedMs=(\d+)"
  $stageMatches = [regex]::Matches($LogText, $pattern)
  if ($stageMatches.Count -gt 0) {
    return [int]$stageMatches[$stageMatches.Count - 1].Groups[1].Value
  }
  return $null
}

function Get-AsrProviderSource {
  param([string]$LogText)
  $sourceMatches = [regex]::Matches($LogText, "Realtime ASR selected source=([^\s]+)")
  if ($sourceMatches.Count -gt 0) {
    return $sourceMatches[$sourceMatches.Count - 1].Groups[1].Value
  }
  return ""
}

function Get-PhotoContextImageId {
  param([string]$LogText)
  $photoMatches = [regex]::Matches($LogText, "Photo context ready imageId=([^\s]+)")
  if ($photoMatches.Count -gt 0) {
    return $photoMatches[$photoMatches.Count - 1].Groups[1].Value
  }
  return ""
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
  Write-Output "Would verify adb device $Serial and installed package $Package $ExpectedVersionCode/$ExpectedVersionName."
  Write-Output "Would optionally call <backend-base-url>/health when -BackendBaseUrl is provided."
  Write-Output "Would launch $Package/$Activity, use FOCUS/F9 style camera entry, capture photo, then open voice capture."
  Write-Output "Would wait $VoiceCaptureSeconds seconds for a human to speak during real Fun-ASR capture."
  Write-Output "Would collect UI dumps, activity focus, server photo context, cloud ASR source/stage latency, and GPT first delta latency."
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
if ($packageDump -notmatch "versionCode=$ExpectedVersionCode" -or
    $packageDump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Formal V9 package $ExpectedVersionCode/$ExpectedVersionName is not installed."
}

$resolvedText = Invoke-AdbText -Arguments @(
  "-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", $Package)
$resolvedRows = @($resolvedText -split "`r?`n" |
  Where-Object { $_ -match "^$([regex]::Escape($Package))/" } |
  Select-Object -Last 1)
$resolvedComponent = if ($resolvedRows.Count -gt 0) { $resolvedRows[0].Trim() } else { "" }
if (-not $resolvedComponent -or $resolvedComponent -ne "$Package/$Activity") {
  throw "Formal V9 launcher activity did not resolve: $resolvedText"
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
Invoke-AdbQuiet -Arguments @("-s", $Serial, "shell", "am", "start", "--display", "0", "-n", $resolvedComponent)
Start-Sleep -Milliseconds 1200
& $adb -s $Serial logcat -c

$cameraReadyMarker = New-Text @(0x53D6, 0x666F, 0x4E2D)
$cameraBackMarker = New-Text @(0x8FD4, 0x56DE, 0x20, 0x41, 0x49, 0x20, 0x5BF9, 0x8BDD)
$assistantTitleMarker = New-Text @(0x53EE, 0x5F53, 0x20, 0x41, 0x49, 0x20, 0x8FD0, 0x7EF4, 0x52A9, 0x624B)
$fieldInputMarker = New-Text @(0x73B0, 0x573A, 0x8F93, 0x5165)
$fieldImageMarker = New-Text @(0x73B0, 0x573A, 0x8F93, 0x5165, 0x20, 0xB7, 0x20, 0x56FE, 0x7247)
$imageAttachedMarker = New-Text @(0x73B0, 0x573A, 0x56FE, 0x7247, 0x5DF2, 0x9644, 0x52A0)
$photoPendingMarker = New-Text @(0x7167, 0x7247, 0x5F85, 0x53D1, 0x9001)
$listeningMarker = New-Text @(0x6B63, 0x5728, 0x542C)

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

$asrPartialLogMs = Get-VoiceStageLatencyMs -LogText $logcat -Stage "asr_first_partial"
$asrFinalLogMs = Get-VoiceStageLatencyMs -LogText $logcat -Stage "asr_final"
$asrProviderSource = Get-AsrProviderSource -LogText $logcat
$photoContextImageId = Get-PhotoContextImageId -LogText $logcat
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

$photoContextReady = ($photoContextImageId.Length -gt 0 -and $photoContextImageId -ne "local-photo")
$asrFinalSourceObserved = ($asrProviderSource.Length -gt 0)
$asrFinalFromCloud = ($asrProviderSource -eq "cloud")
$cameraUiVisible = ($cameraUi.Contains($cameraReadyMarker) -or $cameraUi.Contains($cameraBackMarker))
$returnedToChatAfterPhoto = ($photoUi.Contains($assistantTitleMarker) -or
  $photoUi.Contains($fieldInputMarker) -or $photoContextReady)
$imageAttached = ($photoUi.Contains($fieldImageMarker) -or
  $photoUi.Contains($imageAttachedMarker) -or
  $photoUi.Contains($photoPendingMarker) -or $photoContextReady)
$asrPartialVisible = ($partialUi.Contains($listeningMarker) -or
  $logcat.Contains("Voice latency stage=asr_first_partial"))
$asrFinalVisible = ($logcat.Contains("Voice latency stage=asr_final") -and $asrFinalSourceObserved)
$gptFirstDeltaObserved = ($null -ne $gptFirstDeltaLogMs)
$inDingdang = ($activity -match "topResumedActivity=.*$([regex]::Escape($Package))")
$latencyWithinBudget = (
  $latency.cameraUi -le $latencyBudgets.cameraUi -and
  $latency.photoReturn -le $latencyBudgets.photoReturn -and
  $latency.asrPartial -le $latencyBudgets.asrPartial -and
  $latency.gptFirstDelta -le $latencyBudgets.gptFirstDelta -and
  $latency.totalInteraction -le $latencyBudgets.totalInteraction
)

$summary = [pscustomobject]@{
  provider = "real"
  packageName = $Package
  versionCode = $ExpectedVersionCode
  versionName = $ExpectedVersionName
  resolvedComponent = $resolvedComponent
  backendBaseUrlProvided = [bool]$BackendBaseUrl
  healthOk = $healthOk
  cameraUiVisible = [bool]$cameraUiVisible
  returnedToChatAfterPhoto = [bool]$returnedToChatAfterPhoto
  imageAttached = [bool]$imageAttached
  photoContextReady = [bool]$photoContextReady
  photoContextImageId = $photoContextImageId
  asrPartialVisible = [bool]$asrPartialVisible
  asrFinalVisible = [bool]$asrFinalVisible
  asrFinalSourceObserved = [bool]$asrFinalSourceObserved
  asrFinalFromCloud = [bool]$asrFinalFromCloud
  asrProviderSource = $asrProviderSource
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
  "photoContextReady",
  "asrPartialVisible",
  "asrFinalVisible",
  "asrFinalSourceObserved",
  "asrFinalFromCloud",
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
