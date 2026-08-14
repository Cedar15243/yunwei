# V9 ExecutionContext Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the authoritative V9 execution service core that resolves project/task identity, immutable Skill snapshots, project memory and confirmed project instructions before every AI call, with no client-side fake activation.

**Architecture:** Add a focused `execution_context.py` module beside the existing V9 gateway. It owns the SQLite schema and deterministic context assembly while reusing the gateway connection, lock and short device session identity. The gateway will require a real project/task binding for V9 AI requests, resolve the active Skill snapshot and memory on the server, send bounded structured context to the previous stable model, and audit the exact context revision used by every trace.

**Tech Stack:** Python 3 standard library, SQLite WAL, existing `v9-ops-gateway` HTTP/SSE server, `unittest`, DashScope OpenAI-compatible streaming API.

---

### Task 1: Execution Context Data Model And Repository

**Files:**
- Create: `v9-ops-gateway/execution_context.py`
- Create: `v9-ops-gateway/test_execution_context.py`

- [x] **Step 1: Write failing repository tests**

Add tests that create the existing project/task rows, publish one immutable Skill version, assign it to the current device, activate it for the task, write revisioned project memory, confirm one project instruction, and assert the assembled context contains only the matching project data. Add negative tests for cross-device projects, unpublished Skills, expired assignments, version conflicts, unknown fields and cross-project instruction leakage.

```python
context = repository.build_context(
    organization_id="org-huafang",
    user_id="user-field-engineer",
    device_id="air3-YM00FCF3NW0031",
    local_project_id="project-local-a",
    local_task_id="task-local-a",
    user_text="冷机控制器仍然报警",
    recent_messages=[{"role": "user", "content": "上一轮"}],
)
self.assertEqual("skill-hvac@1.0.0", context.skill.version_id)
self.assertEqual(3, context.memory_revision)
self.assertEqual([2], [item.version for item in context.instructions])
```

- [x] **Step 2: Run the tests and observe the missing-module failure**

Run:

```powershell
python -m unittest -v v9-ops-gateway/test_execution_context.py
```

Expected: fail because `execution_context` and its repository do not exist.

- [x] **Step 3: Implement the minimal repository**

Create immutable dataclasses for `SkillSnapshot`, `ProjectInstructionSnapshot` and `ExecutionContext`. Create `ExecutionContextRepository` with schema for published Skill versions, assignments, task Skill snapshots, project memory, versioned project instructions and execution context audits. Store canonical JSON plus SHA-256 for immutable records; reject unknown keys, invalid state transitions, stale memory revisions and cross-identity references.

```python
@dataclass(frozen=True)
class ExecutionContext:
    organization_id: str
    user_id: str
    device_id: str
    project_id: str
    task_id: str
    local_project_id: str
    local_task_id: str
    skill: Optional[SkillSnapshot]
    project_summary: str
    confirmed_facts: tuple[str, ...]
    excluded_facts: tuple[str, ...]
    risks: tuple[str, ...]
    memory_revision: int
    instructions: tuple[ProjectInstructionSnapshot, ...]
    recent_messages: tuple[dict[str, str], ...]
    user_text: str
```

- [x] **Step 4: Run focused tests until green**

Run the command from Step 2. Expected: all context repository tests pass.

### Task 2: Managed Identity And Strict AI Context Resolution

**Files:**
- Modify: `v9-ops-gateway/gateway.py`
- Modify: `v9-ops-gateway/test_gateway.py`
- Modify: `v9-ops-gateway/test_voiceprint_gateway.py`
- Modify: `v9-ops-gateway/deploy/dingdang-v9-gateway.env.example`
- Modify: `v9-ops-gateway/test_deployment.py`

- [x] **Step 1: Write failing gateway tests**

Require `V9_ORGANIZATION_ID` and `V9_USER_ID`, reject AI requests without `localProjectId` and `localTaskId`, reject unknown or cross-device tasks, and prove the provider receives bounded structured context rather than the raw user prompt. Assert that an active Skill snapshot changes the provider context and that missing/expired authorization explicitly returns `skill_not_authorized`.

```python
response = service.diagnose(
    "Bearer " + token,
    "session-a",
    {
        "finalText": "检查冷机报警",
        "localProjectId": "project-local-a",
        "localTaskId": "task-local-a",
    },
)
self.assertEqual(200, response.status)
self.assertEqual("skill-hvac@1.0.0", provider.context.skill.version_id)
```

- [x] **Step 2: Run focused gateway tests and confirm red**

Run:

```powershell
python -m unittest -v v9-ops-gateway/test_gateway.py v9-ops-gateway/test_voiceprint_gateway.py v9-ops-gateway/test_deployment.py
```

Expected: failures for missing identity fields and raw prompt routing.

- [x] **Step 3: Integrate the repository and provider contract**

Extend `GatewayConfig` with organization/user identity. Initialize `ExecutionContextRepository` from `SqliteStore`. Resolve project/task/Skill/memory before appending the user message or starting the provider request. Serialize the context as bounded canonical JSON fields under a dedicated user content block; keep the platform system prompt separate. Record success/failure and the exact Skill digest, memory revision and instruction versions by trace ID.

- [x] **Step 4: Run focused gateway tests until green**

Run the command from Step 2. Expected: all focused tests pass.

### Task 3: Device Skill And Project Memory APIs

**Files:**
- Modify: `v9-ops-gateway/gateway.py`
- Modify: `v9-ops-gateway/test_gateway.py`
- Modify: `v9-ops-gateway/README.md`

- [x] **Step 1: Write failing HTTP contract tests**

Cover authorized Skill list, task Skill activation/deactivation, project list/detail, task end summary, project instruction confirmation and explicit rejection of missing authorization, stale revisions, closed-task mutation and cross-device references.

- [x] **Step 2: Run tests and observe 404/contract failures**

Run `python -m unittest -v v9-ops-gateway/test_gateway.py`.

- [x] **Step 3: Add strict device routes**

Add only white-listed routes under `/device-sync/skills`, `/device-sync/projects`, `/device-sync/tasks/{id}/skill`, `/device-sync/tasks/{id}/end-summary` and `/device-sync/projects/{id}/instructions`. Mutations require stable idempotency keys; task completion/closure and instruction confirmation require explicit confirmation text. Return `Cache-Control: no-store` for identity, Skill, memory and summary responses.

- [x] **Step 4: Run the full gateway suite**

Run `python -m unittest discover -s v9-ops-gateway -p 'test_*.py' -v`. Expected: zero failures.

### Task 4: Deployment And Performance Gate

**Files:**
- Modify: `v9-ops-gateway/deploy/install.sh`
- Modify: `v9-ops-gateway/test_deployment.py`
- Create: `docs/verification/2026-08-03-v9-execution-context-runtime.md`

- [x] **Step 1: Add failing deployment and latency tests**

Require root-only identity/runtime environment, pre-deploy database backup, health rollback and a context-assembly benchmark covering 1000 messages, 100 facts and 50 instructions. Context assembly must remain below 50 ms p95 locally and add no extra model network round trip.

- [x] **Step 2: Implement deployment guards and benchmark**

Update the installer to verify both environment files, snapshot the SQLite databases before restart, and restore the prior release/database on failed health. Document that database reads occur in one local transaction before the existing single provider request.

- [ ] **Step 3: Run full verification**

Run gateway tests, Python compilation, deployment contract tests, public health checks, server file-permission checks and a real short-session AI request. Verify the existing expert service remains HTTP 200.

- [x] **Step 4: Update project memory**

Update `agent_memory/context.md`, `agent_memory/progress.md` and `agent_memory/bugs.md` with the exact deployed release, test counts, latency evidence and remaining Android/Web integration work.
