# 叮当 V9 受管 AI 网关

该服务为 V9 眼镜提供设备短期会话、任务事件、签名工作流交付/执行回执、照片/视频证据、AI 流式回复和受管声纹生命周期。主 AI 与视觉固定使用上一稳定版 `qwen3-vl-plus`，实时 ASR 固定上一版 `fun-asr-realtime`；声纹只使用讯飞“声纹识别（新）”`s1aa729d0`。供应商长期密钥和设备 bootstrap 明文只进入服务器的 root-only 环境文件，不进入 APK、Git 或日志。

## 设备接口

- `POST /device-sync/session`
- `POST /device-sync/events`
- `GET /device-sync/skills?localProjectId=:localProjectId&localTaskId=:localTaskId`
- `POST /device-sync/tasks/:localTaskId/skill`
- `GET /device-sync/projects`
- `GET /device-sync/projects/:localProjectId`
- `POST /device-sync/tasks/:localTaskId/end-summary`
- `POST /device-sync/projects/:localProjectId/instructions`
- `GET /device-sync/work-orders?view=pending|pending_execute|executing|completed&limit=50`
- `GET /device-sync/work-orders/:orderId?resource=detail|node_form|flow_records|execution_records|checkins`
- `GET /device-sync/work-orders/:orderId?resource=checkin_form&direction=in|out`
- `GET /device-sync/work-orders/:orderId?resource=checkin_required&definitionId=:definitionId`
- `POST /device-sync/work-orders/:orderId/checkin`
- `GET /device-sync/workflows/assignments`
- `GET /device-sync/workflows/assignments/:assignmentId/package`
- `POST /device-sync/workflows/assignments/:assignmentId/status`
- `POST /device-sync/workflows/executions`
- `POST /device-sync/workflows/executions/:executionId/steps`
- `POST /device-sync/workflows/evidence`
- `POST /sessions/:sessionId/images`
- `POST /sessions/:sessionId/diagnose/stream`
- `GET /device-sync/voiceprint/profile`
- `POST /device-sync/voiceprint/consent`
- `POST /device-sync/voiceprint/enrollment/samples`
- `POST /device-sync/voiceprint/verify`
- `POST /device-sync/voiceprint/reenroll`
- `POST /device-sync/voiceprint/delete`
- `POST /admin/voiceprints/:profileId/revoke`

工作流网关只交付管理面已发布、已签名并绑定到当前设备的不可变执行包。没有真实分配时返回空列表，不生成演示工单、执行包或假成功。

AI 请求必须携带真实 `localProjectId` 与 `localTaskId`。网关会在单次模型调用前解析服务端受管的组织/用户身份、任务归属、不可变 Skill 快照、授权有效期、项目记忆、已确认项目指令和最近会话，并以有界结构化上下文发送给 `qwen3-vl-plus`。未知任务、关闭任务、过期或未授权 Skill 会明确拒绝，不使用本地假激活或默认 Skill。

Skill 启用、Skill 关闭、任务结束摘要和项目指令确认分别要求 `ACTIVATE_SKILL`、`DEACTIVATE_SKILL`、`END_TASK`、`CONFIRM_PROJECT_INSTRUCTION`，并要求稳定 `idempotencyKey`。同一幂等键改写请求会返回冲突；身份、Skill、项目、记忆和写操作响应均使用 `Cache-Control: no-store`。

MVS 工单只通过服务器端 HTTPS 连接器访问，眼镜只持有我方短期设备会话。列表和详情按资源懒加载，避免一次请求串行拉取全部表单与记录。签到和签退分别要求 `CONFIRM_MVS_CHECKIN` 与 `CONFIRM_MVS_CHECKOUT`；每次写入持久化幂等键、追踪号、状态和不可变审计事件。供应商失败返回 `retryable=true` 并保留 outbox 记录；后台使用同一幂等键和追踪号指数退避重试，默认最多 5 次，进程中断后的超时 `pending/retrying` 也可恢复。达到上限后明确标记 `mvs_retry_exhausted`，始终不返回假成功，也不允许 AI 自动写入 MVS。

## 本地验证

```powershell
python -m unittest discover -s v9-ops-gateway -p 'test_*.py' -v
```

## 云端边界

- 服务仅监听 `127.0.0.1:8790`。
- 公网入口为 `https://bb.chinacedar.top:2305/v9-ops`。
- 现有专家服务 `127.0.0.1:8787`、`/health`、`/api/*`、`/collab` 和网页 fallback 不改变。
- 部署前后都必须验证 `https://bb.chinacedar.top:2305/health`。
- `/etc/dingdang-v9-gateway.env` 权限必须为 `0600`，模型必须为 `qwen3-vl-plus`。
- `/etc/dingdang-v9-gateway.env` 必须由服务端配置 `V9_ORGANIZATION_ID` 与 `V9_USER_ID`，不得由 APK 自报身份。
- MVS 可选配置为 `V9_MVS_BASE_URL`、`V9_MVS_AUTHORIZATION`、`V9_MVS_ENGINEER_ID`；只要配置其中一项就必须完整配置，地址必须为 HTTPS，凭据不得进入 APK、Git、响应或日志。签到签退等 MVS 写操作还必须显式设置 `V9_MVS_WRITE_ENABLED=true`，默认关闭；未开启时读接口仍可用，写接口失败关闭且不会创建 outbox。
- MVS outbox 可选调节 `V9_MVS_RETRY_INTERVAL_SECONDS`、`V9_MVS_RETRY_BASE_SECONDS`、`V9_MVS_RETRY_STALE_SECONDS`、`V9_MVS_RETRY_MAX_ATTEMPTS` 和 `V9_MVS_RETRY_BATCH_SIZE`；模板保持空值，默认分别为 `5/5/30/5/10`，不能用于绕过幂等或二次确认。
- `/etc/dingdang-v9-voiceprint.env` 权限必须为 `0600`，接口必须为讯飞新版 `https://api.xf-yun.com/v1/private/s1aa729d0`。

## V9 Skill/知识清单后台同步

- 网关使用 `V9_CONTENT_MANIFEST_SYNC_BASE_URL` 调用 Supabase Edge Function 的 `/internal/v9/content-manifest`，该地址必须是 HTTPS。
- `V9_CONTENT_MANIFEST_SYNC_TOKEN` 只保存在 `/etc/dingdang-v9-gateway.env`，权限必须为 `0600`；Supabase 只保存该 token 的 SHA-256，不保存明文。
- 网关按 `V9_CONTENT_MANIFEST_REFRESH_SECONDS` 在后台刷新，失败按 `V9_CONTENT_MANIFEST_MAX_BACKOFF_SECONDS` 封顶退避；`task_started` 只投递本地触发信号，不在设备事件、AI、ASR、相机或声纹请求线程执行网络同步。
- 同步失败保留最后一份仍有效的清单并记录稳定错误码；无有效清单时继续返回 `content_manifest_unavailable`，不得启用本地假授权、默认 Skill 或其他模型。
- V9 主 AI/视觉固定 `qwen3-vl-plus`，实时 ASR 固定 `fun-asr-realtime`，声纹固定讯飞新版 `s1aa729d0`；清单同步不改变这三条运行链路。

## V9 私有 PDF/DOCX 解析

- 内部接口为 `POST /internal/knowledge/parse`，只接受独立 Bearer token；网关环境仅保存 `V9_KNOWLEDGE_PARSER_TOKEN_SHA256`，Supabase Edge Function 仅保存对应明文 token。
- PDF 使用锁定版本 `pypdf==6.15.0`，DOCX 使用标准库 ZIP/XML 安全解析；统一限制文件大小、页数、正文大小、压缩膨胀和归档路径，并校验原文件与正文 SHA-256。
- 解析响应只包含正文和正文摘要，不包含原文件、设备身份、AI/ASR/声纹/MVS 凭据或内部堆栈。token 未配置时接口返回 503，不使用本地假正文。
- `deploy/install.sh` 为每个不可变 release 创建 `.venv` 并安装 `requirements.txt`；systemd 只从当前 release 的 venv 启动，回滚时随 release 一起切换依赖版本。

`deploy/install.sh` 以不可变 release 目录安装，并保存 Caddy 备份；`deploy/rollback.sh` 恢复上一次 Caddy 配置和服务 release。讯飞声纹使用独立服务端配置和生命周期接口，不复用或下发 AI 密钥。数据库只保存供应商分组/特征引用、同意版本、状态和审计事件，不保存原始音频或模板正文。

## 生产桥接配置发布

Supabase、V9 网关和声纹管理使用由 `scripts/prepare-v9-production-secrets.mjs` 同时生成的三件套。网关 overlay 只允许内容清单同步、控制面同步和知识解析字段；声纹 overlay 只允许 `V9_VOICEPRINT_ADMIN_TOKEN_SHA256` 与过渡期的 `V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256`。额外字段、模型漂移、非 `s1aa729d0` 声纹服务、非 HTTPS 控制面或 token/hash 格式错误均失败关闭。

云服务器按以下顺序发布：

```sh
sudo ./deploy/stage-production-overlay.sh \
  /root/dingdang-v9.env.local \
  /root/dingdang-v9-voiceprint.env.local \
  <release-id>

# 声纹 token 轮换时，先上线双哈希运行时，再激活 transition 配置。
sudo ./deploy/activate-production-overlay.sh <release-id>
```

stage 生成带 `SHA256SUMS` 的自包含 root-only candidate，candidate 内同时固化合并器和 stage/activate/rollback 工具；它不重启 V9、不改 Caddy、不触碰专家协同。activate 原子更新 `/etc/dingdang-v9-gateway.env` 和 `/etc/dingdang-v9-voiceprint.env`，只重启 `dingdang-v9-gateway.service`，并验证本机 health/ready、公网 V9 health 与专家 health；失败时在脚本内部恢复原 `/etc` 配置和原 `current/previous` 指针。人工回滚使用 current release 内的 `rollback-production-overlay.sh`，不直接编辑 `/etc` 文件。

声纹 token 零中断轮换顺序固定为：`update-runtime.sh` 上线双哈希兼容 -> transition 配置激活 -> 新 token 公网验收 -> Supabase secrets/Edge 部署 -> steady 配置清除 previous hash。内容同步、知识解析和其他桥接 token 必须从生产 seed 复用，不随声纹轮换随机改变。
