# V9 Skill 与知识库全生命周期实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建成从管理网页创建、审核、发布和分配 Skill/知识，到眼镜获取授权清单、任务固定 Skill 快照、AI 使用可追溯知识引用的真实闭环。

**Architecture:** Supabase 是组织级 Skill/知识版本、审核、分配、清单和审计的权威控制面；Edge Function 只通过服务端身份推导权限并生成设备清单；V9 网关把已发布授权转换为任务级不可变 `ExecutionContext`，Android 只缓存经服务端验证的目录和版本摘要。发布版本不可变，回滚通过重新分配旧版本完成，后端不可用或未授权时明确拒绝。

**Tech Stack:** PostgreSQL/Supabase migrations、Deno Edge Functions、TypeScript、React 19/Vite/Vitest、Python V9 gateway、Android Java/JUnit。

---

### Task 1: 固化领域状态机与安全合同

**Files:**
- Create: `supabase/functions/ops-glasses/skill-knowledge-domain.ts`
- Create: `supabase/functions/ops-glasses/skill-knowledge-domain.test.ts`

- [x] **Step 1: 写失败测试**：覆盖 Skill `draft -> review_pending -> published -> deprecated -> archived`、知识 `uploaded -> scanning -> parsing -> parsed -> review_pending -> published -> expired/deprecated -> archived`，以及非法跳转、发布版本不可变、知识范围和规则输入校验。
- [x] **Step 2: 验证红灯**：运行 `deno test --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/skill-knowledge-domain.test.ts`，确认因领域模块缺失而失败。
- [x] **Step 3: 最小实现**：实现纯函数状态转换、严格对象字段、文本/数组上限、禁止 URL/脚本/凭据字段、稳定 JSON 与 SHA-256 摘要。
- [x] **Step 4: 验证绿灯**：重跑领域测试并确认全部通过。

### Task 2: 建立 Supabase 权威数据模型

**Files:**
- Create: `supabase/migrations/20260803054347_skill_knowledge_control_plane.sql`
- Create: `scripts/validate-skill-knowledge-control-plane.mjs`
- Modify: `package.json`

- [x] **Step 1: 写失败合同测试**：要求 Skill 定义/版本/审核/分配、知识条目/版本/审核/授权/引用、设备清单版本和审计事件实体存在。
- [x] **Step 2: 验证红灯**：运行 `node scripts/validate-skill-knowledge-control-plane.mjs`，确认迁移尚不满足合同。
- [x] **Step 3: 生成迁移**：使用仓库 Supabase CLI 创建迁移，再填入组织复合外键、唯一约束、不可变发布版本触发器、RLS、受控 RPC、理由、幂等键和审计写入。
- [x] **Step 4: 权限收紧**：公共表全部启用 RLS；撤销 `public/anon/authenticated` 的直接写权限；需要 `security definer` 的 RPC 显式校验操作者、组织和角色，并撤销默认 PUBLIC 执行权限。
- [x] **Step 5: 验证绿灯**：运行定向 schema 合同和 `npm run validate:supabase`。

### Task 3: 实现管理 API

**Files:**
- Create: `supabase/functions/ops-glasses/skill-knowledge-management.ts`
- Create: `supabase/functions/ops-glasses/skill-knowledge-management.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] **Step 1: 写失败路由测试**：覆盖列表、创建草稿、提交审核、审核通过/拒绝、发布、分配、撤销、回滚、归档和知识案例草稿。
- [x] **Step 2: 写负向测试**：覆盖无令牌、跨组织、错误角色、缺理由、缺二次确认、重复幂等键、未审核发布和修改已发布版本。
- [x] **Step 3: 验证红灯**：运行定向 Deno 测试，确认路由尚不存在。
- [x] **Step 4: 最小实现**：复用现有 Supabase Auth/`ops_profiles` 身份模式，只接受白名单 DTO，调用受控 RPC，响应不返回供应商密钥、模板或未发布内容。
- [x] **Step 5: 验证绿灯**：运行管理 API 测试、`deno check` 和现有 Edge Function 全量测试。

### Task 4: 实现设备发布清单

**Files:**
- Create: `supabase/functions/ops-glasses/skill-knowledge-device.ts`
- Create: `supabase/functions/ops-glasses/skill-knowledge-device.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] **Step 1: 写失败测试**：设备只能得到组织/人员/设备/项目范围内已发布 Skill 和知识目录；响应包含 `manifestVersion`、`etag`、有效期和最小必要元数据。
- [x] **Step 2: 写负向测试**：未发布、已撤销、已过期、跨组织和超出 Skill `knowledgeScopes` 的内容不得出现；失效清单不得标记成功。
- [x] **Step 3: 验证红灯**：运行定向设备路由测试。
- [x] **Step 4: 最小实现**：复用短期设备会话，服务端推导组织、人员、设备和项目；生成稳定 ETag，支持 `If-None-Match`，不下发规则秘密、原文附件或供应商凭据。
- [x] **Step 5: 验证绿灯**：运行设备路由测试和现有 device/workflow 回归。

### Task 5: 实现管理 Web 工作区

**Files:**
- Create: `ops-management-web/src/api/skill-knowledge-types.ts`
- Modify: `ops-management-web/src/api/management-api.ts`
- Create: `ops-management-web/src/features/skills/SkillsPage.tsx`
- Create: `ops-management-web/src/features/skills/SkillsPage.test.tsx`
- Create: `ops-management-web/src/features/knowledge/KnowledgePage.tsx`
- Create: `ops-management-web/src/features/knowledge/KnowledgePage.test.tsx`
- Modify: `ops-management-web/src/App.tsx`
- Modify: `ops-management-web/src/App.test.tsx`
- Modify: `ops-management-web/src/styles/app.css`

- [x] **Step 1: 写失败 UI/API 测试**：导航存在“AI 运维技能”和“华方知识库”；页面覆盖加载、空、失败、无权限、草稿、待审核、已发布、分配和版本历史状态。
- [x] **Step 2: 验证红灯**：运行 Vitest 定向测试。
- [x] **Step 3: 最小实现**：沿用现有企业工作台布局、色彩、字号和紧凑密度，不改变远程专家 Web，不做营销式页面；高影响动作要求确认和理由。
- [x] **Step 4: 验证绿灯**：运行 Web 全量测试、TypeScript 和生产构建。

### Task 6: 接入 V9 网关 ExecutionContext

**Files:**
- Modify: `v9-ops-gateway/execution_context.py`
- Modify: `v9-ops-gateway/gateway.py`
- Create or modify: `v9-ops-gateway/test_execution_context.py`
- Create or modify: `v9-ops-gateway/test_gateway.py`

- [x] **Step 1: 写失败测试**：任务启动固定一个已授权 published Skill；每轮上下文带经过授权的知识引用；后续发布不替换活跃快照。
- [x] **Step 2: 写失败路径测试**：必需知识不可用时拒绝，可选知识失败时明确记录未使用来源；未授权 Skill、过期清单和组织不一致全部拒绝。
- [x] **Step 3: 验证红灯**：运行 V9 网关定向 unittest。
- [x] **Step 4: 最小实现**：从 Supabase 清单/同步适配层读取真实版本，保持当前 SQLite 任务快照作为执行缓存和审计副本，不再把它作为组织级权威来源。
- [x] **Step 5: 验证绿灯**：运行网关全量测试和部署合同测试。

### Task 7: Android 授权目录与眼镜联动

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/SkillKnowledgeManifestClient.java`
- Create: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/SkillKnowledgeManifestClientTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/assets/voice-first-hud.html`

- [x] **Step 1: 写失败测试**：150ms 内展示缓存名称/版本/授权缓存状态，网络确认成功后才能启用；失败明确拒绝；知识页只显示已授权目录和引用。
- [x] **Step 2: 验证红灯**：运行 Android 定向 JVM 测试。
- [x] **Step 3: 最小实现**：使用短期设备会话拉取 ETag 清单，缓存只读目录；启用 Skill 仍由服务端生成任务快照；保持首页、九宫格、HUD 布局和 V8 行为。
- [x] **Step 4: 验证绿灯**：运行 Android 全量单测、HUD 合同和构建验证。

### Task 8: 联调、部署与证据

**Files:**
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`
- Create: `docs/verification/2026-08-03-v9-skill-knowledge-lifecycle.md`

- [ ] **Step 1: 云端验证**：真实执行迁移、部署 Edge Function 和独立管理 Web，检查 RLS、角色、负向授权、健康端点和回滚。
- [ ] **Step 2: 端到端验证**：发布 Skill/知识、授权 Air3、自动同步、启用、任务对话行为对照、知识引用、撤销和回滚。
- [ ] **Step 3: 性能与稳定性**：测量清单缓存反馈、服务端确认、知识检索、首 token、弱网、断网、重启、20 分钟连续使用、温升和耗电。
- [ ] **Step 4: 安全验证**：秘密扫描、APK 内容、跨组织/RLS、Prompt/知识投毒、审计、备份恢复和故障演练。
- [ ] **Step 5: 记录边界**：只有真实云端、Air3 和角色证据齐全时才把闭环标记完成；缺凭据或试点数据继续记为发布阻断，不使用 mock 冒充。
