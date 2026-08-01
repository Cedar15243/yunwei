# V9 工作流管理与设备 API 验证

验证日期：2026-08-01

## 验证范围

- 现场应用与工作流定义的组织级管理路由。
- 草稿保存前校验、服务端复验、规范化编译和 Ed25519 签名发布。
- 不可变版本读取和工单 `assigned | none | conflict` 确定性解析。
- 工作流发布、分配、认领、状态上报、执行启动和步骤审计事务 RPC。
- 设备短期会话、分配游标、签名执行包白名单响应、兼容性检查和送达状态。
- 执行启动、步骤状态、证据 UUID、身份推导、幂等和终态拒绝。

## 当前提交

- `6da2320 feat: add workflow management route contract`
- `c04affd feat: manage validated workflow drafts`
- `9c98064 feat: publish signed workflow versions`
- `3685f86 feat: add workflow API transactions`
- `3adc703 feat: resolve and assign work order workflows`
- `205d8ac feat: deliver signed workflows to devices`
- `2818862 feat: persist workflow execution audit`

## 接口白名单

管理接口：

```text
GET  /management/field-apps
POST /management/field-apps
GET  /management/field-apps/:fieldAppId/workflows
POST /management/field-apps/:fieldAppId/workflows
GET  /management/workflows/:workflowId
PUT  /management/workflows/:workflowId/draft
POST /management/workflows/:workflowId/validate
POST /management/workflows/:workflowId/publish
GET  /management/workflows/:workflowId/versions
GET  /management/workflow-versions/:versionId
POST /management/work-orders/:workOrderId/resolve-workflow
```

设备接口：

```text
GET  /device-sync/workflows/assignments
GET  /device-sync/workflows/assignments/:assignmentId/package
POST /device-sync/workflows/assignments/:assignmentId/status
POST /device-sync/workflows/executions
POST /device-sync/workflows/executions/:executionId/steps
```

服务角色事务 RPC：

```text
publish_workflow_version
apply_work_order_workflow_resolution
claim_workflow_assignment
report_workflow_assignment_status
start_workflow_execution
append_workflow_step_execution
```

六个 RPC 均撤销 `public`、`anon` 和 `authenticated` 执行权限，只允许 `service_role` 通过 Edge Function 调用。

## 验证结果

| 验证 | 结果 | 当前证据 |
|---|---|---|
| 工作流设备目标测试 | 通过 | `14/14` |
| 工作流管理目标测试 | 通过 | `21/21` |
| 工作流领域与绑定测试 | 通过 | `20/20` |
| 签名测试 | 通过 | `3/3` |
| Edge Function 全量测试 | 通过 | `96/96` |
| Edge Function 入口类型检查 | 通过 | `deno check .../index.ts` |
| Supabase 根验证链 | 通过 | 资产、管理、身份/设备、短期会话、工作流领域、事务和 AI brain 合同全部通过 |
| 本轮工作流文件格式检查 | 通过 | 管理、设备及对应测试4个文件通过 `deno fmt --check` |
| Edge Function 全目录格式检查 | 未通过，既有偏差 | 20个文件中10个旧文件不符合当前格式；未格式化大体量旧入口以避免无关改动 |
| Git 空白检查 | 通过 | 无空白错误；仅有既有 Windows LF/CRLF 提示 |

执行命令：

```powershell
deno test --allow-env supabase/functions/ops-glasses/*.test.ts
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
npm run validate:supabase
deno fmt --check supabase/functions/ops-glasses/workflow-device.ts supabase/functions/ops-glasses/workflow-device.test.ts supabase/functions/ops-glasses/workflow-management.ts supabase/functions/ops-glasses/workflow-management.test.ts
deno fmt --check supabase/functions/ops-glasses/*.ts
git diff --check
```

## 已证明的行为

- 无 Bearer 短期设备会话、会话失效或设备未绑定时，设备工作流接口拒绝访问。
- 分配游标最多返回100条白名单元数据，不返回执行包、连接器字段或数据库内部字段。
- 执行包拉取前认领设备，并校验 App 版本、DSL 版本、能力清单、数据库摘要和包内摘要。
- `none` 分配不返回执行包，设备不能自行上报 `revoked`，分配状态不能后退。
- 工作流发布需要固定确认词、理由、最低 V9 版本和可用服务端签名密钥。
- 工单绑定只使用服务端组织范围内的工单事实和已发布规则；客户端组织、人员和版本输入不参与解析。
- 执行启动只向事务 RPC 传递短期会话推导的人员与设备身份，以及分配、项目、任务、开始节点、快照和幂等键。
- 步骤上报拒绝非法状态、非对象输入/输出/转移、重复或非法证据 UUID 和失败无原因。
- 步骤响应严格白名单，不返回组织、操作者、设备或数据库内部字段。
- 签名包中的节点、合法下一节点、证据任务归属、当前节点、执行终态和状态迁移由数据库事务再次校验。
- 已完成或取消的执行不能继续追加步骤，数据库错误稳定映射为可恢复的 `409` 错误。
- 重复幂等键由事务返回原有发布、分配、执行或步骤结果，不重复创建业务对象。

## 尚未验证

- `202607310002` 至 `202607310005`、`202608010001` 和 `202608010002` 尚未在真实 Postgres/Supabase 执行。
- RLS、复合外键、触发器、事务并发和 service role 权限尚无真实数据库正负测试证据。
- 生产 `WORKFLOW_SIGNING_PRIVATE_KEY`、密钥轮换、签名公钥投放和真实签名包尚未验证。
- 在线推送控制通道、断线游标补偿时延和中国网络环境后台存活尚未验证。
- 管理网页图形编排、固定 HUD 预览、模拟、审核、发布和分配操作尚未实现。
- Android 执行包验签、原子缓存、本地状态机、证据门禁、outbox 恢复和 HUD 映射尚未实现。
- MVS 连接器、云端部署、真实外部回写和 Air3 实机端到端验收尚未完成。

因此，本报告只证明工作流管理和设备 API 在本地代码与静态数据库合同层达到下一阶段入口条件，不代表工作流已经部署，也不代表 V9 产品已经完成。
