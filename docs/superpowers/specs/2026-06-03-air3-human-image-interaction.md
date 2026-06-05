# Air3 Human-Image Interaction Design

## Goal

Define how the onsite operator, Air3 glasses, uploaded images, backend, and AI interact during the SSH console recovery MVP.

The operator should not need to understand Linux operations. The glasses should guide them one step at a time.

## Core Interaction Loop

```text
show instruction -> capture photo -> upload -> analyze -> show next instruction -> execute/confirm -> capture next photo
```

Each loop asks the operator to do only one thing.

## Roles

### Onsite Operator

The operator:

- reads the instruction on the glasses,
- points their head/glasses at the requested target,
- taps to capture a photo,
- types only the command shown on the glasses,
- captures command output,
- waits for the next instruction.

The operator does not decide diagnostic steps.

### Air3 Glasses App

The glasses app:

- displays current task and step,
- captures the requested photo,
- uploads the photo with `sessionId`, `step`, and `action`,
- shows loading state while the backend analyzes,
- displays the next instruction,
- stores the latest `sessionId`,
- supports retry/re-photo.

### Backend / AI

The backend:

- decides what photo is needed,
- sends the image to AI vision when needed,
- checks photo quality/classification,
- returns safe instructions,
- blocks unsafe command generation,
- verifies final SSH recovery.

## Operator Controls

First version controls:

```text
single tap: capture photo / continue
long press: retake current photo
double tap: request human help
```

Minimum viable version:

```text
single tap: capture and upload
long press: retake
```

## Glasses Screen States

### 1. Task Start

Displayed text:

```text
服务器 SSH 失联恢复
目标：ASSET-CONSOLE-001
请到服务器前，单击拍摄资产标签和屏幕。
```

Action:

```text
single tap -> capture photo
```

### 2. Capture Prompt

Displayed text:

```text
请拍摄本地控制台屏幕
要求：屏幕文字清晰，尽量拍全。
单击拍照，长按重拍。
```

Action:

```text
single tap -> capture and upload
```

### 3. Uploading / Analyzing

Displayed text:

```text
正在上传照片...
正在分析控制台内容...
```

Action:

```text
disable repeated capture until response returns
```

### 4. Need Better Photo

Displayed text:

```text
照片不清晰，无法识别屏幕文字。
请靠近屏幕，避免反光，重新拍摄。
```

Action:

```text
single tap -> recapture
```

### 5. Command Instruction

Displayed text:

```text
请输入：
sudo systemctl status ssh --no-pager

只输入这一条命令。
执行后请单击拍摄完整输出。
```

Action:

```text
operator types command on server keyboard
single tap -> capture command output
```

### 6. Recovery Instruction

Displayed text:

```text
SSH 服务未运行。
请输入：
sudo systemctl start ssh

执行后请拍摄输出。
```

Action:

```text
operator types command
single tap -> capture output
```

### 7. Verification

Displayed text:

```text
正在复测远程 SSH...
请等待。
```

Action:

```text
backend runs remote probe
```

### 8. Completed

Displayed text:

```text
远程 SSH 已恢复。
服务器可重新远程运维。
```

Action:

```text
single tap -> finish session
```

### 9. Human Escalation

Displayed text:

```text
当前状态不适合继续自动指导。
请联系人工运维专家。
```

Action:

```text
stop workflow
```

## Photo Requirements

For console photos:

- screen text must be readable,
- capture the full command output,
- avoid glare,
- keep camera stable for one second before capture.

For server identity photos:

- asset tag must be visible,
- rack label or server label should be included when possible.

## Payload Contract

Request:

```json
{
  "sessionId": "optional-session-id",
  "taskType": "ssh_console_recovery",
  "step": "inspect_console",
  "action": "console_photo_uploaded",
  "imageBase64": "..."
}
```

Response:

```json
{
  "ok": true,
  "sessionId": "session-id",
  "step": "run_diagnostic_command",
  "text": "请输入：sudo systemctl status ssh --no-pager",
  "requiresPhoto": true,
  "canRetake": true,
  "canEscalate": true
}
```

## First Implementation Recommendation

Do not build complex UI first.

Use one screen with:

- large status text,
- instruction text,
- small footer showing tap action.

The existing Camera2 app can evolve into this by adding:

- stored `sessionId`,
- current `step`,
- current `action`,
- loading state,
- retake behavior,
- multi-line response display.

