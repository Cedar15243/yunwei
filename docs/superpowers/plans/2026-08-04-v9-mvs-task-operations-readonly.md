# V9 MVS 节点可执行操作只读接入实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过我方 V9 HTTPS 网关读取 MVS 当前节点支持的操作，在眼镜现有工单 HUD 中只读展示，并保持所有表单提交和流程推进能力关闭。

**Architecture:** 网关先读取绑定工程师名下的工单详情，以服务端返回的 `definitionId` 和 `nodeCode` 请求 `/prod-api/mvs/order/task/operations`，拒绝客户端自报流程上下文。网关仅保留明确白名单操作并隐藏未知项；Android 使用短期设备会话读取类型化 DTO，只显示说明文本，不生成任何 MVS 写操作按钮。

**Tech Stack:** Python `unittest`、V9 Python 网关、原生 Android Java、JUnit、现有 `OperationDetail` HUD。

---

### Task 1: 网关连接器红测与最小实现

**Files:**
- Modify: `v9-ops-gateway/test_mvs_work_order.py`
- Modify: `v9-ops-gateway/mvs_work_order.py`

- [ ] 增加失败测试：读取当前工单详情后，只使用其 `definitionId` 与 `nodeCode` 请求固定 `GET /prod-api/mvs/order/task/operations`。
- [ ] 增加失败测试：只保留 `skip`、`transfer`、`reject`、`back-to-node` 白名单，隐藏未知操作和全部额外字段。
- [ ] 增加失败测试：缺失流程上下文、重复操作码、非法返回结构均失败关闭。
- [ ] 运行目标测试，确认因 `get_task_operations()` 不存在而失败。
- [ ] 实现最小连接器和严格 DTO 清洗，再运行目标测试至通过。

### Task 2: V9 设备资源路由

**Files:**
- Modify: `v9-ops-gateway/test_mvs_gateway.py`
- Modify: `v9-ops-gateway/gateway.py`

- [ ] 增加失败测试：短期设备会话可读取 `task_operations`，客户端查询参数不能覆盖权威流程上下文。
- [ ] 增加失败测试：未知资源仍返回 `mvs_resource_not_allowed`。
- [ ] 在既有单资源端点加入 `task_operations`，返回 `Cache-Control: no-store`，不增加写路由。
- [ ] 运行 MVS 网关测试至通过。

### Task 3: Android 类型化读取与 HUD 只读展示

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/MvsWorkOrderDeviceClientTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/MvsWorkOrderDeviceClient.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/workflow/MvsWorkOrderHudPresenterTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/workflow/MvsWorkOrderHudPresenter.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [ ] 增加失败测试：工单 DTO 安全解析 `nodeCode`，客户端只接受网关的类型化操作列表。
- [ ] 增加失败测试：HUD 显示“当前节点可执行操作”，所有列表项 action、主 action 和次 action 均不含 `submit`、`skip`、`transfer` 或 `reject` 写命令。
- [ ] 实现 `TaskOperation` / `TaskOperations` 只读模型与资源读取。
- [ ] 在工单详情增加按需入口，在现有 HUD 中显示服务端声明项和“尚未启用办理”的明确说明。
- [ ] 保持现有 Camera2、AI、语音、专家协同、签到签退和首页布局不变。

### Task 4: 回归与记录

**Files:**
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`
- Create: `docs/verification/2026-08-04-v9-mvs-task-operations-readonly.md`

- [ ] 运行 MVS Python 目标测试与 V9 网关全量测试。
- [ ] 使用便携 JDK 17 运行 Android 目标测试和全量 JVM 测试。
- [ ] 运行 `npm run validate:native-hud`、`npm run validate:native-build`、`npm run validate:v9-release` 和 `git diff --check`。
- [ ] 记录未完成的真实 MVS 联调、写回 DTO、状态字典、Air3 实机和正式签名阻断，不把本地合同测试描述为线上可用。
