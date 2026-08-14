# V9 模型基线与在线 Air3 验证

## 范围

- V9 主 AI/视觉继续使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 继续使用上一稳定版 `fun-asr-realtime`。
- “小叮当”唤醒继续使用上一版讯飞 AIKit。
- 声纹只使用新申请的讯飞服务 `s1aa729d0`。
- 不修改 V8 稳定包、现有 HUD、Camera2、专家协同或历史非安全构建。

## 本轮改动

- Android Gradle 增加 V9 包名门禁：`applicationId` 含独立 `v9` 段时必须启用 `secureRuntime`。
- Debug 私有投放也必须启用 `secureRuntime`，且继续禁止进入 Release 构建。
- 因此 V9 包不能误落回共享工程的历史客户端直连模型；主模型和声纹供应商选择继续留在服务端。

## TDD 证据

1. 先在 `scripts/validate-device-session-android.mjs` 增加 V9 安全运行时门禁合同。
2. RED：验证脚本因 `def v9PackageRequested` 缺失按预期失败。
3. GREEN：补充 Gradle 门禁后验证通过。
4. 负向 Gradle 验证：V9 包名未启用 `secureRuntime` 时明确失败 `V9 builds require secureRuntime`。
5. 正向 Gradle 验证：相同 V9 包名启用 `secureRuntime=true` 时 `gradlew help` 成功。

## 审计包

- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9.online.longreply.audit`
- versionCode：`9030`
- versionName：`9.0.30-online-long-reply-audit`
- 主 APK SHA256：`FDC6ED1050FEB44C02B35143F6F2585F738000D73121D00AF09E8C1A306606F8`
- 签名：APK Signature Scheme v2 Debug，仅用于审计，不能作为市场发布包。
- 产物：`output/v9.0.30-online-longreply-air3/`

## Air3 在线结果

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- 新包与 V8、V9.0.29 和其他审计包并存安装，没有覆盖稳定 APK。
- 现有受管后端配置与上一版讯飞 AIKit配置投放到应用私有 `files/`，权限均为 `0600`；ADB 临时文件已删除。
- `MultiTurnTaskDeviceTest`：`OK (1 test)`，`6.754s`。
- 第一轮在线回复给出服务器前面板电源指示灯检查步骤。
- 第二轮准确恢复 `H3C UniServer R4900 G5 / 服务器无法启动`。
- 返回首页后任务为 `PAUSED`，可从项目记录恢复。
- 首页截图为现有“语音待命”HUD；没有“AI 服务暂不可用”或“服务请求失败”。
- crash buffer 为空；当前进程未出现产品异常或 ANR。
- 公网专家健康与 V9 网关健康均为 HTTP `200`。

## 自动化结果

- Android JVM：`79` 个套件，`474/474`，失败 `0`。
- V9 网关：`107/107`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `assembleDebug`、`assembleDebugAndroidTest`：通过。

## 安全检查

- APK 对当前本机 `5` 项唯一受管长期值精确扫描：`0` 命中。
- `qwen3-vl-plus`、讯飞声纹服务 ID `s1aa729d0`、OpenClaw 在 APK 中均为 `0` 命中；`fun-asr-realtime` 仅作为受管 ASR 协议常量出现一次。
- 通用 `sk-` 命中仅来自既有 `task-*` CSS 片段，不是 API key。
- APK 内 `48` 个 PEM 私钥标记与解包后的上一版讯飞 AIKit 原厂载荷完全一致，分布于供应商二进制；未擅自删除或改写。正式发布前仍需讯飞用途说明、安全评估或清理版 SDK。

## 未完成边界

- 本轮真实在线链路验证的是双轮任务与记忆，不是三页以上真实在线长回复；V9.0.29 的五页验证仍是受控夹具证据。
- 由于 SSH 运维认证仍失效，本轮没有新鲜直读服务器 root-only 环境文件；模型精确值由网关启动硬门禁、部署合同和自动化固定，公网只验证了实际在线调用成功。
- 本人三段声纹录入、独立第四段 `1:1` 验证、旁人/噪声/反重放/误拒误受和双击监听性能验收仍未完成。
- 该包为 Debug 审计包，不是正式签名市场包。
