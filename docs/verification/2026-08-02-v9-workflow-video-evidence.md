# V9.0.8 工作流短视频证据验证

日期：2026-08-02

## 实现范围

- 在既有 `WorkflowCapabilityRegistry` 中真实注册 `camera.video`，未接线的语音、AI、专家和连接器能力仍不声明。
- 复用现有 Camera2、MediaRecorder 和任务 HUD，不增加页面，不改变首页、专家协同或普通短视频布局与行为。
- 工作流录像限制为 Air3 当前验证边界 `1-15s`、H.264/MP4、2Mbps、30fps；服务端目录也将最短/最长配置上限收敛到 15 秒。
- 工作流 MP4 先写入应用私有 `task-evidence`，通过 `MediaMetadataRetriever` 读取完成文件的实际时长；时长无法验证或短于节点要求时明确失败并要求重录。
- 有效录像登记为 `WorkflowEvidenceReference(VIDEO)`，本地推进工作流；上传在后台队列执行，失败保留任务快照和 MP4，不显示假成功。
- 照片与视频共用可靠上传协调器，但分别校验扩展名、MIME、时长和大小：照片最大 5MB，视频最大 8MB。
- Edge Function 仅接受 `photo/image/jpeg` 或 `video/video/mp4`，复核 Base64、字节数、SHA-256 和视频时长，使用服务端派生的 `.jpg/.mp4` 私有路径并保留幂等恢复/冲突拒绝。
- 新迁移 `202608020001_workflow_video_evidence.sql` 为 `media_assets` 增加 `duration_seconds`；管理任务详情读取该字段。

## 自动化证据

```text
Android JVM: 356/356
failures=0
errors=0
skipped=0

Edge Function: 118/118
npm run validate:supabase: PASS
npm run validate:native-build: PASS
npm run validate:native-hud: PASS
deno check ops-glasses/index.ts: PASS
本轮 workflow-device/catalog 文件 deno fmt --check: PASS
git diff --check: PASS（仅既有 LF/CRLF 提示）
```

- Android 回归使用 JDK 17、讯飞离线唤醒源集和本地 Sherpa ASR 源集。
- 全目录 Deno 格式检查仍会命中既有 `management.ts` 等历史文件；本轮未整体格式化旧文件，避免无关大范围差异。

## V9.0.8 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.video.audit
versionCode: 9008
versionName: 9.0.8-workflow-video-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (Android Debug certificate)
DEX: 12
SHA256: 5004628284C7FB3156E35DDB00F2D595983711E3C2A6235854DE4774406B2E18
path: output/air3-v9-workflow-video-audit-20260802/DingdangAI-v9.0.8-workflow-video-audit.apk
```

- 安全运行时生成配置中的 OPS/GPT/ASR/讯飞客户端密钥均为空。
- 首次构建命令因 PowerShell 未给 Gradle `-P` 参数加引号而把包名误解析为任务；修正为显式参数后构建通过，代码未因此改动。
- D8 对 API 34 的既有工具链警告仍存在；该包为 Debug 签名，不是正式发布包。

## 实机状态

- 目标设备仍为 `YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- Air3 恢复 USB/ADB 后已成功并存安装 V9.0.8；设备端核对为 `9008 / 9.0.8-workflow-video-audit`。V8.0.48、V9.0.7 和 V9.0.8 均保留，未覆盖或卸载稳定包。
- 从真实“AI 能力中心 -> 短视频取证”入口启动 Camera2/MediaRecorder，15 秒硬上限自动结束并返回能力中心。应用私有目录生成 `scene-1785657878501.mp4`，大小 `4,667,255` 字节，SHA256 `5E6EF3E25722ED6AFBECA108F29411ED5B414C6D6977113061F8FAAD9B1601B0`。
- 对实机 MP4 容器的独立解析结果为 `14.9638s`，仅包含 `vide` 轨道，不包含 `soun` 轨道；Camera2 日志为 `CONNECT -> DISCONNECT`，符合录像结束释放相机且不新增录音抢占的设计。
- 录像结束后实体确认键可重新启动 `AudioRecord`；日志记录 `audio_record_started`，随后静音自动停止。安全运行时无后端凭据时明确返回 `managed_backend_credential_missing`，未伪装为转写成功。
- 强制停止和冷启动后 MP4 仍以相同大小保留；V8.0.48 可独立冷启动，V9.0.8 再次冷启动正常。实机检查期间 crash buffer 为空，未发现目标包 ANR。
- 当前源码按相同包名、版本和应用名重新构建后，SHA256 与已安装审计 APK 完全一致；v2 Debug 签名、12 个 DEX、`usesCleartextTraffic=false` 和客户端敏感配置为空均再次通过。
- 专家协同健康端点在实机验收后仍返回 HTTP 200，内容为 `{"ok":true,"service":"expert-collab"}`；本轮未修改或部署专家服务。

## 剩余边界

- `202608020001` 及前置 V9 迁移尚未在真实 Supabase 执行；真实对象存储、RLS、并发幂等和成功上传仍需隔离云端正负验证。
- 生产签名工作流、真实工单分配和受管设备会话尚未投放，因此本轮只能证明 V9.0.8 的真实 Camera2/MediaRecorder、普通短视频回归、无音轨、资源释放和冷启动留存，不能用本地假分配替代 `task-evidence` 工作流录像、步骤推进和上传端到端验收。
- 下一次实机闭环必须由真实受管设备会话拉取已签名、当前节点为 `video_capture` 的工单分配，再验证最短/最长时长、`WorkflowEvidenceReference(VIDEO)`、重启恢复、未授权/弱网上传失败和服务端资产 UUID 回执。
