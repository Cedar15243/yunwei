# V9 SAST 与渗透测试计划

## 已纳入发布门禁的本地控制

- `scripts/v9-operational-readiness.mjs` 扫描 V9 相关源码中的高风险动态执行、`shell=True`、不安全反序列化、前端原始 HTML 注入和明文 HTTP 配置。
- `scripts/test-v9-operational-readiness.mjs` 用安全与不安全 fixture 验证扫描器确实能报错，避免只测试“扫描通过”。
- CycloneDX SBOM、npm/Python 依赖审计、发布包私钥/秘密扫描仍由 `test:v9-security` 和 `validate:v9-delivery` 强制执行。

这些规则是项目级 SAST 补充，不能替代 Semgrep、CodeQL、Android SAST 或供应商 SDK 审核。

## DAST / 渗透测试

`scripts/run-v9-dast-baseline.ps1` 只接受显式 HTTPS 目标，并调用 OWASP ZAP baseline。真实生产目标、测试窗口和授权缺失时脚本必须失败关闭，不允许用本地 mock 结果冒充市场证据。

2026-08-12 已对授权生产目标 `https://bb.chinacedar.top:2305` 执行 OWASP ZAP `2.17.0` 被动 DAST r4；报告实例仅限 `/v9-ops`，High/Medium/Low 风险数均为 0，信息级项为对 `Cache-Control: no-store` 的复核。原始 JSON/HTML 和机器校验器已纳入正式交付包。

该执行由开发方完成，不是第三方渗透测试、独立安全审批或市场 GA 证据。第三方测试与复测仍至少覆盖：

1. V9 HTTPS 网关认证、短期设备会话、重放和幂等。
2. 管理 Web 的 RLS、项目范围、账号邀请/恢复和高风险二次确认。
3. 工作流签名、撤销、设备清单过期、媒体分片和取消竞态。
4. 声纹录入/删除/授权、日志脱敏、供应商密钥边界。
5. MVS 网关白名单、DTO 适配、写操作拒绝、离线队列和审计。
6. 专家站点与 V9 路由隔离、Caddy 配置和稳定 APK 并存安装。

GA 放行条件：无 Critical/High；Medium 必须有已批准的缓解或延期；第三方报告、复测报告、授权记录和独立安全审批进入交付归档。当前开发方被动 DAST 为 `completed`，第三方渗透与独立安全审批为 `pending_external`。
