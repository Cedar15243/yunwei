# V9 正式发布与安全激活设计

文档日期：2026-08-03

状态：已确认，进入实现

## 1. 目标

把 V9 从独立 Debug 审计包推进为可正式签名、可持续升级、可安全激活的 Release 候选，同时保持以下运行合同不变：

- 主 AI 与视觉只使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 只使用上一版 `fun-asr-realtime`；本地 Sherpa 只作断网或云端失败回退。
- 小叮当唤醒继续使用上一版讯飞 AIKit。
- 声纹录入和 `1:1` 验证只使用新申请的讯飞 `s1aa729d0`。
- 不使用 OpenClaw、GPT 5.5/5.6、DeepSeek、Claude、Qwen Max 或其他新增模型。

本阶段不修改已确认的首页、HUD、相机、专家协同视觉和交互布局，不覆盖 V8 稳定 APK。

## 2. 发布身份

- 正式包名：`com.codex.air3nativecamera.dingdangexpert.v9`。
- 正式首发版本：`versionCode=900000`，`versionName=9.0.0`。
- 审计包继续使用独立包名和 Debug 签名，与正式包、V8 稳定包并存。
- 正式包只接受显式 Release 签名配置；缺少签名文件、别名或密码时构建立即失败。
- 签名文件和密码只来自 Git 忽略的本地配置或 CI Secret，不进入源码、APK 元数据、日志和发布报告。

## 3. 模型和供应商密钥边界

### 3.1 服务端固定模型

V9 网关启动时强制校验：

- `V9_AI_MODEL=qwen3-vl-plus`
- `V9_ASR_MODEL=fun-asr-realtime`
- `V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0`

任一值缺失或偏离时服务拒绝启动，不允许自动改用其他模型。

### 3.2 客户端边界

- V9 安全包不保存主 AI、ASR、声纹供应商长期密钥。
- 讯飞离线唤醒授权继续通过企业 MDM 受管配置投放；非 MDM 激活只投放我方设备 bootstrap，不投放讯飞供应商密钥。
- 15 分钟设备 access token 只驻留内存，不写文件、不写 `SharedPreferences`、不进入日志。
- 设备本地只持有我方 HTTPS 地址和设备级 bootstrap 凭据。

## 4. 双通道设备激活

### 4.1 企业 MDM 通道

`RestrictionsManager` 继续为最高优先级来源，适用于企业批量部署。受管配置至少包含我方 HTTPS 服务地址、设备 bootstrap 凭据和策略版本；可选包含上一版讯飞离线唤醒授权。

### 4.2 非 MDM 通道

管理后台为已登记且已绑定工程师/项目的眼镜生成一次性激活码或二维码。激活码：

- 默认 10 分钟过期。
- 只能成功兑换一次。
- 服务端只保存 SHA-256 哈希，不保存明文。
- 明文只在管理员确认生成后显示一次。
- 兑换必须绑定预先登记的设备、组织、工程师和允许项目范围。
- 失败、过期、重复使用、设备不匹配和组织不匹配均明确拒绝并写审计事件。

眼镜通过现有“设置”入口进入激活，不新增第二套 UI。二维码只是激活码的输入方式，不下载新 APK，也不携带供应商密钥。

## 5. 激活网关合同

### 5.1 管理端创建

`POST /management/devices/{deviceId}/activation-codes`

请求：

```json
{
  "reason": "首次部署到华方实训室",
  "confirmation": "ISSUE DEVICE ACTIVATION",
  "expiresInSeconds": 600
}
```

响应只返回一次明文：

```json
{
  "activationCode": "HF9-ABCD-EFGH-JKLM",
  "expiresAt": "2026-08-03T12:10:00Z",
  "deviceId": "device-uuid"
}
```

### 5.2 眼镜兑换

`POST /device-activation/redeem`

请求：

```json
{
  "activationCode": "HF9-ABCD-EFGH-JKLM",
  "deviceInstanceId": "安装后生成的随机 UUID",
  "packageName": "com.codex.air3nativecamera.dingdangexpert.v9",
  "appVersion": "9.0.0",
  "deviceModel": "IMA301"
}
```

成功响应：

```json
{
  "backendBaseUrl": "https://bb.chinacedar.top:2305/v9-ops",
  "bootstrapCredential": "一次设备级 bootstrap 明文",
  "credentialExpiresAt": "2026-11-01T12:00:00Z",
  "deviceId": "device-uuid",
  "organizationId": "organization-uuid",
  "policyVersion": "v9-production-1"
}
```

响应使用 `Cache-Control: no-store`，不得返回供应商密钥、管理员 token、Skill 正文或知识库高权限地址。

## 6. Android Keystore 存储

- 使用 Android Keystore 生成不可导出的 AES-256/GCM 密钥。
- 加密内容只包含 `backendBaseUrl`、`bootstrapCredential`、设备/组织 ID、凭据到期时间和策略版本。
- AAD 绑定包名和存储 schema 版本，防止跨包复制密文。
- 密文、IV 和 schema 存入应用私有 `SharedPreferences`；`allowBackup=false` 保持关闭。
- 解密失败、schema 不支持或密钥失效时清除本地激活记录并显示“设备需要重新激活”，不得回退到 APK 默认密钥。
- MDM 受管值始终优先于本地激活记录；MDM 撤除后只有仍有效的本地正式激活记录才能接管。

## 7. 会话、撤销与失败行为

- 启动时从本地 Keystore 快速读取 bootstrap，并异步预热 15 分钟 access token，不阻塞首页显示。
- 网络失败保留 bootstrap 和本地任务，只提示网络阶段并允许重试。
- 服务端返回“设备已撤销”“bootstrap 已撤销”“账号已停用”或“绑定已撤销”时，清除 access token 和本地 bootstrap，停止新的 AI、Skill、工作流和上传请求。
- 普通 `401`、超时或服务端 `5xx` 不能盲目清除本地凭据；必须按稳定错误码判断。
- 相机、录像、专家通话和现有本地任务不因激活服务不可用而崩溃。

## 8. 性能边界

- Keystore 本地解密目标 p95 小于 50ms，不等待网络后再显示首页。
- access token 预热在后台线程执行；有效 token 在过期前 2 分钟异步刷新。
- 声纹采集先走本地音频分段，再请求讯飞 `1:1`；通过后才建立 `fun-asr-realtime`，不并行占用两条上行链路。
- 激活和模型门禁不改变相机帧率、录像时长、专家通话、实体键单击或现有 HUD 渲染路径。

## 9. 发布门禁

正式候选必须同时通过：

1. Android JVM、Release 变体编译、签名和 APK 解包扫描。
2. V9 网关、Supabase 激活合同、一次性兑换并发和撤销测试。
3. APK 对主 AI/ASR/讯飞长期密钥、OpenClaw 和禁用模型零命中。
4. Air3 并存安装、激活、重启恢复、撤销、弱网、相机/语音/专家回归。
5. 服务器环境文件保持 `0600 root:root`，模型基线与声纹 URL 新鲜直读一致。

在正式签名、真实激活、本人声纹和长期性能验收完成前，V9 仍为 `NO-GO`，不得作为市场正式版本发布。
