# V9 Workflow Studio Web Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在独立 V9 管理后台实现可真实保存、校验、签名发布和绑定工单的受控工作流工作台，不使用假数据或前端假成功。

**Architecture:** Edge Function 提供组织隔离的工作流目录、草稿、版本、工单和绑定接口，Postgres RPC 保证高影响写操作的幂等与审计。React 管理后台使用受控三栏工作台和 `@xyflow/react` 画布，节点属性来自服务端公共目录，草稿保存服务端仍执行权威校验；Air3 预览只渲染固定 HUD 模板。普通工作台与工作流编辑器保持模块隔离，现有专家 Web、眼镜 HUD 和稳定 APK 不改动。

**Tech Stack:** React 19、TypeScript、Vite、Vitest、Testing Library、lucide-react、@xyflow/react、Deno TypeScript、Supabase Postgres/RPC、Playwright

**Design sources:** `design/context.md`、`design/audit.md`、`design/workflow-studio-directions.md`、`docs/superpowers/specs/2026-08-01-v9-configurable-field-app-workflow-design.md`

---

### Task 1: 服务端公共节点目录与类型化配置合同

**Files:**
- Create: `supabase/functions/ops-glasses/workflow-catalog.ts`
- Create: `supabase/functions/ops-glasses/workflow-catalog.test.ts`
- Modify: `supabase/functions/ops-glasses/workflow-domain.ts`
- Modify: `supabase/functions/ops-glasses/workflow-domain.test.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.test.ts`

- [ ] **Step 1: 写失败测试**

验证 `GET /management/workflow-catalog` 只向管理员返回15类节点、固定页面模板、字段类型、风险/离线策略和每类允许配置键；验证未知配置键、错误字段类型、超长文本、任意 HTML、脚本、URL、连接器凭据和未声明动作无法保存。

```ts
const response = await routeWorkflowManagement(
  request("GET", "/management/workflow-catalog", "admin-token"),
  gateway(),
);
assertEquals(response.status, 200);
assertEquals((await response.json()).nodes.length, 15);

const result = validateWorkflowDraft({
  ...validDraft(),
  nodes: [{
    nodeId: "photo",
    type: "photo_capture",
    config: { title: "拍摄铭牌", arbitraryHtml: "<iframe>" },
  }],
});
assertEquals(result.errors[0].code, "node_config_key_invalid");
```

- [ ] **Step 2: 运行 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-catalog.test.ts supabase/functions/ops-glasses/workflow-domain.test.ts supabase/functions/ops-glasses/workflow-management.test.ts`

Expected: FAIL，公共目录路由和严格配置错误码尚不存在。

- [ ] **Step 3: 实现目录和权威校验**

目录至少公开：

```ts
export type WorkflowNodeCatalogItem = {
  type: WorkflowNodeType;
  label: string;
  category: "flow" | "content" | "evidence" | "input" | "assist" | "integration";
  pageTemplate: "instruction" | "evidence_capture" | "form" | "choice" | "conversation" | "confirmation" | "completion" | "none";
  requiredCapability: string | null;
  fields: WorkflowConfigField[];
};
```

所有节点共同字段限制为 `title`、`description`、`voicePrompt`、`riskLevel`、`offlinePolicy`、`allowedActions` 和目录声明的类型专用字段。校验仅接受对象、字符串、布尔、有限整数、受控枚举、UUID数组和类型化字段/选项数组，不执行 HTML、表达式、正则或动态代码。

- [ ] **Step 4: 运行 GREEN 与回归**

Run: `deno test --allow-env supabase/functions/ops-glasses/*.test.ts`

Expected: 全部通过，既有最小 `start -> complete` 草稿仍有效。

- [ ] **Step 5: 提交**

```powershell
git add -- supabase/functions/ops-glasses/workflow-catalog.ts supabase/functions/ops-glasses/workflow-catalog.test.ts supabase/functions/ops-glasses/workflow-domain.ts supabase/functions/ops-glasses/workflow-domain.test.ts supabase/functions/ops-glasses/workflow-management.ts supabase/functions/ops-glasses/workflow-management.test.ts
git commit -m "feat: publish typed workflow node catalog"
```

### Task 2: 工单读取与受控绑定规则管理 API

**Files:**
- Create: `supabase/migrations/202608010003_workflow_binding_rule_management.sql`
- Create: `scripts/validate-workflow-binding-rule-management.mjs`
- Modify: `supabase/functions/ops-glasses/workflow-management.ts`
- Modify: `supabase/functions/ops-glasses/workflow-management.test.ts`
- Modify: `package.json`

- [ ] **Step 1: 写失败测试**

覆盖：

```text
GET  /management/work-orders?status=received&limit=50
GET  /management/workflow-binding-rules
POST /management/workflow-binding-rules
PUT  /management/workflow-binding-rules/:ruleId
```

创建要求 `CREATE_WORKFLOW_BINDING_RULE`、理由、幂等键、合法来源/模式、已发布版本和类型化匹配条件；更新要求 `UPDATE_WORKFLOW_BINDING_RULE`、理由、版本号和幂等键。客户端组织和操作者字段不参与写入。

- [ ] **Step 2: 运行 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/workflow-management.test.ts`

Expected: FAIL，路由和网关事务方法不存在。

- [ ] **Step 3: 实现事务 RPC 与白名单响应**

迁移新增 `create_workflow_binding_rule` 和 `update_workflow_binding_rule`，两者仅 `service_role` 可执行，使用组织行锁、乐观版本、幂等事件和 `audit_events`。GET 工单只返回列表所需字段，不返回连接器凭据或外部原始载荷。

- [ ] **Step 4: 验证迁移和 API**

Run: `npm run validate:supabase`

Run: `deno test --allow-env supabase/functions/ops-glasses/*.test.ts`

Expected: 静态合同与全量测试通过。

- [ ] **Step 5: 提交**

```powershell
git add -- supabase/migrations/202608010003_workflow_binding_rule_management.sql scripts/validate-workflow-binding-rule-management.mjs supabase/functions/ops-glasses/workflow-management.ts supabase/functions/ops-glasses/workflow-management.test.ts package.json
git commit -m "feat: manage workflow binding rules"
```

### Task 3: 管理网页工作流 API 客户端

**Files:**
- Create: `ops-management-web/src/api/workflow-types.ts`
- Create: `ops-management-web/src/api/workflow-api.test.ts`
- Modify: `ops-management-web/src/api/management-api.ts`
- Modify: `ops-management-web/src/App.test.tsx`

- [ ] **Step 1: 写失败测试**

测试客户端生成正确路径、JSON请求头和确认载荷，并把 `validationErrors`、`signing_unavailable`、`workflow_already_started` 和 `conflict` 保留为结构化 `ManagementApiError`。

```ts
await api.saveWorkflowDraft("workflow-a", draft);
expect(fetch).toHaveBeenCalledWith(
  expect.stringContaining("/management/workflows/workflow-a/draft"),
  expect.objectContaining({ method: "PUT" }),
);
```

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix ops-management-web test -- --run src/api/workflow-api.test.ts`

Expected: FAIL，工作流类型和方法不存在。

- [ ] **Step 3: 实现真实 API 合同**

`ManagementApi` 增加目录、现场应用、工作流、草稿、校验、版本、发布、工单、规则和解析方法。所有路径段使用 `encodeURIComponent`，写请求自动设置 `Content-Type: application/json`，204响应不解析 JSON。

- [ ] **Step 4: 运行 GREEN**

Run: `npm --prefix ops-management-web test -- --run`

Expected: 全部通过。

- [ ] **Step 5: 提交**

```powershell
git add -- ops-management-web/src/api/workflow-types.ts ops-management-web/src/api/workflow-api.test.ts ops-management-web/src/api/management-api.ts ops-management-web/src/App.test.tsx
git commit -m "feat: connect workflow management web APIs"
```

### Task 4: 现场应用目录与工作流创建

**Files:**
- Create: `ops-management-web/src/features/workflows/FieldAppsPage.tsx`
- Create: `ops-management-web/src/features/workflows/FieldAppsPage.test.tsx`
- Create: `ops-management-web/src/features/workflows/WorkflowCreateDialog.tsx`
- Modify: `ops-management-web/src/App.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [ ] **Step 1: 写失败交互测试**

验证导航出现“现场应用”，真实加载/空/错误/重试状态可达；创建应用与工作流需要完整字段，服务端成功后才进入编辑器，失败保留输入。

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix ops-management-web test -- --run src/features/workflows/FieldAppsPage.test.tsx`

Expected: FAIL，页面不存在。

- [ ] **Step 3: 实现高密度目录页**

桌面使用未嵌套的应用列表和流程表格，显示真实状态、入口模式、最新版本和更新时间。创建命令使用文字加图标按钮；删除、复制和导入在后端未实现前不显示。

- [ ] **Step 4: 验证页面与回归**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

- [ ] **Step 5: 提交**

```powershell
git add -- ops-management-web/src/features/workflows ops-management-web/src/App.tsx ops-management-web/src/styles/app.css
git commit -m "feat: add field application workspace"
```

### Task 5: 受控三栏工作流画布

**Files:**
- Modify: `ops-management-web/package.json`
- Modify: `ops-management-web/package-lock.json`
- Create: `ops-management-web/src/features/workflows/WorkflowStudioPage.tsx`
- Create: `ops-management-web/src/features/workflows/WorkflowStudioPage.test.tsx`
- Create: `ops-management-web/src/features/workflows/WorkflowCanvas.tsx`
- Create: `ops-management-web/src/features/workflows/workflow-editor-state.ts`
- Create: `ops-management-web/src/features/workflows/workflow-editor-state.test.ts`
- Modify: `ops-management-web/src/styles/app.css`

- [ ] **Step 1: 安装画布依赖**

Run: `npm --prefix ops-management-web install @xyflow/react`

- [ ] **Step 2: 写失败状态机与交互测试**

覆盖添加节点、唯一ID、连接边、禁止指向开始节点、禁止从结束节点连出、删除节点同时删除关联边、位置保存、撤销未保存修改和从服务端草稿恢复。

- [ ] **Step 3: 运行 RED**

Run: `npm --prefix ops-management-web test -- --run src/features/workflows/workflow-editor-state.test.ts src/features/workflows/WorkflowStudioPage.test.tsx`

Expected: FAIL，编辑状态和页面不存在。

- [ ] **Step 4: 实现三栏画布**

左栏固定宽度展示应用/流程和节点目录；中间画布支持选择、拖动、连接、缩放和适应视图；右栏展示选中节点。画布仅保存 `nodeId`、`type`、`config`、编辑位置和受控转移，不生成脚本或任意表达式。

- [ ] **Step 5: 运行 GREEN 与构建**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run build`

- [ ] **Step 6: 提交**

```powershell
git add -- ops-management-web/package.json ops-management-web/package-lock.json ops-management-web/src/features/workflows ops-management-web/src/styles/app.css
git commit -m "feat: add controlled workflow canvas"
```

### Task 6: 类型化属性与 Air3 固定 HUD 预览

**Files:**
- Create: `ops-management-web/src/features/workflows/NodeInspector.tsx`
- Create: `ops-management-web/src/features/workflows/NodeInspector.test.tsx`
- Create: `ops-management-web/src/features/workflows/Air3HudPreview.tsx`
- Create: `ops-management-web/src/features/workflows/Air3HudPreview.test.tsx`
- Modify: `ops-management-web/src/features/workflows/WorkflowStudioPage.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [ ] **Step 1: 写失败测试**

对15类节点验证目录驱动字段、错误提示、必填项、风险/离线策略和固定模板映射。确认预览不允许改变HUD布局，也不会把任意HTML作为标记渲染。

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix ops-management-web test -- --run src/features/workflows/NodeInspector.test.tsx src/features/workflows/Air3HudPreview.test.tsx`

- [ ] **Step 3: 实现属性和预览**

字段控件严格按目录类型选择输入框、选择器、开关、数字步进和选项列表。预览模板只包含 `instruction`、`evidence_capture`、`form`、`choice`、`conversation`、`confirmation`、`completion` 和无页面控制节点。

- [ ] **Step 4: 验证 GREEN**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

- [ ] **Step 5: 提交**

```powershell
git add -- ops-management-web/src/features/workflows ops-management-web/src/styles/app.css
git commit -m "feat: configure fixed Air3 workflow templates"
```

### Task 7: 保存、校验、签名发布、版本与工单绑定

**Files:**
- Create: `ops-management-web/src/features/workflows/WorkflowCommandBar.tsx`
- Create: `ops-management-web/src/features/workflows/WorkflowCommandBar.test.tsx`
- Create: `ops-management-web/src/features/workflows/WorkflowVersionsPanel.tsx`
- Create: `ops-management-web/src/features/workflows/WorkOrderBindingPanel.tsx`
- Create: `ops-management-web/src/features/workflows/WorkOrderBindingPanel.test.tsx`
- Modify: `ops-management-web/src/features/workflows/WorkflowStudioPage.tsx`

- [ ] **Step 1: 写失败测试**

覆盖脏状态、保存中、服务端字段级校验、签名不可用、发布确认词/理由/最低版本、发布成功版本刷新、规则冲突、已开始工单拒绝换版和二次提交幂等。

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix ops-management-web test -- --run src/features/workflows/WorkflowCommandBar.test.tsx src/features/workflows/WorkOrderBindingPanel.test.tsx`

- [ ] **Step 3: 实现真实命令链**

保存成功后才清除脏状态；校验显示服务端 `code + path`；发布必须显式输入理由并确认，服务端返回版本后才显示已发布；绑定面板显示工单当前模式、解析来源、冲突候选和最终分配状态。

- [ ] **Step 4: 全量网页验证**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

Run: `npm --prefix ops-management-web run build`

- [ ] **Step 5: 提交**

```powershell
git add -- ops-management-web/src/features/workflows
git commit -m "feat: publish and assign field workflows"
```

### Task 8: 浏览器设计 QA 与阶段证据

**Files:**
- Create: `design/qa.md`
- Create: `docs/verification/2026-08-01-v9-workflow-studio-web.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: 启动独立开发服务器**

Run: `npm --prefix ops-management-web run dev -- --host 127.0.0.1 --port 5189`

- [ ] **Step 2: Playwright 验证核心流程**

验证桌面1440x900和窄屏1024x768：现场应用列表、创建、打开流程、加节点、连接、配置、HUD预览、保存、校验错误、发布确认、版本列表和工单绑定错误态。检查无重叠、无裁切、焦点可见、文字不越界。

- [ ] **Step 3: 设计 QA**

将实现截图与 `design/context.md`、`design/workflow-studio-directions.md` 和现有工作台对比，在 `design/qa.md` 记录布局、字体、颜色、图标、状态、窄屏和交互结论；任何高严重度问题修复后重新截图。

- [ ] **Step 4: 完整回归**

Run: `deno test --allow-env supabase/functions/ops-glasses/*.test.ts`

Run: `npm run validate:supabase`

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

Run: `npm --prefix ops-management-web run build`

Run: `git diff --check`

- [ ] **Step 5: 记录未验证边界并提交**

真实 Supabase、生产签名密钥、在线推送、Android运行时和Air3仍需直接证据，不因网页测试通过而标记完成。

```powershell
git add -- design/audit.md design/workflow-studio-directions.md design/qa.md docs/superpowers/plans/2026-08-01-v9-workflow-studio-web.md docs/verification/2026-08-01-v9-workflow-studio-web.md
git commit -m "docs: verify V9 workflow studio"
```

## 阶段停止条件

- 服务端类型化节点目录未完成时，不实现自由 JSON 配置面板。
- 工作流写操作未返回真实服务端结果时，不显示保存、发布或绑定成功。
- 管理工作台不得调用 MVS、第三方任意 URL 或携带长期凭据。
- 高严重度设计 QA、键盘访问、构建、全量测试或现有管理页面回归未通过时，不进入 Android 接入。
