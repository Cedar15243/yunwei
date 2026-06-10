# 叮当运维AI 聊天式现场诊断设计规格

## 目标

把现有 Air3 运维应用重新设计为“叮当运维AI”：一个类似 GPT App 的现场诊断对话应用。用户先进入现场诊断页，拍照上传现场画面，再通过长按语音按钮描述问题；阿里 Fun-ASR 只负责实时语音转文字，所有诊断、追问、建议、操作步骤和结论都由 GPT 返回。

本轮目标是快路径体验：用户感觉是在和 AI 直接对话。图片上传、语音转写、GPT 流式回答必须优先；数据库记录、审计、长期上下文整理全部后置为 best-effort，不能挡住对话响应。

## 已选方案

选择“纯聊天框 + 独立相机页 + 后端代理 ASR/GPT”的方案。

对比过的方向：

- 继续沿用旧 HUD：开发成本低，但用户已经明确否定；它以拍照和结构化结果为中心，不符合语音助手式对话。
- 聊天框加右侧证据卡：上一版预览接近这个方向，但仍像运维控制台；图片长期占据界面，和“GPT 式聊天”不一致。
- 纯聊天框：最符合用户需求。图片作为聊天消息和上下文存在，相机是独立页面，主界面始终围绕用户和 AI 的对话展开。

## 产品身份

- 应用名：叮当运维AI
- 公司品牌：华方智联
- 测试包建议：`com.codex.air3nativecamera.dingdangops`
- 测试版建议：`601 / 6.0.1-chat`
- 正式 delivery 包暂不覆盖；先用并装测试包验证体验。
- Figma 设计文件：`https://www.figma.com/design/pDX9LEKARKp5GGchwuFLz4`
- 当前 Figma 状态：文件已创建；受 Starter MCP 调用限制影响，页面节点尚未写入。限制解除后需补齐聊天主屏、独立相机页、语音识别中、GPT 流式回答四个画板。

Logo 资产线索：

- 正式源文件候选：`C:\Users\59979\Documents\xwechat_files\mr-cedar_5b85\msg\file\2026-04\logo华方智联.ai`
- 备选缓存候选：`C:\Users\59979\Documents\WXWork\Global\corp_logo`

实现时先把 `.ai` 导出为 Android 可用的 PNG/WebP 或 vector drawable。若导出工具不可用，首版使用文字品牌“华方智联 / 叮当运维AI”，不让 Logo 资产阻塞快路径。

## 核心用户流程

1. 用户进入现场诊断页面。
2. 页面显示 GPT 式聊天主界面，首条 AI 气泡提示“先拍一张现场图，我会结合画面回答”。
3. 用户点击拍照按钮，进入独立相机页面。
4. 用户拍照并确认，App 立即压缩图片并上传。
5. 后端快速返回 `image_id`，不调用 GPT。
6. App 回到聊天页，追加一条图片消息气泡，标记“现场图片已上传”。
7. 用户长按语音按钮说问题。
8. App 将音频流发给后端，后端代理阿里 Fun-ASR realtime。
9. App 实时显示 partial transcript，松手后显示 final transcript。
10. App 把 `final_text + image_id + session_id` 发给后端 GPT 诊断流。
11. 后端调用 GPT，流式返回文字。
12. App 在 AI 气泡里边生成边显示诊断建议。

## UI 结构

### 聊天首页

主界面必须是聊天框，不再是旧 HUD、证据卡或分页指导面板。

顶部区域：

- 左侧显示华方智联 Logo 或文字品牌。
- 标题显示“叮当运维AI”。
- 小状态显示“现场诊断”以及连接状态，例如“在线”“语音识别中”“AI 正在回答”。

中间区域：

- 使用纵向消息流。
- 用户文字消息靠右。
- 用户图片消息靠右，以缩略图气泡或“现场图片已上传”气泡呈现。
- AI 回复靠左。
- ASR partial 以正在输入的用户气泡显示，不写入正式历史；final 后替换为正式用户消息。
- GPT streaming 以正在生成的 AI 气泡显示，结束后固定为正式 AI 消息。

底部区域：

- 拍照按钮：进入独立相机页。
- 长按语音按钮：按住说话，松手发送。
- 当前图片上下文提示：用很小的行内状态表达，例如“已关联 1 张现场图”，不得变成独立证据面板。

Air3 硬件按键映射：

- 按键 1：拍照 / 重新拍照。
- 按键 2：按住语音；若硬件无法持续按住，则点击开始、再次点击结束。
- 按键 3：返回聊天页；在相机页返回聊天页，不退出 App。
- 当前实机和公开资料结论见 `docs/air3-shortcut-key-research.md`：普通 APK 不承诺接管 `CAMERA(27)` / `DVR(173)`，产品交互优先使用 `ENTER/DPAD_CENTER`、`FOCUS/F9`、`RIGHT/MENU/F12`、`BACK/LEFT/F10` 与 `VOLUME_UP/DOWN`。

### 独立相机页面

相机页面只负责拍照，不负责诊断。

页面元素：

- 全屏 Camera2 预览。
- 中央或边缘取景提示。
- 底部三个操作：返回、拍照、确认使用。
- 拍照后进入确认态，可重拍或使用照片。

确认使用后：

- App 压缩图片。
- 上传到后端。
- 成功后返回聊天页并追加图片消息。
- 失败时留在相机页或回聊天页显示错误气泡，让用户可重试。

## 后端接口契约

### 图片上传

`POST /sessions/:session_id/images`

请求：

```json
{
  "image_base64": "<compressed jpeg/webp>",
  "image_kind": "field_photo",
  "client_ts": "2026-06-09T00:00:00.000Z"
}
```

响应：

```json
{
  "ok": true,
  "session_id": "session-id",
  "image_id": "image-id",
  "image_bytes": 123456
}
```

约束：

- 该接口只存图并返回 `image_id`。
- 不调用 GPT。
- 不等待数据库审计完成后才响应。

### 实时语音转文字

`WS /sessions/:session_id/asr`

App 到后端：

- `start`：携带 `image_id`、采样率、声道、编码。
- `audio`：连续发送 16 kHz mono PCM 音频帧。
- `finish`：用户松手后发送。

后端到 App：

```json
{ "type": "partial", "text": "这个水泵" }
{ "type": "final", "text": "这个水泵为什么报警" }
{ "type": "error", "code": "asr_unavailable", "message": "语音识别暂时不可用" }
```

约束：

- 后端代理阿里 Fun-ASR realtime，API Key 只在后端。
- Fun-ASR 的输出只能作为 transcript。
- Fun-ASR 不产生诊断、不产生建议、不改写用户意图。

### GPT 流式诊断

`POST /sessions/:session_id/diagnose/stream`

请求：

```json
{
  "image_id": "image-id",
  "final_text": "这个水泵为什么报警",
  "client_context": {
    "source": "voice",
    "app": "dingdang-ops-ai"
  }
}
```

响应采用 SSE 或 Web Stream：

```text
event: delta
data: {"text":"先看报警灯和压力表，"}

event: delta
data: {"text":"如果压力低于设定值..."}

event: done
data: {"message_id":"ai-message-id"}
```

约束：

- GPT 是唯一诊断来源。
- App 本地不得生成诊断性文案，只能显示状态和错误。
- 后端可在流结束后异步写入数据库、审计、上下文摘要。

## 快路径原则

- 图片先上传，拿到 `image_id` 后马上回聊天页。
- 语音边说边转写，partial 直接显示。
- 松手后立即用 `final_text + image_id` 请求 GPT。
- GPT token 到达即显示，不等待完整回答。
- 数据库、审计、长期上下文、统计埋点全部后置。
- 后端日志和持久化失败不能让用户对话失败。

## 错误状态

- 未拍照就语音：AI 气泡提示“请先拍一张现场图”，不进入假诊断。
- 图片上传失败：聊天页显示用户可重试的错误气泡。
- ASR 连接失败：显示“语音转文字暂时不可用”，不调用 GPT。
- ASR final 为空：显示“没有听清，请靠近麦克风再说一遍”，不调用 GPT。
- GPT 流失败：保留已收到内容，并追加“AI 回复中断，可重试”状态。
- 权限缺失：相机或麦克风页面内直接请求权限，失败后回聊天页显示原因。

## 验收标准

- 首屏是聊天消息流，不出现旧 HUD 主面板、右侧证据卡、分页指导区。
- 点击拍照进入独立相机页面，确认后返回聊天页。
- 图片上传接口只返回 `image_id`，不触发 GPT。
- 长按语音时能看到实时 partial transcript。
- 松手后 final transcript 成为用户消息。
- GPT 回复以流式 AI 气泡生成。
- 所有诊断内容来自 GPT。
- Fun-ASR/API Key 不进入 APK。
- 数据库写入失败时，聊天快路径仍能完成。

## 范围边界

本轮包含：

- 叮当运维AI 聊天式 UI。
- 独立相机页。
- 图片上传拿 `image_id`。
- Fun-ASR realtime 后端代理。
- GPT 流式诊断。
- 并装测试包与验证脚本。

本轮不包含：

- 完整工单系统。
- 完整用户账号体系。
- 长期知识库检索。
- 后台数据库管理页面。
- 复杂多图相册。
- 人工坐席工作台。

## 自检结论

- 规格已明确“纯聊天框”，排除了旧 HUD 和右侧证据卡。
- 规格已明确 Fun-ASR 只做转写，GPT 才做诊断。
- 规格已明确图片上传和 GPT 诊断解耦。
- 规格已明确数据库后置，不阻塞即时对话。
- 外部接口密钥只允许后端 env/secrets 注入。
