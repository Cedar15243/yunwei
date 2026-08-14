# V9 外部验收证据契约

本契约用于把生产部署和市场 GA 从人工口头结论改为与当前正式包绑定的机器校验证据。默认清单位于：

`evidence/v9-external-acceptance/manifest.json`

该目录不进入源码交付包，也不能存放生产密钥、登录令牌、原始声纹音频或未脱敏个人信息。证据文件只保留测试结果、脱敏日志、批准函、截图索引或第三方报告。审批私钥必须留在离线介质、HSM 或 KMS，不能进入仓库、证据目录、环境示例或正式 ZIP。

## 两级放行

生产部署需要以下三项全部通过：

- `production_supabase`：真实账号、邀请、RLS、多角色项目权限和审计落库。
- `management_cloud`：隔离目录、独立容器/端口、TLS、Caddy 校验、回滚和原专家站点非回归。
- `device_activation`：真实 Air3 激活、绑定、短期会话、撤销和权限负向测试。

市场 GA 还需要：

- `air3_voice_voiceprint_performance`：双语音互斥、本人/旁人声纹、噪声、弱网、20 分钟稳定性、功耗、温升和 p95 响应。
- `mvs_writeback`：正式上传、附件绑定、动态 Form、流程推进、幂等和二次确认。
- `expert_collaboration`：真实双向音视频、音频抢占和退出后回原项目/步骤。
- `operations_resilience`：生产备份恢复、RPO/RTO、磁盘故障和通知链演练。
- `security_assessment`：授权 DAST、第三方渗透、修复与复测报告。
- `supplier_sdk_approval`：讯飞 AIKit/声纹用途与安全审批。

## 初始化验收工作区

源码仓库中可直接生成与当前正式 APK/ZIP 绑定的空验收工作区：

```powershell
npm run init:v9-external-acceptance
```

正式交付 ZIP 解压后，可显式传入原始 ZIP 的 sidecar：

```powershell
node security/init-v9-external-acceptance.mjs . evidence/v9-external-acceptance `
  --delivery-sidecar ..\DingdangAI-V9-9.0.0-formal-delivery.zip.sha256
```

生成器只创建 `manifest.json`、`required-checks.json`、操作说明和九类 `pending` attestation 模板。它不会生成 `trusted-approvers.json`、审批签名、私钥或任何 `passed` 结果；目标目录非空时直接拒绝，避免覆盖真实证据。外部团队必须在生产环境逐项执行后再填写结果、计算文件摘要并完成离线/HSM/KMS 签名。

## 登记单项 Attestation

完成某个 gate 的真实生产验收后，先把对应模板复制到工作区 `files/` 并填写真实结果。登记工具会重新校验全部必检项、计算 SHA-256/字节数并原子更新 manifest；必须携带当前 manifest 哈希和固定确认词：

```powershell
$manifestSha = (Get-FileHash evidence\v9-external-acceptance\manifest.json `
  -Algorithm SHA256).Hash
npm run record:v9-external-attestation -- . evidence/v9-external-acceptance `
  production_supabase files/production_supabase-attestation.json `
  --approver "审批单号或审批人" `
  --expected-manifest-sha256 $manifestSha `
  --confirm RECORD_EXTERNAL_ACCEPTANCE
```

正式交付 ZIP 解压后使用：

```powershell
node security/record-v9-external-attestation.mjs . evidence/v9-external-acceptance `
  production_supabase files/production_supabase-attestation.json `
  --approver "审批单号或审批人" `
  --expected-manifest-sha256 $manifestSha `
  --confirm RECORD_EXTERNAL_ACCEPTANCE `
  --delivery-sidecar ..\DingdangAI-V9-9.0.0-formal-delivery.zip.sha256
```

登记器不允许覆盖已存在 gate，也不允许修改已有签名的 manifest；manifest 哈希变化、路径穿越、缺少必检项、非生产环境或任何失败状态都会拒绝且保留原文件。所有 gate 登记完毕后再导出签名载荷并进入双角色审批。

## Manifest 结构

`release` 必须逐字绑定当前 `release-manifest.json` 和正式 ZIP sidecar；每个 gate 必须为生产环境、完成时间不得早于正式 APK 构建时间，并至少关联一个可校验文件。`signatures` 对移除签名数组后的规范化 JSON 字节签名。

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-08T08:30:00.000Z",
  "release": {
    "applicationId": "com.codex.air3nativecamera.dingdangexpert.v9",
    "versionCode": 900000,
    "versionName": "9.0.0",
    "apkSha256": "<64位SHA-256>",
    "deliveryZipSha256": "<64位SHA-256>",
    "generatedAt": "<release-manifest builtAt>"
  },
  "gates": {
    "production_supabase": {
      "status": "passed",
      "environment": "production",
      "completedAt": "2026-08-08T08:20:00.000Z",
      "approver": "<审批人或审批单号>",
      "attestation": {
        "path": "files/production-supabase-attestation.json",
        "sha256": "<64位SHA-256>",
        "bytes": 1234
      },
      "evidence": [
        {
          "path": "files/production-supabase-attestation.json",
          "sha256": "<64位SHA-256>",
          "bytes": 1234
        }
      ]
    }
  },
  "signatures": [
    {
      "keyId": "release-key-1",
      "algorithm": "ed25519",
      "signedAt": "2026-08-08T08:31:00.000Z",
      "signatureBase64": "<Ed25519签名Base64>"
    }
  ]
}
```

证据路径必须相对 manifest 目录，禁止绝对路径、`..` 穿越和指向目录外的符号链接。文件大小和 SHA-256 必须同时匹配。

## 结构化 Attestation

每个 gate 必须提供 `attestation`，并把同一描述符放入 `evidence`。声明文件使用 JSON，绑定当前 release、gate、生产环境和完成时间；所有必检项必须存在且状态为 `passed`。

```json
{
  "schemaVersion": 1,
  "gateId": "production_supabase",
  "release": {
    "applicationId": "com.codex.air3nativecamera.dingdangexpert.v9",
    "versionCode": 900000,
    "versionName": "9.0.0",
    "apkSha256": "<64位SHA-256>",
    "deliveryZipSha256": "<64位SHA-256>",
    "generatedAt": "<release-manifest builtAt>"
  },
  "environment": "production",
  "executedAt": "2026-08-08T08:20:00.000Z",
  "runner": "controlled-acceptance-runner",
  "result": "passed",
  "checks": [
    { "id": "auth_invitation_email", "status": "passed" },
    { "id": "organization_rls_isolation", "status": "passed" }
  ]
}
```

九类 gate 的完整必检项位于 `docs/operations/v9-external-acceptance-required-checks.json`。缺项、重复 ID、失败状态、错误 gateId、错误 release、非生产环境或执行时间不一致均判定为非法外部证据。

## 审批签名与信任库

信任库默认位于 `evidence/v9-external-acceptance/trusted-approvers.json`，只保存 Ed25519 公钥。生产部署至少需要一个 `release_manager` 有效签名；市场 GA 必须由两个不同密钥分别提供 `release_manager` 和 `security_approver` 签名。

```json
{
  "schemaVersion": 1,
  "keys": [
    {
      "keyId": "release-key-1",
      "algorithm": "ed25519",
      "role": "release_manager",
      "status": "active",
      "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----\n"
    }
  ]
}
```

信任库本身必须由受保护的发布系统固定 SHA-256，不能只相信工作树内同目录文件：

```powershell
$env:V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256 = (Get-FileHash `
  evidence\v9-external-acceptance\trusted-approvers.json -Algorithm SHA256).Hash
```

导出不含签名数组的规范化载荷：

```powershell
node scripts/v9-external-acceptance.mjs . `
  evidence/v9-external-acceptance/manifest.json `
  --print-signing-payload > evidence\v9-external-acceptance\manifest.signing.json
```

随后使用离线 OpenSSL、HSM 或 KMS 对 `manifest.signing.json` 的原始字节执行 Ed25519 签名，将 Base64 签名、`keyId` 和 `signedAt` 写入 `signatures`。再次导出时字节保持一致，因为签名数组不进入签名载荷。

## 安全附加审批签名

把离线介质、HSM 或 KMS 返回的 Base64 Ed25519 签名保存到工作区 `signatures/`。附加工具不会读取私钥；它会校验信任库固定哈希、公钥、角色、签名时间、签名内容、release 绑定和当前 manifest 哈希：

```powershell
$manifestSha = (Get-FileHash evidence\v9-external-acceptance\manifest.json `
  -Algorithm SHA256).Hash
$trustSha = (Get-FileHash evidence\v9-external-acceptance\trusted-approvers.json `
  -Algorithm SHA256).Hash
npm run attach:v9-external-approval-signature -- . `
  evidence/v9-external-acceptance release-key-1 `
  "2026-08-08T12:05:00.000Z" signatures/release-key-1.sig `
  --trust-store trusted-approvers.json `
  --expected-manifest-sha256 $manifestSha `
  --expected-trust-store-sha256 $trustSha `
  --confirm ATTACH_EXTERNAL_APPROVAL_SIGNATURE
```

正式交付 ZIP 解压后使用 `security/attach-v9-external-approval-signature.mjs`，并追加 `--delivery-sidecar` 参数。先附加 `release_manager`，重新计算 manifest SHA-256 后，再用不同密钥附加 `security_approver`。签名数组不进入规范签名载荷，因此第二个审批人签署的仍是同一份未签名业务内容。

工具拒绝重复 key、伪造签名、撤销/未知公钥、错误角色、信任库 pin 不匹配、过早/未来签名、manifest 并发变化和工作区外签名文件；失败时原 manifest 保持不变。私钥必须始终留在离线介质、HSM 或 KMS。

## 验证命令

查看当前外部门禁状态：

```powershell
npm run audit:v9-external-acceptance
npm run audit:dingdang-goal
```

正式申请市场 GA 时使用强制门禁：

```powershell
node scripts/v9-external-acceptance.mjs . evidence/v9-external-acceptance/manifest.json --require-market-ga
```

输出还必须满足每个 gate 的结构化 attestation 通过、`externalAcceptance.approval.trustStorePinned=true`，且市场 GA 的 `validRoles` 同时包含 `release_manager` 和 `security_approver`。只有 `currentV9Gate.localCandidate.status`、`productionDeployment.status`、`marketGa.status`、`delivery.status` 和 `secretScan.status` 全部为 `proven`，才允许结束长期交付目标。
