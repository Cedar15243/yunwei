# V9 项目记忆与任务会话隔离验证

验证日期：2026-08-05

## 目标

- 历史项目导航只浏览记录，不自动恢复 V9 活跃任务。
- 受管项目恢复必须从项目记录入口发起，经过二次确认并刷新服务端状态。
- 异步任务结束回执只完成请求对应的旧任务，不影响已经创建的当前新任务。
- 新任务不继承旧任务聊天消息，但继续使用同一项目内人工确认的持久记忆。
- 网关不得接受与 `localTaskId` 不一致的聊天会话 ID。

## 实现边界

- Android `MainActivity.resumeTaskFromLocalProjectNavigation(...)` 和 `switchProjectChat(...)` 对 V9 受管任务只执行历史浏览；非受管旧版继续保留原有本地恢复行为。
- `TaskSessionManager.complete(taskId)` 按精确任务 ID 收口异步结束结果。
- 网关显式会话 ID 必须等于 `localTaskId`；`_` 仅用于由网关解析为当前任务 ID。错配在读取历史消息和调用模型前返回 `409 task_session_mismatch`。

## 自动化证据

- Android JVM：`93` 个报告、`548/548`，0 失败、0 错误、0 跳过；`21` 个 Gradle 任务通过 `--rerun-tasks` 全部实际执行。
- V9 网关 Python：`155/155`，0 失败、0 错误。
- 管理 Web：`25` 个测试文件、`122/122`；TypeScript 检查与生产构建通过。
- Supabase Edge Function：`240/240`；Deno `index.ts` 类型检查通过。

关键回归覆盖：

- `MainActivityProjectBindingTest` 验证 V9 历史浏览不触发本地恢复，以及异步旧任务回执不影响当前新任务。
- `GatewayServiceTest.test_rejects_a_chat_session_id_from_a_different_task` 验证错配会话返回冲突且不会调用模型。
- `GatewayServiceTest.test_new_task_keeps_project_memory_without_reusing_closed_task_messages` 验证新任务 `recent_messages` 为空，同时保留人工确认的项目记忆。

## 结论

当前代码证据支持“项目经验持续积累、任务对话严格隔离”的 V9 记忆边界。本验证不替代生产 Supabase 部署、真实账号/设备授权和 Air3 受管服务联机验收；外部条件缺失时仍必须失败关闭。
