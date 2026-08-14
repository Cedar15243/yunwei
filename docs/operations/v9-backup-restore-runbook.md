# V9 备份与恢复运行手册

## 目标与边界

V9 网关的本地 SQLite 状态包含设备短期会话、项目/任务事件、工作流执行、媒体证据索引、MVS 操作幂等记录和声纹生命周期审计。备份恢复只在 V9 网关目录内操作，不覆盖稳定 APK、专家协同站点或 Supabase 云端数据。

`gateway.db`、`voiceprint.db` 和 `evidence/` 下的照片/视频分片由 `backup_restore.py` 复制。数据库使用 SQLite 在线备份 API；证据文件逐项写入 manifest。每份备份带 `manifest.json`（schema version 2），包含 UTC 时间、文件大小、SHA-256 和数据库 `PRAGMA quick_check` 结果；数据库或证据集合校验失败时，恢复在改写目标前停止。

## 目标指标

- RPO：24 小时。定时器每天 UTC `02:15` 运行，允许 15 分钟随机延迟；生产部署可在不超过 24 小时的窗口内调整。
- RTO：60 分钟。包含停止服务、选择最近完整备份、恢复、就绪检查和网关/专家健康检查。
- 保留：默认 14 天，由 `V9_BACKUP_RETENTION_DAYS` 控制；不得设置为负数或非数字。

## 日常操作

```sh
systemctl status dingdang-v9-backup.timer
journalctl -u dingdang-v9-backup.service --since today
cat /var/backups/dingdang-v9-gateway/last-good
/opt/dingdang-v9-gateway/current/restore-drill.sh
```

恢复演练脚本只恢复到临时目录，不停生产服务、不改生产数据。它会输出开始/结束时间、备份 manifest SHA-256、两份 SQLite 的 `PRAGMA quick_check`、证据文件数量、本机与公网 V9 `health/ready`、专家健康和耗时。每季度至少执行一次并保存这些输出。

## 生产恢复

1. 记录事件编号、当前版本和最近一次 `last-good` 备份；先确认专家站点仍可独立访问。
2. 停止 V9 网关和备份定时器：`systemctl stop dingdang-v9-gateway.service`、`systemctl disable --now dingdang-v9-backup.timer`。
3. 运行 `backup_restore.py verify <backup>`；任何 hash、字节数或 SQLite 检查失败都不得继续。
4. 运行 `backup_restore.py restore "$V9_DATA_DIR" <backup>`，再启动网关。
5. 依次检查 `http://127.0.0.1:8790/health`、`http://127.0.0.1:8790/ready`、V9 HTTPS 路径和专家 HTTPS 健康端点。
6. 重新启用备份定时器，记录 RTO、数据缺口和审计事件；无法达到 60 分钟时升级为 P1。

生产 Supabase、对象存储和 MVS 数据不由该脚本备份。云端恢复必须使用 Supabase/对象存储供应商的受控备份流程，并在真实项目上完成演练后才能作为市场 GA 证据。

## 2026-08-13 隔离恢复演练

- 时间：`2026-08-13T06:25:05Z`。
- 备份：`/var/backups/dingdang-v9-gateway/20260812T182711Z`。
- manifest SHA-256：`5fa6ceb339c69c9a13b5bf5a32b77acf72beaa89d45dcc4a9d3ad7d59e1fad9d`。
- 恢复目标：临时目录；生产数据库、current/previous 指针、网关、专家服务和 Caddy 均未改写。
- 校验：`gateway.db` 与 `voiceprint.db` 的 `PRAGMA quick_check=ok`；证据文件 `3/3` 恢复；备份恢复前后 SHA-256 清单一致。
- 探针：本机和公网 V9 `health/ready`、专家 `/health` 均返回成功。
- 测得脚本恢复阶段低于 1 秒；此值只覆盖隔离拷贝和校验，不替代完整故障处置 RTO 的 60 分钟目标。
