# V9 身份与设备控制面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改眼镜 UI、专家协同和稳定 APK 的前提下，建立组织隔离的账号状态、项目授权、设备绑定/解绑/撤销和不可变审计闭环。

**Architecture:** Supabase Auth 继续承担试点登录，`ops_profiles` 承担 V9 业务账号状态；项目授权和设备绑定使用独立历史表，管理 Edge Function 从已认证身份推导组织与权限，客户端提交的组织和角色不可信。高影响动作必须携带固定确认词和理由，由数据库 RPC 再次验证操作者与目标同组织后原子执行并写入审计。

**Tech Stack:** Supabase Postgres/RLS/RPC、Deno Edge Function、Deno test、React/Vite/TypeScript。

**Scope Lock:** 不实现 MVS 工单，不自研企业密码/MFA/MDM，不修改 `expert-collab-*`、TRTC、眼镜 HUD、相机、语音或 AI 主链路；没有真实 API 的网页操作不提前展示。

---

### Task 1: 建立账号、项目授权与设备绑定数据合同

**Files:**
- Create: `scripts/validate-identity-device-control-plane.mjs`
- Create: `supabase/migrations/202607310004_identity_device_control_plane.sql`

- [x] **Step 1: 写失败的 schema 合同测试**

验证迁移必须包含 `ops_profile_status`、`ops_project_memberships`、`device_bindings`、设备元数据、账号状态 RPC、绑定/解绑/撤销 RPC、审计防篡改触发器、RLS 与 partial unique index。

- [x] **Step 2: 运行失败测试**

Run: `node scripts/validate-identity-device-control-plane.mjs`

Expected: FAIL，指出迁移文件或所需合同不存在。

- [x] **Step 3: 实现最小数据库合同**

新增账号生命周期状态 `invited|active|disabled|archived`；新增项目成员关系和只允许一个有效设备绑定的历史表；为设备增加型号、应用版本、MDM 状态与撤销字段。RPC 必须从数据库验证 actor、target 和 project 同组织，解绑/撤销/账号状态变更要求非空理由，并在同一事务写 `audit_events`。

- [x] **Step 4: 运行合同测试**

Run: `node scripts/validate-identity-device-control-plane.mjs`

Expected: `identity-device control-plane schema contract passed`

### Task 2: 增加受权人员与设备管理 API

**Files:**
- Modify: `supabase/functions/ops-glasses/management.test.ts`
- Modify: `supabase/functions/ops-glasses/management.ts`

- [x] **Step 1: 先写失败路由测试**

覆盖管理员读取人员、低权限角色拒绝、设备绑定、缺少二次确认拒绝解绑/撤销、只有 `super_admin` 可签发凭据和改变账号状态。

- [x] **Step 2: 运行并确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/management.test.ts`

Expected: FAIL，原因是新路由或网关方法尚不存在。

- [x] **Step 3: 实现最小路由和网关调用**

增加 `GET /management/people`、`POST /management/devices/:id/bindings`、`POST /management/devices/:id/unbind`、`POST /management/devices/:id/revoke`、`POST /management/people/:id/status`。请求体只接受白名单字段；解绑、撤销和账号状态变更分别要求 `UNBIND_DEVICE`、`REVOKE_DEVICE`、`CHANGE_ACCOUNT_STATUS`；所有组织、操作者和目标关系由服务端/RPC 推导。

- [x] **Step 4: 运行目标测试和类型检查**

Run: `deno test --allow-env supabase/functions/ops-glasses/management.test.ts`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`

### Task 3: 对现有查询执行项目范围授权

**Files:**
- Create: `supabase/functions/ops-glasses/project-scope.test.ts`
- Create: `supabase/functions/ops-glasses/project-scope.ts`
- Modify: `supabase/functions/ops-glasses/management.ts`

- [x] **Step 1: 写跨组织和无项目授权的失败测试**

分别证明 `field_engineer`、`remote_expert`、`viewer` 只能取得有效 `ops_project_memberships` 对应的项目、任务、媒体与设备；管理员仍限制在本组织。

- [x] **Step 2: 运行并确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/project-scope.test.ts`

- [x] **Step 3: 实现服务端范围投影**

管理员范围为本组织；其他角色从有效项目成员关系计算允许的项目 ID。项目、任务、详情、Dashboard 统计、签名媒体 URL 和设备列表全部应用同一范围，空范围直接返回空集合或 `404`，不得退化为组织全读。

- [x] **Step 4: 运行全部 Deno 测试**

Run: `deno test --allow-env supabase/functions/ops-glasses/*.test.ts`

### Task 4: 接入真实管理网页

**Files:**
- Modify: `ops-management-web/src/api/management-api.ts`
- Create: `ops-management-web/src/features/people/PeopleDevicesPage.test.tsx`
- Create: `ops-management-web/src/features/people/PeopleDevicesPage.tsx`
- Modify: `ops-management-web/src/App.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [ ] **Step 1: 写失败的交互测试**

覆盖真实人员/设备加载、加载/空/错误态、理由输入、二次确认、一次性凭据只展示一次，以及 `viewer` 不出现管理操作。

- [ ] **Step 2: 运行并确认 RED**

Run: `npm --prefix ops-management-web test -- --run src/features/people/PeopleDevicesPage.test.tsx`

- [ ] **Step 3: 实现现有工作台风格的人员与设备页**

复用现有全宽表格和状态样式，不改变导航结构，不嵌套卡片；所有数据来自正式 API，操作成功后重新拉取，失败保留输入并展示可恢复错误。凭据明文不写 LocalStorage、日志或 URL。

- [ ] **Step 4: 运行 Web 验证**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

Run: `npm --prefix ops-management-web run build`

### Task 5: 部署与真实撤销验收

**Files:**
- Modify: `docs/deployment/ops-management-web-cloud.md`
- Create: `docs/verification/2026-07-31-v9-identity-device-control-plane.md`

- [ ] **Step 1: 运行本地完整验证**

运行 schema validators、全部 Deno 测试、Supabase validator、Web 测试/typecheck/build、`git diff --check` 和敏感信息扫描。

- [ ] **Step 2: 满足云端前提后部署 migration/Function/Web**

部署必须使用独立管理站目录、容器、端口和域名；部署前后验证 `https://bb.chinacedar.top:2305/health` 未变化。缺少 Supabase project ref、access token 或生产 secrets 时停止，不用本地假状态代替。

- [ ] **Step 3: 真实账号和 Air3 验收**

用两个组织、五种角色和一台受管 Air3 验证跨组织拒绝、项目范围、绑定、解绑、凭据轮换、设备撤销、待发事件保留和恢复。未经安装确认不安装 V9 APK。
