param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangexpert.v9",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [int]$ExpectedVersionCode = 900000,
  [string]$ExpectedVersionName = "9.0.0",
  [string]$ProtectedV8Package = "com.codex.air3nativecamera.dingdangexpert.follow.preview.investorv8audit48",
  [int]$DurationSeconds = 1200,
  [int]$CameraCycles = 3,
  [int]$CameraHoldSeconds = 5,
  [int]$SampleIntervalSeconds = 30,
  [int]$MaxJankPercent = 10,
  [int]$MaxPssDeltaKb = 30000,
  [int]$MaxBatteryTemperature = 450,
  [int]$MaxThermalStatus = 2,
  [string]$OutDir = "",
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath {
  param([string]$PathValue)
  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return $PathValue
  }
  return Join-Path $repoRoot $PathValue
}

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

function Invoke-AdbText {
  param([string[]]$Arguments)
  return (& $adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
}

function Get-ElapsedSeconds {
  param([datetime]$Start)
  return [int][Math]::Round(((Get-Date) - $Start).TotalSeconds)
}

function Get-UiDump {
  return (Invoke-AdbText -Arguments @("-s", $Serial, "exec-out", "uiautomator", "dump", "/dev/tty"))
}

function Get-FocusText {
  return (Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "window") |
    Select-String -Pattern "mCurrentFocus|mFocusedApp" |
    ForEach-Object { $_.ToString() }) -join "`n"
}

function Get-PackageVersion {
  param([string]$PackageName)
  $dump = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $PackageName)
  $versionCode = $null
  $versionName = ""
  if ($dump -match "versionCode=(\d+)") {
    $versionCode = [int64]$matches[1]
  }
  if ($dump -match "versionName=([^\s]+)") {
    $versionName = $matches[1]
  }
  return [pscustomobject]@{
    present = [bool]($null -ne $versionCode)
    versionCode = $versionCode
    versionName = $versionName
  }
}

function Get-BatterySnapshot {
  $raw = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "battery")
  $level = $null
  $temperature = $null
  $status = $null
  $acPowered = $null
  $usbPowered = $null
  $wirelessPowered = $null
  $dockPowered = $null
  if ($raw -match "(?m)^\s*level:\s*(\d+)") { $level = [int]$matches[1] }
  if ($raw -match "(?m)^\s*temperature:\s*(\d+)") { $temperature = [int]$matches[1] }
  if ($raw -match "(?m)^\s*status:\s*(\d+)") { $status = [int]$matches[1] }
  if ($raw -match "(?im)^\s*AC powered:\s*(true|false)") { $acPowered = $matches[1] -ieq "true" }
  if ($raw -match "(?im)^\s*USB powered:\s*(true|false)") { $usbPowered = $matches[1] -ieq "true" }
  if ($raw -match "(?im)^\s*Wireless powered:\s*(true|false)") { $wirelessPowered = $matches[1] -ieq "true" }
  if ($raw -match "(?im)^\s*Dock powered:\s*(true|false)") { $dockPowered = $matches[1] -ieq "true" }
  return [pscustomobject]@{
    level = $level
    temperature = $temperature
    status = $status
    acPowered = $acPowered
    usbPowered = $usbPowered
    wirelessPowered = $wirelessPowered
    dockPowered = $dockPowered
    raw = $raw
  }
}

function Get-ThermalSnapshot {
  $raw = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "thermalservice")
  $status = $null
  foreach ($pattern in @(
      "(?i)Current Thermal Status\s*:\s*(\d+)",
      "(?i)Current thermal status\s*:\s*(\d+)",
      "(?i)mStatus\s*=\s*(\d+)")) {
    if ($raw -match $pattern) {
      $status = [int]$matches[1]
      break
    }
  }
  return [pscustomobject]@{
    status = $status
    raw = $raw
  }
}

function Get-PssKb {
  $raw = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "meminfo", $Package)
  $pss = $null
  if ($raw -match "(?m)^\s*TOTAL\s+(\d+)\s+") {
    $pss = [int]$matches[1]
  }
  return [pscustomobject]@{
    pssKb = $pss
    raw = $raw
  }
}

function Get-GfxSnapshot {
  $raw = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "gfxinfo", $Package)
  $frames = $null
  $jankyFrames = $null
  $jankPercent = $null
  if ($raw -match "(?m)Total frames rendered:\s*(\d+)") { $frames = [int]$matches[1] }
  if ($raw -match "(?m)Janky frames:\s*(\d+)") { $jankyFrames = [int]$matches[1] }
  if ($raw -match "(?m)Janky frames:\s*\d+\s*\(([0-9.]+)%\)") { $jankPercent = [double]$matches[1] }
  return [pscustomobject]@{
    frames = $frames
    jankyFrames = $jankyFrames
    jankPercent = $jankPercent
    raw = $raw
  }
}

function Get-CameraSnapshot {
  $raw = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "media.camera")
  $empty = $raw -match "(?im)^\s*Active Camera Clients:\s*\[\s*\]\s*$"
  return [pscustomobject]@{
    empty = [bool]$empty
    raw = $raw
  }
}

function Get-AudioSnapshot {
  $audio = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "audio")
  $flinger = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "media.audio_flinger")
  $combined = "$audio`n$flinger"
  $packageLines = @($combined -split "`r?`n" | Where-Object {
      $_ -match [regex]::Escape($Package)
    })
  return [pscustomobject]@{
    packageLineCount = $packageLines.Count
    packageLines = $packageLines
    raw = $combined
  }
}

function Get-FilteredLogcat {
  return Invoke-AdbText -Arguments @("-s", $Serial, "logcat", "-d", "-v", "threadtime", "-s", "DingdangKey", "AndroidRuntime", "ActivityManager")
}

function New-Text {
  param([int[]]$Codes)
  return -join ($Codes | ForEach-Object { [char]$_ })
}

function Wait-ForCondition {
  param([scriptblock]$Condition, [int]$TimeoutSeconds = 10, [int]$PollMs = 250)
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (& $Condition) {
      return $true
    }
    Start-Sleep -Milliseconds $PollMs
  }
  return $false
}

function Start-V9 {
  & $adb -s $Serial shell am force-stop $Package | Out-Null
  Start-Sleep -Milliseconds 300
  $launch = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "am", "start", "-W", "--display", "0", "-n", $resolvedComponent)
  if ($launch -notmatch "Status:\s*ok") {
    throw "V9 launcher did not report Status: ok: $launch"
  }
  if (-not (Wait-ForCondition -TimeoutSeconds 10 -Condition {
        (Get-FocusText) -match [regex]::Escape($Package)
      })) {
    throw "V9 did not become the foreground activity."
  }
}

function Save-Text {
  param([string]$Name, [string]$Value)
  $Value | Set-Content -LiteralPath (Join-Path $OutDir $Name) -Encoding UTF8
}

function Get-ThermalSensorPeaks {
  param([object[]]$Snapshots)
  $cpu = $null
  $gpu = $null
  $skin = $null
  $sampleCount = 0
  $pattern = "Temperature\{mValue=([-+]?[0-9]+(?:\.[0-9]+)?),\s*mType=(\d+),\s*mName=([^,}]+),\s*mStatus=(\d+)\}"

  foreach ($snapshot in $Snapshots) {
    if ($null -eq $snapshot.thermal -or [string]::IsNullOrWhiteSpace($snapshot.thermal.raw)) {
      continue
    }
    $sampleCount++
    foreach ($match in [regex]::Matches($snapshot.thermal.raw, $pattern)) {
      $value = [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
      $type = [int]$match.Groups[2].Value
      if ($type -eq 0 -and ($null -eq $cpu -or $value -gt $cpu)) { $cpu = $value }
      if ($type -eq 1 -and ($null -eq $gpu -or $value -gt $gpu)) { $gpu = $value }
      if ($type -eq 3 -and ($null -eq $skin -or $value -gt $skin)) { $skin = $value }
    }
  }

  return [pscustomobject]@{
    peaksC = [ordered]@{
      cpu = $cpu
      gpu = $gpu
      skin = $skin
    }
    sampleCount = $sampleCount
  }
}

if ($DurationSeconds -lt 1 -or $CameraCycles -lt 0 -or $SampleIntervalSeconds -lt 1 -or
    $CameraHoldSeconds -lt 0) {
  throw "DurationSeconds, CameraCycles, SampleIntervalSeconds and CameraHoldSeconds must be non-negative, with duration/sample interval positive."
}

if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "output\air3-v9-hardware-soak-$stamp"
} else {
  $OutDir = Resolve-RepoPath -PathValue $OutDir
}

if ($DryRun) {
  Write-Output "Dry run for V9 Air3 hardware soak."
  Write-Output "Would verify com.codex.air3nativecamera.dingdangexpert.v9 900000/9.0.0 and resolve the launcher dynamically."
  Write-Output "Would run $CameraCycles Camera2 cycles, then $DurationSeconds seconds of standby sampling every $SampleIntervalSeconds seconds."
  Write-Output "Would collect battery, thermalservice, meminfo, gfxinfo, media.camera, audio, foreground, crash and ANR evidence without network access."
  Write-Output "Would write summary.json under $OutDir and fail closed on leaks, crash/ANR, thermal status or jank budget violations."
  return
}

$adb = Resolve-AdbPath
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$devices = Invoke-AdbText -Arguments @("devices", "-l")
Save-Text -Name "devices.txt" -Value $devices
if ($devices -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
  throw "Air3 device not found by adb: $Serial"
}

$packageDump = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $Package)
Save-Text -Name "package.txt" -Value $packageDump
if ($packageDump -notmatch "versionCode=$ExpectedVersionCode" -or
    $packageDump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Formal V9 package $ExpectedVersionCode/$ExpectedVersionName is not installed."
}

$resolvedText = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", $Package)
Save-Text -Name "resolve-activity.txt" -Value $resolvedText
$resolvedRows = @($resolvedText -split "`r?`n" |
  Where-Object { $_ -match "^$([regex]::Escape($Package))/" } |
  Select-Object -Last 1)
$resolvedComponent = if ($resolvedRows.Count -gt 0) { $resolvedRows[0].Trim() } else { "" }
if (-not $resolvedComponent) {
  throw "Formal V9 launcher activity did not resolve: $resolvedText"
}

$protectedBefore = Get-PackageVersion -PackageName $ProtectedV8Package
$cameraReadyMarker = New-Text @(0x53D6, 0x666F, 0x4E2D)
$cameraBackMarker = New-Text @(0x8FD4, 0x56DE, 0x20, 0x41, 0x49, 0x20, 0x5BF9, 0x8BDD)

& $adb -s $Serial logcat -c | Out-Null
Start-V9
& $adb -s $Serial shell dumpsys gfxinfo $Package reset | Out-Null

$cycleResults = @()
for ($cycle = 1; $cycle -le $CameraCycles; $cycle++) {
  & $adb -s $Serial logcat -c | Out-Null
  & $adb -s $Serial shell input keyevent 80 | Out-Null
  $opened = Wait-ForCondition -TimeoutSeconds 12 -Condition {
    $ui = Get-UiDump
    ($ui.Contains($cameraReadyMarker) -or $ui.Contains($cameraBackMarker))
  }
  $openUi = Get-UiDump
  Save-Text -Name ("cycle-{0:d2}-open-ui.xml" -f $cycle) -Value $openUi
  $openCamera = Get-CameraSnapshot
  Save-Text -Name ("cycle-{0:d2}-open-camera.txt" -f $cycle) -Value $openCamera.raw
  if ($CameraHoldSeconds -gt 0) {
    Start-Sleep -Seconds $CameraHoldSeconds
  }

  & $adb -s $Serial shell input keyevent 66 | Out-Null
  Start-Sleep -Seconds 3
  $afterCaptureUi = Get-UiDump
  Save-Text -Name ("cycle-{0:d2}-after-capture-ui.xml" -f $cycle) -Value $afterCaptureUi

  & $adb -s $Serial shell input keyevent 4 | Out-Null
  $returned = Wait-ForCondition -TimeoutSeconds 12 -Condition {
    ((Get-FocusText) -match [regex]::Escape($Package)) -and
      (Get-CameraSnapshot).empty
  }
  $afterBackCamera = Get-CameraSnapshot
  $afterBackFocus = Get-FocusText
  Save-Text -Name ("cycle-{0:d2}-after-back-camera.txt" -f $cycle) -Value $afterBackCamera.raw
  Save-Text -Name ("cycle-{0:d2}-after-back-focus.txt" -f $cycle) -Value $afterBackFocus
  $cycleResults += [pscustomobject]@{
    cycle = $cycle
    opened = [bool]$opened
    returned = [bool]$returned
    cameraEmptyAfterBack = [bool]$afterBackCamera.empty
    foregroundAfterBack = [bool]($afterBackFocus -match [regex]::Escape($Package))
  }
  if (-not $opened -or -not $returned) {
    throw "Camera2 cycle $cycle did not open and release cleanly."
  }
}

$soakStart = Get-Date
$baselineBattery = Get-BatterySnapshot
$baselineThermal = Get-ThermalSnapshot
$baselinePss = Get-PssKb
$baselineGfx = Get-GfxSnapshot
$baselineCamera = Get-CameraSnapshot
$baselineAudio = Get-AudioSnapshot
$baselineFocus = Get-FocusText
$baseline = [pscustomobject]@{
  elapsedSeconds = 0
  battery = $baselineBattery
  thermal = $baselineThermal
  pss = $baselinePss
  gfx = $baselineGfx
  camera = $baselineCamera
  audio = $baselineAudio
  foreground = ($baselineFocus -match [regex]::Escape($Package))
}
$baseline | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir "baseline.json") -Encoding UTF8

$requiredSampleCount = [Math]::Ceiling($DurationSeconds / $SampleIntervalSeconds) + 1
$snapshots = @($baseline)
while ($snapshots.Count -lt $requiredSampleCount) {
  $targetElapsedSeconds = [Math]::Min(
    $DurationSeconds,
    $snapshots.Count * $SampleIntervalSeconds
  )
  $nextSampleAt = $soakStart.AddSeconds($targetElapsedSeconds)
  while ((Get-Date) -lt $nextSampleAt) {
    $remainingMs = [Math]::Ceiling(($nextSampleAt - (Get-Date)).TotalMilliseconds)
    Start-Sleep -Milliseconds ([Math]::Min(1000, [Math]::Max(1, $remainingMs)))
  }
  $battery = Get-BatterySnapshot
  $thermal = Get-ThermalSnapshot
  $pss = Get-PssKb
  $gfx = Get-GfxSnapshot
  $camera = Get-CameraSnapshot
  $audio = Get-AudioSnapshot
  $focus = Get-FocusText
  $snapshot = [pscustomobject]@{
    elapsedSeconds = Get-ElapsedSeconds -Start $soakStart
    battery = $battery
    thermal = $thermal
    pss = $pss
    gfx = $gfx
    camera = $camera
    audio = $audio
    foreground = ($focus -match [regex]::Escape($Package))
  }
  $snapshots += $snapshot
}

$finalBattery = Get-BatterySnapshot
$finalThermal = Get-ThermalSnapshot
$finalPss = Get-PssKb
$finalGfx = Get-GfxSnapshot
$finalCamera = Get-CameraSnapshot
$finalAudio = Get-AudioSnapshot
$finalFocus = Get-FocusText
$filteredLogcat = Get-FilteredLogcat
$crashLogcat = Invoke-AdbText -Arguments @("-s", $Serial, "logcat", "-b", "crash", "-d")
$allLogcat = Invoke-AdbText -Arguments @("-s", $Serial, "logcat", "-d", "-v", "threadtime")

$snapshots | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir "snapshots.json") -Encoding UTF8
$finalBattery.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-battery.txt") -Encoding UTF8
$finalThermal.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-thermalservice.txt") -Encoding UTF8
$finalPss.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-meminfo.txt") -Encoding UTF8
$finalGfx.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-gfxinfo.txt") -Encoding UTF8
$finalCamera.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-camera.txt") -Encoding UTF8
$finalAudio.raw | Set-Content -LiteralPath (Join-Path $OutDir "final-audio.txt") -Encoding UTF8
$filteredLogcat | Set-Content -LiteralPath (Join-Path $OutDir "logcat-filtered.txt") -Encoding UTF8
$crashLogcat | Set-Content -LiteralPath (Join-Path $OutDir "logcat-crash.txt") -Encoding UTF8
$allLogcat | Set-Content -LiteralPath (Join-Path $OutDir "logcat-all.txt") -Encoding UTF8

$maxTemperature = ($snapshots | Where-Object { $null -ne $_.battery.temperature } |
  ForEach-Object { $_.battery.temperature } | Measure-Object -Maximum).Maximum
$maxThermalStatus = ($snapshots | Where-Object { $null -ne $_.thermal.status } |
  ForEach-Object { $_.thermal.status } | Measure-Object -Maximum).Maximum
$maxPss = ($snapshots | Where-Object { $null -ne $_.pss.pssKb } |
  ForEach-Object { $_.pss.pssKb } | Measure-Object -Maximum).Maximum
$thermalSensorEvidence = Get-ThermalSensorPeaks -Snapshots $snapshots
$externalPowerConnected = [bool]($snapshots | Where-Object {
    $_.battery.acPowered -or $_.battery.usbPowered -or
      $_.battery.wirelessPowered -or $_.battery.dockPowered
  } | Select-Object -First 1)
$powerMeasurementValid = -not $externalPowerConnected
$pssDeltaKb = if ($null -ne $maxPss -and $null -ne $baselinePss.pssKb) {
  [int]$maxPss - [int]$baselinePss.pssKb
} else {
  $null
}
$jankPercent = $finalGfx.jankPercent
$jankWithinBudget = ($null -eq $jankPercent -or $jankPercent -le $MaxJankPercent)
$durationReached = ((Get-ElapsedSeconds -Start $soakStart) -ge $DurationSeconds)
$sampleCoveragePassed = ($snapshots.Count -ge $requiredSampleCount) -and $durationReached
$crashBufferEmpty = [string]::IsNullOrWhiteSpace($crashLogcat)
$anrObserved = [bool]($allLogcat -match "(?i)ANR in|Application Not Responding|am_anr")
$cameraLeak = -not [bool]$finalCamera.empty
$audioLeak = $finalAudio.packageLineCount -gt 0
$foregroundFinal = [bool]($finalFocus -match [regex]::Escape($Package))
$thermalWithinBudget = (($null -eq $maxThermalStatus -or $maxThermalStatus -le $MaxThermalStatus) -and
  ($null -eq $maxTemperature -or $maxTemperature -le $MaxBatteryTemperature))
$memoryWithinBudget = ($null -eq $pssDeltaKb -or $pssDeltaKb -le $MaxPssDeltaKb)
$protectedAfter = Get-PackageVersion -PackageName $ProtectedV8Package
$v8Unchanged = (-not $protectedBefore) -or ($protectedAfter.present -and
  $protectedAfter.versionCode -eq $protectedBefore.versionCode -and
  $protectedAfter.versionName -eq $protectedBefore.versionName)
$identityOk = ($Package -eq "com.codex.air3nativecamera.dingdangexpert.v9" -and
  $ExpectedVersionCode -eq 900000 -and $ExpectedVersionName -eq "9.0.0")
$cyclesPassed = ($cycleResults.Count -eq $CameraCycles) -and
  -not ($cycleResults | Where-Object { -not $_.opened -or -not $_.returned -or -not $_.cameraEmptyAfterBack })
$passed = [bool]($identityOk -and $sampleCoveragePassed -and $cyclesPassed -and
  $foregroundFinal -and $crashBufferEmpty -and -not $anrObserved -and
  -not $cameraLeak -and -not $audioLeak -and $thermalWithinBudget -and
  $memoryWithinBudget -and $jankWithinBudget -and $v8Unchanged)

$summary = [ordered]@{
  provider = "local_device"
  packageName = $Package
  versionCode = $ExpectedVersionCode
  versionName = $ExpectedVersionName
  serial = $Serial
  durationSecondsRequested = $DurationSeconds
  durationSecondsObserved = Get-ElapsedSeconds -Start $soakStart
  sampleIntervalSeconds = $SampleIntervalSeconds
  requiredSampleCount = $requiredSampleCount
  sampleCount = $snapshots.Count
  sampleCoveragePassed = [bool]$sampleCoveragePassed
  cameraCyclesRequested = $CameraCycles
  cameraCyclesPassed = [bool]$cyclesPassed
  cycles = $cycleResults
  baselineBatteryLevel = $baselineBattery.level
  finalBatteryLevel = $finalBattery.level
  batteryLevelDelta = if ($null -ne $baselineBattery.level -and $null -ne $finalBattery.level) { $finalBattery.level - $baselineBattery.level } else { $null }
  maxBatteryTemperature = $maxTemperature
  maxThermalStatus = $maxThermalStatus
  thermalSensorPeaksC = $thermalSensorEvidence.peaksC
  thermalSensorSampleCount = $thermalSensorEvidence.sampleCount
  externalPowerConnected = [bool]$externalPowerConnected
  powerMeasurementValid = [bool]$powerMeasurementValid
  baselinePssKb = $baselinePss.pssKb
  maxPssKb = $maxPss
  pssDeltaKb = $pssDeltaKb
  finalJankPercent = $jankPercent
  cameraLeak = [bool]$cameraLeak
  audioLeak = [bool]$audioLeak
  crashBufferEmpty = [bool]$crashBufferEmpty
  anrObserved = [bool]$anrObserved
  foregroundFinal = [bool]$foregroundFinal
  thermalWithinBudget = [bool]$thermalWithinBudget
  memoryWithinBudget = [bool]$memoryWithinBudget
  jankWithinBudget = [bool]$jankWithinBudget
  protectedV8Package = $ProtectedV8Package
  protectedV8Present = [bool]$protectedBefore.present
  protectedV8Unchanged = [bool]$v8Unchanged
  evidenceDir = $OutDir
  passed = $passed
  generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir "summary.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 10

if (-not $passed) {
  throw "V9 Air3 hardware soak failed one or more checks. See $OutDir\summary.json"
}
