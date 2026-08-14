# V9 监控与告警合同

## 探针

- `/health`：存活探针，只证明进程能响应，不访问 AI、ASR、讯飞声纹、MVS 或 Supabase。
- `/ready`：本地就绪探针，执行 SQLite `PRAGMA quick_check` 并确认证据目录存在；不发起外部网络调用，避免增加眼镜请求延迟。
- 专家站点 `/health`：独立探针。V9 发布和回滚必须同时检查，失败时不能影响或重写专家路由。

`v9-ops-gateway/deploy/health-check.sh` 是人工/值班脚本；`monitoring/prometheus-rules.yml` 是 blackbox exporter + Prometheus 的告警规则模板。上线时必须把三个目标配置为独立 job，避免把专家站点失败误报为 V9 内部存储故障。

`dingdang-v9-monitor` 是独立的 systemd oneshot/timer 单元，每分钟检查本机 V9 `health/ready`、公网 V9 `health/ready`、专家 `/health`、备份 timer 和最近完整备份的新鲜度。它运行在 `/opt/dingdang-v9-monitor` 的不可变 release 中，不重启或改写 V9 网关、专家服务、Caddy、数据库或眼镜请求链路。状态持久化在 `/var/lib/dingdang-v9-monitor/state.json`，首次部署且未配置 webhook 时只能报告 `local_audit_only`，不得声称告警已送达。

## 告警与处置

| 告警 | 条件 | 级别 | 首个动作 |
| --- | --- | --- | --- |
| `DingdangV9GatewayUnavailable` | `/health` 探针失败 2 分钟 | critical | 检查 systemd、最近发布和 Caddy，保留专家站点不变 |
| `DingdangV9GatewayReadinessFailed` | `/ready` 探针失败 2 分钟 | critical | 停止写入、检查磁盘/SQLite，执行恢复 runbook |
| `DingdangV9ProbeLatencyHigh` | 探针延迟 >1 秒持续 5 分钟 | warning | 查 CPU、磁盘和网络；不在眼镜端增加重试 |
| `backup_stale` 或 backup timer 非 active | 最近完整备份超过 27 小时或 timer 非 active | critical | 检查备份 service/timer 与备份目录；保留服务状态后执行隔离恢复演练 |

## 记录要求

网关现有 JSON 访问日志不得记录 Authorization、请求体、原始声纹音频或供应商密钥。值班系统应采集状态码、路径、时间戳和探针耗时，并以发布 ID、trace ID 或事件 ID 关联审计；日志保留期限和告警通知渠道由生产组织策略注入，不能写死在 APK。

通知端点由 `/etc/dingdang-v9-monitor.env` 的 `V9_MONITOR_WEBHOOK_URL` 与可选的 `V9_MONITOR_WEBHOOK_BEARER_TOKEN` 配置，权限必须为 `0600`。未配置时只写本机审计状态；配置后仅在故障、恢复或上次送达失败时发送结构化事件。任何 webhook 失败都会保留未送达状态并以退出码 `2` 失败，不会被记成已通知。通知演练使用 `monitoring_probe.py --notification-drill`，只发送带 `notification_drill` 标签的测试事件，不更改生产告警状态。

## 2026-08-13 生产安装状态

- 独立监控 release：`/opt/dingdang-v9-monitor/releases/20260813T061500Z-monitoring-v1`。
- `dingdang-v9-monitor.timer` 已启用并 active；首次运行于 `2026-08-13T06:23:44Z`，7 项检查均成功。
- 本机 V9 延迟 `1-2 ms`，公网 V9 `43-52 ms`，专家健康 `53 ms`；备份年龄约 11.9 小时。
- 当前没有生产 webhook，因此运行状态为 `local_audit_only`，通知送达演练仍为 pending。

## 放行边界

本合同和本地规则可在工作树验证。2026-08-13 的真实隔离恢复演练与独立本机监控已完成，但没有真实通知渠道、磁盘满演练、云端依赖故障演练、第三方渗透和独立安全审批时，市场 GA 仍不得放行。
