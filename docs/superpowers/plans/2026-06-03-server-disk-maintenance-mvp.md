# Server Disk Maintenance Glasses MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a phase-2 MVP where Air3 glasses guide a non-expert through a server failed-disk location and replacement workflow.

**Architecture:** Keep the existing Air3 upload path, but route it into a stateful maintenance session instead of returning fixed text. Use a demo RAID/BMC fixture first, store session steps locally, and return short instructions to the glasses.

**Tech Stack:** Node.js ESM HTTP server, built-in `node:test`, JSON file fixtures, existing Air3 Camera2 APK upload path.

---

## File Structure

- Modify: `package.json`
  - Add a `test` script using Node's built-in test runner.
- Modify: `src/server.js`
  - Keep existing endpoints.
  - Delegate the Air3 maintenance request to focused modules.
- Create: `src/maintenance/demoAlert.js`
  - Contains the fixed demo server disk fault.
- Create: `src/maintenance/sessionStore.js`
  - In-memory session store for the MVP.
- Create: `src/maintenance/workflow.js`
  - Step state machine and instruction generation.
- Create: `src/maintenance/handler.js`
  - HTTP payload handling for `/air3/vision-test`.
- Create: `test/maintenance-workflow.test.js`
  - Unit tests for state transitions and safety behavior.
- Create: `test/session-store.test.js`
  - Unit tests for session creation and step logging.

## Task 1: Add Maintenance Workflow Tests

**Files:**
- Create: `test/maintenance-workflow.test.js`
- Create: `src/maintenance/demoAlert.js`
- Create: `src/maintenance/workflow.js`
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

Create `test/maintenance-workflow.test.js`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import { demoDiskAlert } from "../src/maintenance/demoAlert.js";
import { advanceWorkflow, createInitialWorkflowState } from "../src/maintenance/workflow.js";

test("starts by asking the onsite operator to locate the rack", () => {
  const state = createInitialWorkflowState(demoDiskAlert);

  assert.equal(state.step, "locate_rack");
  assert.match(state.text, /R01/);
  assert.match(state.text, /U12/);
});

test("does not allow disk replacement before slot confirmation", () => {
  const state = createInitialWorkflowState(demoDiskAlert);
  const next = advanceWorkflow(state, {
    action: "operator_claimed_replaced_disk",
    imageBytes: 640000
  });

  assert.equal(next.step, "needs_human_expert");
  assert.match(next.text, /不能确认/);
});

test("moves from rack location to server asset confirmation when a photo is received", () => {
  const state = createInitialWorkflowState(demoDiskAlert);
  const next = advanceWorkflow(state, {
    action: "photo_uploaded",
    imageBytes: 640000
  });

  assert.equal(next.step, "confirm_server_asset");
  assert.match(next.text, /ASSET-DEMO-001/);
});
```

- [ ] **Step 3: Run the test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because `src/maintenance/demoAlert.js` and `src/maintenance/workflow.js` do not exist.

- [ ] **Step 4: Create the demo alert fixture**

Create `src/maintenance/demoAlert.js`:

```js
export const demoDiskAlert = {
  serverId: "srv-demo-01",
  assetTag: "ASSET-DEMO-001",
  rack: "R01",
  uPosition: "U12",
  fault: "disk_failed",
  failedSlot: "3",
  raidState: "degraded",
  replacementSafe: true
};
```

- [ ] **Step 5: Implement the minimal workflow**

Create `src/maintenance/workflow.js`:

```js
export function createInitialWorkflowState(alert) {
  return {
    alert,
    step: "locate_rack",
    text: [
      `服务器 ${alert.serverId} 检测到硬盘故障。`,
      `请到机柜 ${alert.rack}，位置 ${alert.uPosition}。`,
      "到达后请拍摄机柜编号和服务器正面。"
    ].join("\n")
  };
}

export function advanceWorkflow(state, event) {
  if (event.action === "operator_claimed_replaced_disk" && state.step !== "replace_disk") {
    return {
      ...state,
      step: "needs_human_expert",
      text: "不能确认故障硬盘槽位，禁止更换硬盘。请联系人工专家。"
    };
  }

  if (state.step === "locate_rack" && event.action === "photo_uploaded") {
    return {
      ...state,
      step: "confirm_server_asset",
      text: [
        `请确认服务器资产标签是否为 ${state.alert.assetTag}。`,
        "请靠近拍摄资产标签和服务器前面板。",
        "不要拔出任何硬盘。"
      ].join("\n")
    };
  }

  return {
    ...state,
    step: "needs_better_photo",
    text: "当前信息不足，请重新拍摄更清晰的照片。"
  };
}
```

- [ ] **Step 6: Run tests and confirm they pass**

Run:

```powershell
npm test
```

Expected: PASS for all workflow tests.

## Task 2: Add Session Store

**Files:**
- Create: `src/maintenance/sessionStore.js`
- Create: `test/session-store.test.js`

- [ ] **Step 1: Create the failing session store test**

Create `test/session-store.test.js`:

```js
import assert from "node:assert/strict";
import test from "node:test";
import { createSessionStore } from "../src/maintenance/sessionStore.js";

test("creates a session and records events", () => {
  const store = createSessionStore();
  const session = store.create({ serverId: "srv-demo-01" });

  store.appendEvent(session.id, {
    type: "photo_uploaded",
    imageBytes: 640000
  });

  const loaded = store.get(session.id);
  assert.equal(loaded.id, session.id);
  assert.equal(loaded.events.length, 1);
  assert.equal(loaded.events[0].imageBytes, 640000);
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

- [ ] **Step 4: Run tests and confirm they pass**

Run:

```powershell
npm test
```

Expected: PASS for workflow and session store tests.

## Task 3: Route `/air3/vision-test` Into the Maintenance Handler

**Files:**
- Create: `src/maintenance/handler.js`
- Modify: `src/server.js`
- Test: manual HTTP request

- [ ] **Step 1: Create the handler**

Create `src/maintenance/handler.js`:

```js
import { demoDiskAlert } from "./demoAlert.js";
import { advanceWorkflow, createInitialWorkflowState } from "./workflow.js";

export function createMaintenanceHandler({ sessionStore }) {
  return function handleMaintenanceVision(payload) {
    const imageBase64 = typeof payload.imageBase64 === "string" ? payload.imageBase64 : "";
    const imageBytes = estimateBase64Bytes(imageBase64);
    const sessionId = typeof payload.sessionId === "string" ? payload.sessionId : "";

    if (!sessionId) {
      const session = sessionStore.create({ serverId: demoDiskAlert.serverId });
      const workflow = createInitialWorkflowState(demoDiskAlert);
      sessionStore.appendEvent(session.id, {
        type: "session_started",
        step: workflow.step,
        imageBytes
      });
      return buildResponse(session.id, workflow, imageBytes);
    }

    const session = sessionStore.get(sessionId);
    const previousStep = session.events.at(-1)?.step || "locate_rack";
    const workflow = advanceWorkflow(
      { alert: demoDiskAlert, step: previousStep, text: "" },
      { action: "photo_uploaded", imageBytes }
    );
    sessionStore.appendEvent(session.id, {
      type: "photo_uploaded",
      step: workflow.step,
      imageBytes
    });
    return buildResponse(session.id, workflow, imageBytes);
  };
}

function buildResponse(sessionId, workflow, imageBytes) {
  return {
    ok: true,
    sessionId,
    step: workflow.step,
    text: workflow.text,
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
import { createMaintenanceHandler } from "./maintenance/handler.js";
import { createSessionStore } from "./maintenance/sessionStore.js";
```

Add after `config`:

```js
const sessionStore = createSessionStore();
const handleMaintenanceVision = createMaintenanceHandler({ sessionStore });
```

Replace the body of `handleAir3VisionTest`:

```js
function handleAir3VisionTest(payload, res) {
  sendJson(res, 200, handleMaintenanceVision(payload));
}
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
  "step": "locate_rack",
  "text": "服务器 srv-demo-01 检测到硬盘故障。"
}
```

## Task 4: Extend the Workflow to a Full Demo Path

**Files:**
- Modify: `src/maintenance/workflow.js`
- Modify: `test/maintenance-workflow.test.js`

- [ ] **Step 1: Add tests for the remaining steps**

Append to `test/maintenance-workflow.test.js`:

```js
test("reaches replacement verification through the safe demo path", () => {
  let state = createInitialWorkflowState(demoDiskAlert);
  state = advanceWorkflow(state, { action: "photo_uploaded", imageBytes: 640000 });
  state = advanceWorkflow(state, { action: "asset_confirmed", imageBytes: 640000 });
  state = advanceWorkflow(state, { action: "front_panel_uploaded", imageBytes: 640000 });
  state = advanceWorkflow(state, { action: "failed_slot_confirmed", imageBytes: 640000 });
  state = advanceWorkflow(state, { action: "replacement_ready", imageBytes: 640000 });
  state = advanceWorkflow(state, { action: "operator_claimed_replaced_disk", imageBytes: 640000 });

  assert.equal(state.step, "verify_rebuild");
  assert.match(state.text, /RAID/);
});
```

- [ ] **Step 2: Run test and confirm it fails**

Run:

```powershell
npm test
```

Expected: FAIL because only the first transition exists.

- [ ] **Step 3: Implement deterministic transitions**

Replace `advanceWorkflow` in `src/maintenance/workflow.js` with:

```js
export function advanceWorkflow(state, event) {
  if (event.action === "operator_claimed_replaced_disk" && state.step !== "replace_disk") {
    return expertEscalation(state);
  }

  const transitions = {
    locate_rack: {
      action: "photo_uploaded",
      next: "confirm_server_asset",
      text: [
        `请确认服务器资产标签是否为 ${state.alert.assetTag}。`,
        "请靠近拍摄资产标签和服务器前面板。",
        "不要拔出任何硬盘。"
      ].join("\n")
    },
    confirm_server_asset: {
      action: "asset_confirmed",
      next: "inspect_front_panel",
      text: [
        "已确认服务器资产标签。",
        "请拍摄服务器正面全部硬盘槽位。",
        `重点拍清第 ${state.alert.failedSlot} 槽附近的指示灯。`
      ].join("\n")
    },
    inspect_front_panel: {
      action: "front_panel_uploaded",
      next: "confirm_failed_slot",
      text: [
        `请确认故障硬盘槽位为第 ${state.alert.failedSlot} 槽。`,
        "请拍摄该槽位近景，确保槽位编号和故障灯清晰。",
        "确认前不要拔盘。"
      ].join("\n")
    },
    confirm_failed_slot: {
      action: "failed_slot_confirmed",
      next: "pre_replacement_check",
      text: [
        "已确认故障槽位。",
        "请确认备件硬盘型号/容量与原硬盘一致或符合替换要求。",
        "请拍摄备件标签。"
      ].join("\n")
    },
    pre_replacement_check: {
      action: "replacement_ready",
      next: "replace_disk",
      text: [
        `可以更换第 ${state.alert.failedSlot} 槽硬盘。`,
        "请先解锁硬盘托架，缓慢拔出故障盘，再插入新盘直到卡扣锁定。",
        "只允许操作已确认的槽位。"
      ].join("\n")
    },
    replace_disk: {
      action: "operator_claimed_replaced_disk",
      next: "verify_rebuild",
      text: [
        "已记录更换动作。",
        "系统正在验证 RAID 状态，预期进入 rebuilding。",
        "请拍摄更换后硬盘槽位状态。"
      ].join("\n")
    }
  };

  const transition = transitions[state.step];
  if (!transition || transition.action !== event.action) {
    return {
      ...state,
      step: "needs_better_photo",
      text: "当前证据不足或步骤不匹配，请重新拍摄更清晰的照片。"
    };
  }

  return {
    ...state,
    step: transition.next,
    text: transition.text
  };
}

function expertEscalation(state) {
  return {
    ...state,
    step: "needs_human_expert",
    text: "不能确认故障硬盘槽位，禁止更换硬盘。请联系人工专家。"
  };
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
npm test
```

Expected: PASS.

## Task 5: Air3 Real-Device Verification

**Files:**
- Use existing APK: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- Use existing server: `src/server.js`

- [ ] **Step 1: Start local server**

Run:

```powershell
npm start
```

Expected:

```text
Feishu bridge listening on http://127.0.0.1:8787
```

- [ ] **Step 2: Configure ADB reverse**

Run:

```powershell
tmp\tools\platform-tools\adb.exe reverse tcp:8787 tcp:8787
```

Expected:

```text
8787
```

- [ ] **Step 3: Launch the Air3 app**

Run the existing install/launch process used in the phase-1 validation.

Expected on glasses:

```text
服务器 srv-demo-01 检测到硬盘故障。
请到机柜 R01，位置 U12。
```

- [ ] **Step 4: Capture proof**

Collect:

- one glasses screenshot,
- server log showing `imageBytes`,
- response JSON showing `sessionId` and `step`.

## Self-Review

- Spec coverage: the plan covers the demo alert, workflow, session logging, endpoint routing, and Air3 verification.
- Placeholder scan: no `TBD`, `TODO`, or open-ended implementation placeholders remain.
- Scope check: the plan avoids real BMC integration and production hot-swap in this phase.
- Risk: the current Air3 APK may need a small client update to preserve and resend `sessionId`; if it does not, the MVP can still show the first-step instruction, but multi-step continuation requires client payload support.

