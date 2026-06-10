param(
  [string]$Serial = "YM00FCF3NW0031",
  [string]$Package = "com.codex.air3nativecamera.dingdangops",
  [string]$Activity = "com.codex.air3nativecamera.MainActivity",
  [string]$SystemCameraPackage = "com.inmo.camera_extreme",
  [int]$DurationSeconds = 20,
  [string]$OutDir = "",
  [switch]$SkipLaunch,
  [switch]$AllowNoKeyEvent,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutDir = Join-Path $repoRoot "tmp\physical-sidekey-$stamp"
}

$adb = Join-Path $repoRoot "tmp\tools\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
  $adb = "adb"
}

function Invoke-AdbText {
  param([string[]]$Arguments)
  return (& $adb @Arguments) -join "`n"
}

function Get-FocusText {
  return (& $adb -s $Serial shell dumpsys window |
    Select-String -Pattern "mCurrentFocus|mFocusedApp" |
    ForEach-Object { $_.ToString() }) -join "`n"
}

function Start-DingdangApp {
  & $adb -s $Serial shell am start --display 0 -n "$Package/$Activity" | Out-Null
  Start-Sleep -Milliseconds 900
}

function Capture-KeySettingLines {
  param([string]$Namespace, [string]$Path)
  (& $adb -s $Serial shell settings list $Namespace |
    Select-String -Pattern "side|button|camera|key|assistant|shortcut" |
    ForEach-Object { $_.ToString() }) -join "`n" |
    Set-Content -LiteralPath $Path -Encoding UTF8
}

if ($DryRun) {
  Write-Output "Dry run for Air3 physical side-key capture."
  Write-Output "Would launch $Package/$Activity unless -SkipLaunch is set."
  Write-Output "Would capture getevent -lt for $DurationSeconds seconds."
  Write-Output "Would capture DingdangKey logcat, focused Activity, settings key lines, and dumpsys input."
  Write-Output "Would write evidence to $OutDir."
  return
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$devices = Invoke-AdbText -Arguments @("devices", "-l")
$devices | Set-Content -LiteralPath (Join-Path $OutDir "devices.txt") -Encoding UTF8
if ($devices -notmatch [regex]::Escape($Serial)) {
  throw "Air3 device not found by adb: $Serial"
}

if (-not $SkipLaunch) {
  Start-DingdangApp
}

& $adb -s $Serial logcat -c
Start-Sleep -Milliseconds 250

$beforeFocus = Get-FocusText
$beforeFocus | Set-Content -LiteralPath (Join-Path $OutDir "focus-before.txt") -Encoding UTF8

$geteventOut = Join-Path $OutDir "getevent-lt.txt"
$geteventErr = Join-Path $OutDir "getevent.err.txt"
Write-Output "Press the physical side key on the glasses now. Capturing for $DurationSeconds seconds..."
$geteventProcess = Start-Process `
  -FilePath $adb `
  -ArgumentList @("-s", $Serial, "shell", "getevent", "-lt") `
  -WindowStyle Hidden `
  -PassThru `
  -RedirectStandardOutput $geteventOut `
  -RedirectStandardError $geteventErr

try {
  Start-Sleep -Seconds $DurationSeconds
} finally {
  if ($geteventProcess -and -not $geteventProcess.HasExited) {
    Stop-Process -Id $geteventProcess.Id -Force
  }
}

Start-Sleep -Milliseconds 500
& $adb -s $Serial logcat -d -s DingdangKey AndroidRuntime > (Join-Path $OutDir "logcat.txt")
& $adb -s $Serial shell dumpsys input > (Join-Path $OutDir "dumpsys-input.txt")
& $adb -s $Serial shell dumpsys package $Package > (Join-Path $OutDir "package.txt")
Capture-KeySettingLines -Namespace "global" -Path (Join-Path $OutDir "settings-global-key-lines.txt")
Capture-KeySettingLines -Namespace "secure" -Path (Join-Path $OutDir "settings-secure-key-lines.txt")
Capture-KeySettingLines -Namespace "system" -Path (Join-Path $OutDir "settings-system-key-lines.txt")

$afterFocus = Get-FocusText
$afterFocus | Set-Content -LiteralPath (Join-Path $OutDir "focus-after.txt") -Encoding UTF8

$logcat = Get-Content -Raw -Encoding UTF8 (Join-Path $OutDir "logcat.txt")
$getevent = if (Test-Path -LiteralPath $geteventOut) {
  Get-Content -Raw -Encoding UTF8 $geteventOut
} else {
  ""
}

$dingdangKeyLines = @()
foreach ($line in ($logcat -split "`r?`n")) {
  if ($line -match "DingdangKey") {
    $dingdangKeyLines += $line
  }
}

$parsedKeys = @()
foreach ($line in $dingdangKeyLines) {
  $code = ""
  $name = ""
  if ($line -match "keyCode=(\d+)") {
    $code = $matches[1]
  }
  if ($line -match "keyName=([^\s]+)") {
    $name = $matches[1]
  }
  if ($code -or $name) {
    $parsedKeys += [pscustomobject]@{
      keyCode = $code
      keyName = $name
      line = $line
    }
  }
}

$summary = [pscustomobject]@{
  serial = $Serial
  package = $Package
  durationSeconds = $DurationSeconds
  keyLogCount = $parsedKeys.Count
  geteventLineCount = (($getevent -split "`r?`n") | Where-Object { $_.Trim().Length -gt 0 }).Count
  inDingdangAfter = [bool]($afterFocus -match [regex]::Escape($Package))
  inSystemCameraAfter = [bool]($afterFocus -match [regex]::Escape($SystemCameraPackage))
  reservedCameraObserved = [bool]($logcat -match "system-reserved camera key observed")
  parsedKeys = $parsedKeys
  evidenceDir = $OutDir
}

$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutDir "summary.json") -Encoding UTF8
$summary | ConvertTo-Json -Depth 6

if (-not $AllowNoKeyEvent -and $parsedKeys.Count -eq 0) {
  throw "No DingdangKey events captured. Re-run while pressing the physical side key, or pass -AllowNoKeyEvent for a capture dry run."
}
