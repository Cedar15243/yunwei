# V9 运行时语音模式与正式包复核

日期：2026-08-04

## 变更

- 设置页不再直接显示编译期默认语音模式。
- `MainActivity.currentVoiceModeLabel()` 现在根据受管后端是否激活、讯飞上一版唤醒授权和当前互斥模式计算文案。
- 未激活的安全 V9 显示“服务不可用 · 设备待激活”；声纹监听、监听关闭和已授权小叮当模式分别显示对应运行时状态。
- 模型合同保持不变：主 AI/视觉 `qwen3-vl-plus`、实时 ASR `fun-asr-realtime`、小叮当上一版讯飞 AIKit；声纹录入与 `1:1` 只走新申请的讯飞 `s1aa729d0`。

## 验证

- Android JVM：`515/515` 通过，使用 Temurin JDK 17；其中包含 `MainActivityVoiceCommandTest.settingsVoiceModeReflectsRuntimeProvisioningInsteadOfBuildFlags`。
- V9 网关：`py -3.13 -m unittest discover -s v9-ops-gateway -p 'test_*.py' -v`，`109/109` 通过。
- 发布契约：`node scripts/validate-v9-release.mjs` 通过；正式 APK 扫描未发现 OpenClaw、禁用模型、主 AI/ASR/声纹服务 ID或长期凭据。
- 正式包：`com.codex.air3nativecamera.dingdangexpert.v9`，`900000 / 9.0.0`，APK SHA256 `9F25BE9A78E63E663F9604F5812BDD87029CEFD44547A64CFE5ADCF36BA81196`，APK v2 签名有效，证书 SHA256 `824c132d64d93ce6be69211f7f55e37035e02fc73a116606707a14e64f208a83`。
- Air3：设备 `YM00FCF3NW0031` 在线；正式包安装成功，与 V8 稳定包并存。启动后打开设置，UI dump `output/v9.0.0-formal-release/v9-formal-settings-fixed.xml` 显示：`当前语音模式：服务不可用 · 设备待激活`。

## 未完成的外部验收

- 生产 Supabase 迁移、Edge Function、管理 Web、一次性激活码签发与真实激活/撤销/重新激活。
- 本人三段实时声纹录入、独立第四段讯飞 `1:1`、旁人/噪声/误拒/误受与长时温升耗电。
- 讯飞 AIKit 供应商 AAR 内既有 PEM 材料的用途说明或清理版 SDK。
