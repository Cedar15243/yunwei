# V9 项目指令全生命周期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有 HUD、专家协同和 V8 稳定包的前提下，为 V9 项目指令补齐查看、改版、停用、重新启用、审计删除和版本冲突恢复闭环。

**Architecture:** V9 网关继续以 `project_instruction_versions` 作为不可变版本账本，并新增严格状态迁移与无变化拒绝；删除只写入 `deleted` 墓碑版本，不物理删除历史。Android 从项目详情读取服务端最新版本，复用现有 `OperationDetail` HUD 展示治理入口，所有写操作生成 `ProjectGovernanceDraft` 并二次确认；遇到 `409` 时不重放旧草稿，而是刷新目标指令的权威版本后让用户重新选择。

**Tech Stack:** Java Android、JUnit、Python `unittest`、SQLite、V9 Python HTTPS 网关、ADB/AndroidX instrumentation。

---

### Task 1: 网关指令状态机

**Files:**
- Modify: `v9-ops-gateway/test_execution_context.py`
- Modify: `v9-ops-gateway/execution_context.py`
- Modify: `v9-ops-gateway/test_gateway.py`

- [x] 先写失败测试：新指令只能以 `active@v1` 创建；`active -> active/disabled/deleted`、`disabled -> disabled/active/deleted` 合法，其中同状态只允许内容真实变化；`deleted` 为终态。
- [x] 先写失败测试：相同状态、条件、动作和例外的无变化新版本返回 `project_instruction_no_change`。
- [x] 运行目标测试确认 RED，失败原因必须是状态机尚未实现。
- [x] 在 `put_project_instruction()` 中读取最新完整快照，校验状态迁移和内容变化后再追加版本。
- [x] HTTP 合同覆盖二次确认、幂等、改版、停用、启用、删除、无变化和过期版本 `409`。
- [x] 运行网关目标测试与全量测试确认 GREEN。

### Task 2: Android 权威指令模型与 HUD

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/ExecutionContextDeviceClientTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/ExecutionContextDeviceClient.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/features/operations/ExecutionContextHudPresenterManifestTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/features/operations/ExecutionContextHudPresenter.java`

- [x] 先写失败测试：`ProjectDetail` 暴露经过严格校验的最新指令列表和按 ID 查找能力。
- [x] 先写失败测试：项目详情中的每条指令可进入治理详情，详情显示版本、状态、条件与动作。
- [x] 先写失败测试：生效指令提供改版、停用、删除；停用指令提供改版、启用、删除；已删除指令只读。
- [x] 运行目标测试确认 RED。
- [x] 实现只读 `ProjectInstruction` 值对象、条件/例外深拷贝和受控 HUD action。
- [x] 运行目标测试确认 GREEN。

### Task 3: Android 二次确认与冲突恢复

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/governance/ProjectGovernanceDraftTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/governance/ProjectGovernanceDraft.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/MainActivityVoiceCommandTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`

- [x] 先写失败测试：改版保留 instruction ID、使用当前版本作为 `expectedVersion`，并生成新的 `active` 版本。
- [x] 先写失败测试：停用、启用和删除保留服务端动作/条件，只改变目标状态；确认页清楚显示当前版本和目标状态。
- [x] 先写失败测试：编辑等待态只接受完整项目规则或取消/返回，不把编辑文本发送给 AI。
- [x] 运行目标测试确认 RED。
- [x] 接入项目指令详情动作、编辑等待态、统一二次确认和动态成功提示。
- [x] `project_instruction_version_conflict` 时清除旧草稿并刷新目标指令；不得自动重试旧版本。
- [x] 运行目标测试确认 GREEN。

### Task 4: 自动化、构建与安全审计

**Files:**
- Update: `scripts/validate-native-hud-flow.mjs`
- Update: `docs/verification/2026-08-03-v9-project-instruction-lifecycle-air3.md`

- [x] 运行 V9 网关全量测试。
- [x] 使用 JDK 17、安全运行时、上一版讯飞 AIKit 和 Sherpa source set 运行 Android 全量 JVM 测试。
- [x] 运行 `validate:native-hud`、`validate:native-build`、`validate:supabase` 和 `git diff --check`。
- [x] 构建独立 `applicationId` 的 V9.0.28 主 APK 与 Test APK，验证 API 34、明文流量关闭、v2 签名和敏感信息零泄露。

### Task 5: 云端与 Air3 端到端验收

**Files:**
- Create: `air3-dingdang-expert-integrated-app/app/src/androidTest/java/com/codex/air3nativecamera/ManagedProjectInstructionLifecycleDeviceTest.java`
- Update: `docs/verification/2026-08-03-v9-project-instruction-lifecycle-air3.md`

- [x] 部署新的不可变 V9 网关 release，保留 previous 并验证 V9/专家双健康。
- [x] Air3 并存安装 V9.0.28，不覆盖 V8.0.48 和既有 V9 审计包。
- [x] 使用同一真实项目依次验证创建 v1、改版 v2、停用 v3、启用 v4、删除 v5。
- [x] 验证停用/删除后不进入 AI `ExecutionContext`，启用后重新进入。
- [x] 制造旧版本写入冲突，确认眼镜刷新最新版本且没有覆盖服务端新版本。
- [x] 核对 APK 哈希、UI 截图、前台进程、crash buffer 与退出原因。

### Task 6: 收口记录

**Files:**
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [x] 记录当前版本、release、测试计数、Air3 证据和剩余发布阻断。
- [x] 不把 Debug 审计包、未完成的本人声纹或未部署的 Supabase/管理 Web 表述为正式市场版本。
