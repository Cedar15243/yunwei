# V9 Android 工作流生命周期与信任根验证

日期：2026-08-01

## 范围

- 使用 MDM 受管 JSON 投放最多八个 Ed25519 工作流签名公钥，支持轮换并严格拒绝未知字段、重复标识、非整数 schema 和非 Ed25519 材料。
- `MainActivity` 在安全运行时、短期设备会话和受管公钥均可用时才创建工作流交付链。
- App 前台恢复和已验证网络恢复共用单飞同步触发器；会话预热失败不阻塞后台产生明确的同步结果。
- Activity 销毁时注销网络回调并停止工作流同步线程，不改变现有 Camera2、语音、AI 或专家生命周期。
- 当前只向服务端声明已实际实现的 `workflow.runtime.v1`；Camera2、录像、语音、AI、专家和连接器必须在处理器接通后才声明。

## TDD 与自动化

失败阶段首先确认新增测试因缺少 `ManagedWorkflowPublicKeySource` 和 `WorkflowDeliveryController` 无法编译。实现后通过：

```text
Android JVM: 327/327
failures=0
errors=0
skipped=0

npm run validate:native-build: PASS
```

`validate:native-build` 现在覆盖受管公钥限制、Ed25519 信任根、前台触发、验证网络恢复、关闭释放和 `MainActivity` 接线合同。

测试必须使用 Java 17；Unity 自带 Java 11 不提供 Ed25519 JCA 实现，会让七项验签测试报 `NoSuchAlgorithmException`，不属于业务断言失败。

## V9.0.4 审计构建

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.delivery.audit
versionCode: 9004
versionName: 9.0.4-workflow-delivery-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (debug certificate)
SHA256: 7B60F9628C0A8C76430282E432DAC28ACEA3945BFFB1EEFFD076FDFFD4F9269D
```

安全运行时生成配置中的后端、GPT、ASR 和讯飞客户端密钥为空，GPT 地址、模型和推理参数为空。APK 未安装到 Air3；debug 证书不能作为正式发布签名。

## 剩余边界

- 生产公钥集尚未通过 MDM 投放，服务端生产签名密钥也未部署。
- 在线轻通知传输尚未实现；当前可靠入口为前台恢复、验证网络恢复和同步游标补偿。
- 原生能力处理器与固定 HUD 尚未接线，因此当前不会向服务端虚报相机、录像、语音、AI、专家或连接器能力。
- D8 对 API 34 的既有工具链警告仍存在，正式发布前必须升级并回归。
