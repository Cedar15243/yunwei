# V9 外部验收工作区

该工作区已绑定当前正式 APK 和交付 ZIP。初始化状态必须保持 pending；templates 下的文件只是待执行清单，不是通过证据。

1. 仅在真实生产环境执行对应 gate，不得用 fixture、mock、静态文案或本地健康端点替代。
2. 将对应模板复制到 files/，填写真实 executedAt、runner、result 和每个检查结果；只有全部通过时才能把 result/check status 改为 passed。
3. 计算 attestation 的 SHA-256 和字节数，再把描述符写入 manifest.json 的 attestation 与 evidence。
4. 本工具不会生成 trusted-approvers.json、签名或私钥；不得生成或保存审批私钥到该目录。信任库只能保存公钥，并由受保护发布系统固定 SHA-256。
5. 使用 security/v9-external-acceptance.mjs（正式包）或 scripts/v9-external-acceptance.mjs（源码仓库）导出规范签名载荷，由离线介质、HSM 或 KMS 完成 Ed25519 签名。
6. 申请市场 GA 前必须运行 --require-market-ga 强制门禁；任何缺项、失败项、错绑或无效签名都会拒绝放行。
