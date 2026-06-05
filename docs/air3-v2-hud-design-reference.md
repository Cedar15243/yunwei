# Air3 V2 即时设计 HUD 对齐稿

## 即时设计文件

- 文件：`无标题 - 即时设计`
- 链接：`https://js.design/f/La5DU2?mode=design&p=frJI41vsVG`
- 目标：承载 Air3 V2 APK 的 14 个横屏 HUD 状态。

## 本地设计资产

- SVG 总览：`docs/air3-v2-hud-states.svg`
- HTML 预览：`docs/air3-v2-hud-states-preview.html`

这两个文件用于 Task 2 的设计对齐、即时设计导入参考、后续 Expo 原型和原生 APK HUD 渲染实现。每个画板尺寸都是 `1920 x 1080`，按 2 列 7 行排布。

## 视觉结构

每个状态遵循同一套眼镜 HUD 框架：

- 全屏相机预览背景，模拟黑底服务器控制台/终端画面。
- 顶部状态栏：`叮当X AI 运维眼镜` / `服务器 SSH 恢复`。
- 中央绿色取景框，比例接近 APK 当前 `GUIDE_FRAME_WIDTH_RATIO=0.72`、`GUIDE_FRAME_HEIGHT_RATIO=0.50`。
- 中央主 AI 指导区，只展示 `displayTitle` 和 `displayText`。
- 底部状态栏，只展示短状态和下一步操作提示。
- 右下角显示状态序号和 `resultType/feedbackCode`，仅用于设计稿识别；正式 HUD 不显示这些技术字段。

## 14 个画板

| 序号 | 状态 | resultType | feedbackCode |
| --- | --- | --- | --- |
| 1 | 相机就绪 | `ready` | `null` |
| 2 | 拍照上传 | `uploading` | `null` |
| 3 | 语音录入 | `recording_voice` | `null` |
| 4 | 语音转写 | `transcribing_voice` | `null` |
| 5 | AI 综合分析 | `ai_analyzing` | `null` |
| 6 | 操作指令 | `instruction` | `null` |
| 7 | 拍错目标 | `recognition_problem` | `wrong_target` |
| 8 | 照片不清晰 | `recognition_problem` | `unclear_photo` |
| 9 | 信息不足 | `recognition_problem` | `insufficient_info` |
| 10 | 语音不清楚 | `recognition_problem` | `voice_unclear` |
| 11 | 网络错误 | `network_error` | `network_error` |
| 12 | 远程复测 | `remote_probe` | `null` |
| 13 | 完成 | `completed` | `null` |
| 14 | 建议转人工 | `human_suggested` | `null` |

## 设计验收

- 不显示 HTTP、bytes、session、异常类名、英文 debug 文案或密钥。
- 主 AI 指导区保持短句，适合 Air3 横屏快速扫读。
- 错误/识别问题状态使用黄色或红色强调，但仍保留绿色取景框。
- 完成状态使用绿色强化；建议转人工使用紫红色强化，但文案明确“是否转人工由你决定”。
- 操作指令状态突出安全命令，后续实现时命令必须来自 `safeCommandKey` allowlist。
