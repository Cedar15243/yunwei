# V9.0.16 菜单、项目记忆与声纹设置 Air3 验证

日期：2026-08-03

## 固定模型合同

- 主 AI/视觉继续使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 继续使用上一版 `fun-asr-realtime`，Sherpa 仅作云端失败或断网回退。
- 声纹只使用刚申请的讯飞新版 `s1aa729d0`。
- V9 不使用 OpenClaw，不启用其他新增模型。
- 供应商长期密钥保留在服务器 root-only 环境文件或被 Git 忽略的本机调试投放文件中，不进入 APK、Git 或日志。

## 构建产物

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.managedmenu.audit`
- 版本：`9016 / 9.0.16-managed-menu-audit`
- 应用名：`叮当AI v9.0.16`
- APK：`output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-managed-menu-audit-debug.apk`
- 大小：`136818784` 字节
- SHA256：`0E7BA3CE446F2892C54AD91F26CF66808C21BFAA029CEDEB2D63907C9E3C8AD1`
- API：`minSdk 34 / targetSdk 34 / compileSdk 34`
- 签名：APK Signature Scheme v2 有效，Android Debug 证书；不能作为正式发布签名包。
- 网络：`usesCleartextTraffic=false`。

本次构建生成配置确认：

- `SECURE_RUNTIME=true`
- `DIRECT_GPT_ENABLED=false`
- 客户端 OPS、AI、ASR、讯飞长期密钥和直连地址/模型均为空
- `IFLYTEK_OFFLINE_WAKE_ENABLED=true`
- `LOCAL_ASR_FALLBACK_ENABLED=true`
- 受管后端为 `https://bb.chinacedar.top:2305/v9-ops`

## 自动化验证

- V9 独立网关全量：`77/77`，失败 `0`。
- Android JVM：`426/426`，失败 `0`、错误 `0`、跳过 `0`。
- `validate:native-hud`：通过。
- `validate:native-build`：通过。
- Gradle `assembleDebug`：成功。
- V9 网关公网健康：HTTP `200`，`dingdang-v9-gateway`。
- 现有专家协同公网健康：HTTP `200`，`expert-collab`。

网关测试明确覆盖并固定：拒绝非上一版主模型、使用 `fun-asr-realtime`、讯飞新版 `s1aa729d0`、供应商失败不返回假成功、声纹三段录入与独立 `1:1`、三次失败锁定、反重放和删除/撤销。

## Air3 实机

设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`

- V9.0.16 使用独立 `applicationId` 并存安装成功，没有覆盖 V8.0.48、V9.0.14 或 V9.0.15。
- 两份调试投放文件仅复制到 V9.0.16 应用私有 `files/`，权限均为 `0600`；ADB 暂存文件随后删除。
- 三道杠第一次点击打开能力中心，第二次点击返回首页，现有九宫格九项能力、首页布局和 HUD 风格未改变。
- V9 安全运行时显示紧凑的“项目记忆”和“设置”入口。
- “项目记忆”真实从服务端加载 `5` 个项目，第一页 `4` 个、第二页 `1` 个；分页、返回能力中心正常。
- “设置”进入现有“声纹与语音模式”页，受管状态显示 `录入中 · 录入 0/3`，当前语音模式仍为“小叮当唤醒”。
- V8.0.48 `848 / 8.0.48-sensor-wiring-flow` 可独立恢复前台并正常显示首页。
- Crash buffer 为空；`dumpsys activity lastanr` 显示本次启动以来无 ANR。

截图位于：

- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-home.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-menu-open.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-menu-closed.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-project-memory.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-project-memory-page2.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v9.0.16-settings.png`
- `output/air3-v9-managed-menu-audit-20260803/air3-v8.0.48-regression.png`

## 未完成与边界

- 本轮没有用历史录音、TTS 或回放替代本人声纹；本人三段实时录入和独立第四段 `1:1` 仍未完成。
- 本人/旁人/噪声/重复音频、三次失败锁定、实体按键双击、相机/录像/专家音频抢占、延迟、温升和耗电仍需 Air3 连续实测。
- V9.0.16 是菜单与受管入口审计包；本轮未重新制造 AI 现场案例。主 AI 图文链路沿用 V9.0.14 的真实 Camera2 证据，模型路由由本轮 `77/77` 网关测试和安全生成配置重新验证。
- 任务结束摘要、项目指令确认、真实 Skill 正向授权启停、生产签名和真实受管工作流分配仍是后续主线，不能用本地假状态替代。

## 验证工具记录

- 首次执行 `apksigner` 因当前 shell 未设置 `JAVA_HOME` 失败；改用仓库便携 JDK 17 后验证通过，属于工具环境问题。
- 首次使用单条 `adb run-as sh -c` 投放文件时发生 Windows/ADB 引号拆分；改为逐条 `mkdir/cp/chmod` 后成功并核对为 `0600`，不属于应用回归。
