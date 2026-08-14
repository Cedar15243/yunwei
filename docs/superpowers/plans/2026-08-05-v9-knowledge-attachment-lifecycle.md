# V9 Knowledge Attachment Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为华方知识库补齐真实私有附件上传、受控处理、版本历史、短期下载和发布门禁，确保未解析附件绝不进入 AI 知识上下文。

**Architecture:** 管理 Web 先创建不可发布的知识版本，再通过我方 Edge Function 上传附件；Edge Function 校验组织权限、文件名、MIME、大小和客户端 SHA-256，使用服务端派生路径写入私有 Supabase Storage。TXT/Markdown 同步完成 UTF-8 解析并通过事务 RPC 原子切换当前附件和知识正文；PDF/DOCX 在没有真实解析器时保留原文件和明确的 `processing_unavailable` 状态。版本历史只返回脱敏附件元数据和 5 分钟签名 URL，不返回 bucket 或 storage path。

**Tech Stack:** Supabase PostgreSQL/RLS/Storage/Edge Functions、Deno TypeScript、React 19、TypeScript、Vitest、Testing Library。

---

## 成功标准

1. 管理员可创建“文件导入”知识版本并上传 TXT、Markdown、PDF 或 DOCX；文件原件进入私有 bucket，客户端不能指定或读取内部对象路径。
2. TXT/Markdown 只有在服务端 SHA-256 一致、扩展名/MIME 匹配、UTF-8 解码成功且正文合法后才将版本置为 `parsed`。
3. PDF/DOCX 在未配置真实解析器时返回明确的 `processing_unavailable`，知识版本保持不可提交审核；不得生成伪正文或伪扫描成功。
4. 同一知识版本可替换附件；只有新附件真实解析成功后才替换当前附件，失败上传不破坏上一份可用正文。
5. 版本列表返回附件状态、原始文件名、MIME、大小、SHA-256、处理错误和短期签名下载 URL，不返回 `storage_bucket` 或 `storage_path`。
6. `uploaded_file` 版本没有当前 `parsed` 附件时，数据库 RPC 拒绝提交审核和发布；手工正文创建后直接进入 `parsed`，不再显示虚假的后台扫描等待。
7. Web 保持现有知识目录/版本双栏布局和视觉风格，提供文件选择、上传进度、处理失败、重试解析、替换附件和下载操作。

## 停止条件

- 不实现虚假的 PDF/DOCX OCR、病毒扫描或全文解析；没有真实处理器就停在明确失败关闭状态。
- 不开放浏览器直写 Storage，不返回永久 URL，不把 service-role、供应商密钥或对象路径下发到 Web。
- 不修改 V8、Air3 Camera2、眼镜首页/HUD、专家协同或 MVS 主链路。

### Task 1: 附件领域校验与解析

**Files:**
- Create: `supabase/functions/ops-glasses/knowledge-attachment-domain.ts`
- Create: `supabase/functions/ops-glasses/knowledge-attachment-domain.test.ts`

- [x] **Step 1: 写失败测试**

覆盖允许的 TXT/Markdown/PDF/DOCX、扩展名与 MIME 不匹配、超限文件、客户端 SHA-256 不一致、PDF/DOCX 魔数不匹配、非法 UTF-8 和多行正文保留。

- [x] **Step 2: 运行测试确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/knowledge-attachment-domain.test.ts`

Expected: FAIL，因为附件校验和解析模块尚不存在。

- [x] **Step 3: 最小实现**

实现 `normalizeKnowledgeAttachmentUpload()`、`parseKnowledgeTextAttachment()`、`sha256Hex()` 和文件类型白名单。TXT/Markdown 最大 512000 字节，PDF/DOCX 最大 8388608 字节；只允许服务端确定的标准 MIME 和扩展名组合。

- [x] **Step 4: 运行测试确认 GREEN**

Run: `deno test --allow-env supabase/functions/ops-glasses/knowledge-attachment-domain.test.ts`

Expected: PASS。

### Task 2: 私有附件数据模型与事务门禁

**Files:**
- Create: `supabase/migrations/20260805190000_knowledge_version_attachments.sql`
- Modify: `scripts/validate-skill-knowledge-control-plane.mjs`

- [x] **Step 1: 写失败的静态契约**

在验证脚本中要求存在 `knowledge_version_attachments`、私有 `ops-knowledge-attachments` bucket、RLS/revoke、组织内幂等键、服务端派生路径、`complete_knowledge_attachment_processing` RPC，以及 `uploaded_file` 提交/发布附件门禁。

- [x] **Step 2: 运行验证确认 RED**

Run: `npm run validate:skill-knowledge`

Expected: FAIL，提示附件迁移或发布门禁缺失。

- [x] **Step 3: 最小迁移实现**

新增附件表和 service-role-only RPC；附件状态使用 `uploaded | parsing | parsed | processing_unavailable | failed | replaced`，记录原始文件名、MIME、大小、文件 SHA-256、解析正文 SHA-256、处理错误、当前附件标志和审计时间。RPC 只有在新附件解析成功时才替换旧当前附件，并原子更新知识正文、来源和版本状态。

- [x] **Step 4: 修正知识草稿和发布状态机**

`manual`、`external_link` 草稿以 `parsed` 创建；`uploaded_file` 允许空正文但保持 `uploaded`。`uploaded_file` 从 `parsed` 提交审核以及从 `review_pending` 发布前，必须存在当前且状态为 `parsed` 的附件。

- [x] **Step 5: 运行验证确认 GREEN**

Run: `npm run validate:skill-knowledge`

Expected: PASS。

### Task 3: Edge 上传、重试和脱敏版本 DTO

**Files:**
- Modify: `supabase/functions/ops-glasses/skill-knowledge-management.ts`
- Modify: `supabase/functions/ops-glasses/skill-knowledge-management.test.ts`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] **Step 1: 写路由失败测试**

覆盖 multipart 上传认证、管理员权限、显式确认、严格字段、哈希不一致、非 `uploaded_file` 版本、幂等重放、解析失败、处理器未配置、重试解析和错误码映射。

- [x] **Step 2: 运行测试确认 RED**

Run: `deno test --allow-env supabase/functions/ops-glasses/skill-knowledge-management.test.ts`

Expected: FAIL，因为附件路由和网关方法尚不存在。

- [x] **Step 3: 实现上传和重试路由**

新增 `POST /management/knowledge-versions/:id/attachments` 和 `POST /management/knowledge-attachments/:id/retry`。multipart 只接受 `file`、`sha256`、`idempotencyKey`、`confirmation`；服务端生成附件 UUID、bucket 和 object path，上传失败时保留可诊断状态并不切换当前附件。

- [x] **Step 4: 实现真实处理边界**

TXT/Markdown 上传后进入 parsing，真实解码并调用事务 RPC；PDF/DOCX 写入私有对象后返回 `knowledge_attachment_processor_unavailable` 的可见状态。重试只从私有 Storage 下载原件重新处理，不接受客户端正文覆盖。

- [x] **Step 5: 实现脱敏版本历史**

`GET /management/knowledge/:id/versions` 批量签发 300 秒下载 URL，返回 `knowledge_attachments` 脱敏字段；禁止 DTO 含 `storage_bucket` 或 `storage_path`。

- [x] **Step 6: 运行测试确认 GREEN**

Run: `deno test --allow-env supabase/functions/ops-glasses/skill-knowledge-management.test.ts`

Expected: PASS。

### Task 4: 管理 Web 文件导入与附件历史

**Files:**
- Modify: `ops-management-web/src/api/skill-knowledge-types.ts`
- Modify: `ops-management-web/src/api/management-api.ts`
- Modify: `ops-management-web/src/api/skill-knowledge-api.test.ts`
- Modify: `ops-management-web/src/features/governance/GovernanceDialogs.tsx`
- Modify: `ops-management-web/src/features/knowledge/KnowledgePage.tsx`
- Modify: `ops-management-web/src/features/knowledge/KnowledgePage.test.tsx`
- Modify: `ops-management-web/src/styles/governance.css`

- [ ] **Step 1: 写 Web 失败测试**

覆盖“文件导入”选项、文件必选、客户端 SHA-256、创建后真实上传、上传失败不关闭表单、附件元数据显示、下载、重试解析、替换附件，以及无附件时禁止提交审核。

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm --prefix ops-management-web test -- --run src/api/skill-knowledge-api.test.ts src/features/knowledge/KnowledgePage.test.tsx`

Expected: FAIL，因为 Web API 和附件交互尚不存在。

- [ ] **Step 3: 实现类型和 API**

新增 `KnowledgeAttachment`、`KnowledgeAttachmentUploadResult`、`uploadKnowledgeAttachment()`、`retryKnowledgeAttachment()`；使用 `FormData` 上传并通过 Web Crypto 计算文件 SHA-256，不手工设置 multipart Content-Type。

- [ ] **Step 4: 实现文件导入表单**

来源类型增加“上传文件”。选择后隐藏必填正文、显示白名单和大小限制；先创建 `uploaded_file` 草稿，再上传文件。任一步失败都保留对话框和错误信息，不显示成功假状态。

- [ ] **Step 5: 实现附件历史操作**

在现有版本行内增加紧凑附件列表，展示当前/历史、处理状态、大小、哈希和错误；提供下载、重试解析、上传或替换按钮。`uploaded_file` 没有当前 parsed 附件时不显示“提交审核”。

- [ ] **Step 6: 运行测试确认 GREEN**

Run: `npm --prefix ops-management-web test -- --run src/api/skill-knowledge-api.test.ts src/features/knowledge/KnowledgePage.test.tsx`

Expected: PASS。

### Task 5: 全量验证、浏览器 QA 和交付刷新

**Files:**
- Create: `docs/verification/2026-08-05-v9-knowledge-attachment-lifecycle.md`
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] **Step 1: Edge 与静态门禁**

Run: `deno test --allow-env supabase/functions/ops-glasses`

Run: `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`

Run: `npm run validate:supabase`

- [ ] **Step 2: Web 全量回归**

Run: `npm --prefix ops-management-web test -- --run`

Run: `npm --prefix ops-management-web run typecheck`

Run: `npm --prefix ops-management-web run build`

- [ ] **Step 3: 浏览器 QA**

在 `1440x900` 和 `390x844` 验证知识目录、文件导入、附件状态、下载/替换操作无溢出，控制台无错误或警告；保持既有 Product Design 风格和页面布局。

- [ ] **Step 4: 仓库回归与交付**

Run: `git diff --check`

Run: `npm run validate:v9-release`

Run: `npm run package:v9-delivery`

Run: `npm run validate:v9-delivery`

- [ ] **Step 5: 更新证据与记忆**

记录实际测试数量、浏览器证据、正式 ZIP/APK 哈希和仍需生产 Supabase 凭据验证的边界；只把当前有效信息写入 `agent_memory`。
