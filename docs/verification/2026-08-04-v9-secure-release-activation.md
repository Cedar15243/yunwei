# V9 安全激活、上一版模型与 Air3 验证

日期：2026-08-04

## 固定模型合同

- 主 AI/视觉继续使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 继续使用上一版 `fun-asr-realtime`，本地 Sherpa 只作云端失败或断网回退。
- 小叮当继续使用上一版讯飞 AIKit。
- 声纹录入与 `1:1` 验证只使用新申请的讯飞 `s1aa729d0`。
- V9 生产合同不使用 OpenClaw、GPT-5.x、DeepSeek、Claude、Qwen Max 或其他新增模型。
- Supabase/V8 历史兼容默认值保留在旧链路，不进入 V9 `secureRuntime`，避免破坏稳定 APK。

## 本轮实现

- `DeviceSessionManager` 改为动态 bootstrap 提供器，短期 access token 仍只保存在内存。
- 服务端明确返回 `device_revoked`、`bootstrap_revoked`、`account_disabled` 或 `device_binding_revoked` 时，立即清除内存会话；超时、普通 `401`、`invalid_bootstrap` 与全部 `5xx` 不删除本地凭据。
- 本地激活凭据使用请求快照和原子 `clearIfCurrent()`，旧撤销响应不能删除刚替换的新凭据。
- 旧凭据的迟到成功响应也不能把旧 access token 写回内存；下一次请求会使用新激活凭据换取新会话。
- MDM 始终优先，APK 不擅自删除企业受管凭据；只有 Android Keystore 本地激活来源允许权威撤销清理。
- HTTP 错误只解析稳定 `error/code`，异常和日志不包含响应正文、bootstrap 或供应商秘密。

## 自动化验证

- Android 聚焦会话/配置测试：`20/20` 通过；新增迟到成功并发用例后 `DeviceSessionManagerTest 9/9` 通过。
- Android 全量 JVM：`511/511` 通过，`21` 个 Gradle 任务实际执行。
- V9 网关：`109/109` 通过。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过，包含短期会话与工作流生命周期合同。
- `npm run validate:v9-release`：通过，上一版模型合同锁定。
- `npm run validate:supabase`：通过。
- `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`：通过。
- `git diff --check`：通过；仅有既有 CRLF 提示。
- V9 Android/网关生产范围禁用模型扫描：`0` 命中。

## 审计 APK

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.activation.audit`。
- 版本：`9032 / 9.0.32-secure-activation-audit`。
- SHA256：`BDE7E2843E961BFA889BAA2C87C040808B9BE95C436BEB2B6156131901C74B47`。
- `secureRuntime=true`、`directGptEnabled=false`、讯飞 AIKit 与 Sherpa source set 已启用。
- 客户端 OPS、主 AI、直连 ASR 与讯飞长期凭据字段均为空。
- v2 Debug 签名有效；没有本机正式 Release 签名凭据，因此该包不是市场发布包。
- APK 扫描 `69` 个文件：OpenClaw、`qwen3-vl-plus`、`s1aa729d0`、GPT-5.5/5.6、DeepSeek、Claude、Qwen Max 和全部本地长期凭据均为 `0`；`fun-asr-realtime` 仅保留 `1` 次受管协议常量。

## Air3 实机

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14`。
- V9.0.32 与 V8 和既有 V9 审计包并存安装，未覆盖稳定 APK。
- 应用私有 Debug bootstrap 与上一版讯飞 AIKit 授权文件均为 `0600`，未进入 APK。
- 首页沿用既有 HUD/布局并显示小叮当待命；设置页显示“受管配置已生效”“当前语音模式：小叮当唤醒”“受管声纹状态：录入中 · 0/3”。
- `ManagedOnlineLongReplyDeviceTest`：`OK (1 test)`，约 `127.2s`；短期会话、真实在线 AI、多页回复和“下一页/下一步”隔离断言全部通过。
- 设备 `base.apk` SHA256 与本地产物一致；最终 V9.0.32 在前台，crash buffer 为空。
- 证据目录：`output/v9.0.32-secure-activation-audit/`。

## 未完成门槛

- 本机缺少正式 Release keystore、alias 和两项密码，未构建正式签名市场包。
- 真实 Supabase 生产迁移、Function、管理 Web 和一次性激活码签发尚未完成线上部署验收。
- 本地激活码兑换、重启恢复、服务端撤销、重新激活的 Air3 端到端流程尚未用生产控制面验证；本轮实机使用 Debug 私有受管投放。
- 本人仍需实时完成三段声纹录入和独立第四段讯飞 `1:1` 验证，并覆盖旁人、噪声、误拒、误受、锁定、耗电和温升。
- 讯飞 AIKit 供应商二进制中的既有 PEM 材料仍需供应商安全说明或清理版 SDK。
