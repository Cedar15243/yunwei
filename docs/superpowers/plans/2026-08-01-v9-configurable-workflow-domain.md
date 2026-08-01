# V9 Configurable Workflow Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可供云端发布、工单绑定、管理网页和 Android 执行器共同依赖的受控工作流领域合同与数据库基础。

**Architecture:** 纯 TypeScript 领域模块负责工作流草稿的类型、节点白名单、图校验、规范化编译和确定性工单绑定，不依赖 Supabase 或 HTTP。Postgres 迁移保存现场应用、不可变版本、绑定规则、工单、分配和执行状态，并使用组织 RLS、部分唯一索引、不可变触发器和服务角色 RPC 保证边界。

**Tech Stack:** Deno TypeScript、Deno test、Supabase Postgres/RLS/RPC、Node schema contract validators

**Scope Lock:** 本计划不实现管理网页、Android 页面、实时推送传输、MVS DTO 或 AI SkillRuntime；不修改现有专家协同、HUD、Camera2、语音和稳定 APK。

---

### Task 1: 工作流 DSL 节点与字段校验

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-domain.test.ts`
- Create: `supabase/functions/ops-glasses/workflow-domain.ts`

- [ ] **Step 1: 写节点白名单和秘密/任意网络拒绝的失败测试**

```ts
Deno.test("accepts the production workflow node catalog", () => {
  const result = validateWorkflowDraft(validDraft());
  assertEquals(result.errors, []);
});

Deno.test("rejects arbitrary http script and secret-shaped fields", () => {
  const result = validateWorkflowDraft({
    ...validDraft(),
    nodes: [{ nodeId: "unsafe", type: "http", config: { url: "https://example.test", token: "secret" } }],
  });
  assertEquals(result.errors.map((item) => item.code), ["unsupported_node_type", "forbidden_config_key"]);
});
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-domain.test.ts`

Expected: FAIL，原因是 `workflow-domain.ts` 或 `validateWorkflowDraft` 不存在。

- [ ] **Step 3: 实现最小类型与递归配置校验**

```ts
export const WORKFLOW_NODE_TYPES = [
  "start", "instruction", "choice", "form", "photo_capture", "video_capture",
  "voice_input", "ai_assist", "expert_call", "confirmation", "condition",
  "repeat_group", "subflow", "connector_action", "complete",
] as const;

const forbiddenConfigKey = /^(url|uri|script|javascript|password|passwd|token|api_?key|api_?secret|authorization)$/i;

export function validateWorkflowDraft(value: unknown): WorkflowValidationResult {
  const errors: WorkflowValidationError[] = [];
  if (!isRecord(value)) return { errors: [{ code: "invalid_draft", path: "$" }] };
  if (typeof value.workflowId !== "string" || value.workflowId.trim() === "") {
    errors.push({ code: "workflow_id_required", path: "$.workflowId" });
  }
  const nodes = Array.isArray(value.nodes) ? value.nodes : [];
  nodes.forEach((node, index) => validateNode(node, `$.nodes[${index}]`, errors));
  return { errors };
}
```

校验必须产生稳定错误码和可定位 `path`，不把秘密值写入错误信息。

- [ ] **Step 4: 运行目标测试确认 GREEN**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-domain.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交任务**

```powershell
git add supabase/functions/ops-glasses/workflow-domain.ts supabase/functions/ops-glasses/workflow-domain.test.ts
git commit -m "feat: validate configurable workflow drafts"
```

### Task 2: 图结构校验与规范化执行包编译

**Files:**
- Modify: `supabase/functions/ops-glasses/workflow-domain.test.ts`
- Modify: `supabase/functions/ops-glasses/workflow-domain.ts`

- [ ] **Step 1: 写入口、结束、不可达、普通环路和有界重复测试**

```ts
Deno.test("rejects unreachable nodes and unbounded cycles", () => {
  const result = validateWorkflowDraft(draftWithUnreachableCycle());
  assertEquals(result.errors.map((item) => item.code), ["unreachable_node", "cycle_not_allowed"]);
});

Deno.test("allows only bounded repeat groups", () => {
  const result = validateWorkflowDraft(draftWithRepeatGroup(3));
  assertEquals(result.errors, []);
});

Deno.test("compiles equivalent drafts to the same canonical package", async () => {
  const first = await compileWorkflowDraft(validDraft());
  const second = await compileWorkflowDraft(reorderedEquivalentDraft());
  assertEquals(first.contentSha256, second.contentSha256);
  assertEquals(first.nodes, second.nodes);
});
```

- [ ] **Step 2: 运行目标测试确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-domain.test.ts`

Expected: FAIL，指出图校验或 `compileWorkflowDraft` 尚未实现。

- [ ] **Step 3: 实现确定性图校验与编译**

```ts
export async function compileWorkflowDraft(draft: WorkflowDraft): Promise<WorkflowExecutionPackage> {
  const validation = validateWorkflowDraft(draft);
  if (validation.errors.length > 0) throw new WorkflowValidationException(validation.errors);
  const normalized = normalizeDraft(draft); // stable node order, stable object keys, no editor metadata
  const canonical = canonicalJson(normalized);
  return { ...normalized, contentSha256: await sha256Hex(canonical) };
}
```

实现要求：恰好一个 `start`、至少一个 `complete`；所有普通节点从入口可达且能到结束；普通边形成 DAG；`repeat_group.maxIterations` 为 `1..100`；`subflow` 只保存精确发布版本引用；所需能力按节点类型确定性生成并排序。

- [ ] **Step 4: 运行目标测试和 Deno check**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-domain.test.ts`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/workflow-domain.ts`

Expected: 全部 PASS。

- [ ] **Step 5: 提交任务**

```powershell
git add supabase/functions/ops-glasses/workflow-domain.ts supabase/functions/ops-glasses/workflow-domain.test.ts
git commit -m "feat: compile immutable workflow packages"
```

### Task 3: 工单三态绑定与确定性优先级

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-binding.test.ts`
- Create: `supabase/functions/ops-glasses/workflow-binding.ts`

- [ ] **Step 1: 写人工、外部映射、规则、默认和冲突测试**

```ts
Deno.test("resolves manual binding before trusted external and policy rules", () => {
  const result = resolveWorkflowBinding(workOrder(), candidates());
  assertEquals(result, { kind: "assigned", source: "manual", mode: "required", workflowVersionId: "version-manual" });
});

Deno.test("returns none when no candidate or default matches", () => {
  assertEquals(resolveWorkflowBinding(workOrder(), []), { kind: "none", mode: "none" });
});

Deno.test("returns conflict for equally specific rules at the same priority", () => {
  const result = resolveWorkflowBinding(workOrder(), conflictingRules());
  assertEquals(result.kind, "conflict");
});
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-binding.test.ts`

Expected: FAIL，原因是绑定解析器不存在。

- [ ] **Step 3: 实现绑定模式、规则谓词和排序**

```ts
const sourceRank = { manual: 500, trusted_external: 400, project: 300, asset_order_type: 200, organization_default: 100 } as const;

export function resolveWorkflowBinding(order: WorkOrderFacts, candidates: BindingCandidate[]): BindingResolution {
  const matches = candidates.filter((candidate) => candidateMatches(order, candidate));
  if (matches.length === 0) return { kind: "none", mode: "none" };
  const ranked = matches.toSorted(compareCandidate);
  if (sameRankAndSpecificity(ranked[0], ranked[1]) && ranked[0].workflowVersionId !== ranked[1].workflowVersionId) {
    return { kind: "conflict", candidateIds: tiedCandidateIds(ranked) };
  }
  return assignedResolution(ranked[0]);
}
```

规则条件只允许设计文档中的类型化字段和操作符；未知字段、跨组织候选、过期规则、未发布版本和错误模式不得参与匹配。

- [ ] **Step 4: 运行测试和类型检查确认 GREEN**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-binding.test.ts`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/workflow-binding.ts`

Expected: 全部 PASS。

- [ ] **Step 5: 提交任务**

```powershell
git add supabase/functions/ops-glasses/workflow-binding.ts supabase/functions/ops-glasses/workflow-binding.test.ts
git commit -m "feat: resolve work order workflow bindings"
```

### Task 4: Postgres 工作流控制面合同

**Files:**
- Create: `scripts/validate-configurable-workflows.mjs`
- Create: `supabase/migrations/202608010001_configurable_field_workflows.sql`
- Modify: `package.json`

- [ ] **Step 1: 写失败的迁移合同验证器**

```js
for (const contract of [
  /create table public\.field_apps\b/i,
  /create table public\.workflow_definitions\b/i,
  /create table public\.workflow_versions\b/i,
  /create table public\.workflow_binding_rules\b/i,
  /create table public\.work_orders\b/i,
  /create table public\.workflow_assignments\b/i,
  /create table public\.workflow_executions\b/i,
  /create table public\.workflow_step_executions\b/i,
  /workflow_versions_immutable/i,
  /workflow_assignment_sequence/i,
  /check \(mode in \('required', 'optional', 'none'\)\)/i,
  /enable row level security/i,
]) assert.match(sql, contract);
```

- [ ] **Step 2: 运行验证器确认 RED**

Run: `node scripts/validate-configurable-workflows.mjs`

Expected: FAIL，指出迁移文件缺失。

- [ ] **Step 3: 实现迁移的最小完整合同**

```sql
create table public.workflow_versions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  workflow_definition_id uuid not null references public.workflow_definitions(id) on delete cascade,
  version_number integer not null check (version_number > 0),
  status text not null check (status in ('published', 'deprecated', 'revoked', 'archived')),
  schema_version integer not null check (schema_version > 0),
  execution_package jsonb not null,
  content_sha256 text not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
  required_capabilities text[] not null default '{}',
  min_app_version_code integer not null default 9000,
  published_by uuid not null references public.ops_profiles(id),
  published_at timestamptz not null default now(),
  unique (workflow_definition_id, version_number),
  unique (organization_id, id)
);
```

其余表按设计文档第 4 节建立组织外键、状态检查、时间、操作者、理由和审计字段。`workflow_assignments` 使用数据库 sequence 生成单调 `delivery_sequence`，同一目标工单只允许一个未撤销分配；执行中保存精确版本。已发布版本使用 `before update or delete` 触发器拒绝内容突变，状态变更通过受控 RPC 记录审计。所有新表启用 RLS，管理员只读本组织，现场人员仅可读本人有效项目/分配；写入只授予 service role RPC。

- [ ] **Step 4: 接入根验证命令**

在 `validate:supabase` 中将 `node scripts/validate-configurable-workflows.mjs` 放在短期会话验证之后，保留现有命令顺序和所有旧验证器。

- [ ] **Step 5: 运行 schema 和全量领域验证**

Run: `node scripts/validate-configurable-workflows.mjs`

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-domain.test.ts supabase/functions/ops-glasses/workflow-binding.test.ts`

Run: `npm run validate:supabase`

Expected: 全部 PASS，旧验证无回归。

- [ ] **Step 6: 提交任务**

```powershell
git add package.json scripts/validate-configurable-workflows.mjs supabase/migrations/202608010001_configurable_field_workflows.sql
git commit -m "feat: add configurable workflow schema"
```

### Task 5: 阶段复验和证据

**Files:**
- Create: `docs/verification/2026-08-01-v9-configurable-workflow-domain.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: 运行当前范围完整验证**

Run: `deno test --allow-env supabase/functions/ops-glasses/*.test.ts`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`

Run: `npm run validate:supabase`

Run: `git diff --check`

Expected: 所有命令 PASS；若既有警告存在，报告其来源，不将警告描述为新功能通过。

- [ ] **Step 2: 写验证证据与剩余边界**

报告记录当前提交、命令、通过数量和未验证项，并明确：迁移尚未在真实 Supabase 执行；管理/设备工作流 API、网页、推送通道、Android 执行器和 Air3 尚未由本计划实现。

- [ ] **Step 3: 更新 agent_memory**

将领域合同与迁移标记为本地完成证据；继续把真实 Postgres、API、云端、Android 和 Air3 列为未完成/P0，不得把 schema 通过描述为产品功能完成。

- [ ] **Step 4: 提交验证材料**

```powershell
git add docs/verification/2026-08-01-v9-configurable-workflow-domain.md agent_memory/progress.md agent_memory/bugs.md
git commit -m "docs: verify configurable workflow domain"
```
