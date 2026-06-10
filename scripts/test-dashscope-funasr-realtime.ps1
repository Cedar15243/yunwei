param(
  [string]$AudioPath = "tmp\tts-zhegeshishenme.wav",
  [string]$ApiKey = "",
  [string]$ApiKeyFile = "tmp\dashscope_api_key.local",
  [string]$Endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
  [string]$Model = "fun-asr-realtime",
  [int]$SampleRate = 16000,
  [int]$ChunkMilliseconds = 100,
  [int]$TimeoutSeconds = 45,
  [string]$EvidencePath = "tmp\dashscope-funasr-realtime-result.json"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$audio = Join-Path $root $AudioPath
$keyFile = Join-Path $root $ApiKeyFile
$evidence = Join-Path $root $EvidencePath

if (-not (Test-Path -LiteralPath $audio)) {
  throw "Audio file not found: $audio"
}

if (-not $ApiKey) {
  $ApiKey = $env:DASHSCOPE_API_KEY
}
if (-not $ApiKey -and (Test-Path -LiteralPath $keyFile)) {
  $ApiKey = (Get-Content -LiteralPath $keyFile -Raw).Trim()
}
if (-not $ApiKey) {
  throw "DASHSCOPE_API_KEY missing. Set env:DASHSCOPE_API_KEY or create ignored file tmp\dashscope_api_key.local."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidence) | Out-Null

function Receive-JsonMessage {
  param(
    [System.Net.WebSockets.ClientWebSocket]$Socket,
    [Threading.CancellationToken]$Token
  )

  $buffer = New-Object byte[] 8192
  $segment = [ArraySegment[byte]]::new($buffer)
  $stream = New-Object System.IO.MemoryStream

  while ($true) {
    $result = $Socket.ReceiveAsync($segment, $Token).GetAwaiter().GetResult()
    if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
      return $null
    }
    if ($result.Count -gt 0) {
      $stream.Write($buffer, 0, $result.Count)
    }
    if ($result.EndOfMessage) {
      break
    }
  }

  $text = [Text.Encoding]::UTF8.GetString($stream.ToArray())
  if (-not $text) {
    return $null
  }
  return $text | ConvertFrom-Json
}

function Send-TextMessage {
  param(
    [System.Net.WebSockets.ClientWebSocket]$Socket,
    [string]$Text,
    [Threading.CancellationToken]$Token
  )

  $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
  $segment = [ArraySegment[byte]]::new($bytes)
  $null = $Socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $Token).GetAwaiter().GetResult()
}

function Send-BinaryChunk {
  param(
    [System.Net.WebSockets.ClientWebSocket]$Socket,
    [byte[]]$Bytes,
    [int]$Offset,
    [int]$Count,
    [Threading.CancellationToken]$Token
  )

  $segment = [ArraySegment[byte]]::new($Bytes, $Offset, $Count)
  $null = $Socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Binary, $true, $Token).GetAwaiter().GetResult()
}

$taskId = [guid]::NewGuid().ToString("N")
$runTask = @{
  header = @{
    action = "run-task"
    task_id = $taskId
    streaming = "duplex"
  }
  payload = @{
    task_group = "audio"
    task = "asr"
    function = "recognition"
    model = $Model
    parameters = @{
      format = "wav"
      sample_rate = $SampleRate
    }
    input = @{}
  }
} | ConvertTo-Json -Depth 10 -Compress

$finishTask = @{
  header = @{
    action = "finish-task"
    task_id = $taskId
    streaming = "duplex"
  }
  payload = @{
    input = @{}
  }
} | ConvertTo-Json -Depth 10 -Compress

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$socket.Options.SetRequestHeader("Authorization", "Bearer $ApiKey")

$cts = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
$events = New-Object System.Collections.Generic.List[object]
$partials = New-Object System.Collections.Generic.List[string]
$started = $false
$finished = $false
$failed = $false
$startedAt = [DateTimeOffset]::UtcNow

try {
  $socket.ConnectAsync([Uri]$Endpoint, $cts.Token).GetAwaiter().GetResult()
  Send-TextMessage -Socket $socket -Text $runTask -Token $cts.Token

  while (-not $started) {
    $message = Receive-JsonMessage -Socket $socket -Token $cts.Token
    if ($null -eq $message) {
      throw "WebSocket closed before task-started"
    }
    $events.Add($message) | Out-Null
    if ($message.header.event -eq "task-started") {
      $started = $true
    } elseif ($message.header.event -eq "task-failed") {
      throw "task-failed before audio: $($message.header.error_message)"
    }
  }

  $audioBytes = [IO.File]::ReadAllBytes($audio)
  $bytesPerSecond = $SampleRate * 2
  $chunkBytes = [Math]::Max(640, [Math]::Round($bytesPerSecond * $ChunkMilliseconds / 1000))

  for ($offset = 0; $offset -lt $audioBytes.Length; $offset += $chunkBytes) {
    $count = [Math]::Min($chunkBytes, $audioBytes.Length - $offset)
    Send-BinaryChunk -Socket $socket -Bytes $audioBytes -Offset $offset -Count $count -Token $cts.Token
    Start-Sleep -Milliseconds $ChunkMilliseconds
  }

  Send-TextMessage -Socket $socket -Text $finishTask -Token $cts.Token

  while (-not $finished -and -not $failed) {
    $message = Receive-JsonMessage -Socket $socket -Token $cts.Token
    if ($null -eq $message) {
      break
    }
    $events.Add($message) | Out-Null
    $event = $message.header.event
    if ($event -eq "result-generated") {
      $text = $message.payload.output.sentence.text
      if ($text) {
        $partials.Add([string]$text) | Out-Null
      }
      Write-Output "result-generated: $text"
    } elseif ($event -eq "task-finished") {
      $finished = $true
    } elseif ($event -eq "task-failed") {
      $failed = $true
    }
  }
} finally {
  if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
    $null = $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  }
  $socket.Dispose()
}

$endedAt = [DateTimeOffset]::UtcNow
$summary = [ordered]@{
  ok = ($finished -and -not $failed)
  endpoint = $Endpoint
  model = $Model
  audioPath = $AudioPath
  sampleRate = $SampleRate
  taskId = $taskId
  startedAt = $startedAt.ToString("o")
  endedAt = $endedAt.ToString("o")
  elapsedMs = [int](($endedAt - $startedAt).TotalMilliseconds)
  eventCount = $events.Count
  resultGeneratedCount = $partials.Count
  finalText = (($partials | Select-Object -Last 1) -as [string])
  events = $events
}

$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $evidence -Encoding UTF8

if (-not $summary.ok) {
  throw "DashScope Fun-ASR realtime smoke failed; evidence=$evidence"
}

Write-Output "DashScope Fun-ASR realtime smoke passed"
Write-Output "Model=$Model"
Write-Output "ResultGeneratedCount=$($partials.Count)"
Write-Output "FinalText=$($summary.finalText)"
Write-Output "Evidence=$evidence"
