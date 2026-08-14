# V9 Supabase 生产就绪审计

审计日期：2026-08-09

## 当前结论

- Supabase CLI：`2.111.0`，部署、secrets 和 link 命令可用。
- 项目链接：已通过 `supabase/.temp/linked-project.json` 识别，`projectRefConfigured=true`。
- 账号授权：`npx supabase projects list --output json` 返回 `Unauthorized`；当前进程没有 `SUPABASE_ACCESS_TOKEN`，也没有可用本地认证文件。
- 生产 secrets：所需 19 个变量已全部在示例中记录，但当前进程为 `0/19`，没有读取或输出任何 secret 值。
- 远程探针：缺少真实 `DEFAULT_ASSET_TAG`、`REMOTE_PROBE_MODE=tcp|http`；HTTP 模式还需要不含凭据的受控 HTTPS `REMOTE_PROBE_URL`。
- 审计结果：`10/13` ready，`canDeployNow=false`，`canRunRealLiveSmoke=false`。

## 本轮修复

生产就绪审计器和生产部署脚本此前只读取旧的 `.supabase/project-ref`，会把当前 Supabase CLI 的链接状态误报为未配置。现已统一按顺序识别：

1. `.supabase/project-ref`
2. `supabase/.temp/linked-project.json`

旧格式保持优先；链接 JSON 损坏、缺少 `ref` 或内容为空时失败关闭。审计器单元测试覆盖新格式、旧格式优先、损坏 JSON 和完全缺失四种情况；部署脚本回归会真实启动 PowerShell dry-run，验证新格式、旧格式优先、损坏 JSON 拒绝，以及不输出 linked project 的账号名称。

完成度复核同时发现旧生产 helper 仍构建/安装历史 `com.codex.air3nativecamera.dingdangops`，真实 provider smoke 也仍校验历史 `versionCode 602`。该路径不能作为 V9 生产证据，现已改为：

- 部署脚本调用 `build-v9-release.ps1`，只生成 `com.codex.air3nativecamera.dingdangexpert.v9 / 900000 / 9.0.0` 正式安全运行时 APK。
- APK 只注入公开的后端、事件和设备激活 URL；不再读取或写入 `DINGDANG_BACKEND_API_KEY`，服务端 `OPS_GLASSES_API_KEY` 只通过 Supabase secrets 管理。
- 新增 `install-and-verify-v9-release.ps1`，校验 release manifest、APK/设备 SHA-256、包版本、动态 launcher、前台状态、Crash buffer 和 V8 并存状态。
- 真实 provider smoke 与摘要验证器均固定校验正式 V9 包名和版本，不再接受旧 `dingdangops` 证据。
- ADB 解析支持当前 worktree、Git common-dir 主仓库、Android SDK 和 PATH，避免共享工具位于主仓库时误报缺失。

## 登录后执行顺序

1. `npx supabase login`
2. 准备 ignored 生产 env 文件，注入已记录的 19 项真实配置和远程探针配置。
3. 运行 `scripts/deploy-dingdang-supabase-prod.ps1`，部署 secrets、Edge Function 并执行健康检查。
4. 使用真实 Air3 运行 `scripts/run-dingdang-real-live-smoke.ps1`。
5. 使用 `validate:real-smoke-summary` 验证真实 ASR partial/final、AI 首段响应和延迟预算。

未完成登录与 secrets 注入前，任何本地测试都不能替代生产 Supabase、真实 RLS 或供应商调用证据。

## 本轮复核证据

- PowerShell 解析探针使用 `[scriptblock]::Create` 直接读取部署脚本，退出码为 `0`。
- `npm run test:supabase-project-ref`、`npm run test:supabase-deploy-ref` 和部署脚本 `-DryRun` 均返回 `0`。
- `npm run audit:dingdang-supabase` 返回 `10/13`，`canDeployNow=false`、`canRunRealLiveSmoke=false`。
- `npx supabase projects list --output json` 返回 `Unauthorized`，证明当前阻断仍是账号登录/授权，而不是 project-ref 解析。
- 本轮未读取、输出或写入任何生产 secret，也未执行远端部署写操作。
- `npm run test:v9-production-runtime`、真实 V9 安装器和 real-smoke summary 自校验通过；当前 Air3 正式 APK 与设备端 SHA-256 均为 `02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`，V8 `audit48` 保持不变，当前安装证据位于 `output/air3-v9-post-build-install-20260813/summary.json`。
