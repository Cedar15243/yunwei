# Server SSH Console Recovery Glasses MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a phase-2 MVP where Air3 glasses guide a non-expert onsite operator to recover SSH access on a Linux server through the local console.

**Architecture:** Keep the existing Air3 upload path and replace the fixed reply with a stateful console recovery workflow. Use deterministic safe commands and remote probes first; add real vision model interpretation after the workflow proves useful.

**Tech Stack:** Node.js ESM HTTP server, built-in `node:test`, Windows PowerShell for local verification, existing Air3 Camera2 APK upload path.

---

## File Structure

- Modify: `package.json`
  - Add a `test` script using Node's built-in test runner.
- Modify: `src/server.js`
  - Keep existing endpoints.
  - Delegate `/air3/vision-test` to the console recovery handler.
- Create: `src/maintenance/consoleDemoTarget.js`
  - Contains the demo server target and safe command allowlist.
- Create: `src/maintenance/sessionStore.js`
  - In-memory session store for the MVP.
- Create: `src/maintenance/consoleWorkflow.js`
  - Step state machine and glasses instruction generation.
- Create: `src/maintenance/remoteProbe.js`
  - Ping and TCP port probe helpers.
- Create: `src/maintenance/consoleRecoveryHandler.js`
  - HTTP payload handler for Air3 uploads.
- Create: `test/console-workflow.test.js`
  - Unit tests for state transitions and command safety.
- Create: `test/remote-probe.test.js`
  - Unit tests for probe result interpretation.

## Task 1: Add Console Workflow Tests

**Files:**
- Create: `test/console-workflow.test.js`
- Create: `src/maintenance/consoleDemoTarget.js`
- Create: `src/maintenance/consoleWorkflow.js`
- Modify: `package.json`

- [ ] **Step 1: Add the Node test script**

Modify `package.json` scripts:

```json
{
  "scripts": {
    "start": "node src/server.js",
    "test": "node --test"
  }
}
```

- [ ] **Step 2: Create the failing workflow test**

Create `test/console-workflow.test.js`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import { demoConsoleTarget } from "../src/maintenance/consoleDemoTarget.js";
import {
  advanceConsoleWorkflow,
  createInitialConsoleWorkflowState
} from "../src/maintenance/consoleWorkflow.js";

test("starts by explaining that remote SSH is unreachable", () => {
  const state = createInitialConsoleWorkflowState(demoConsoleTarget, {
    pingReachable: true,
    sshReachable: false
  });

  assert.equal(state.step, "locate_server");
  assert.match(state.text, /SSH/);
  assert.match(state.text, /ASSET-CONSOLE-001/);
});

test("blocks unapproved shell commands", () => {
  const state = {
    target: demoConsoleTarget,
    step: "run_recovery_command",
    text: ""
  };

  const next = advanceConsoleWorkflow(state, {
    action: "operator_requested_command",
    command: "sudo rm -rf /"
  });

  assert.equal(next.step, "needs_human_expert");
  assert.match(next.text, /不在安全命令列表/);
});

test("moves from console photo to diagnostic command", () => {
  const state = createInitialConsoleWorkflowState(demoConsoleTarget, {
    pingReachable: true,
    sshReachable: false
  });

  const next = advanceConsoleWorkflow(state, {
    action: "console_photo_uploaded",
    imageBytes: 500000
  });

  assert.equal(next.step, "run_diagnostic_command");
  assert.match(next.text, /systemctl status/);
});
```

- [ ] **Step 3: Run the test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because `consoleDemoTarget.js` and `consoleWorkflow.js` do not exist.

- [ ] **Step 4: Create the demo target fixture**

Create `src/maintenance/consoleDemoTarget.js`:

```js
export const demoConsoleTarget = {
  serverId: "srv-console-demo-01",
  assetTag: "ASSET-CONSOLE-001",
  host: process.env.CONSOLE_DEMO_HOST || "192.168.1.50",
  sshPort: Number(process.env.CONSOLE_DEMO_SSH_PORT || 22),
  expectedIssue: "ssh_unreachable",
  safeCommands: {
    sshStatus: "sudo systemctl status ssh --no-pager",
    sshStart: "sudo systemctl start ssh",
    sshdStatus: "sudo systemctl status sshd --no-pager",
    sshdStart: "sudo systemctl start sshd"
  }
};

export function isAllowedCommand(target, command) {
  return Object.values(target.safeCommands).includes(command);
}
```

- [ ] **Step 5: Implement the minimal workflow**

Create `src/maintenance/consoleWorkflow.js`:

```js
import { isAllowedCommand } from "./consoleDemoTarget.js";

export function createInitialConsoleWorkflowState(target, probe) {
  return {
    target,
    step: "locate_server",
    text: [
      `服务器 ${target.serverId} 远程 SSH 不可达。`,
      `请到资产标签 ${target.assetTag} 对应的服务器前。`,
      "请拍摄服务器标签和本地控制台屏幕。"
    ].join("\n"),
    probe
  };
}

export function advanceConsoleWorkflow(state, event) {
  if (event.action === "operator_requested_command" && !isAllowedCommand(state.target, event.command)) {
    return {
      ...state,
      step: "needs_human_expert",
      text: `命令不在安全命令列表，禁止执行：${event.command}`
    };
  }

  if (state.step === "locate_server" && event.action === "console_photo_uploaded") {
    return {
      ...state,
      step: "run_diagnostic_command",
      text: [
        "已收到本地控制台照片。",
        `请输入：${state.target.safeCommands.sshStatus}`,
        "只输入这一条命令，执行后请拍摄完整输出。"
      ].join("\n")
    };
  }

  return {
    ...state,
    step: "needs_better_photo",
    text: "当前照片或步骤信息不足，请重新拍摄清晰的本地控制台。"
  };
}
```

- [ ] **Step 6: Run tests and confirm they pass**

Run:

```powershell
npm test
```

Expected: PASS for workflow tests.

## Task 2: Add Remote Probe Interpretation

**Files:**
- Create: `src/maintenance/remoteProbe.js`
- Create: `test/remote-probe.test.js`

- [ ] **Step 1: Create the failing remote probe test**

Create `test/remote-probe.test.js`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import { classifyProbeResult } from "../src/maintenance/remoteProbe.js";

test("classifies ping reachable but ssh closed as ssh service issue", () => {
  const result = classifyProbeResult({
    pingReachable: true,
    sshReachable: false
  });

  assert.equal(result.issue, "ssh_service_unreachable");
  assert.match(result.summary, /SSH/);
});

test("classifies ping unreachable as network or host issue", () => {
  const result = classifyProbeResult({
    pingReachable: false,
    sshReachable: false
  });

  assert.equal(result.issue, "host_or_network_unreachable");
  assert.match(result.summary, /网络/);
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because `remoteProbe.js` does not exist.

- [ ] **Step 3: Implement probe classification**

Create `src/maintenance/remoteProbe.js`:

```js
export function classifyProbeResult(probe) {
  if (probe.pingReachable && !probe.sshReachable) {
    return {
      issue: "ssh_service_unreachable",
      summary: "服务器网络可达，但 SSH 端口不可达，优先检查 SSH 服务状态。"
    };
  }

  if (!probe.pingReachable) {
    return {
      issue: "host_or_network_unreachable",
      summary: "服务器网络不可达，需要现场确认电源、网线、本地控制台和网卡状态。"
    };
  }

  return {
    issue: "remote_access_healthy",
    summary: "远程网络和 SSH 均可达。"
  };
}

export async function probeTarget(target) {
  return {
    host: target.host,
    sshPort: target.sshPort,
    pingReachable: false,
    sshReachable: false,
    checkedAt: new Date().toISOString()
  };
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
npm test
```

Expected: PASS.

## Task 3: Add Session Store

**Files:**
- Create: `src/maintenance/sessionStore.js`
- Create: `test/session-store.test.js`

- [ ] **Step 1: Create the failing session store test**

Create `test/session-store.test.js`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import { createSessionStore } from "../src/maintenance/sessionStore.js";

test("creates a session and records console recovery events", () => {
  const store = createSessionStore();
  const session = store.create({ serverId: "srv-console-demo-01" });

  store.appendEvent(session.id, {
    type: "console_photo_uploaded",
    step: "run_diagnostic_command",
    imageBytes: 500000
  });

  const loaded = store.get(session.id);
  assert.equal(loaded.id, session.id);
  assert.equal(loaded.events.length, 1);
  assert.equal(loaded.events[0].step, "run_diagnostic_command");
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because `sessionStore.js` does not exist.

- [ ] **Step 3: Implement the session store**

Create `src/maintenance/sessionStore.js`:

```js
import crypto from "node:crypto";

export function createSessionStore() {
  const sessions = new Map();

  return {
    create(metadata = {}) {
      const now = new Date().toISOString();
      const session = {
        id: crypto.randomUUID(),
        metadata,
        createdAt: now,
        updatedAt: now,
        events: []
      };
      sessions.set(session.id, session);
      return session;
    },

    get(id) {
      const session = sessions.get(id);
      if (!session) {
        throw new Error(`session_not_found:${id}`);
      }
      return session;
    },

    appendEvent(id, event) {
      const session = this.get(id);
      session.events.push({
        ...event,
        timestamp: new Date().toISOString()
      });
      session.updatedAt = new Date().toISOString();
      return session;
    }
  };
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
npm test
```

Expected: PASS.

## Task 4: Route Air3 Uploads Into Console Recovery

**Files:**
- Create: `src/maintenance/consoleRecoveryHandler.js`
- Modify: `src/server.js`

- [ ] **Step 1: Create the console recovery handler**

Create `src/maintenance/consoleRecoveryHandler.js`:

```js
import { demoConsoleTarget } from "./consoleDemoTarget.js";
import {
  advanceConsoleWorkflow,
  createInitialConsoleWorkflowState
} from "./consoleWorkflow.js";
import { classifyProbeResult, probeTarget } from "./remoteProbe.js";

export function createConsoleRecoveryHandler({ sessionStore }) {
  return async function handleConsoleRecovery(payload) {
    const imageBase64 = typeof payload.imageBase64 === "string" ? payload.imageBase64 : "";
    const imageBytes = estimateBase64Bytes(imageBase64);
    const sessionId = typeof payload.sessionId === "string" ? payload.sessionId : "";

    if (!sessionId) {
      const probe = await probeTarget(demoConsoleTarget);
      const classification = classifyProbeResult(probe);
      const session = sessionStore.create({
        serverId: demoConsoleTarget.serverId,
        probe,
        issue: classification.issue
      });
      const workflow = createInitialConsoleWorkflowState(demoConsoleTarget, probe);
      sessionStore.appendEvent(session.id, {
        type: "session_started",
        step: workflow.step,
        imageBytes
      });
      return buildResponse(session.id, workflow, imageBytes, classification.summary);
    }

    const session = sessionStore.get(sessionId);
    const previousStep = session.events.at(-1)?.step || "locate_server";
    const workflow = advanceConsoleWorkflow(
      { target: demoConsoleTarget, step: previousStep, text: "" },
      { action: "console_photo_uploaded", imageBytes }
    );
    sessionStore.appendEvent(session.id, {
      type: "console_photo_uploaded",
      step: workflow.step,
      imageBytes
    });
    return buildResponse(session.id, workflow, imageBytes, "");
  };
}

function buildResponse(sessionId, workflow, imageBytes, summary) {
  return {
    ok: true,
    sessionId,
    step: workflow.step,
    text: summary ? `${summary}\n${workflow.text}` : workflow.text,
    imageBytes,
    timestamp: new Date().toISOString()
  };
}

function estimateBase64Bytes(value) {
  const commaIndex = value.indexOf(",");
  const raw = commaIndex === -1 ? value : value.slice(commaIndex + 1);
  const normalized = raw.replace(/\s/g, "");
  if (!normalized) {
    return 0;
  }
  const padding = normalized.endsWith("==") ? 2 : normalized.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((normalized.length * 3) / 4) - padding);
}
```

- [ ] **Step 2: Wire the handler in `src/server.js`**

Add imports near the top:

```js
import { createConsoleRecoveryHandler } from "./maintenance/consoleRecoveryHandler.js";
import { createSessionStore } from "./maintenance/sessionStore.js";
```

Add after `config`:

```js
const sessionStore = createSessionStore();
const handleConsoleRecovery = createConsoleRecoveryHandler({ sessionStore });
```

Replace `handleAir3VisionTest`:

```js
async function handleAir3VisionTest(payload, res) {
  sendJson(res, 200, await handleConsoleRecovery(payload));
}
```

Ensure the caller still uses:

```js
return handleAir3VisionTest(payload, res);
```

- [ ] **Step 3: Verify syntax**

Run:

```powershell
node --check src/server.js
```

Expected: no output and exit code 0.

- [ ] **Step 4: Verify endpoint response**

Start server:

```powershell
npm start
```

In another terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8787/air3/vision-test `
  -Method POST `
  -ContentType "application/json; charset=utf-8" `
  -Body '{"imageBase64":"dGVzdA=="}'
```

Expected response includes:

```json
{
  "ok": true,
  "step": "locate_server",
  "text": "服务器 srv-console-demo-01 远程 SSH 不可达。"
}
```

## Task 5: Add Full Demo Workflow Steps

**Files:**
- Modify: `src/maintenance/consoleWorkflow.js`
- Modify: `test/console-workflow.test.js`

- [ ] **Step 1: Add a full-path workflow test**

Append to `test/console-workflow.test.js`:

```js
test("reaches remote verification through the safe recovery path", () => {
  let state = createInitialConsoleWorkflowState(demoConsoleTarget, {
    pingReachable: true,
    sshReachable: false
  });

  state = advanceConsoleWorkflow(state, {
    action: "console_photo_uploaded",
    imageBytes: 500000
  });
  state = advanceConsoleWorkflow(state, {
    action: "diagnostic_output_uploaded",
    imageBytes: 500000
  });
  state = advanceConsoleWorkflow(state, {
    action: "recovery_command_ready",
    command: demoConsoleTarget.safeCommands.sshStart
  });
  state = advanceConsoleWorkflow(state, {
    action: "recovery_output_uploaded",
    imageBytes: 500000
  });

  assert.equal(state.step, "verify_remote_access");
  assert.match(state.text, /复测/);
});
```

- [ ] **Step 2: Run test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because remaining transitions do not exist.

- [ ] **Step 3: Implement the full deterministic workflow**

Replace `advanceConsoleWorkflow` in `src/maintenance/consoleWorkflow.js` with:

```js
export function advanceConsoleWorkflow(state, event) {
  if (event.command && !isAllowedCommand(state.target, event.command)) {
    return {
      ...state,
      step: "needs_human_expert",
      text: `命令不在安全命令列表，禁止执行：${event.command}`
    };
  }

  const transitions = {
    locate_server: {
      action: "console_photo_uploaded",
      next: "run_diagnostic_command",
      text: [
        "已收到本地控制台照片。",
        `请输入：${state.target.safeCommands.sshStatus}`,
        "只输入这一条命令，执行后请拍摄完整输出。"
      ].join("\n")
    },
    run_diagnostic_command: {
      action: "diagnostic_output_uploaded",
      next: "confirm_diagnostic_output",
      text: [
        "已记录 SSH 状态输出。",
        "如果输出显示 inactive、failed 或 stopped，请准备执行恢复命令。",
        "请确认你仍在目标服务器本地控制台。"
      ].join("\n")
    },
    confirm_diagnostic_output: {
      action: "recovery_command_ready",
      next: "run_recovery_command",
      text: [
        `请输入：${state.target.safeCommands.sshStart}`,
        "只允许输入这一条恢复命令。",
        "执行后请拍摄命令输出。"
      ].join("\n")
    },
    run_recovery_command: {
      action: "recovery_output_uploaded",
      next: "verify_remote_access",
      text: [
        "已收到恢复命令输出。",
        "后台将复测 ping 和 SSH 端口。",
        "请等待结果，不要继续输入其他命令。"
      ].join("\n")
    }
  };

  const transition = transitions[state.step];
  if (!transition || transition.action !== event.action) {
    return {
      ...state,
      step: "needs_better_photo",
      text: "当前照片或步骤信息不足，请重新拍摄清晰的本地控制台。"
    };
  }

  return {
    ...state,
    step: transition.next,
    text: transition.text
  };
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
npm test
```

Expected: PASS.

## Task 6: Air3 Real-Device Verification

**Files:**
- Use existing APK: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Use existing server: `src/server.js`

- [ ] **Step 1: Prepare the demo server**

On a Linux test server:

```bash
sudo systemctl stop ssh
```

or, if the service is named `sshd`:

```bash
sudo systemctl stop sshd
```

- [ ] **Step 2: Start local backend**

Run:

```powershell
npm start
```

Expected:

```text
Feishu bridge listening on http://127.0.0.1:8787
```

- [ ] **Step 3: Configure ADB reverse**

Run:

```powershell
tmp\tools\platform-tools\adb.exe reverse tcp:8787 tcp:8787
```

Expected:

```text
8787
```

- [ ] **Step 4: Launch the Air3 app and capture proof**

Expected on glasses:

```text
服务器 srv-console-demo-01 远程 SSH 不可达。
请到资产标签 ASSET-CONSOLE-001 对应的服务器前。
```

Collect:

- one glasses screenshot,
- backend response JSON with `sessionId` and `step`,
- server log showing `imageBytes`,
- final remote probe result showing SSH restored.

## Self-Review

- Spec coverage: the plan covers remote unreachable detection, console photo upload, safe command guidance, session logging, endpoint routing, and Air3 verification.
- Placeholder scan: no open-ended implementation placeholders remain.
- Scope check: the plan avoids production recovery and uses safe allowlisted commands.
- Risk: the current Air3 APK may need a small client update to preserve and resend `sessionId`; if it does not, the first demo can still show the initial instruction, but multi-step continuation requires client payload support.

