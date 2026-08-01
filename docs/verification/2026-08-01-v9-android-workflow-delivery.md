# V9 Android 工作流分配与断线补偿验证

日期：2026-08-01

## 范围

- 多工作流分配索引、严格恢复和持久化单调游标。
- 已签名执行包的按分配隔离缓存、缺包补拉和撤销失效。
- 前台、网络恢复和在线通知触发的单飞同步协调。
- 工单重新绑定后旧分配撤销事件推进服务端交付序列。
- V9 安全运行时构建非回归。

## 关键合同

- 分配页必须严格递增；小数、回退、跳跃、版本篡改和非法状态回退均拒绝。
- 游标与分配索引先原子落盘，再拉取执行包；包失败不回滚游标，后续只补缺包。
- 每个执行包使用分配 ID 的 SHA-256 文件名隔离，安装前重新执行摘要、Ed25519、schema、版本和能力校验。
- `failed`、`revoked` 和 `none` 分配不可执行；撤销会清除缓存可用标记并删除物理快照。
- 同步触发单飞，执行中到达的新提示最多追加一轮；网络失败不自旋。
- 未开始工单重新绑定时，旧分配更新 `delivery_sequence`，离线设备可通过游标补到撤销事件。

## 验证结果

```text
Android JVM: 323/323
failures=0
errors=0
skipped=0

Deno Edge Function: 112/112
failures=0
```

通过：

```text
npm run validate:supabase
npm run validate:native-build
npm run validate:native-hud
deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
git diff --check
```

V9.0.3 审计构建：

```text
applicationId: com.codex.air3nativecamera.dingdangexpert.v9.workflow.audit
versionCode: 9003
versionName: 9.0.3-workflow-runtime-audit
targetSdk: 34
usesCleartextTraffic: false
APK v2 signature: verified (debug certificate)
SHA256: DCAE1BF8234B3F7E8987A8CC732AF5AE50454734F099FF2AE61DE6BEF7135379
```

APK 未安装到 Air3。当前产物为 debug 签名审计包，不是正式发布文件。

## 剩余边界

- 生产工作流公钥、数据库迁移、在线通知通道和管理账号流程尚未在真实云端验证。
- 同步协调器尚未接入 `MainActivity` 生命周期、网络回调和实际在线推送传输。
- Camera2、录像、语音、AI、专家和固定 HUD 的运行时处理器尚未完成接线。
- D8 对 API 34 的既有工具链警告仍存在，正式发布前必须升级并回归。
