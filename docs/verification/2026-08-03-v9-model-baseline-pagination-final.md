# V9 模型基线与真实在线长回复最终验证

## 固定模型合同

- 主 AI/视觉只使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 只使用上一版 `fun-asr-realtime`，本地 Sherpa 仅作断网或云端失败回退。
- “小叮当”唤醒继续使用上一版讯飞 AIKit。
- 声纹只使用新申请的讯飞“声纹识别（新）”`s1aa729d0`。
- V9 不使用 OpenClaw、GPT 5.5/5.6、DeepSeek、Claude、Qwen Max 或其他新增模型。
- 供应商长期密钥保留在本地忽略文件和服务器 root-only 环境文件，不进入 APK、Git 或日志。

## 本轮代码收口

- 修正 Android 工作流交付验证合同，使 AI 错误阶段先归一化，再进入可恢复错误提示。
- 删除 V9 Android 运行代码中的历史 `gpt-4.1-mini` 自动回退；直连模型缺失时明确返回 `DIRECT_GPT_MODEL missing`。
- 保留共享工程历史非安全构建的既有 Gradle 默认值，避免破坏 V8；V9 包名强制 `secureRuntime=true`，不会进入该路径。
- 修复长回复分页器生成纯换行或纯空白 HUD 页的问题，不改原始 AI 回复、任务记录或项目记忆。
- 加固真实在线长回复实机用例，使等待条件同时绑定活动页、页码和期望正文，避免旧页码或占位正文提前放行。

## 云端运行值

- current：`/opt/dingdang-v9-gateway/releases/20260803T200500Z-multiline-user-text`。
- previous：`/opt/dingdang-v9-gateway/releases/20260803T172503Z-project-instruction-lifecycle`。
- `/etc/dingdang-v9-gateway.env`：`0600 root:root`。
- `/etc/dingdang-v9-voiceprint.env`：`0600 root:root`。
- `V9_AI_MODEL=qwen3-vl-plus`。
- `V9_ASR_MODEL=fun-asr-realtime`。
- `V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0`。
- `dingdang-v9-gateway.service`：`active`。
- V9 公网 `/v9-ops/health` 与现有专家 `/health`：HTTP `200`。

## V9.0.31 审计包

- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9.online.longreply.e2e.audit`。
- versionCode：`9031`。
- versionName：`9.0.31-online-long-reply-e2e-audit`。
- 主 APK SHA256：`2579DD276B88F36E7EF2D1F6A12D90B63FC618D21CCB6A22AFA52035BDE9A730`。
- AndroidTest APK SHA256：`8EFC7ECD7ADC288CC6EC0C275DE2A86FF93A420BAED0917A905C9E32114EE368`。
- targetSdk：`34`；`usesCleartextTraffic=false`。
- APK Signature Scheme v2 Debug 签名有效，只能作为审计包，不能作为市场发布包。

## 自动化结果

- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过，包含禁止 Android 运行代码携带历史直连模型回退的合同。
- Android JVM：`79` 份报告，`476/476`，失败 `0`、错误 `0`、跳过 `0`。
- V9 网关：`109/109`。
- `assembleDebug`、`assembleDebugAndroidTest`：`59` 个任务实际执行，构建成功。
- 第一次全量 Android 验证把工作树根目录多退了一层，JDK/SDK 路径解析失败；修正为真实工作树路径后完整回归通过，属于验证参数问题，不是产品回归。
- D8 仍提示既有 API 34 工具链兼容警告；当前构建不阻断，正式发布前仍需升级工具链并独立回归。

## APK 安全扫描

- 对本机 `7` 项唯一受管凭据精确值扫描：全部 `0` 命中。
- OpenClaw、`qwen3-vl-plus`、`s1aa729d0`、`gpt-4.1-mini`、GPT 5.5/5.6、DeepSeek、Claude、Qwen Max：全部 `0` 命中。
- `fun-asr-realtime`：`1` 次，仅为受管 ASR 协议常量。
- 两份 Debug 私有投放 JSON 和本地供应商密钥文件均保留且被 Git 忽略；未删除、未轮换、未写入 APK。

## Air3 真实在线结果

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- `ManagedOnlineLongReplyDeviceTest`：`OK (1 test)`。
- 最终重建 APK 已重新安装；设备 `base.apk` SHA256 与本地主 APK `2579DD276B88F36E7EF2D1F6A12D90B63FC618D21CCB6A22AFA52035BDE9A730` 一致。
- 真实在线 AI 回复：`2324` 字符，分页 `42` 页，短期设备会话成功。
- 已逐页保存第 `1` 至第 `42` 页截图，并目视复核第 `1`、`7`、`20`、`42` 页：无空白页、压字、越界或底部语音 HUD 遮挡。
- “下一页”只翻对话内容；“下一步”只把维修步骤推进到 `2/3`。
- 设备结果记录模型基线 `qwen3-vl-plus/fun-asr-realtime`，声纹服务 `s1aa729d0`。
- crash buffer 为空；V8.0.48 与 V9.0.31 保持并存安装，没有覆盖稳定 APK。
- 最终实机证据目录：`output/v9.0.31-model-baseline-pagination-fix-final-device/`。
- APK 扫描证据目录：`output/v9.0.31-model-baseline-pagination-fix-final/`。

## 剩余发布门槛

- V9.0.31 仍是独立包名、v2 Debug 签名的审计包，不是正式市场发布包。
- 讯飞声纹仍需用户本人完成三段实时录入和独立第四段 `1:1` 验证，并覆盖旁人、噪声、误拒、误受、反重放和锁定/撤销。
- 双击声纹监听、音频抢占、温升、耗电和长时稳定性仍需完整 Air3 验收。
- Supabase 生产迁移、管理 Web、真实 Skill/知识授权、真实受管工作流和 MVS 工单写回仍需按各自发布门槛完成，不能用本地测试数据替代。
