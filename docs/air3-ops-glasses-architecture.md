# Air3 运维眼镜 V2 架构规划

## V2 固定架构

主 AI 是系统大脑，直接指导现场小白操作。后端不替主 AI 做运维判断，只负责接收、保存、组装上下文、调用模型、记录过程、校验返回格式和基础安全边界。小白是执行人，并最终决定是否人工介入。

除非用户后续明确修改，本文件作为第二版 APK 的架构规则和开发边界。

## 产品目标

Air3 AI 运维眼镜用于指导现场小白恢复服务器 SSH 远程访问。现场人员只看眼镜 HUD，按照主 AI 返回的中文指导完成拍照、录音、输入命令、复拍输出、等待远程复测和决定是否转人工。

## 角色边界

- Air3 相机是眼睛：负责拍摄服务器本地控制台、登录界面、黑底终端或命令输出。
- 用户自部署 STT 是耳朵：负责把眼镜录音转成 transcript，不做运维判断。
- 主 AI 是大脑：同时读取图片、transcript、currentStep 和 taskGoal，直接生成给小白看的现场指导。
- Supabase 后端是神经通路、记录员、工具箱和基础安全护栏：保存证据、组装上下文、调用 STT、调用主 AI、记录过程、校验返回格式和安全命令边界。
- Air3 HUD 是显示器：只显示中文业务指导、识别反馈、重试提示、远程复测状态、完成提示或建议转人工。
- 小白是执行人：根据 HUD 指导操作，并最终决定是否人工介入。AI 可以建议转人工，但不能强制替小白决定。

## V2 数据流

```text
Air3 眼镜拍照/录音
  -> Supabase 保存图片和音频
  -> 自部署 STT 把音频转成 transcript
  -> AI Context Builder 绑定 imageId + voiceInputId + transcript + currentStep + taskGoal
  -> 主 AI 读取图片和文字后生成现场指导
  -> 后端记录并校验格式/安全边界
  -> Air3 HUD 显示 AI 指导
  -> 小白执行并决定是否转人工
```

## 总体架构

```text
Air3 原生 Camera2 APK
  - 全屏相机预览
  - HUD 指令层
  - 绿色取景框与裁剪上传
  - 单击拍照
  - 长按录音
  - sessionId/currentStep 本地保存
        |
        | HTTPS + x-ops-glasses-key
        v
Supabase Edge Function: ops-glasses
  - 接收图片和音频
  - 保存 Storage 和 Postgres
  - 调用自部署 STT 生成 transcript
  - AI Context Builder 组装多模态上下文
  - 调用主 AI 大脑
  - 校验结构化返回和基础安全边界
  - 写入 ai_context_bundles / ai_decisions
        |
        v
主 AI 大脑
  - 输入：图片 + transcript + currentStep + taskGoal + allowlist
  - 输出：结构化 HUD 指导
        |
        v
Air3 HUD
  - 显示主 AI 指导
  - 显示识别问题反馈
  - 显示远程复测/完成/建议转人工
```

## 眼镜端职责

主线工程：`air3-native-camera-test`

眼镜端负责真实设备能力，不负责运维决策：

- 打开 Air3 真实 Camera2 相机。
- 以全屏预览作为 HUD 背景。
- 显示顶部任务栏、中央主指令、绿色取景框、底部操作栏。
- 单击拍照上传，长按录音上传。
- 拍照后按 HUD 取景框比例裁剪上传图。
- 上传 `sessionId`、`step`、`action`、`imageBase64`、录音文件或音频 base64。
- 解析后端返回的 V2 结构化 HUD 契约：`resultType`、`feedbackCode`、`displayTitle`、`displayText`、`displayHint`。
- 保存最近一次 `sessionId/currentStep/last_ops_response.json/last_voice_response.json` 便于排查。
- 不显示 HTTP、bytes、session、异常类名、英文 debug 文案或任何密钥。

## HUD 页面原则

眼镜端页面以最新 Air3 横屏 HUD 为准，不做普通手机 App 卡片式页面。

- 全屏相机预览背景。
- 顶部：`叮当X AI 运维眼镜 / 服务器 SSH 恢复`。
- 中部：绿色取景框和主 AI 指导。
- 底部：短状态与操作提示。
- 所有可见内容必须是现场小白能理解的中文业务文案。
- 页面状态必须覆盖相机就绪、拍照上传、语音录入、语音转写、AI 综合分析、操作指令、拍错目标、照片不清晰、信息不足、语音不清楚、网络错误、远程复测、完成、建议转人工。

详细页面规范见 `docs/air3-v2-hud-pages.md`。

## 后端职责

主线工程：`supabase/functions/ops-glasses`

后端不是运维决策者，不根据旧 `workflowSignal` 自行决定下一步。V2 后端的核心职责是把现场证据完整、可信、可追溯地交给主 AI 大脑，并把主 AI 返回结果安全地交回 HUD。

职责：

- 创建和维护 `ops_sessions`。
- 接收眼镜端图片事件和语音事件。
- 保存图片、音频和事件记录。
- 调用用户自部署 STT，把音频转成 transcript。
- 组装 `imageId + voiceInputId + transcript + currentStep + taskGoal`。
- 调用主 AI 大脑生成结构化指导。
- 校验 AI 返回字段是否符合契约。
- 当 `safeCommandKey` 存在时，校验它是否属于 `safe_commands` allowlist。
- 保存 AI 请求、上下文包和 AI 决策记录。
- 提供远程复测接口，并把复测结果作为后续上下文交给主 AI。
- 支持建议转人工，但不替小白做最终转人工决定。

## 主 AI 职责

主 AI 是系统大脑，直接指导现场小白。

输入：

- 服务器控制台或终端图片。
- 自部署 STT 产出的 transcript。
- 当前步骤 `currentStep`。
- 任务目标 `taskGoal`。
- 允许展示的安全命令 allowlist。
- 历史上下文摘要。

输出：

- `resultType`：结果类型，例如操作指令、识别问题、网络错误、远程复测、完成、建议转人工。
- `feedbackCode`：识别问题或异常原因，例如拍错目标、照片不清晰、信息不足、语音不清楚。
- `displayTitle`：HUD 主标题。
- `displayText`：HUD 主内容。
- `displayHint`：HUD 底部短提示。
- `safeCommandKey`：需要展示安全命令时对应 allowlist key。
- `humanEscalationSuggestion`：是否建议转人工。

详细返回契约见 `docs/air3-v2-response-contract.md`。

## Supabase 数据边界

V2 数据库需要能追溯一轮 AI 指导的全部证据：

- `ops_sessions`：会话状态和任务目标。
- `ops_events`：眼镜事件、语音事件、复测事件。
- `ops_images`：图片存储记录。
- `voice_inputs`：音频、transcript 和转写状态。
- `ai_context_bundles`：图片、语音、步骤和任务目标绑定后的上下文包。
- `ai_requests`：模型调用记录。
- `ai_decisions`：主 AI 返回的结构化 HUD 决策。
- `safe_commands`：允许显示给小白输入的安全命令。
- `remote_probes`：远程 SSH 复测结果。

## Expo 的定位

工程：`air3-ops-expo-app`

Expo 不作为当前 Air3 真机主 APK，原因：

- 真实 Air3 Camera2 链路已经在原生 APK 跑通。
- Air3 相机方向、预览变换、裁剪上传和物理输入需要原生层控制。
- Air3 标准 Android SpeechRecognizer 不可用，语音主线需要录音上传到自部署 STT。

Expo 适合作为：

- V2 HUD 页面原型。
- V2 结构化返回状态模拟器。
- 团队和客户查看的交互演示。
- 后续手机辅助端或管理端基础。

## 安全边界

- 眼镜端不直接调用主 AI。
- 后端不替主 AI 做运维判断。
- 主 AI 不允许自由生成 shell 命令；需要展示命令时只能引用 allowlist 中的 `safeCommandKey`。
- 小白最终决定是否人工介入。
- APK 内置 `OPS_GLASSES_API_KEY` 只适合 PoC/演示；正式版应改为设备注册、短期 token、按设备吊销或 MDM/手机端下发凭证。
- HUD 不显示密钥、HTTP 状态、字节数、sessionId、异常类名或调试文本。

## 验收标准

### 架构验收

- 文档明确主 AI 是系统大脑。
- 文档明确后端不替主 AI 做运维判断。
- 文档明确图片和 transcript 必须一起进入主 AI 上下文。
- 文档明确小白最终决定是否人工介入。

### 眼镜端验收

- APK 可安装并启动。
- HUD 正常显示，无英文调试信息。
- 相机预览不明显拉伸，方向正确。
- 绿色取景框与上传裁剪一致。
- 单击可拍照上传。
- 长按可录音上传到 STT 链路。
- 后端返回不同 `resultType/feedbackCode` 时，HUD 能显示对应中文业务文案。

### 后端验收

- `/health` 返回 `ok=true`。
- `/sessions/events` 可保存图片并创建 AI context bundle。
- `/sessions/:id/voice` 可保存音频、调用 STT、把 transcript 和最新图片一起交给主 AI。
- `ai_context_bundles` 和 `ai_decisions` 有记录。
- 返回给 APK 的响应符合 V2 契约。
- `safeCommandKey` 必须通过 allowlist 校验。

### 演示验收

- 物理屏幕前景必须是真正服务器控制台或终端窗口。
- 控制台文字必须位于绿色取景框内。
- 至少完成一轮：拍控制台 -> 主 AI 返回诊断命令 -> 拍诊断输出 -> 主 AI 返回恢复命令。
- 若现场画面不合格，眼镜必须显示“拍错目标/照片不清晰/信息不足”等中文反馈，而不是崩溃或显示调试内容。
