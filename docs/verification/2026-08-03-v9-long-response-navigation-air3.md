# V9.0.29 长回复分页与维修步骤导航 Air3 验证

## 范围

- 不修改现有 HUD 视觉、布局、按钮位置、Camera2、专家协同或 V8 稳定包。
- 验证长回复分页、页码、底部操作区与维修步骤导航互不遮挡、互不串线。
- 主 AI/视觉继续固定上一稳定版 `qwen3-vl-plus`，实时 ASR 固定 `fun-asr-realtime`，小叮当继续讯飞 AIKit，声纹只使用讯飞新版 `s1aa729d0`。

## 根因与改动

- `HudWebPresentation.applyPendingState()` 在 WebView 首次加载时会无条件重放默认维修步骤，覆盖已经准备好的长回复。增加待恢复状态类型，只在维修指导状态重放步骤，否则恢复对话分页。
- 首轮真机失败来自测试夹具调用旧兼容入口 `handleVoiceCommand()`。生产 ASR 实际先走 `handleVoicePreviewInteraction()` 与新版 `VoiceCommandRouter`；测试改为调用真实生产入口，生产路由未改。
- Air3 的系统合成器比 WebView DOM 滞后一帧。截图工具在 `VisualStateCallback` 后再等待两个 vsync，确保截图与断言状态一致。
- 真机用例遍历全部 5 页，逐页比较可见正文与任务分页源；只忽略 WebView 不显示的页首尾空白，内部段落与字符保持严格比较。

## 新鲜验证

- Air3：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- 主包：`com.codex.air3nativecamera.dingdangexpert.v9.longreply.navigation.audit`。
- 版本：`9029 / 9.0.29-long-reply-navigation-audit`。
- 已安装主 APK SHA256：`E95D3B69C492264317F93CCCFB5F23B8FC4FE44BBB5F2038B637AD47F7010536`。
- `ManagedLongReplyNavigationDeviceTest`：`OK (1 test)`，`9.81s`。
- 长回复：`5` 页，逐页正文、页码和布局指标通过；最终对话页索引为 `4`。
- 维修指导：对话页保持不变，维修步骤由 `1/3` 仅推进到 `2/3`。
- Android JVM：`79` 个报告，`474/474`，失败 `0`、错误 `0`、跳过 `0`。
- V9 网关：`107/107`。
- `npm run validate:native-hud`、`npm run validate:native-build` 通过。
- Air3 crash buffer、`FATAL EXCEPTION`、ANR 均为空。
- APK 为 API 34、v2 Debug 签名；9 项本机受管凭据精确扫描命中 `0`，APK 与运行时源码的 OpenClaw 命中 `0`。

截图和结果位于 `output/v9.0.29-long-reply-navigation-air3/`，包括第 1 至第 5 页、维修步骤 2 和结果文件。

## 结论与边界

- 长回复分页、页面布局与“下一页/下一步”语义已在 Air3 真实 WebView 中闭环。
- 当前包仍为 Android Debug 签名，只是独立审计包，不能作为市场正式包。
- 真实 Skill/知识生产授权、签名工作流投放、本人三段声纹录入与第四段独立 `1:1` 验证、生产管理 Web/权限和长期性能仍是发布阻断。
