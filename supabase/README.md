# Air3 运维眼镜 Supabase 后端

这个目录是运维眼镜后端的 Supabase 版本。现在采用你要求的方式：**表结构由后端代码定义，运行时自动创建**。

也就是说，你不需要手动写数据库语法来建表。核心结构在：

```text
supabase/functions/ops-glasses/automigrate.ts
```

Edge Function 首次收到请求时，如果 `AUTO_MIGRATE=true`，会自动创建：

- Postgres enum 类型
- 运维会话、事件、图片、AI、语音、复测、安全命令等表
- RLS 策略
- Storage 私有 bucket：`ops-glasses-captures`
- 受控安全命令种子数据

自动迁移不会创建演示资产。开始任务前必须先在 `ops_assets` 登记真实资产，并把 `DEFAULT_ASSET_TAG` 指向该资产；缺少资产、资产不存在或命中历史演示资产时，接口会失败关闭。

## V9 网关内部 Skill/知识同步

V9 网关通过 `GET /internal/v9/content-manifest?localProjectId=:localProjectId` 拉取服务端解析后的执行清单。该接口不对眼镜 APK 开放，只接受独立 Bearer token，并使用服务器固定的组织、用户和设备身份查找真实项目及已发布授权。

Supabase Function Secrets 只保存：

```ini
V9_GATEWAY_SYNC_TOKEN_SHA256=<同步明文 token 的 SHA-256>
V9_GATEWAY_SYNC_ORGANIZATION_ID=org-huafang
V9_GATEWAY_SYNC_USER_ID=user-field-engineer
V9_GATEWAY_SYNC_DEVICE_ID=air3-REPLACE_WITH_DEVICE_ID
V9_VOICEPRINT_ADMIN_URL=https://<v9-gateway-host>/v9-ops
V9_VOICEPRINT_ADMIN_TOKEN=<独立的声纹管理员 Bearer token>
V9_KNOWLEDGE_PARSER_URL=https://<v9-gateway-host>/v9-ops
V9_KNOWLEDGE_PARSER_TOKEN=<独立的知识文档解析 Bearer token>
V9_DEVICE_ACTIVATION_BACKEND_BASE_URL=https://<v9-gateway-host>/v9-ops
V9_DEVICE_ACTIVATION_POLICY_VERSION=v9-production-1
OPS_ACCOUNT_RECOVERY_REDIRECT_URL=https://<v9-management-web-host>/
```

`OPS_ACCOUNT_RECOVERY_REDIRECT_URL` 必须是管理 Web 的 HTTPS 地址。超级管理员触发身份恢复时，Edge Function 只请求 Supabase Auth 发送恢复邮件，不生成、返回或保存恢复链接与新密码；未配置或不是 HTTPS 时失败关闭。

同步明文 token 只保存在 V9 网关 root-only 环境文件中。设备清单不返回 Skill `rules`；内部同步接口只为已经授权、已发布且不可变的 Skill 版本补取规则，并继续执行字段白名单、URL/脚本/密钥字段拒绝、ETag/304 和有效期校验。

## V9 私有知识文档解析

TXT/Markdown 继续在 Edge Function 内完成 UTF-8 校验和正文规范化；PDF/DOCX 由 Edge Function 通过 `V9_KNOWLEDGE_PARSER_URL` 调用 V9 网关的私有 `/internal/knowledge/parse`。请求只允许 HTTPS，使用独立 Bearer token，默认 15 秒超时，并对返回正文再次计算 SHA-256；原文件、供应商密钥和解析器凭据不会返回浏览器或眼镜端。

同一随机 token 的明文只放在 Supabase Function Secret `V9_KNOWLEDGE_PARSER_TOKEN`，SHA-256 只放在网关 root-only 环境变量 `V9_KNOWLEDGE_PARSER_TOKEN_SHA256`。未配置或临时不可用时附件保持 `processing_unavailable`，管理 Web 可在服务恢复后从私有 Storage 显式重试；文档损坏、加密、超限或无可提取正文时进入 `failed`，知识版本不能提交审核或发布。

## V9 声纹中央管理

管理 Web 不直接访问讯飞或 V9 网关。浏览器先访问 Supabase Edge Function 的 `/management/voiceprints`，Edge Function 再使用独立的服务端 Bearer token 代理到 V9 网关。返回内容只包含人员、设备、状态、录入进度、失败次数、授权版本和最近验证等脱敏字段，不返回讯飞密钥、group/feature 引用或录音。

声纹撤销只允许超级管理员执行，必须提供原因、确认词和幂等键。`audit_events_voiceprint_revoke_idempotency_idx` 保证同一组织内重复提交同一撤销请求时只生成一条审计记录。讯飞 `APPID`、`APISecret`、`APIKey` 和服务地址仍只保存在 V9 网关的 root-only 环境文件中。

V9 的运行模型合同不由这些 Supabase 旧版兼容变量决定：主 AI/视觉固定为 `qwen3-vl-plus`，实时 ASR 固定为 `fun-asr-realtime`，声纹固定使用讯飞新版 `s1aa729d0`。Supabase 中原有 `OPENAI_*` 默认值保留给 V8 历史兼容路径，避免影响当前稳定端。

## V9 设备激活

正式 V9 支持两种设备投放方式：企业 MDM 继续通过 Android 受管配置下发我方 HTTPS 地址和设备 bootstrap；非 MDM 设备由超级管理员调用 `POST /management/devices/:deviceId/activation-codes` 生成 5 到 15 分钟的一次性激活码，再由眼镜调用 `POST /device-activation/redeem` 兑换。数据库只保存激活码、设备实例和 bootstrap 的 SHA-256，明文激活码只在签发响应显示一次，明文 bootstrap 只在成功兑换响应返回一次。

激活前必须先登记并绑定设备。兑换会在单事务中锁定激活码、校验正式包名、设备型号、账号与绑定状态，将激活码标记为 used，并轮换设备 bootstrap。响应使用 `Cache-Control: no-store`，不包含主 AI、ASR、讯飞声纹或管理员密钥。

`supabase/migrations/` 里的 SQL 只作为备份和审计参考，不是主流程。

## 后端接口

Edge Function 名称：`ops-glasses`

部署后基础地址：

```text
https://<project-ref>.supabase.co/functions/v1/ops-glasses
```

接口：

```text
GET  /health
POST /sessions/events
GET  /sessions/:sessionId
POST /sessions/:sessionId/images
WS   /sessions/:sessionId/asr
POST /sessions/:sessionId/diagnose/stream
POST /sessions/:sessionId/voice
POST /sessions/:sessionId/probe
POST /sessions/:sessionId/escalate
```

除 `/health` 外，请求需要带后端调用密钥：

```http
x-ops-glasses-key: <OPS_GLASSES_API_KEY>
```

## 叮当运维AI 快路径接口

聊天式 App 优先使用下面三段接口，目的是让眼镜端“先看到反馈，再做慢记录”。

```http
POST /sessions/:sessionId/images
Content-Type: application/json
```

```json
{
  "image_base64": "data:image/jpeg;base64,...",
  "image_kind": "field_photo",
  "client_ts": "2026-06-09T00:00:00.000Z"
}
```

返回：

```json
{
  "ok": true,
  "session_id": "session-id",
  "image_id": "image-id",
  "image_bytes": 123456
}
```

这个接口只存图并返回 `image_id`，不调用 GPT。

```http
WS /sessions/:sessionId/asr
```

眼镜端发送 `start`、PCM 音频片段和 `finish`；后端代理 DashScope Fun-ASR realtime。Fun-ASR 只产生 `partial/final transcript`，不产生诊断、不改写意图。

```http
POST /sessions/:sessionId/diagnose/stream
Content-Type: application/json
```

```json
{
  "image_id": "image-id",
  "final_text": "这个设备为什么报警",
  "client_context": {
    "source": "voice",
    "app": "dingdang-ops-ai"
  }
}
```

返回 `text/event-stream`：

```text
event: delta
data: {"text":"先看报警灯和压力表，"}

event: done
data: {"message_id":"..."}
```

数据库审计和长上下文整理通过 best-effort 异步执行，不阻塞图片返回、语音转写或 GPT 首 token。

## 眼镜端主接口

```http
POST /sessions/events
Content-Type: application/json
```

创建任务或推进任务：

```json
{
  "sessionId": "",
  "taskType": "ssh_console_recovery",
  "step": "locate_server",
  "action": "console_photo_uploaded",
  "imageBase64": "data:image/jpeg;base64,...",
  "imageKind": "console"
}
```

返回给眼镜端：

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "run_diagnostic_command",
  "text": "请输入：sudo systemctl status ssh --no-pager\n只输入这一条命令。执行后请单击拍摄完整输出。",
  "requiresPhoto": true,
  "canRetake": true,
  "canEscalate": true,
  "imageBytes": 659582,
  "timestamp": "2026-06-04T00:00:00.000Z"
}
```

## 语音接口

```http
POST /sessions/:sessionId/voice
Content-Type: application/json
```

```json
{
  "audioBase64": "data:audio/mp4;base64,...",
  "audioFormat": "m4a"
}
```

语音只映射为受控意图：

```text
start_task
confirm_done
retake
escalate
describe_scene
unknown
```

它不会被当作 shell 命令执行。

## 远程复测

```http
POST /sessions/:sessionId/probe
```

复测结果类型：

```text
recovered
same_issue_unresolved
new_issue_detected
needs_human_expert
```

默认 `REMOTE_PROBE_MODE=disabled`。未配置真实探针时，接口返回可恢复的 `503 remote_probe_not_configured`，不会伪造复测成功。生产环境只能显式选择：

- `REMOTE_PROBE_MODE=http`：调用企业内网探针服务，由探针检测 SSH/应用端口。
- `REMOTE_PROBE_MODE=tcp`：仅适合 Edge Function 运行环境能直接访问目标服务器的情况。

`http` 模式必须配置不含账号密码的 HTTPS `REMOTE_PROBE_URL`。探测主机和端口来自任务已绑定的真实资产，不接受客户端临时指定目标。

## 环境变量

Supabase Function Secrets：

```ini
OPS_GLASSES_API_KEY=<给眼镜端配置的后端调用密钥>

AUTO_MIGRATE=true

OPENAI_API_KEY=<openai-api-key>
OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
OPENAI_VISION_MODEL=qwen3-vl-plus
OPENAI_TRANSCRIBE_API_KEY=<stt-api-key>
OPENAI_TRANSCRIBE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
OPENAI_TRANSCRIBE_MODEL=fun-asr-realtime
DASHSCOPE_API_KEY=<dashscope-api-key>
DASHSCOPE_FUNASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference
DASHSCOPE_FUNASR_MODEL=fun-asr-realtime
V9_KNOWLEDGE_PARSER_URL=https://<v9-gateway-host>/v9-ops
V9_KNOWLEDGE_PARSER_TOKEN=<独立的知识文档解析 Bearer token>

DEFAULT_ASSET_TAG=<已在 ops_assets 登记的真实资产标签>
DEFAULT_TARGET_SSH_PORT=22
DEFAULT_TARGET_APP_PORT=
REMOTE_PROBE_MODE=disabled
REMOTE_PROBE_URL=https://<受控内网探针地址>/check
```

说明：

- 托管 Edge Function 自动提供 `SUPABASE_URL`、`SUPABASE_SERVICE_ROLE_KEY` 和 `SUPABASE_DB_URL`；Supabase 禁止自定义 secret 使用 `SUPABASE_` 前缀，因此这三项不得写入生产上传 env 文件。
- `AUTO_MIGRATE=true`：后端运行时自动创建/补齐表结构。
- 平台注入的 `SUPABASE_DB_URL` 用于执行建表 SQL，平台注入的 `SUPABASE_SERVICE_ROLE_KEY` 用于 Function 写表和 Storage；应用日志和上传 env 均不得输出它们。
- `OPS_GLASSES_API_KEY`：给眼镜端调用 API 用，不要使用 service role key。
- `DASHSCOPE_API_KEY`：只放在后端 Function Secrets 中，用于代理 Fun-ASR realtime；不要写进 APK。
- `DASHSCOPE_FUNASR_MODEL`：固定使用接口模型 ID `fun-asr-realtime`，不要写页面中文名。
- `V9_KNOWLEDGE_PARSER_URL` 与 `V9_KNOWLEDGE_PARSER_TOKEN`：必须成对配置；地址必须是我方 V9 网关 HTTPS 前缀，token 不得复用设备、声纹、MVS 或供应商凭据。
- `DEFAULT_ASSET_TAG`：必须指向数据库中已登记的真实资产；禁止使用历史演示资产。
- `REMOTE_PROBE_MODE`：缺省和未验收阶段保持 `disabled`；生产验收必须配置为 `tcp` 或 `http`。
- `REMOTE_PROBE_URL`：仅 `http` 模式必填，必须是受控 HTTPS 地址且不能包含账号密码。

## 部署步骤

本机当前没有安装 Supabase CLI，所以这里先给部署命令：

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase functions deploy ops-glasses
supabase secrets set AUTO_MIGRATE=true
supabase secrets set OPS_GLASSES_API_KEY=<random-long-secret>
supabase secrets set OPENAI_API_KEY=<openai-api-key>
supabase secrets set OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
supabase secrets set OPENAI_VISION_MODEL=qwen3-vl-plus
supabase secrets set OPENAI_TRANSCRIBE_API_KEY=<stt-api-key>
supabase secrets set OPENAI_TRANSCRIBE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
supabase secrets set OPENAI_TRANSCRIBE_MODEL=fun-asr-realtime
supabase secrets set DASHSCOPE_API_KEY=<dashscope-api-key>
supabase secrets set DASHSCOPE_FUNASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference
supabase secrets set DASHSCOPE_FUNASR_MODEL=fun-asr-realtime
supabase secrets set V9_KNOWLEDGE_PARSER_URL=https://<v9-gateway-host>/v9-ops
supabase secrets set V9_KNOWLEDGE_PARSER_TOKEN=<knowledge-parser-bearer-token>
supabase secrets set DEFAULT_ASSET_TAG=<已登记的真实资产标签>
supabase secrets set DEFAULT_TARGET_SSH_PORT=22
supabase secrets set REMOTE_PROBE_MODE=http
supabase secrets set REMOTE_PROBE_URL=https://<受控内网探针地址>/check
```

部署后第一次请求会触发自动建表：

```powershell
Invoke-RestMethod https://<project-ref>.supabase.co/functions/v1/ops-glasses/health
```

然后调用业务接口：

```powershell
Invoke-RestMethod https://<project-ref>.supabase.co/functions/v1/ops-glasses/sessions/events `
  -Method POST `
  -Headers @{ "x-ops-glasses-key" = "<OPS_GLASSES_API_KEY>" } `
  -ContentType "application/json" `
  -Body '{"action":"start_task"}'
```

## 验收顺序

1. 确认托管 Function 已自动注入 `SUPABASE_DB_URL`，并设置 `AUTO_MIGRATE=true`。
2. 部署 `ops-glasses` Edge Function。
3. 调用 `/health` 返回 `ok: true`。
4. 在 `ops_assets` 登记真实资产并配置 `DEFAULT_ASSET_TAG`，确认缺资产和历史演示资产均被拒绝。
5. 调用 `/sessions/events`，确认返回 `sessionId` 和下一步指令。
6. Supabase 表里能看到 `ops_sessions`、`ops_events` 记录。
7. 上传图片后，能看到 `ops_images` 记录和 Storage 文件。
8. 配置服务端 AI Key 后，上传真实现场照片，确认 `ai_requests` 和 `ai_observations` 有记录。
9. 保持 `REMOTE_PROBE_MODE=disabled` 调用 `/probe`，确认返回可恢复的 503，且没有成功复测记录。
10. 配置并验收真实 `tcp` 或 HTTPS `http` 探针后，再确认 `/probe` 能进入 `recovered`、`same_issue_unresolved` 或 `new_issue_detected` 分支。
