# V9 Skill/知识清单同步运输验证

日期：2026-08-03

## 本轮完成

- Supabase 新增 root-only `GET /internal/v9/content-manifest`，按服务器固定的组织、用户、设备身份和 `localProjectId` 解析真实项目，只返回已授权、已发布、不可变的 Skill/知识执行清单。
- 设备清单继续不返回 Skill `rules`；内部接口只为服务端执行补取规则，并递归拒绝 URL、脚本、token、password、secret 等危险字段。
- V9 网关新增 HTTPS 清单客户端和后台 worker，支持 ETag/304、5 秒超时、2 MiB 上限、周期刷新、任务启动触发、指数退避和撤销传播。
- `task_started` 请求只写入事件并调用内存队列 `trigger(localProjectId)`，不会在设备事件、AI、ASR、相机或声纹请求线程执行 Supabase 网络请求。
- SQLite 保存脱敏同步状态、失败次数和下次重试时间；worker 重启后继续遵守持久化退避，失败不删除最后一份仍有效的清单。
- 部署脚本已携带 `content_manifest_sync.py`，网关保存同步明文 token，Supabase 只保存 SHA-256；两端都不复用 AI、ASR、声纹或设备会话密钥。

## 固定模型合同

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 声纹：讯飞新版 `s1aa729d0`。
- Sherpa 只用于云端失败或断网回退；V9 不使用 OpenClaw 或其他新增模型。
- Android 安全构建生成配置为 `secureRuntime=true`、`directGptEnabled=false`，客户端直连 AI/ASR/讯飞长期密钥和模型字段均为空。

## 新鲜验证

- Python 网关全量：`100/100` 通过；核心模块 `py_compile` 通过。
- Supabase Edge Function 全量：`149/149` 通过；`deno check index.ts` 通过。
- 管理 Web：`64/64` 通过；TypeScript 和生产构建通过。
- Android：`447/447` 通过；21 个 Gradle 任务全部重新执行。
- `npm run validate:supabase`、`npm run validate:native-hud`、`npm run validate:native-build` 通过。
- Supabase 生产就绪审计已检查内部同步路由和 13 项 secrets，当前 `9/12`，因缺 project ref、access token 且进程环境为 `0/13`，明确判定不可部署、不可运行真实线上 smoke。
- `git diff --check` 无空白错误；生产路径模型扫描只命中 `qwen3-vl-plus`、`fun-asr-realtime`、`s1aa729d0`；常见密钥、私钥和 JWT 模式无命中。

## 当前边界

- 本轮完成的是本地代码、合同和自动化验证，尚未真实部署 Supabase 迁移、Edge Function、管理 Web、同步 token 和新版 V9 网关 release。
- 未完成真实 Supabase 到网关的 HTTPS 联调、授权发布、撤销传播告警、Air3 正向 Skill 启停和知识引用端到端验收。
- 没有真实有效清单时，严格网关继续返回 `content_manifest_unavailable`，不得用本地假授权、默认 Skill 或测试数据绕过。
- 本人讯飞声纹仍需在 Air3 完成三段实时录入和独立第四段 `1:1` 验证；本轮自动化不能替代本人实机验收。
