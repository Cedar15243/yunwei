# V9 外部验收工作区交接

生成日期：2026-08-09

绑定更新：2026-08-13

## 工作区身份

- 路径：`evidence/v9-external-acceptance/`
- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9`
- versionCode：`900000`
- versionName：`9.0.0`
- APK SHA-256：`02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`
- 正式交付 ZIP SHA-256：`172C5F743B7A4636F0F821A8F691B1D115E12DCEA6FA2F2B03D2E8EC3754AAD2`
- 初始化 manifest SHA-256：`9039348C2286F6AC5ECD74B9F023D6EE3B7915DE62DA8FAE05CD96E74F8129AA`。

## 当前状态

- `releaseBindingStatus=proven`：manifest 与当前正式 APK、release manifest 和交付 ZIP sidecar 一致。
- 九类 attestation 模板全部为 `pending`，`executedAt=null`，`runner` 为空，全部检查项为 `pending`。
- `gates` 为空，`signatures` 为空，未创建 `trusted-approvers.json`，没有生成私钥或通过结果。
- `--require-market-ga` 强制门禁按设计退出码 `1`，生产部署与市场 GA 均保持 `pending_external_validation`。
- 对同一非空目录再次初始化会被拒绝，且 manifest SHA-256 不变，不覆盖后续真实证据。
- 本轮旧空工作区 `0DCE7645...9F0C` 与中间包 `84E379F6...8C36CE` 均在确认 `gates={}`、`signatures=[]` 且无 `trusted-approvers.json` 后完整归档；当前工作区只绑定最终 ZIP `172C5F74...4AAD2`。不得对含真实 gate、签名或信任库的工作区执行归档重建流程。

## 外部执行顺序

1. 生产部署先完成 `production_supabase`、`management_cloud`、`device_activation` 三项真实验收。
2. 对每项 gate 复制对应模板到 `files/`，填写真实执行人、执行时间和全部检查结果。
3. 使用 `record:v9-external-attestation` 和固定确认词登记，工具负责摘要、release 绑定、路径与乐观锁校验。
4. 市场 GA 再完成 Air3 语音/声纹/性能、MVS 写回、专家协同、运行韧性、安全评估和供应商 SDK 审批。
5. 由受保护系统提供公钥信任库固定哈希；发布负责人和独立安全审批人使用不同 Ed25519 密钥签名，私钥不得进入工作区或正式包。

本工作区只解决验收材料的结构、版本绑定和防误放行问题，不替代真实账号、生产环境、现场执行、第三方报告或审批。
