# V9.0.23 任务恢复提示 Air3 验证

## 范围

- 修复显式恢复空任务后错误显示“正在等待 AI 回复”的问题。
- 恢复后不发起虚假的 AI 请求，直接进入原任务工作区并提示“任务已恢复，请继续描述现场情况。”。
- 不修改现有首页、HUD 布局、专家协同、Camera2、语音主链路或 V8 稳定包。
- 模型保持：主 AI/视觉 `qwen3-vl-plus`，实时 ASR `fun-asr-realtime`，声纹为讯飞新版 `s1aa729d0`，小叮当唤醒保留上一版讯飞 AIKit。

## 产物

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.taskrestore.prompt.audit`
- 版本：`9023 / 9.0.23-task-restore-prompt-audit`
- APK：`output/air3-v9-task-restore-prompt-audit-20260803/air3-v9.0.23-task-restore-prompt-audit-debug.apk`
- SHA256：`0AFD64828AA3C3269C96A620D91C181E4A2B45E5780632E1AE01A2ACCB76AE13`
- targetSdk/compileSdk：`34 / 34`
- 签名：APK Signature Scheme v2 Debug 签名有效；不是正式发布签名。

## 自动化验证

- TDD RED：`npm run validate:native-hud` 按预期失败，错误为 `a restored empty task must show a truthful continuation prompt`。
- TDD GREEN：最小实现后 `npm run validate:native-hud` 通过。
- `npm run validate:native-build` 通过。
- Android JVM：`78` 个套件、`463/463`，失败 `0`、错误 `0`、跳过 `0`。
- `assembleDebug` 与 `assembleDebugAndroidTest` 通过；既有 API 34/D8 工具链警告未阻断构建。
- 安全生成配置：`SECURE_RUNTIME=true`、`DIRECT_GPT_ENABLED=false`；APK 中 AI、ASR 和讯飞长期密钥字段为空。
- APK 解包扫描：OpenClaw `0`、声纹服务 ID `s1aa729d0` `0`、五项本地敏感值命中 `0`。

## 云端核对

- `dingdang-v9-gateway.service`：`active`。
- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T115438Z-recent-messages-r2`。
- 运行模型：`V9_AI_MODEL=qwen3-vl-plus`、`V9_ASR_MODEL=fun-asr-realtime`。
- 声纹接口：`https://api.xf-yun.com/v1/private/s1aa729d0`。
- `/etc/dingdang-v9-gateway.env` 与 `/etc/dingdang-v9-voiceprint.env` 均为 `0600 root:root`。
- V9 网关与现有专家协同健康端点均为 HTTP `200`。

## Air3 实机验证

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- V9.0.23 并存安装成功；两份 ADB 私有投放文件位于应用私有目录，权限均为 `0600`，公共中转副本已删除，宿主 ignored 文件保留。
- 真实网关仪器测试 `ManagedTaskRestoreDeviceTest`：`OK (1 test)`；取消不激活任务，确认前重新读取服务端状态，确认后恢复精确项目和任务。
- 用户页面链路：三道杠菜单 -> 项目记忆 -> 项目详情 -> 进行中任务 -> 二次确认 -> 确认恢复。
- 确认后真实显示“任务已恢复，请继续描述现场情况。”，没有显示虚假的等待 AI 状态。
- 返回首页后回到 `standby`；V8.0.48、V9.0.22、V9.0.23 均可独立冷启动，未覆盖稳定包。
- crash buffer 为空；进程退出记录仅有仪器测试结束导致的 `USER REQUESTED / FORCE STOP`，未发现 crash 或 ANR。

## 证据

- `output/air3-v9-task-restore-prompt-audit-20260803/v923-confirm-restore.png`
- `output/air3-v9-task-restore-prompt-audit-20260803/v923-restored-prompt.png`
- `output/air3-v9-task-restore-prompt-audit-20260803/v923-final-home.png`

## 剩余边界

- 本阶段只证明显式恢复及真实提示闭环，不代表整个 V9 已达到正式发布标准。
- 任务结束摘要、项目记忆修订、真实 Skill/知识生产部署、本人声纹三段录入与独立 `1:1` 验证、弱网和长时性能仍需继续完成。
