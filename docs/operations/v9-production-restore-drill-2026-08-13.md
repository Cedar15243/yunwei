# V9 生产隔离恢复演练记录

- 执行时间：`2026-08-13T06:25:05Z`。
- 目标：`36.212.8.89` 上的 V9 网关；演练只恢复至 `/tmp` 隔离目录。
- 备份：`20260812T182711Z`，manifest SHA-256 为 `5fa6ceb339c69c9a13b5bf5a32b77acf72beaa89d45dcc4a9d3ad7d59e1fad9d`。

## 结果

- `gateway.db`：SQLite `PRAGMA quick_check=ok`。
- `voiceprint.db`：SQLite `PRAGMA quick_check=ok`。
- `evidence/`：备份 `3` 个文件，恢复 `3` 个文件；各文件与 manifest 的大小和 SHA-256 一致。
- V9：本机 `/health`、本机 `/ready`、公网 `/v9-ops/health`、公网 `/v9-ops/ready` 全部成功。
- 专家协同：`https://bb.chinacedar.top:2305/health` 成功。
- 生产数据、V9 release 指针、V9 配置指针、专家服务和 Caddy 均未变更；临时恢复目录已由脚本清理。

## 结论

该记录证明当前网关本地 SQLite 与证据媒体备份可被隔离恢复和校验，且演练未导致生产探针回归。它不证明 Supabase、对象存储、MVS、通知渠道、完整灾难处置流程或市场 GA 已通过。
