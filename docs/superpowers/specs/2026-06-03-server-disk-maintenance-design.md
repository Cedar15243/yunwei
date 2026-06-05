# Server Disk Maintenance Glasses MVP Design

## Goal

Build a second-stage internal operations MVP that proves the value of AI glasses for offline server maintenance.

The chosen scenario is: **server failed-disk location and replacement guidance**.

## Value Thesis

Online AI can detect or explain a server hardware alert, but it cannot enter the server room, identify the exact rack/U position, confirm the asset label, inspect front-panel drive LEDs, or guide a non-expert through a physical replacement.

A junior onsite operator can reach the server, but may not know which server, slot, disk, or action is safe.

The AI glasses bridge that gap:

- The glasses provide AI with live onsite visual evidence.
- The AI guides the onsite operator step by step.
- The backend verifies the outcome using simulated or real RAID/BMC/monitoring status.

## Phase-2 MVP Scope

### In Scope

- Use the existing Air3 image capture and upload path.
- Create one server maintenance session.
- Simulate or ingest one server disk alert.
- Guide the operator through:
  - finding the correct rack/server,
  - confirming the asset label,
  - photographing the server front panel,
  - identifying the failed drive slot,
  - confirming replacement preconditions,
  - confirming post-replacement status.
- Return short, readable steps to the glasses display.
- Store each session step, uploaded image metadata, AI response, and final result.

### Out of Scope

- Production hot-swap on critical servers.
- Automatic changes to RAID/BMC/server configuration.
- Full CMDB integration.
- Full expert dashboard.
- Multi-device generalized repair.
- Voice interaction.
- AR overlay precise spatial anchoring.

## Recommended Demo Setup

Use a test server, lab chassis, or mocked front-panel photo.

Recommended demo alert:

```json
{
  "serverId": "srv-demo-01",
  "assetTag": "ASSET-DEMO-001",
  "rack": "R01",
  "uPosition": "U12",
  "fault": "disk_failed",
  "failedSlot": "3",
  "raidState": "degraded"
}
```

The first implementation may use this as a fixture instead of real BMC data.

## User Flow

1. Backend creates or exposes a demo maintenance task.
2. Operator launches the Air3 app and captures the first onsite photo.
3. Backend starts a session and returns the next instruction.
4. Operator captures the requested photo at each step.
5. AI or rule-based MVP logic checks the current step evidence.
6. Backend returns the next safe operation.
7. Operator confirms the disk has been replaced.
8. Backend verifies the simulated RAID status changed from `degraded` to `rebuilding`.
9. Glasses show a final result: replacement complete, RAID rebuilding, no further onsite action.

## Step State Machine

```text
created
  -> locate_rack
  -> confirm_server_asset
  -> inspect_front_panel
  -> confirm_failed_slot
  -> pre_replacement_check
  -> replace_disk
  -> verify_rebuild
  -> completed
```

Escalation states:

```text
needs_better_photo
needs_human_expert
aborted
```

## AI Response Style

Each glasses response must be short:

- one status line,
- one instruction,
- one safety warning if needed,
- one expected photo/action.

Example:

```text
已确认服务器 ASSET-DEMO-001。
请靠近拍摄硬盘槽位 1-6，重点拍清第 3 槽指示灯。
不要拔出任何硬盘，直到系统明确提示。
```

## Success Criteria

The MVP is successful when:

- the glasses can upload a real onsite photo,
- the backend can associate photos with a maintenance session,
- the backend returns staged instructions instead of fixed text,
- the operator can follow instructions without server expertise,
- the final result shows `RAID rebuilding` or equivalent simulated recovery,
- all steps are logged for review.

## Safety Boundaries

- First validation must use a test server, lab device, or simulated physical workflow.
- The AI must not instruct destructive actions unless the scenario fixture explicitly marks the action safe.
- If the photo is unclear, the system asks for a better photo instead of guessing.
- If the asset tag or slot cannot be confirmed, the system escalates to a human expert.

