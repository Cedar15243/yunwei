# V9 工作流 HUD 与照片证据闭环验证

日期：2026-08-01

## 范围

- `MainActivity` 将真实工单列表、详情和 `required | optional | none` 三态入口映射到既有 `OperationDetail` HUD，不新增第二套页面或改变首页布局。
- 已验签工作流只声明实际接通的 `camera.photo`。录像、语音、AI 和专家能力仍不声明，未接通动作明确提示不可用。
- 工作流照片使用现有 Camera2，先写入应用私有 `task-evidence`，再登记 `WorkflowEvidenceReference`；持久化失败、状态冲突或拍照取消不会推进步骤。
- 设备 API 新增 `POST /device-sync/workflows/evidence`，仅接受短期设备会话、当前绑定执行实例和最大 5 MB 的 `image/jpeg`；服务端复核 Base64、字节数和 SHA-256，并使用服务端生成的私有路径。
- 服务端先以唯一 `file_path` 预留 `media_assets` 为 `uploading`，再使用确定性路径和 `upsert` 写入对象存储；相同大小与 SHA-256 的 `uploading | failed` 记录可恢复，摘要冲突返回 `409 workflow_evidence_conflict`，避免对象已上传但媒体记录尚未创建的崩溃窗口。
- 未解析证据由原子工作流快照充当持久化上传队列。取得真实 `media_assets` UUID 后补全快照并按原顺序回放延期步骤，不生成假上传或假完成状态。
- 上传运行期间的新触发会合并为一次后续运行，避免刚拍摄的证据等待下一次前台或网络事件。
- 实体返回键和语音返回使用独立导航策略：步骤返回工单详情，工单详情/执行方式返回工单列表，不会误执行“普通任务”等业务次操作。
- 返回首页、Activity 暂停或销毁会释放工作流拍照回调和 Camera2 所有权，避免下次拍照被旧状态阻塞。

## TDD 与自动化证据

先确认以下回归在修复前失败：

- 可选工作流的语音返回可能执行 `workflow_standard:*`。
- 上传运行期间的新 `request()` 被单飞状态吞掉。
- 返回首页未释放工作流拍照回调。
- 原生构建合同仍要求空能力注册表，无法识别真实接通的 `camera.photo`。

修复后执行：

```text
Android JVM: 351/351
failures=0
errors=0
skipped=0

Edge Function: 116/116
npm run validate:supabase: PASS
npm run validate:native-build: PASS
npm run validate:native-hud: PASS
deno fmt workflow-device.ts workflow-device.test.ts: PASS
deno check ops-glasses/index.ts: PASS
git diff --check: PASS（仅既有 LF/CRLF 提示）
```

## V9.0.6 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.evidence.audit
versionCode: 9006
versionName: 9.0.6-workflow-evidence-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (Android Debug certificate)
SHA256: D71487E3580A84883DC88EE84C8F3F273A0DF22FAEF322962C6EBCE901EF4595
path: output/air3-v9-workflow-evidence-audit-20260801/DingdangAI-v9.0.6-workflow-evidence-audit.apk
```

构建使用安全运行时、讯飞离线唤醒和本地 ASR 的实际交付源集；客户端 OPS/GPT/ASR/讯飞配置字段为空。12 个 DEX 的常见密钥哨兵命中为 0。讯飞原厂两个 ABI 的 `libAIKIT.so` 仍命中既有 PEM 风险，未修改供应商二进制。

2026-08-02 将该包并存安装到 Air3 后，发现安全运行时缺少受管后端授权时，照片自动发送会停留在“AI 分析中”而不进入错误态。V9.0.6 因该 P1 问题被淘汰，不再作为当前审计候选；修复和复测见 `docs/verification/2026-08-02-v9-ai-upload-failure-recovery.md`。debug 证书不能作为正式发布签名，构建仍保留既有 D8 API 34 工具链警告。

## 未完成边界

- 工作流迁移、Edge Function、签名密钥/公钥和管理 Web 尚未部署到真实云端。
- 在线轻通知、录像、语音、AI、专家和 MVS 连接器能力尚未接通；服务端不得提前声明这些能力。
- 正式发布仍需要供应商 SDK 风险处置、正式签名、Air3 全流程和生产云端验收。
