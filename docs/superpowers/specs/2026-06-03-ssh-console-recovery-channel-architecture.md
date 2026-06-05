# SSH Console Recovery End-to-End Channel Architecture

## Goal

Plan the full channel for the phase-2 demo:

```text
Air3 glasses app -> backend API -> ops orchestrator -> AI vision -> remote probe -> glasses response
```

The demo problem is: **a Linux server is unreachable by SSH, and a non-expert onsite operator uses AI glasses to recover it through the local console**.

## Core Principle

The glasses should not call AI directly.

Use a backend operations orchestrator between the glasses and AI. The orchestrator owns:

- session state,
- allowed workflow steps,
- safe command allowlist,
- remote probes,
- AI prompt context,
- escalation rules,
- final result verification.

This keeps the demo controlled and prevents AI from issuing arbitrary risky commands.

## Applications and Modules

### 1. Air3 Glasses App

Responsibilities:

- capture onsite photos using the existing Camera2 path,
- upload photos to the backend,
- receive and store `sessionId`,
- display the current instruction text,
- expose simple actions:
  - start task,
  - capture/continue,
  - confirm command executed,
  - request human help,
  - finish task,
- send `sessionId`, `step`, and `action` with each upload.

First version UI:

```text
Top: current step/status
Middle: instruction text
Bottom: action hint, such as "tap to capture next photo"
```

Payload example:

```json
{
  "sessionId": "optional-session-id",
  "taskType": "ssh_console_recovery",
  "step": "inspect_console",
  "action": "console_photo_uploaded",
  "imageBase64": "..."
}
```

Response example:

```json
{
  "ok": true,
  "sessionId": "session-id",
  "step": "run_diagnostic_command",
  "text": "请输入：sudo systemctl status ssh --no-pager\n只输入这一条命令，执行后请拍摄完整输出。",
  "requiresPhoto": true,
  "timestamp": "2026-06-03T00:00:00.000Z"
}
```

### 2. Backend API

Responsibilities:

- provide `POST /air3/vision-test` during the MVP,
- later rename to `POST /ops-glasses/tasks/:taskId/events`,
- validate request size and required fields,
- create or load session,
- pass image and action to the orchestrator,
- return short glasses-friendly text.

### 3. Ops Orchestrator

Responsibilities:

- own the state machine,
- decide next step,
- call remote probes,
- call AI vision only when needed,
- enforce command allowlist,
- escalate when evidence is unclear.

State machine:

```text
created
  -> remote_probe_failed
  -> locate_server
  -> inspect_console
  -> run_diagnostic_command
  -> confirm_diagnostic_output
  -> run_recovery_command
  -> verify_remote_access
  -> completed
```

Escalation:

```text
needs_better_photo
needs_human_expert
aborted
```

### 4. AI Vision Layer

Responsibilities:

- read console photos,
- classify what is visible:
  - login screen,
  - shell prompt,
  - command output,
  - SSH service status,
  - network interface state,
  - emergency mode,
  - unreadable photo,
- summarize evidence for the orchestrator.

Important: AI returns classification and evidence, not final dangerous commands.

Example AI output:

```json
{
  "screenType": "command_output",
  "recognizedText": "Active: inactive (dead)",
  "evidence": "SSH service appears stopped",
  "confidence": 0.84,
  "recommendedWorkflowSignal": "ssh_service_stopped"
}
```

### 5. Remote Probe Layer

Responsibilities:

- check whether the target server is reachable,
- run:
  - ping or TCP reachability check,
  - TCP connect to port 22,
  - optional HTTP health check,
- verify final recovery.

Probe result:

```json
{
  "host": "192.168.1.50",
  "pingReachable": true,
  "sshReachable": false,
  "checkedAt": "2026-06-03T00:00:00.000Z"
}
```

### 6. Safety Gateway

Responsibilities:

- keep an allowlist of safe commands,
- block commands outside the allowlist,
- require human escalation for unexpected states.

Initial allowlist:

```text
sudo systemctl status ssh --no-pager
sudo systemctl start ssh
sudo systemctl status sshd --no-pager
sudo systemctl start sshd
ip addr
ip route
```

First demo should use only SSH service restart commands.

## Implementation Phases

### Phase A: Rule-Based Backend Demo

Goal:

- prove the full channel without real AI vision.

Build:

- session state,
- fixed SSH recovery workflow,
- safe command allowlist,
- remote probe stub,
- glasses text response.

Success:

- Air3 uploads a photo,
- backend returns staged instructions,
- session continues using `sessionId`.

### Phase B: Air3 Session App Update

Goal:

- make the glasses app support multi-step tasks.

Build:

- store `sessionId`,
- send `action` and `step`,
- display multi-line instructions,
- add simple continue/capture behavior.

Success:

- one onsite operator can follow three or more steps on the glasses.

### Phase C: Real Remote Probe

Goal:

- make final verification real.

Build:

- ping/TCP probe to target host,
- SSH port 22 check,
- final recovery status.

Success:

- backend reports SSH unavailable before recovery and available after recovery.

### Phase D: AI Vision Console Reading

Goal:

- let AI interpret the console photo.

Build:

- send image and current step to vision model,
- parse structured classification,
- route result through safety gateway.

Success:

- AI can identify whether the console shows login prompt, SSH inactive output, or unclear photo.

### Phase E: Demo Script and Evidence

Goal:

- make the demo persuasive.

Prepare:

- one Linux test server or VM,
- known IP,
- visible console,
- stopped SSH service,
- Air3 app,
- backend logs,
- before/after probe result.

Demo line:

```text
线上 AI 连不上服务器，现场小白看不懂控制台。运维眼镜让 AI 看见本地屏幕并指导小白输入安全命令，最终 SSH 恢复。
```

## Recommended Next Build Step

Start with Phase A and Phase B:

1. Implement backend session workflow.
2. Update Air3 app to preserve `sessionId` and send `action`.
3. Verify the glasses can display staged instructions.

Only after that, add real AI vision.

