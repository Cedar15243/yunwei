param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangexpert.v9",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$SystemCameraPackage = "com.inmo.camera_extreme",
  [int]$ExpectedVersionCode = 900000,
  [string]$ExpectedVersionName = "9.0.0",
  [ValidateSet("SystemCamera", "AppCameraWhenDelivered")]
  [string]$CameraKeyMode = "SystemCamera",
  [int]$WaitSeconds = 30,
  [int]$WaitAfterKeyMs = 1200,
  [string]$OutDir = ""
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
  $OutDir = Join-Path $repoRoot "tmp\sidekey-shortcut-regression-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$adb = Resolve-AdbPath

function Invoke-AdbText {
  param([string[]]$Arguments)
  return (& $adb @Arguments) -join "`n"
}

function Wait-ForDevice {
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  while ((Get-Date) -lt $deadline) {
    $devices = Invoke-AdbText -Arguments @("devices", "-l")
    if ($devices -match [regex]::Escape($Serial)) {
      $devices | Set-Content -LiteralPath (Join-Path $OutDir "devices.txt") -Encoding UTF8
      return
    }
    Start-Sleep -Seconds 2
  }
  Invoke-AdbText -Arguments @("devices", "-l")
  throw "Air3 device not found by adb: $Serial"
}

function Start-DingdangApp {
  & $adb -s $Serial shell am force-stop $SystemCameraPackage | Out-Null
  & $adb -s $Serial shell am force-stop $Package | Out-Null
  Start-Sleep -Milliseconds 250
  & $adb -s $Serial shell am start --display 0 -n "$Package/$Activity" | Out-Null
  Start-Sleep -Milliseconds 900
}

function Get-FocusText {
  return (& $adb -s $Serial shell dumpsys window |
    Select-String -Pattern "mCurrentFocus|mFocusedApp" |
    ForEach-Object { $_.ToString() }) -join "`n"
}

function Get-KeyNameFromLog {
  param([string]$LogText)
  $match = [regex]::Match($LogText, "keyName=([^\s]+)")
  if ($match.Success) {
    return $match.Groups[1].Value
  }
  return ""
}

Wait-ForDevice

$packageDump = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $Package)
$packageDump | Set-Content -LiteralPath (Join-Path $OutDir "package.txt") -Encoding UTF8
if ($packageDump -notmatch "versionCode=$ExpectedVersionCode") {
  throw "Dingdang package versionCode mismatch; expected $ExpectedVersionCode"
}
if ($packageDump -notmatch "versionName=$([regex]::Escape($ExpectedVersionName))") {
  throw "Dingdang package versionName mismatch; expected $ExpectedVersionName"
}

$resolved = Invoke-AdbText -Arguments @("-s", $Serial, "shell", "cmd", "package", "resolve-activity", "--brief", $Package)
$resolved | Set-Content -LiteralPath (Join-Path $OutDir "resolve-activity.txt") -Encoding UTF8
if ($resolved -notmatch [regex]::Escape("$Package/$Activity")) {
  throw "Dingdang package activity did not resolve: $resolved"
}

$cameraKeyExpectation = if ($CameraKeyMode -eq "AppCameraWhenDelivered") { "app_camera_or_system_camera" } else { "system_camera" }

$keyCases = @(
  @{ name = "enter_66_voice"; code = 66; expect = "voice" },
  @{ name = "dpad_center_23_voice"; code = 23; expect = "voice" },
  @{ name = "focus_80_app_camera"; code = 80; expect = "app_camera" },
  @{ name = "f9_139_app_camera"; code = 139; expect = "app_camera" },
  @{ name = "up_19_chat_scroll"; code = 19; expect = "chat_scroll" },
  @{ name = "down_20_chat_scroll"; code = 20; expect = "chat_scroll" },
  @{ name = "right_22_send"; code = 22; expect = "send" },
  @{ name = "menu_82_send"; code = 82; expect = "send" },
  @{ name = "f12_142_send"; code = 142; expect = "send" },
  @{ name = "back_4_chat"; code = 4; expect = "chat" },
  @{ name = "left_21_chat"; code = 21; expect = "chat" },
  @{ name = "f10_140_chat"; code = 140; expect = "chat" },
  @{ name = "volume_up_24_consumed"; code = 24; expect = "consumed" },
  @{ name = "volume_down_25_consumed"; code = 25; expect = "consumed" },
  @{ name = "camera_27_reserved"; code = 27; expect = $cameraKeyExpectation },
  @{ name = "dvr_173_reserved"; code = 173; expect = $cameraKeyExpectation },
  @{ name = "f11_141_not_bound"; code = 141; expect = "not_bound" }
)

$summary = @()
foreach ($case in $keyCases) {
  Start-DingdangApp
  & $adb -s $Serial logcat -c
  & $adb -s $Serial shell input keyevent $case.code | Out-Null
  Start-Sleep -Milliseconds $WaitAfterKeyMs

  $focusText = Get-FocusText
  $uiText = (& $adb -s $Serial exec-out uiautomator dump /dev/tty) -join "`n"
  $logText = (& $adb -s $Serial logcat -d -s DingdangKey AndroidRuntime) -join "`n"

  $casePrefix = Join-Path $OutDir $case.name
  $focusText | Set-Content -LiteralPath "$casePrefix-focus.txt" -Encoding UTF8
  $uiText | Set-Content -LiteralPath "$casePrefix-ui.xml" -Encoding UTF8
  $logText | Set-Content -LiteralPath "$casePrefix-logcat.txt" -Encoding UTF8

  $appLog = $logText -match "keyCode=$($case.code)"
  $inDingdang = $focusText -match [regex]::Escape($Package)
  $inSystemCamera = $focusText -match [regex]::Escape($SystemCameraPackage)
  $voiceStarted = $logText -match "Realtime ASR start state=LISTENING"
  $cameraScreenLog = $logText -match "screen=CAMERA"
  $cameraPreviewLog = $logText -match "Camera preview transform"
  $cameraUiMarker = -join @([char]0x5BF9, [char]0x51C6, [char]0x73B0, [char]0x573A, [char]0x540E, [char]0x62CD, [char]0x7167)
  $cameraUiVisible = $uiText -match [regex]::Escape($cameraUiMarker)
  $appCameraStarted = ($cameraScreenLog -or $cameraPreviewLog -or $cameraUiVisible)
  $chatScrollHandled = ($appLog -and $inDingdang -and -not $inSystemCamera)
  $reservedObserved = $logText -match "system-reserved camera key observed"

  $expectedOk = switch ($case.expect) {
    "voice" { $appLog -and $inDingdang -and $voiceStarted }
    "app_camera" { $appLog -and $inDingdang -and $appCameraStarted }
    "chat_scroll" { $chatScrollHandled }
    "send" { $appLog -and $inDingdang -and -not $inSystemCamera }
    "chat" { $appLog -and $inDingdang -and -not $inSystemCamera }
    "consumed" { $appLog -and $inDingdang -and -not $inSystemCamera }
    "system_camera" { $inSystemCamera }
    "app_camera_or_system_camera" { ($appLog -and $inDingdang -and $appCameraStarted) -or $inSystemCamera }
    "not_bound" { $appLog -and $inDingdang -and -not $appCameraStarted -and -not $inSystemCamera }
    default { $false }
  }

  $summary += [pscustomobject]@{
    name = $case.name
    code = $case.code
    androidKeyName = Get-KeyNameFromLog -LogText $logText
    expect = $case.expect
    appLog = [bool]$appLog
    inDingdang = [bool]$inDingdang
    inSystemCamera = [bool]$inSystemCamera
    systemIntercepted = [bool]((-not $appLog) -and $inSystemCamera)
    voiceStarted = [bool]$voiceStarted
    appCameraStarted = [bool]$appCameraStarted
    reservedObserved = [bool]$reservedObserved
    expectedOk = [bool]$expectedOk
  }
}

$settingsGlobal = (& $adb -s $Serial shell settings list global |
  Select-String -Pattern "side|button|camera|key|assistant|shortcut" |
  ForEach-Object { $_.ToString() }) -join "`n"
$settingsSecure = (& $adb -s $Serial shell settings list secure |
  Select-String -Pattern "side|button|camera|key|assistant|shortcut" |
  ForEach-Object { $_.ToString() }) -join "`n"
$settingsSystem = (& $adb -s $Serial shell settings list system |
  Select-String -Pattern "side|button|camera|key|assistant|shortcut" |
  ForEach-Object { $_.ToString() }) -join "`n"
$settingsGlobal | Set-Content -LiteralPath (Join-Path $OutDir "settings-global-key-lines.txt") -Encoding UTF8
$settingsSecure | Set-Content -LiteralPath (Join-Path $OutDir "settings-secure-key-lines.txt") -Encoding UTF8
$settingsSystem | Set-Content -LiteralPath (Join-Path $OutDir "settings-system-key-lines.txt") -Encoding UTF8

Start-DingdangApp
$finalFocus = Get-FocusText
$finalFocus | Set-Content -LiteralPath (Join-Path $OutDir "final-focus.txt") -Encoding UTF8

$allExpectedOk = -not ($summary | Where-Object { -not $_.expectedOk })
$result = [pscustomobject]@{
  serial = $Serial
  package = $Package
  versionCode = $ExpectedVersionCode
  versionName = $ExpectedVersionName
  cameraKeyMode = $CameraKeyMode
  allExpectedOk = [bool]$allExpectedOk
  finalInDingdang = [bool]($finalFocus -match [regex]::Escape($Package))
  evidenceDir = $OutDir
  cases = $summary
}
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutDir "summary.json") -Encoding UTF8

$summary | Format-Table -AutoSize
Write-Output "Evidence: $OutDir"
if (-not $allExpectedOk) {
  throw "Air3 shortcut regression failed; see $OutDir\summary.json"
}
if ($finalFocus -notmatch [regex]::Escape($Package)) {
  throw "Dingdang app is not focused after shortcut regression"
}
