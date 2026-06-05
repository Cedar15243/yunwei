# Air3 V2 AI 返回契约

## 契约目标

本契约定义 Supabase 后端返回给 Air3 HUD、Expo 原型和后续测试工具的统一结构。V2 中主 AI 是大脑，返回结果必须能直接指导现场小白，或明确说明照片、语音、信息、网络等问题。

后端可以校验格式、记录过程、校验基础安全边界，但不替主 AI 做运维判断。

## 通用字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `ok` | boolean | 是 | 请求是否被后端正常处理。 |
| `sessionId` | string | 是 | 运维会话 ID。HUD 可保存但不可显示给小白。 |
| `step` | string | 是 | 当前或下一步流程标识。 |
| `resultType` | string | 是 | HUD 结果类型。 |
| `feedbackCode` | string/null | 是 | 识别问题或错误原因；无问题时为 `null`。 |
| `displayTitle` | string | 是 | HUD 主标题，必须是中文短句。 |
| `displayText` | string | 是 | HUD 主内容，必须能直接给小白看。 |
| `fullText` | string/null | 否 | 主 AI 返回的完整可展示中文文本。长文本时必须填写并保存到后端记录。 |
| `displayPages` | string[]/null | 否 | 长文本分页后的 HUD 文本数组。每一页都必须能直接给小白看。 |
| `textOverflowMode` | `"single"`/`"paged"` | 否 | HUD 文本显示方式。缺省视为 `"single"`；长文本必须为 `"paged"`。 |
| `currentPage` | number/null | 否 | 后端返回长文本时可置为 `1`。眼镜端翻页后以本地页码为准。 |
| `totalPages` | number/null | 否 | 长文本总页数，应等于 `displayPages.length`。 |
| `displayHint` | string | 是 | HUD 底部提示，必须短、清楚、可执行。 |
| `safeCommandKey` | string/null | 否 | 需要显示命令时，对应 `safe_commands` allowlist key。 |
| `transcript` | string | 否 | 本次语音转文字结果。没有语音时可为空字符串。 |
| `humanEscalationSuggestion` | boolean | 是 | 主 AI 是否建议转人工。最终是否转人工由小白决定。 |
| `canRetake` | boolean | 是 | 当前是否允许重新拍照。 |
| `canUseVoice` | boolean | 是 | 当前是否允许长按补充语音。 |
| `canHumanEscalate` | boolean | 是 | 当前是否允许小白选择转人工。 |
| `requiresPhoto` | boolean | 是 | 下一步是否需要小白继续拍照。 |
| `timestamp` | string | 是 | ISO 8601 响应时间。 |

## resultType 枚举

- `instruction`：主 AI 返回操作指令。
- `recognition_problem`：图片、文字或上下文不满足识别流程。
- `network_error`：后端、STT、主 AI 或网络暂时不可用。
- `remote_probe`：正在远程复测 SSH 访问。
- `completed`：任务完成。
- `human_suggested`：主 AI 建议转人工，小白自行决定。

Expo 原型可额外使用本地 UI 中间态：

- `ready`
- `uploading`
- `recording_voice`
- `transcribing_voice`
- `ai_analyzing`

这些中间态不要求后端持久化为 AI 决策。

## feedbackCode 枚举

- `wrong_target`：拍错目标，画面不是服务器控制台、登录界面、黑底终端或命令输出。
- `unclear_photo`：照片不清晰，AI 看不清屏幕文字。
- `insufficient_info`：信息不足，缺少完整命令输出或关键上下文。
- `voice_unclear`：语音不清楚，transcript 为空、噪声过大或语义无法判断。
- `image_voice_conflict`：图片和语音描述冲突，需要小白重新补充。
- `ai_unavailable`：主 AI 暂时不可用。
- `network_error`：网络或服务请求失败。
- `null`：无识别问题。

## 长文本分页规则

AI 返回的中文指导可能较长，但眼镜 HUD 不允许把长文直接塞进单个文本框，也不允许截断后丢失内容。后端、Expo 原型和原生 APK 必须遵守同一套分页契约。

- `fullText` 保存主 AI 返回的完整可展示中文文本；其中不得包含 HTTP、bytes、sessionId、异常类名、密钥、原始 JSON 或英文 debug 信息。
- 当 `fullText` 无法在 HUD 中央面板一屏清晰显示时，后端必须生成 `displayPages`，并设置 `textOverflowMode="paged"`、`currentPage=1`、`totalPages=displayPages.length`。
- `displayPages` 必须按阅读顺序覆盖 `fullText` 的全部可展示内容；不得因为分页而省略安全提醒、操作条件或下一步动作。
- 每页文字应是短句或短段落，优先控制在 4 行以内；命令必须单独成页或使用独立短行，避免小白抄错。
- `displayText` 作为兼容字段保留。长文本响应中，`displayText` 应等于第一页或第一页摘要，不能作为唯一显示来源。
- 眼镜端中心点击优先翻到下一页；到最后一页后，中心点击才执行拍照、下一步或重试。
- 返回键优先回到上一页；如果已在第一页，才执行重拍或返回上一步。
- 长按中心始终可用于语音确认或补充说明，不改变分页内容本身。
- 如果后端临时只返回 `fullText` 而没有 `displayPages`，原生 APK 可以按同一行数限制本地分页，但正式后端必须优先返回结构化分页。

## 标准操作指令响应

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "run_recovery_command",
  "resultType": "instruction",
  "feedbackCode": null,
  "displayTitle": "请输入恢复命令",
  "displayText": "sudo systemctl start ssh",
  "displayHint": "输入完成后，单击中心拍摄输出结果。",
  "safeCommandKey": "ssh_start",
  "transcript": "我已经输入完成了",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": true,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

## 标准识别问题响应

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "needs_better_photo",
  "resultType": "recognition_problem",
  "feedbackCode": "wrong_target",
  "displayTitle": "拍错目标",
  "displayText": "当前画面不是服务器控制台。",
  "displayHint": "请只拍登录界面、黑底终端或命令输出。",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": true,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

## 长文本分页响应

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "run_recovery_command",
  "resultType": "instruction",
  "feedbackCode": null,
  "displayTitle": "按顺序检查并恢复 SSH",
  "displayText": "先确认屏幕上是否仍显示 ssh 服务 inactive。",
  "fullText": "先确认屏幕上是否仍显示 ssh 服务 inactive。如果是，请输入允许列表中的恢复命令 sudo systemctl start ssh。命令执行后不要关闭当前终端，等待新的输出稳定后再拍照。若出现权限提示，请长按中心说明你看到的提示文字。",
  "displayPages": [
    "先确认屏幕上是否仍显示 ssh 服务 inactive。",
    "如果是，请输入允许列表中的恢复命令：sudo systemctl start ssh",
    "命令执行后不要关闭当前终端，等待新的输出稳定后再拍照。",
    "若出现权限提示，请长按中心说明你看到的提示文字。"
  ],
  "textOverflowMode": "paged",
  "currentPage": 1,
  "totalPages": 4,
  "safeCommandKey": "ssh_start",
  "transcript": "我已经看到 ssh 是 inactive",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": true,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

## 语音不清楚响应

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "await_voice_retry",
  "resultType": "recognition_problem",
  "feedbackCode": "voice_unclear",
  "displayTitle": "语音不清楚",
  "displayText": "AI 没听清你的补充说明。",
  "displayHint": "请重新长按，说短一点。",
  "transcript": "",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": false,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

## 建议转人工响应

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "human_suggested",
  "resultType": "human_suggested",
  "feedbackCode": null,
  "displayTitle": "建议转人工",
  "displayText": "当前指令超出安全范围，我建议请运维专家介入。",
  "displayHint": "AI 只是建议，是否转人工由你决定。",
  "humanEscalationSuggestion": true,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": false,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

## 安全命令规则

- `displayText` 可以展示命令，但命令必须来自 `safe_commands` allowlist。
- 当 `safeCommandKey` 存在时，后端必须确认 key 存在且命令文本匹配。
- 如果主 AI 返回了不在 allowlist 内的命令，后端必须返回 `human_suggested`，不能把该命令展示给小白。
- 主 AI 可以解释下一步，但不能自由生成新的 shell 命令。

## 图片和文字上下文规则

- 有图片时，后端必须把 `imageId` 放入 AI Context Builder。
- 有语音时，后端必须先调用自部署 STT，得到 `transcript` 后再与最新图片一起交给主 AI。
- 主 AI 输入必须包含 `currentStep` 和 `taskGoal`。
- 语音只作为补充上下文，不替代图片识别流程。
- 如果图片和 transcript 冲突，优先让主 AI 返回 `feedbackCode=image_voice_conflict` 或要求小白补充信息。

## HUD 展示规则

Air3 HUD 只能展示：

- `displayTitle`
- `displayText`
- `displayPages` 中的当前页
- 中文页码，例如 `第 1/4 页`
- `displayHint`
- 与状态有关的本地中文短提示

Air3 HUD 不允许展示：

- `sessionId`
- HTTP 状态码
- 上传字节数
- Java/Android 异常类名
- 原始模型 JSON
- 密钥或 token
- 英文 debug 文本

## 兼容规则

Task 6 之前，旧 APK 仍可能读取 `text` 和 `step`。V2 后端实现期间可以临时保留 `text` 字段作为 `displayText` 的兼容副本，但新代码必须以 `displayTitle/displayText/displayHint/resultType/feedbackCode/displayPages/textOverflowMode` 为准。
