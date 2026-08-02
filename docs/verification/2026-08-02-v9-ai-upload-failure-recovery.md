# V9.0.7 AI 上传失败恢复与 Air3 验证

日期：2026-08-02

## 实机发现

- V9.0.6 在安全运行时未投放受管后端授权时，照片自动发送先进入 `AI_PENDING`，随后图片上传立即失败。
- 旧失败回调只更新附件文字，没有退出 `AI_PENDING`；GPT 请求尚未创建，因此 90 秒流式看门狗也不会启动，页面会无限停留在“AI 分析中”。
- 照片已可靠保存在应用私有 `task-evidence`，进程没有 crash/ANR，但错误状态不收敛，违反 V9 的失败关闭与可恢复要求。

## 修复

- 只有“自动发送待处理”且状态已进入 `AI_PENDING` 时，图片上传失败才终止本轮 AI 请求；普通草稿上传失败仍保留在草稿页。
- 错误页明确显示请求阶段、网络/授权状态和恢复操作，例如：`阶段：照片上传阶段；状态：后端未授权`。
- 当前照片和描述继续保留；语音“重试”会复位上传失败状态，允许重新上传，不要求重新拍照。
- 原生合同校验失败回调已真实接入 `failAssistantStreamingMessage(error, null, "照片上传阶段")`，避免只增加辅助函数而漏接运行时。

## 自动化证据

```text
Android JVM: 351/351
failures=0
errors=0
skipped=0

Edge Function: 116/116
npm run validate:supabase: PASS
npm run validate:native-build: PASS
npm run validate:native-hud: PASS
deno fmt --check workflow-device.ts workflow-device.test.ts: PASS
deno check ops-glasses/index.ts: PASS
git diff --check: PASS（仅既有 LF/CRLF 提示）
```

Android 回归使用 JDK 17 和完整讯飞离线唤醒、本地 ASR 源集。Unity 自带 JDK 11 不提供测试所需 Ed25519 算法，不能作为 V9 工作流测试运行时。

## V9.0.7 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.ai.recovery.audit
versionCode: 9007
versionName: 9.0.7-ai-recovery-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (Android Debug certificate)
SHA256: E79A52F9263F4343F6491B5DB962007B696B7E6E43FBC9105ECCC4BE375479FB
path: output/air3-v9-ai-recovery-audit-20260802/DingdangAI-v9.0.7-ai-recovery-audit.apk
```

- APK 含 12 个 DEX，常见客户端密钥哨兵命中 `0`。
- V8 稳定包、V9.0.6 和 V9.0.7 在 Air3 上并存；未覆盖或卸载稳定包。
- 构建仍为 Debug 签名，并保留既有 D8 API 34 工具链警告；不能作为正式发布包。

## Air3 实机证据

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- 冷启动成功，V9.0.7 首页保持既有 HUD 风格；权限授权后 Camera2 真实预览和拍摄成功。
- 实拍 JPEG：`221145` 字节，SHA-256 `45037e25d2005712bb2e39325e3fcc606a3e0fc77286d4f8236c1d198c2d0ee9`。
- 30 秒仅图发送触发后，后端未授权立即进入错误页，不再停留分析 HUD；页面显示照片、失败阶段、授权状态和“重试/重新拍摄”。
- 强制停止并冷启动后回到待命首页，不自动恢复旧任务；上述 JPEG 仍以相同 SHA-256 保存在私有目录。
- 本轮应用进程未出现 crash/ANR。截图与 UI 树保存在 `output/air3-v9-ai-recovery-audit-20260802/device-qa/`。

## 剩余边界

- 工作流迁移、Edge Function、生产签名密钥/公钥和管理 Web 尚未部署到真实云端，本轮只验证未授权失败关闭，未验证真实上传成功。
- 在线轻通知、录像、语音、AI、专家和 MVS 连接器仍未接通为工作流能力，服务端不得提前声明。
- 正式发布仍需供应商 SDK 风险处置、正式签名、真实云端正负流程、弱网、长时性能、温升和耗电验收。
