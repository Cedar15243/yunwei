# 叮当运维AI最终验收 Runbook

本文档用于把长期目标收口到可验证状态。前四节保留历史原型、设计与设备检查方法；当前 V9 市场版是否完成，只读取第 5、6 节定义的发布绑定外部证据和顶层 `currentV9Gate`，不再把旧聊天原型的 `allComplete` 当作正式门禁。

## 0. 当前本地门禁

在任何真实部署或 Figma 写入前，先跑本地门禁：

```powershell
npm run validate:figma-blueprint
npm run validate:figma-write-script
npm run validate:figma-frames-evidence -- --self-test
npm run validate:mock-smoke-latency -- --self-test
npm run validate:real-smoke-summary -- --self-test
npm run validate:supabase
npm run audit:dingdang-supabase
npm run test:v9-external-acceptance
npm run audit:v9-external-acceptance
npm run audit:dingdang-goal
rg -n "sk-[A-Za-z0-9_-]{12,}" .
```

`rg` 退出码为 1 且无输出表示未发现 `sk-...` 形态的 key。

两条 `--self-test` 只证明摘要验收器能接受有效夹具并拒绝无效夹具，不代表当前版本已经完成真实 Air3 或真实 provider 验收；真实结果仍必须按第 2、3、5 节生成并绑定当前 release。

## 1. Figma 真实画板收口

前置条件：Figma MCP 额度恢复，`get_metadata` 不再返回 Starter tool call limit。

1. 按 `figma-use` skill 调用 `use_figma`。
2. 文件 key：`pDX9LEKARKp5GGchwuFLz4`。
3. 将 `docs/figma/dingdang-ops-ai-use-figma-script.js` 的完整内容作为 `code` 传给 `use_figma`。
4. `use_figma` 返回后，保存 `pageId`、5 个 frame 的 nodeId 和 frame 名称。
5. 对每个 frame 调用 `get_metadata` 与 `get_screenshot`，确认：
   - `chat-main`
   - `camera-capture`
   - `asr-streaming`
   - `gpt-streaming`
   - `shortcut-flow`
6. 将验证结果写入 `tmp/dingdang-figma-frames-<timestamp>/evidence.json`：

```json
{
  "ok": true,
  "figmaFileKey": "pDX9LEKARKp5GGchwuFLz4",
  "metadataVerified": true,
  "frames": [
    {
      "id": "chat-main",
      "nodeId": "123:456",
      "metadataVerified": true,
      "screenshotVerified": true,
      "width": 1440,
      "height": 900
    }
  ]
}
```

`frames` 必须包含 5 个 required frame。然后运行：

```powershell
npm run validate:figma-frames-evidence -- tmp\dingdang-figma-frames-<timestamp>\evidence.json
npm run audit:dingdang-goal
```

## 2. Supabase 真实 provider 部署

前置条件：

- 已轮换此前对话中暴露过的 DashScope/GPT key。
- 新 key 只进入 Supabase secrets 或 ignored env 文件。
- 本地存在 Supabase 登录态或 `SUPABASE_ACCESS_TOKEN`。
- 已确定 Supabase project ref。

准备 ignored env 文件，例如 `supabase/.env.production.local`，只写 secret 名称和值，不提交：

```dotenv
AUTO_MIGRATE=true
OPS_GLASSES_API_KEY=
OPENAI_API_KEY=
OPENAI_BASE_URL=
OPENAI_VISION_MODEL=
OPENAI_TRANSCRIBE_API_KEY=
OPENAI_TRANSCRIBE_BASE_URL=
OPENAI_TRANSCRIBE_MODEL=
DASHSCOPE_API_KEY=
DASHSCOPE_FUNASR_URL=
DASHSCOPE_FUNASR_MODEL=
V9_GATEWAY_SYNC_TOKEN_SHA256=
V9_GATEWAY_SYNC_ORGANIZATION_ID=
V9_GATEWAY_SYNC_USER_ID=
V9_GATEWAY_SYNC_DEVICE_ID=
V9_VOICEPRINT_ADMIN_URL=
V9_VOICEPRINT_ADMIN_TOKEN=
V9_KNOWLEDGE_PARSER_URL=
V9_KNOWLEDGE_PARSER_TOKEN=
V9_DEVICE_ACTIVATION_BACKEND_BASE_URL=
V9_DEVICE_ACTIVATION_POLICY_VERSION=
OPS_ACCOUNT_RECOVERY_REDIRECT_URL=
DEFAULT_ASSET_TAG=
DEFAULT_TARGET_SSH_PORT=
REMOTE_PROBE_MODE=
```

`SUPABASE_URL`、`SUPABASE_SERVICE_ROLE_KEY` 和 `SUPABASE_DB_URL` 由托管 Edge Function 自动注入，禁止写入 `secrets set` 上传文件。先运行下列生成器，一次生成不可拆分的 Supabase、网关和声纹三件套；生成器固定上一版 AI/ASR 与讯飞 `s1aa729d0`，并保持已有桥接 token 稳定：

```powershell
node scripts/prepare-v9-production-secrets.mjs `
  --gateway-env tmp/v9-cloud-gateway-source.env.local `
  --voiceprint-env tmp/v9-cloud-voiceprint-source.env.local `
  --bootstrap-token-file tmp/v9_bootstrap_token.local `
  --supabase-seed-env supabase/.env.production.pending.local `
  --gateway-seed-env tmp/v9-gateway-production-overlay.env.local `
  --supabase-output supabase/.env.production.local `
  --gateway-output v9-ops-gateway/deploy/dingdang-v9.env.local `
  --voiceprint-output v9-ops-gateway/deploy/dingdang-v9-voiceprint.env.local `
  --voiceprint-rotation-mode transition `
  --project-ref <project-ref> `
  --public-gateway-base-url https://bb.chinacedar.top:2305/v9-ops `
  --account-recovery-redirect-url https://<dedicated-management-domain>/ `
  --default-asset-tag <registered-real-asset-tag> `
  --default-target-ssh-port <real-ssh-port> `
  --default-target-app-port <real-app-port> `
  --remote-probe-mode tcp
```

`--supabase-seed-env` 与 `--gateway-seed-env` 必须指向现有受控生产 seed，避免内容同步、知识解析和管理 API token 无故轮换。声纹 token 轮换必须使用下列零中断顺序：

1. 先用 `deploy/update-runtime.sh` 发布支持 current/previous 双哈希的 V9 运行时，保持现网配置不变。
2. 使用 `--voiceprint-rotation-mode transition` 生成三件套；新 token 为 current，现网旧 hash 为 previous。
3. 将两份 overlay 以 root-only 临时文件传到 V9 云服务器，执行 `stage-production-overlay.sh` 后再激活 transition candidate。
4. 确认新声纹管理 token 已通过公网接口验收，再部署 Supabase secrets 与 Edge Function。
5. Supabase 与声纹管理联调通过后，使用 `--voiceprint-rotation-mode steady` 重新生成并激活配置，清除 previous hash。

stage 不重启服务、不改 Caddy、不触碰专家协同。activate 只重启 V9 网关；任一阶段失败会恢复原 `/etc` 配置和原 `current/previous` 指针。`OPS_ACCOUNT_RECOVERY_REDIRECT_URL` 必须先具备 IPv4 A 记录并返回可用 HTTPS 响应，否则真实部署在登录和任何 Supabase 写入之前失败关闭。

部署：

```powershell
npx supabase login
powershell -ExecutionPolicy Bypass -File scripts\deploy-dingdang-supabase-prod.ps1 `
  -SecretsEnvFile supabase\.env.production.local `
  -GatewayOverlayEnvFile v9-ops-gateway\deploy\dingdang-v9.env.local `
  -VoiceprintOverlayEnvFile v9-ops-gateway\deploy\dingdang-v9-voiceprint.env.local `
  -PreflightOnly
powershell -ExecutionPolicy Bypass -File scripts\deploy-dingdang-supabase-prod.ps1 `
  -SecretsEnvFile supabase\.env.production.local `
  -GatewayOverlayEnvFile v9-ops-gateway\deploy\dingdang-v9.env.local `
  -VoiceprintOverlayEnvFile v9-ops-gateway\deploy\dingdang-v9-voiceprint.env.local
npm run audit:dingdang-supabase
```

## 3. Air3 真实 live smoke

前置条件：

- Air3 通过 ADB 可见。
- 已安装部署脚本生成的正式安全运行时 V9 APK：`com.codex.air3nativecamera.dingdangexpert.v9 / 900000 / 9.0.0`。
- 现场人员可以在脚本提示窗口内真实说话。

运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-dingdang-real-live-smoke.ps1 -BackendBaseUrl https://<project-ref>.supabase.co/functions/v1/ops-glasses -OutDir tmp\real-live-smoke-<timestamp>
npm run validate:real-smoke-summary -- tmp\real-live-smoke-<timestamp>\summary.json
```

验收必须证明：

- `provider=real`
- 后端 `/health` 通过
- 拍照返回聊天输入上下文
- ASR partial/final 来自 logcat
- GPT first delta 来自 logcat
- 延迟预算通过
- 最终 resumed activity 仍属于 `com.codex.air3nativecamera.dingdangexpert.v9`

## 4. Air3 快捷键与 APK 回归

真实 provider smoke 通过后，再跑 APK 与快捷键回归：

```powershell
npm run validate:native-hud
npm run validate:native-build
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-v9-release.ps1 -WaitSeconds 30 -OutDir tmp\v9-install-final-<timestamp>
powershell -ExecutionPolicy Bypass -File scripts\test-dingdang-air3-shortcuts.ps1 -Package com.codex.air3nativecamera.dingdangexpert.v9 -ExpectedVersionCode 900000 -ExpectedVersionName 9.0.0 -WaitSeconds 30 -OutDir tmp\sidekey-final-<timestamp>
```

快捷键必须保持：

- `ENTER(66)` / `DPAD_CENTER(23)`：语音开始或结束
- `FOCUS(80)` / `F9(139)`：App 内相机
- `RIGHT(22)` / `MENU(82)` / `F12(142)`：发送
- `BACK(4)` / `LEFT(21)` / `F10(140)`：返回或保持聊天页
- `VOLUME_UP(24)` / `VOLUME_DOWN(25)`：App 内消费
- `CAMERA(27)` / `DVR(173)`：继续判定为系统相机保留键，不作为普通 APK 可改绑承诺

## 5. 生产与市场外部验收

按照 `docs/operations/v9-external-acceptance-evidence.md` 建立与当前 APK/ZIP 绑定的证据目录。生产部署三项和市场 GA 九项必须使用真实生产或现场证据，并按 `v9-external-acceptance-required-checks.json` 提交结构化 attestation；不得引用 fixture、mock、旧版本报告或健康端点代替业务验收。

在受保护的发布环境固定审批公钥信任库：

```powershell
$env:V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256 = (Get-FileHash `
  evidence\v9-external-acceptance\trusted-approvers.json -Algorithm SHA256).Hash
```

生产部署需要 `release_manager` 签名；市场 GA 还必须由不同密钥提供 `security_approver` 签名。私钥不得进入工作树或交付 ZIP。

```powershell
npm run audit:v9-external-acceptance
node scripts/v9-external-acceptance.mjs . evidence/v9-external-acceptance/manifest.json --require-market-ga
```

## 6. 最终完成判定

只有以下当前 V9 命令都通过，才能把长期目标判定完成：

```powershell
npm run validate:real-smoke-summary -- tmp\real-live-smoke-<timestamp>\summary.json
npm run validate:supabase
npm run validate:native-hud
npm run validate:native-build
npm run validate:v9-release
npm run validate:v9-delivery
npm run audit:dingdang-supabase
npm run test:v9-external-acceptance
node scripts/v9-external-acceptance.mjs . evidence/v9-external-acceptance/manifest.json --require-market-ga
npm run test:dingdang-goal
npm run audit:dingdang-goal
```

`allComplete` 和 `nextRequiredActions` 属于 2026-06 聊天原型历史基线，不再作为 V9 市场版判定。`npm run audit:dingdang-goal` 必须输出：

- `currentV9Gate.localCandidate.status=proven`
- `currentV9Gate.productionDeployment.status=proven`
- `currentV9Gate.marketGa.status=proven`
- `currentV9Gate.delivery.status=proven`
- `currentV9Gate.secretScan.status=proven`
- `currentV9Gate.externalAcceptance.approval.trustStorePinned=true`
- `currentV9Gate.externalAcceptance.approval.validRoles` 同时包含 `release_manager`、`security_approver`
- 九类 gate 的 attestation 均包含全部必检项，且每项 `status=passed`

任一项为 pending、incomplete、unknown 或 invalid，都不能结束长期交付目标。
