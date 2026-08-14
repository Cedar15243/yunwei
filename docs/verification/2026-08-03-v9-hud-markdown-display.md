# V9 HUD Markdown 展示验证

## 范围

- 主 AI/视觉继续使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 继续使用上一版 `fun-asr-realtime`，Sherpa 只作断网或云端失败回退。
- 声纹只使用刚申请的讯飞新版 `s1aa729d0`。
- 不使用 OpenClaw，不启用其他新增模型，不修改 V8 历史默认值。
- 本轮只修复眼镜端 AI 文本展示，不调整首页、任务 HUD、专家协同或既有布局。

## 实现

- 新增纯 Java `HudTextNormalizer`，将常见 Markdown 标题、强调、无序列表、链接和代码围栏转换为适合 HUD 阅读的纯文本。
- 保留有序步骤编号、段落边界、代码正文和 `TEMP_SENSOR_1`、`4*20mA`、`find **/*.log` 等现场字面量。
- 流式回复中的未闭合 `**` 或 `*` 不再短暂显示在 HUD。
- 规范化只接入流式 AI、任务对话分页、诊断分页、维修步骤和 AI 消息气泡。任务序列化、项目记忆、AI 原文和同步事件仍保存原始回复。
- “下一页”继续只切换内容分页，“下一步”继续只推进维修步骤。

## TDD 与自动化

- 红灯：新增测试后，JVM 编译按预期因 `HudTextNormalizer` 尚不存在而失败；`validate:native-hud` 按预期报告 AI 展示面尚未规范化。
- 绿灯：Android 全量 `453/453`，失败 `0`，跳过 `0`。
- `npm run validate:native-hud` 通过。
- `npm run validate:native-build` 通过。
- V9 网关全量 `102/102` 通过，继续覆盖并锁定 `qwen3-vl-plus`、`fun-asr-realtime` 和讯飞 `s1aa729d0`。
- `git diff --check` 通过；仅存在仓库既有 LF/CRLF 提示。

## APK 与 Air3

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.hudtext.audit`
- 版本：`9018 / 9.0.18-hud-text-audit`
- APK：`output/air3-v9-hud-text-audit-20260803/DingdangAI-v9.0.18-hud-text-audit.apk`
- SHA256：`BAAF6B2099967539FA46C8205716AC41198EFD13B9C6F4B4F407F30F73287A03`
- `minSdk/targetSdk`：`34/34`
- 签名：APK Signature Scheme v2 Debug，有效；不能作为正式签名发布包。
- 安全生成配置：`secureRuntime=true`、`directGptEnabled=false`，直连 AI、直连 ASR 和讯飞长期凭据字段均为空。
- 2026-08-03 已并存安装到 Air3 `YM00FCF3NW0031 / IMA301 / Android 14`；首页保持原有 HUD 布局，无新增首页层级或第二套视觉。
- V8.0.48 与 V9.0.18 均可独立启动，崩溃缓冲为空，未覆盖或卸载稳定包。

## 未完成验收

- 使用本机中文 TTS 尝试触发真实受管语音到 AI 的 Markdown 回答，但该轮没有形成可用在线 AI 回复；因此本报告不宣称真实模型 Markdown 长回复已经在 Air3 完成端到端显示验收。
- 仍需使用真实在线任务回复覆盖长文分页、步骤分页、底部语音 HUD 遮挡、返回链和“下一页/下一步”语义分离。
- 构建仍有仓库既有的 D8 API 34 工具链兼容警告，构建成功但正式发布前应升级工具链并回归。
