# V9.0.9 工作流语音填写验证

日期：2026-08-02

## 实现范围

- 在既有 `WorkflowCapabilityRegistry` 中真实注册 `audio.voice_input`，设备仅在 handler 接通后声明该能力。
- 新增 `WorkflowVoiceInputPlan`，严格校验 `voice_input` 节点、`fieldKey`、布尔 `required` 和整数 `maxDurationSeconds`。
- 服务端目录仍允许配置 1-300 秒；Air3 当前真实录音上限为 30 秒，因此眼镜明确拒绝超过 30 秒的节点，不截断、不假装完成。
- 新增 `WorkflowVoiceInputSession`，把最终转写绑定到当前 assignment、execution、node 和 attempt，只允许完成一次。
- ASR 最终结果只写入 `WorkflowStepContext.putField(fieldKey, transcript)` 并调用工作流协调器推进，不进入菜单命令或普通 AI 对话。
- ASR 空结果、含糊、网络/凭据错误、结束超时、麦克风切换失败均停留当前步骤并明确提示；必填步骤不会假推进。
- 相机、录像、专家页、其他录音或已有工作流语音会话占用时拒绝启动；返回、首页、暂停、销毁和运行时重置都会清理会话与回调。
- 复用现有 HUD、AudioRecord 和 ASR 链路，没有新增页面或改变首页、任务页、专家协同布局。

## TDD 与自动化证据

```text
WorkflowVoiceInputPlanTest: 先因类不存在 RED，随后 GREEN
WorkflowVoiceInputSessionTest: 先因类不存在 RED，随后 GREEN
原生工作流接线合同: 先因 audio.voice_input handler 不存在 RED，随后 GREEN

Android JVM: 364/364
failures=0
errors=0
skipped=0

Edge Function: 118/118
npm run validate:supabase: PASS
npm run validate:native-build: PASS
npm run validate:native-hud: PASS
deno check ops-glasses/index.ts: PASS
git diff --check: PASS（仅既有 LF/CRLF 提示）
```

- Android 全量回归使用 Temurin JDK `17.0.20`、讯飞离线唤醒源集和本地 Sherpa ASR 源集。
- 首次全量命令误用 Unity JDK 11，10 个 Ed25519 测试因算法不可用失败；切回项目固定 JDK 17 后 `364/364` 通过，不能将该环境失败当作产品回归。
- 最终复核首次命令从 Android 子目录错误解析根目录 JDK，随后一次未启用 `secureRuntime` 而触发 `OPS_GLASSES_API_KEY is required`；修正为根目录绝对 JDK 路径和安全运行时后，使用 `--rerun-tasks` 强制新鲜执行 `364/364`，不是复用 Gradle 缓存。
- 原生 HUD 旧合同只识别离线唤醒不激活普通任务页；合同已扩展为离线唤醒与工作流语音都不得激活普通 AI 任务工作区。

## V9.0.9 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.voice.audit
versionCode: 9009
versionName: 9.0.9-workflow-voice-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (Android Debug certificate)
DEX: 12
SHA256: BBB4D5AA90B27C5DCB5F6827A6BC58A9D4615150CBC63CCB67F00495E5318AA2
path: output/air3-v9-workflow-voice-audit-20260802/DingdangAI-v9.0.9-workflow-voice-audit.apk
```

- 安全运行时生成配置中的 OPS、GPT、ASR 和讯飞客户端密钥均为空。
- D8 对 API 34 的既有工具链警告仍存在；该包为 Debug 签名，不是正式发布包。

## Air3 实机证据

- 目标设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- V9.0.9 于 2026-08-02 16:55:01 并存安装，设备端核对为 `9009 / 9.0.9-workflow-voice-audit`。
- V8.0.48、V9.0.7、V9.0.8 和 V9.0.9 均保留，未覆盖或卸载稳定包。
- 普通语音入口真实启动 `AudioRecord` 和实时 ASR，HUD 显示“正在监听/正在识别现场描述”；安全运行时缺少受管后端凭据时明确返回 `managed_backend_credential_missing`，没有假转写。
- 最终复核时，音频策略显示目标 UID `10108` 的活动 `AudioRecord` 会话；错误返回后 `Inputs (0)`，麦克风完全释放，进程继续存活。
- V9.0.9 再次强制停止后冷启动成功，PID 从 `23214` 变为 `23820`；V8.0.48 强制停止后以新 PID `24296` 冷启动，随后 V9.0.9 以 PID `23986` 恢复前台。
- 从设备拉取已安装 `base.apk` 后计算 SHA-256，与本地审计 APK 均为 `BBB4D5AA90B27C5DCB5F6827A6BC58A9D4615150CBC63CCB67F00495E5318AA2`。
- crash buffer 为空；退出记录只有测试主动 `force-stop` 的 `USER REQUESTED`，未发现目标包 crash 或 ANR。
- 专家协同健康端点仍返回 HTTP 200：`{"ok":true,"service":"expert-collab"}`；本轮未修改或部署专家服务与网页。

## 未完成边界

- 当前设备没有真实受管短期会话、签名工作流版本和当前节点为 `voice_input` 的工单分配，因此不能宣称完成工作流语音的 Air3 端到端验收。
- 不使用本地假分配替代以下证据：字段写入、步骤推进、步骤审计事件、重启恢复、弱网/未授权失败和服务端回执。
- 下一次真实验收需由管理端发布不超过 30 秒的签名 `voice_input` 节点并绑定工单，验证最终转写只进入配置字段、不进入 AI/菜单、失败停留原步骤和成功推进下一节点。
- `202608010001`、`202608010002`、`202608020001` 及前置迁移仍未在隔离 Supabase 项目真实执行；本地自动化通过不能表述为云端已交付。
