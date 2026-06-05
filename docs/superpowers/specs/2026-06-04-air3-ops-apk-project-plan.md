# Air3 运维眼镜 APK 项目规划

## 1. 产品功能清单

### 第一版核心目标

小白全程只操作 Air3 眼镜端 APK，通过“看提示、拍照片、输入指定命令、再拍结果”的方式，在 AI/后端指导下完成一次服务器 SSH 失联恢复演示。

### 眼镜端功能

- 任务首页：显示当前演示任务“服务器 SSH 失联恢复”。
- 任务开始：创建或加入一个运维会话。
- 拍照上传：调用 Air3 原生 Camera2 拍摄现场照片。
- 语音输入：录制小白语音并转成文字，用于免文字输入的动作确认和现场补充描述。
- 多轮会话：保存 `sessionId`，每次上传带上当前 `step` 和 `action`。
- 指令显示：显示后端返回的运维步骤、命令、安全提醒。
- 加载状态：显示“正在上传”“正在分析”“正在复测”。
- 重拍：照片不清楚时允许重新拍摄。
- 完成页：显示“SSH 已恢复”或“需要人工介入”。
- 复测分诊：复测时如果发现新问题，进入二次诊断分支，而不是直接结束。
- 本地日志：记录最近一次 session、step、服务端返回结果，方便排查。

### 后端功能

- 接收眼镜上传的图片和动作。
- 创建/维护运维会话。
- 按状态机返回下一步指令。
- 管理安全命令 allowlist。
- 调用远程探测：ping、SSH 22 端口。
- 后续调用 AI 视觉识别控制台照片。
- 存储任务、步骤、图片、AI 判断、探测结果。

### AI 功能

- 第一版必须接入真实 AI 视觉识别，不能只做规则模拟。
- 规则流程只作为安全兜底和演示降级方案。
- AI 视觉模型负责识别控制台照片：
  - 登录界面，
  - shell prompt，
  - 命令输出，
  - SSH 服务状态，
  - 照片不清晰，
  - 异常状态。
- AI 必须输出结构化 JSON，不能直接返回自由文本维修指令。
- 后端编排层根据 AI 结构化结果决定下一步，AI 不直接决定最终操作命令。

### 语音转文字功能

- 第一版加入语音转文字，但不要求小白输入任意长文本。
- 语音主要用于：
  - 开始任务，
  - 确认已输入命令，
  - 说明现场情况，
  - 请求重拍，
  - 请求人工介入。
- 语音识别结果必须经过后端意图分类，不能直接当成运维命令执行。
- 对高风险动作，仍以眼镜显示的安全命令和状态机为准。

## 2. 页面结构

### 页面 1：首页 / 任务入口

用途：让小白只看到一个明确任务。

内容：

- 标题：服务器 SSH 失联恢复
- 目标服务器：`ASSET-CONSOLE-001`
- 当前状态：等待开始
- 主操作：单击开始 / 拍摄第一张照片

### 页面 2：拍照提示页

用途：告诉小白当前要拍什么。

内容：

- 当前步骤名：拍摄服务器控制台
- 拍摄要求：屏幕文字清晰、尽量拍全、避免反光
- 操作提示：单击拍照，长按重拍
- 可选语音：说“重拍”或“转人工”

### 页面 3：上传分析页

用途：防止用户重复操作。

内容：

- 正在上传照片
- 正在分析控制台内容
- 禁止重复点击

### 页面 4：指令页

用途：显示后端/AI 返回的下一步。

内容示例：

```text
请输入：
sudo systemctl status ssh --no-pager

只输入这一条命令。
执行后请单击拍摄完整输出。
```

可选语音：

```text
小白说“已输入” -> 眼镜进入拍摄输出步骤
小白说“看不懂” -> 后端返回更简短提示或转人工
```

### 页面 5：重拍页

用途：照片不清楚时重新采集。

内容：

- 照片不清晰，无法识别
- 请靠近屏幕重新拍摄
- 单击重拍

### 页面 6：复测页

用途：后端正在验证结果。

内容：

- 正在复测远程 SSH
- 请等待

复测可能结果：

- 已恢复：进入完成页。
- 原问题未恢复：返回上一诊断步骤或要求重拍。
- 出现新问题：进入“新问题处理页”。
- 高风险/不确定：进入转人工页。

### 页面 6A：新问题处理页

用途：复测发现 SSH 恢复之外的新问题时继续处理。

内容示例：

```text
SSH 已恢复，但发现新的网络异常：
服务器可 ping 通，但应用端口不可达。

是否继续排查？
单击继续，长按转人工。
```

说明：

- 第一版只支持有限的新问题分支。
- 超出分支范围必须转人工。

### 页面 7：完成 / 转人工页

完成内容：

```text
远程 SSH 已恢复
服务器可重新远程运维
```

转人工内容：

```text
当前状态不适合继续自动指导
请联系人工运维专家
```

## 3. 技术架构

### 总体架构

```text
Air3 Android APK
  -> Backend API
  -> Ops Orchestrator
  -> AI Vision Service
  -> Remote Probe
  -> Database
```

### Air3 APK

建议继续基于当前已跑通的原生 Android Camera2 测试 App 演进，而不是回到 Unity SDK。

职责：

- Camera2 拍照。
- 麦克风录音。
- HTTP 上传图片。
- HTTP 上传音频。
- 显示后端文本。
- 管理 `sessionId`、`step`、`action`。
- 单击拍照/继续，长按重拍。
- 支持按住说话或固定语音入口。

### 后端 API

当前 MVP 可以继续用 Node.js 服务：

- 保留 `POST /air3/vision-test` 做兼容。
- 新增更正式的接口可后续再做：`POST /ops-glasses/sessions/events`。

### Ops Orchestrator

后端核心模块，负责：

- 状态机推进。
- 判断下一步。
- 复测结果分诊。
- 安全命令 allowlist。
- AI 结果解释。
- 远程复测。

### AI Vision

第一版即接入真实 AI 视觉模型。

职责：

- 识别控制台照片。
- 输出结构化结果。
- 不直接生成危险命令。

推荐接入方式：

- 眼镜端仍然只上传图片给后端，不直接调用 AI。
- 后端使用 OpenAI Responses API 发送 `input_text` + `input_image`。
- 图片可以使用 Base64 data URL 传入。
- AI 返回严格 JSON，后端验证 JSON 后再推进状态机。

推荐模型策略：

- 默认使用支持视觉输入和结构化输出的最新稳定模型。
- 若需要控制成本，可配置轻量视觉模型作为默认模型。
- 模型名通过环境变量配置，例如 `OPENAI_VISION_MODEL`，避免写死在 APK 中。

### AI Speech-to-Text

第一版加入真实语音转文字。

职责：

- 把眼镜端上传的现场语音转成文字。
- 将文字交给后端做意图分类。
- 支持中文现场表达，例如“我已经输入了”“照片看不清”“转人工”。

推荐接入方式：

- 眼镜端录制短音频后上传后端。
- 后端调用 OpenAI Speech-to-Text transcription 接口。
- 后端把转写文本映射为受控 `voiceIntent`。
- `voiceIntent` 只能推进状态或请求帮助，不能直接生成 shell 命令。

推荐模型策略：

- 默认使用 `gpt-4o-mini-transcribe` 控制成本。
- 对噪声环境或识别质量要求更高时使用 `gpt-4o-transcribe`。
- 通过 `OPENAI_TRANSCRIBE_MODEL` 环境变量配置。

### Remote Probe

职责：

- 检测服务器是否可达。
- 检测 SSH 22 端口是否恢复。
- 复测后识别是否出现新问题。

复测分类：

```text
recovered
same_issue_unresolved
new_issue_detected
needs_human_expert
```

第一版建议支持的新问题：

- SSH 已恢复，但 ping 不稳定。
- SSH 已恢复，但目标应用端口不可达。
- SSH 已恢复，但 AI 从控制台照片看到新的明显错误。

不建议第一版继续自动处理的新问题：

- 磁盘满。
- 文件系统损坏。
- 权限异常。
- 防火墙复杂规则。
- 网络路由复杂异常。

这些直接转人工。

## 4. 数据库表设计

第一版可以先用 SQLite，后续再迁移 PostgreSQL。

### `ops_sessions`

运维会话表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | sessionId |
| task_type | text | `ssh_console_recovery` |
| target_asset | text | 目标资产编号 |
| target_host | text | 目标服务器 IP |
| status | text | running/completed/escalated/aborted |
| current_step | text | 当前步骤 |
| created_at | text | 创建时间 |
| updated_at | text | 更新时间 |
| completed_at | text/null | 完成时间 |

### `ops_events`

每一步事件表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | eventId |
| session_id | text | 所属 session |
| step | text | 当前步骤 |
| action | text | 用户动作 |
| image_id | text/null | 关联图片 |
| instruction_text | text | 返回给眼镜的指令 |
| created_at | text | 创建时间 |

### `ops_images`

图片记录表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | imageId |
| session_id | text | 所属 session |
| event_id | text | 所属事件 |
| file_path | text | 本地图片路径 |
| image_bytes | integer | 图片大小 |
| image_kind | text | asset/console/output |
| created_at | text | 上传时间 |

### `ai_observations`

AI 识别结果表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | observationId |
| session_id | text | 所属 session |
| image_id | text | 关联图片 |
| screen_type | text | login/prompt/output/unclear/error |
| recognized_text | text | OCR/视觉识别文本 |
| confidence | real | 置信度 |
| raw_json | text | 原始 AI 返回 |
| created_at | text | 创建时间 |

### `ai_requests`

AI 请求记录表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | requestId |
| session_id | text | 所属 session |
| image_id | text | 关联图片 |
| provider | text | openai |
| model | text | 使用模型 |
| prompt_version | text | 提示词版本 |
| status | text | success/failed |
| latency_ms | integer | 请求耗时 |
| error_message | text/null | 错误信息 |
| created_at | text | 创建时间 |

### `voice_inputs`

语音输入表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | voiceInputId |
| session_id | text | 所属 session |
| event_id | text | 关联事件 |
| file_path | text | 本地音频路径 |
| audio_bytes | integer | 音频大小 |
| transcript | text | 语音转文字结果 |
| voice_intent | text | start/confirm_done/retake/escalate/unknown |
| confidence | real/null | 置信度 |
| created_at | text | 创建时间 |

### `remote_probes`

远程探测表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | probeId |
| session_id | text | 所属 session |
| host | text | 目标 IP |
| ping_reachable | integer | 0/1 |
| ssh_reachable | integer | 0/1 |
| app_port_reachable | integer/null | 应用端口是否可达 |
| result_type | text | recovered/same_issue_unresolved/new_issue_detected/needs_human_expert |
| summary | text | 探测摘要 |
| created_at | text | 探测时间 |

### `safe_commands`

安全命令表。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | text | commandId |
| command_key | text | ssh_status/ssh_start |
| command_text | text | 命令文本 |
| enabled | integer | 是否启用 |
| description | text | 用途说明 |

## 5. 接口设计

### 5.1 创建/推进眼镜事件

MVP 可复用：

```text
POST /air3/vision-test
```

正式接口建议：

```text
POST /ops-glasses/sessions/events
```

请求：

```json
{
  "sessionId": "",
  "taskType": "ssh_console_recovery",
  "step": "locate_server",
  "action": "console_photo_uploaded",
  "imageBase64": "..."
}
```

响应：

```json
{
  "ok": true,
  "sessionId": "session-id",
  "step": "run_diagnostic_command",
  "text": "请输入：sudo systemctl status ssh --no-pager\n只输入这一条命令，执行后请拍摄完整输出。",
  "requiresPhoto": true,
  "canRetake": true,
  "canEscalate": true,
  "timestamp": "2026-06-04T00:00:00.000Z"
}
```

### 5.2 查询 session 状态

```text
GET /ops-glasses/sessions/:sessionId
```

响应：

```json
{
  "ok": true,
  "session": {
    "id": "session-id",
    "status": "running",
    "currentStep": "run_diagnostic_command",
    "targetAsset": "ASSET-CONSOLE-001"
  }
}
```

### 5.3 远程复测

```text
POST /ops-glasses/sessions/:sessionId/probe
```

响应：

```json
{
  "ok": true,
  "pingReachable": true,
  "sshReachable": true,
  "appPortReachable": false,
  "resultType": "new_issue_detected",
  "summary": "SSH 已恢复，但应用端口不可达"
}
```

### 5.4 人工介入

```text
POST /ops-glasses/sessions/:sessionId/escalate
```

响应：

```json
{
  "ok": true,
  "status": "escalated",
  "text": "已转人工运维专家"
}
```

### 5.5 语音转文字

```text
POST /ops-glasses/sessions/:sessionId/voice
```

请求：

```json
{
  "step": "run_diagnostic_command",
  "audioBase64": "...",
  "audioFormat": "m4a"
}
```

响应：

```json
{
  "ok": true,
  "transcript": "我已经输入完成了",
  "voiceIntent": "confirm_done",
  "nextAction": "capture_command_output",
  "text": "请拍摄完整命令输出。"
}
```

允许的 `voiceIntent`：

```text
start_task
confirm_done
retake
escalate
describe_scene
unknown
```

### 5.6 复测分诊结果

复测分诊由后端内部生成，并返回给眼镜端。

```json
{
  "ok": true,
  "sessionId": "session-id",
  "step": "new_issue_triage",
  "text": "SSH 已恢复，但发现应用端口不可达。是否继续排查？",
  "retestResult": {
    "type": "new_issue_detected",
    "newIssueType": "app_port_unreachable",
    "canContinueAutomatically": true
  },
  "requiresPhoto": false,
  "canEscalate": true
}
```

### 5.7 AI 控制台照片识别（后端内部接口）

此接口不暴露给眼镜端，只供后端编排层调用。

```text
POST /internal/ai/console-observations
```

请求：

```json
{
  "sessionId": "session-id",
  "imageId": "image-id",
  "step": "confirm_diagnostic_output",
  "imageBase64": "..."
}
```

AI 结构化结果：

```json
{
  "screenType": "command_output",
  "recognizedText": "Active: inactive (dead)",
  "sshServiceState": "inactive",
  "photoQuality": "readable",
  "confidence": 0.86,
  "workflowSignal": "ssh_service_stopped",
  "riskLevel": "low",
  "needsBetterPhoto": false,
  "needsHumanExpert": false
}
```

后端只接受这些 `workflowSignal`：

```text
login_screen
shell_prompt
ssh_service_running
ssh_service_stopped
ssh_service_unknown
photo_unclear
unexpected_error
```

AI 不允许返回任意 shell 命令；恢复命令只能来自 `safe_commands`。

## 6. 开发阶段拆分

### 阶段 1：先做 App 页面

目标：先把小白在眼镜内看到的页面和操作流程做出来。

内容：

- 首页/任务入口。
- 拍照提示页。
- 上传分析页。
- 指令显示页。
- 重拍页。
- 完成页。
- 单击拍照/继续。
- 长按重拍。
- 语音入口 UI：按住说话或单独“语音确认”状态。
- 本地模拟后端返回文案。

### 阶段 2：再做后端接口

目标：把页面从本地模拟改成真实请求后端。

内容：

- `POST /air3/vision-test` 支持 `sessionId`、`step`、`action`。
- 后端内存 session。
- SSH 恢复状态机。
- 安全命令 allowlist。
- 复测分诊状态机。
- 真实 AI 视觉识别接口。
- 真实语音转文字接口。
- 语音意图分类。
- AI 结构化 JSON 校验。
- 返回多步骤指令。
- 日志打印每次图片大小、step、action。

### 阶段 3：再做数据库

目标：把 session 和操作记录持久化。

内容：

- SQLite 初始化。
- 创建 `ops_sessions`、`ops_events`、`ops_images`、`ai_observations`、`ai_requests`、`voice_inputs`、`remote_probes`。
- 保存图片到本地目录。
- 保存音频到本地目录。
- 保存 AI 识别结果和原始返回。
- 保存每一步返回的指令。
- 提供 session 查询接口。

### 阶段 4：最后打包 APK

目标：生成可安装、可演示的 Air3 APK。

内容：

- 配置后端地址。
- 构建 release/debug APK。
- 安装到 Air3。
- 配置 `adb reverse` 或局域网地址。
- 跑完整演示。

## 7. 每个阶段的验收标准

### 阶段 1 验收标准：App 页面

- Air3 上能打开 APK。
- 能看到任务首页。
- 单击后进入拍照/上传流程。
- 页面能显示模拟的多步骤指令。
- 长按能触发重拍状态。
- 能看到语音输入入口，并能进入“正在识别语音”的模拟状态。
- 不依赖后端也能演示 UI 流程。

### 阶段 2 验收标准：后端接口

- Air3 能把真实照片上传到后端。
- 后端能返回 `sessionId`。
- Air3 第二次上传会带上同一个 `sessionId`。
- 后端能根据当前 `step/action` 返回不同指令。
- 后端能把控制台照片发送给真实 AI 视觉模型。
- AI 能返回结构化 JSON，而不是自由文本。
- 后端能根据 AI 的 `workflowSignal` 推进或要求重拍。
- Air3 能上传短语音到后端。
- 后端能调用真实语音转文字模型。
- 后端能把“我已输入”“重拍”“转人工”等语音映射为受控 `voiceIntent`。
- 复测结果不是简单成功/失败，而是能区分 `recovered`、`same_issue_unresolved`、`new_issue_detected`、`needs_human_expert`。
- 当出现可处理的新问题时，眼镜能显示“继续排查/转人工”的下一步。
- 眼镜能显示至少三步流程：
  - 拍控制台，
  - 输入 `systemctl status ssh`，
  - 输入 `systemctl start ssh`。

### 阶段 3 验收标准：数据库

- 每次任务创建后 `ops_sessions` 有记录。
- 每次拍照后 `ops_events` 有记录。
- 图片元数据能写入 `ops_images`。
- AI 请求能写入 `ai_requests`。
- AI 识别结果能写入 `ai_observations`。
- 语音文件和转写文本能写入 `voice_inputs`。
- 后端重启后仍能查询历史 session。
- 能看到一次完整演示的操作链路。

### 阶段 4 验收标准：打包 APK

- 生成可安装 APK。
- APK 能在 Air3 真机启动。
- Air3 拍照、上传、显示后端返回结果正常。
- 完整跑通一次演示：
  - SSH 故障准备，
  - 眼镜提示小白操作，
  - 小白输入命令，
  - 后端复测恢复，
  - 眼镜显示完成，或在复测发现新问题时进入新问题分诊页。
- 保存截图、后端日志、数据库记录作为演示证据。
