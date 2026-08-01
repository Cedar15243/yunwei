# V9 工作流工单摘要与执行协调器验证

日期：2026-08-01

## 范围

- 设备分配接口关联真实内部 `work_orders`，只下发工单号、标题、说明、客户、类型、资产、优先级、风险、状态和时间字段，不透传绑定证据、连接器字段或凭据。
- Android 分配缓存原子保存工单摘要；旧缓存没有摘要时显示“工单详情待同步”，不生成假工单内容。
- `WorkflowExecutionCoordinator` 提供真实任务列表、工单详情、三态入口、不可变执行 ID、固定 HUD 步骤和后台 outbox 发送。
- 现场证据必须先持久化。UI 临时声明的照片不能通过门禁；本地证据未取得远端资产 UUID 时允许离线推进，但不生成虚假的服务端完成事件。
- 延期步骤、输入输出、精确本地证据 ID、下一节点、运行快照、时间和幂等键进入同一原子快照。进程重启后，上传回调补入真实资产 UUID，并按原节点顺序生成完成事件。
- 后续无证据节点也排在未上传证据步骤之后；同一任务的队首事件退避时保持顺序，不越过发送，同时其他工单仍可同步。
- 后台发送器按分配单飞；发送或确认前重新加载最新原子快照，避免旧启动事件覆盖并发保存的新证据、远端资产 ID 或步骤状态。
- 生产 `WorkflowPackageSnapshotAccess` 复用已验签 `WorkflowPackageStore`，测试与 APK 使用同一快照合同。

## TDD 证据

先确认新增测试分别因缺少 `WorkflowDeferredStep`、远端证据补全、协调器推进/回放、生产快照适配器和队首阻塞行为而失败。最小实现后执行：

```text
Android JVM: 339/339
failures=0
errors=0
skipped=0

Edge Function: 112/112
npm run validate:supabase: PASS
npm run validate:native-build: PASS
npm run validate:native-hud: PASS
deno fmt workflow-device.ts workflow-device.test.ts: PASS
git diff --check: PASS
```

关键回归覆盖：

- 工单白名单 DTO 解析和分配缓存重启恢复。
- 原子 `delivered -> verified -> ready -> execution_start` 检查点。
- 本地照片离线推进、延期步骤重启恢复和远端 UUID 不可变。
- 照片步骤与后续完成节点按 `photo -> complete` 回放。
- 未落盘证据声明不能推进，空/冲突资产 UUID 被拒绝。
- 退避中的同任务队首事件阻止后续事件越序，其他任务不被阻塞。
- 启动事件发送期间并发新增证据和步骤不会被旧快照覆盖。

## V9.0.5 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.execution.audit
versionCode: 9005
versionName: 9.0.5-workflow-execution-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (debug certificate)
SHA256: 28CC1F4ED07B7A43F6DD49CD7DDF43E6288870E0BA9DDC1A27BA0BDFB88CC6DC
path: output/air3-v9-workflow-execution-audit-20260801/DingdangAI-v9.0.5-workflow-execution-audit.apk
```

该包未安装到 Air3；debug 证书不能作为正式发布签名。构建仍出现既有 D8 API 34 工具链警告，已保留为发布前技术债。

## 未完成边界

- 执行协调器尚未接入 `MainActivity` 的维修任务入口、现有固定 HUD 和 Camera2 回调。
- 真实媒体上传器尚未把服务端资产 UUID 回调到协调器。
- 工作流数据库迁移、Edge Function、签名密钥和管理 Web 尚未部署到真实云端。
- 当前没有 V9 Air3 安装、UI、弱网、温升、耗电、crash/ANR 或正式签名证据。
