# V9 可配置现场工作流领域验证

验证日期：2026-08-01

## 验证范围

- 受控工作流节点白名单、秘密字段拒绝和稳定错误路径。
- 图结构、条件 DSL、有界重复、精确子流程/连接器引用和规范化执行包编译。
- 工单 `required | optional | none` 三态绑定、固定来源优先级、具体度和冲突解析。
- 现场应用、工作流版本、绑定规则、工单、分配、执行和步骤数据库合同。
- 发布版本不可变、分配单调序列、跨组织复合外键、RLS 和浏览器只读权限。

## 当前提交

- `b4fac89 feat: validate configurable workflow drafts`
- `cacc507 fix: harden workflow draft validation`
- `f20e2c0 feat: compile immutable workflow packages`
- `7e179d8 feat: resolve work order workflow bindings`
- `5f87b46 feat: add configurable workflow schema`

## 验证结果

| 验证 | 结果 | 当前证据 |
|---|---|---|
| 工作流领域目标测试 | 通过 | `12/12` |
| 工单绑定目标测试 | 通过 | `8/8` |
| Edge Function 全量测试 | 通过 | `58/58` |
| Edge Function 类型检查 | 通过 | `deno check .../index.ts` |
| Supabase 根验证链 | 通过 | 资产、管理、身份/设备、短期会话、工作流、AI brain、文本平衡全部通过 |
| 工作流 TypeScript 格式检查 | 通过 | 4 个领域/测试文件通过 `deno fmt --check` |
| 迁移静态合同 | 通过 | 8 张表、RLS、不可变触发器、序列、复合外键、索引和权限合同通过 |
| PostgreSQL 语法树解析 | 通过 | 临时使用 `pgsql-parser@18.1.2` 解析 90 条语句 |
| Git 空白检查 | 通过 | 无错误；仅有既有 Windows LF/CRLF 转换警告 |

执行命令：

```powershell
deno test --allow-env supabase/functions/ops-glasses/*.test.ts
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
npm run validate:supabase
deno fmt --check supabase/functions/ops-glasses/workflow-domain.ts supabase/functions/ops-glasses/workflow-domain.test.ts supabase/functions/ops-glasses/workflow-binding.ts supabase/functions/ops-glasses/workflow-binding.test.ts
node scripts/validate-configurable-workflows.mjs
git diff --check
```

## 已证明的行为

- 任意 HTTP/脚本节点、秘密形态配置键、不安全条件操作符和原型链字段无法通过草稿验证。
- 普通环路、悬空边、不可达节点、开始节点入边和结束节点出边无法编译。
- 等价编辑器草稿生成相同规范化内容摘要，编辑器坐标不进入执行包。
- 人工指定优先于受信外部映射、项目规则、资产/工单规则和组织默认规则。
- 同来源同具体度但模式或版本不同会返回冲突；系统不会静默任选。
- 跨组织、过期、未发布、非法模式、未知字段、来源伪装、重复谓词和畸形候选不会参与绑定。
- 数据库合同固定发布版本、组织作用域、分配序列、活跃分配唯一性和现场人员项目授权。

## 尚未验证

- 本机没有 Docker/Postgres，迁移尚未在真实 Supabase 或隔离数据库执行，RLS 行为也尚未做数据库级正负测试。
- 管理/设备工作流 API、发布签名服务、执行包拉取、游标补偿和审计写入尚未实现。
- 管理网页图形编辑器、模板预览、模拟、审核、发布和分配尚未实现。
- Android 包校验、原子缓存、本地状态机、证据门禁、恢复和自动推送尚未实现。
- SkillRuntime、知识、项目记忆、MVS 连接器、云端部署和 Air3 实机验收尚未完成。

因此，本报告只证明工作流领域和数据库合同在本地达到下一阶段入口条件，不代表 V9 工作流功能或市场产品已经完成。
