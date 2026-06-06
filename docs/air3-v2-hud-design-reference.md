# Air3 V2 即时设计 HUD 对齐稿

## 即时设计文件

- 文件：`无标题 - 即时设计`
- 链接：`https://js.design/f/La5DU2?mode=design&p=frJI41vsVG`
- 目标：承载 Air3 V2 APK 的 14 个横屏 HUD 状态。

## 本地设计资产

- SVG 总览：`docs/air3-v2-hud-states.svg`
- HTML 预览：`docs/air3-v2-hud-states-preview.html`

这两个文件用于 Task 2 的设计对齐、即时设计导入参考、后续 Expo 原型和原生 APK HUD 渲染实现。每个画板尺寸都是 `1920 x 1080`，按 2 列 7 行排布。

## 基准截图

用户提供的 `17b89924bedef92fae8ea5fcf043bcb2.png` 是 Air3 V2 HUD 的固定布局基准。后续页面设计、Expo 原型和原生 APK HUD 渲染都必须优先匹配该截图的结构，而不是回到普通 App 卡片页或旧版状态卡布局。

## 视觉结构

每个状态遵循同一套眼镜 HUD 框架：

- 全屏深色相机背景，左侧可用现场屏幕或终端线条作为设计模拟。
- 顶部是一条很薄的边框栏，左侧 `叮当X AI 运维眼镜`，右侧 `AI 运维现场指导`。
- 中央是大号绿色取景框，位置约为 `left=14%`、`top=22%`、`width=72%`、`height=52%`。
- 主 AI 指导区放在绿色框内部中央，半透明深色背景，只展示给现场人员看的中文标题和短说明。
- 指导区下方保留中心十字线，帮助小白对准需要 AI 判断的关键画面。
- 取景框下方固定提示：`把关键画面放入绿色框内`。
- 底部先显示状态行，左侧是业务状态，右侧固定说明 `调试信息仅写入日志，不显示给现场人员`。
- 最底部是三段操作栏：`中心点击 拍照 / 下一步`、`长按中心 语音确认 / 补充说明`、`返回键 重拍 / 返回上一步`。
- 正式 HUD 不显示状态序号、`resultType`、`feedbackCode`、HTTP、bytes、session、异常类名、英文 debug 文案或密钥。

## 状态变化规则

14 个状态只允许改变主标题、短说明、状态行、强调色和当前高亮操作入口；整体布局不变。绿色取景框始终保留，因为它是图片是否符合 AI 识别流程的核心约束。

## 14 个画板

| 序号 | 状态 | resultType | feedbackCode |
| --- | --- | --- | --- |
| 1 | 相机就绪 | `ready` | `null` |
| 2 | 拍照上传 | `uploading` | `null` |
| 3 | 语音录入 | `recording_voice` | `null` |
| 4 | 语音转写 | `transcribing_voice` | `null` |
| 5 | AI 综合分析 | `ai_analyzing` | `null` |
| 6 | 操作指令 | `instruction` | `null` |
| 7 | 场景不匹配 | `recognition_problem` | `wrong_target` |
| 8 | 照片不清晰 | `recognition_problem` | `unclear_photo` |
| 9 | 信息不足 | `recognition_problem` | `insufficient_info` |
| 10 | 语音不清楚 | `recognition_problem` | `voice_unclear` |
| 11 | 网络错误 | `network_error` | `network_error` |
| 12 | 远程复测 | `remote_probe` | `null` |
| 13 | 完成 | `completed` | `null` |
| 14 | 建议转人工 | `human_suggested` | `null` |

## 设计验收

- 不显示 HTTP、bytes、session、异常类名、英文 debug 文案或密钥。
- 首屏必须看起来像眼镜取景 HUD，而不是普通手机 App 页面。
- 主 AI 指导区必须位于绿色取景框内部中央，适合 Air3 横屏快速扫读。
- 底部三段操作栏必须一直可见，明确中心点击、长按中心、返回键的作用。
- 错误/识别问题状态使用黄色或红色强调，但仍保留绿色取景框。
- 完成状态使用绿色强化；建议转人工使用紫红色强化，但文案明确“是否转人工由你决定”。
- 操作指令状态突出安全命令，后续实现时命令必须来自 `safeCommandKey` allowlist。
- 清晰非服务器画面不能被默认判为拍错目标，必须先展示主 AI 对现场画面的真实反馈。
