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
- 演示资产和安全命令种子数据

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
POST /sessions/:sessionId/voice
POST /sessions/:sessionId/probe
POST /sessions/:sessionId/escalate
```

除 `/health` 外，请求需要带后端调用密钥：

```http
x-ops-glasses-key: <OPS_GLASSES_API_KEY>
```

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

默认 `REMOTE_PROBE_MODE=mock`，适合 Supabase 云端演示。企业内网真实复测建议：

- `REMOTE_PROBE_MODE=http`：调用企业内网探针服务，由探针检测 SSH/应用端口。
- `REMOTE_PROBE_MODE=tcp`：仅适合 Edge Function 运行环境能直接访问目标服务器的情况。

## 环境变量

Supabase Function Secrets：

```ini
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service-role-key>
OPS_GLASSES_API_KEY=<给眼镜端配置的后端调用密钥>

AUTO_MIGRATE=true
SUPABASE_DB_URL=postgresql://postgres.<project-ref>:<password>@aws-xxx.pooler.supabase.com:6543/postgres

OPENAI_API_KEY=<openai-api-key>
OPENAI_VISION_MODEL=gpt-4.1-mini
OPENAI_TRANSCRIBE_MODEL=gpt-4o-mini-transcribe

DEMO_ASSET_TAG=ASSET-CONSOLE-001
DEMO_TARGET_HOST=192.168.1.50
DEMO_TARGET_SSH_PORT=22
REMOTE_PROBE_MODE=mock
MOCK_SSH_REACHABLE=false
```

说明：

- `AUTO_MIGRATE=true`：后端运行时自动创建/补齐表结构。
- `SUPABASE_DB_URL`：数据库连接串，用于执行建表 SQL。没有它，`supabase-js` 只能做 CRUD，不能建表。
- `SUPABASE_SERVICE_ROLE_KEY`：用于 Edge Function 写表和 Storage。
- `OPS_GLASSES_API_KEY`：给眼镜端调用 API 用，不要使用 service role key。

## 部署步骤

本机当前没有安装 Supabase CLI，所以这里先给部署命令：

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase functions deploy ops-glasses
supabase secrets set SUPABASE_DB_URL="<postgres-connection-string>"
supabase secrets set AUTO_MIGRATE=true
supabase secrets set OPS_GLASSES_API_KEY=<random-long-secret>
supabase secrets set OPENAI_API_KEY=<openai-api-key>
supabase secrets set OPENAI_VISION_MODEL=gpt-4.1-mini
supabase secrets set OPENAI_TRANSCRIBE_MODEL=gpt-4o-mini-transcribe
supabase secrets set DEMO_ASSET_TAG=ASSET-CONSOLE-001
supabase secrets set DEMO_TARGET_HOST=192.168.1.50
supabase secrets set DEMO_TARGET_SSH_PORT=22
supabase secrets set REMOTE_PROBE_MODE=mock
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

1. 设置 `AUTO_MIGRATE=true` 和 `SUPABASE_DB_URL`。
2. 部署 `ops-glasses` Edge Function。
3. 调用 `/health` 返回 `ok: true`。
4. 调用 `/sessions/events`，确认返回 `sessionId` 和下一步指令。
5. Supabase 表里能看到 `ops_sessions`、`ops_events` 记录。
6. 上传图片后，能看到 `ops_images` 记录和 Storage 文件。
7. 配置 OpenAI Key 后，上传真实控制台照片，确认 `ai_requests` 和 `ai_observations` 有记录。
8. 调用 `/probe`，确认能进入 `recovered`、`same_issue_unresolved` 或 `new_issue_detected` 分支。
