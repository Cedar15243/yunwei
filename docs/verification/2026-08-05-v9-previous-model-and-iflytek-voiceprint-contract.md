# V9 上一版模型与讯飞声纹合同验证

## 范围

- V9 主 AI/视觉固定为 `qwen3-vl-plus`。
- V9 实时 ASR 固定为 `fun-asr-realtime`。
- 小叮当唤醒继续使用上一版讯飞 AIKit。
- 声纹录入和 `1:1` 校验仅使用讯飞 `s1aa729d0`。
- 不修改稳定 V8、远程运维 Web、首页 HUD、专家协同或 Camera2 链路。

## 本轮调整

- V9 集成工程的直连预览默认地址改为 DashScope OpenAI 兼容接口，默认模型改为 `qwen3-vl-plus`。
- Android 构建在直连预览指定其他模型时以 `direct_gpt_model_not_previous_stable` 失败关闭。
- `validate:v9-release` 现同时检查正式网关、Android 构建配置、集成预览脚本和运行时兜底，不允许 OpenClaw、GPT-4.1 mini、GPT-5.x、DeepSeek、Claude、Qwen Max 或 OpenAI 默认地址进入 V9 合同。
- 声纹凭据仍只由 V9 网关服务端持有，APK 不保存讯飞声纹服务标识或长期密钥。

## 验证证据

- TDD RED：增强后的 `npm run validate:v9-release` 首次准确报告 V9 预览仍含 `gpt-4.1-mini` 和 `api.openai.com`。
- TDD GREEN：完成调整后 `npm run validate:v9-release` 通过。
- 非白名单模型构建实测被拒绝，错误为 `direct_gpt_model_not_previous_stable`。
- `npm run validate:native-hud` 通过。
- `npm run validate:native-build` 通过。
- V9 网关模型、声纹和部署专项 Python 测试 `3/3` 通过。
- Android 使用 Temurin JDK 17、上一版讯飞 AIKit 和 Sherpa 依赖执行全量 JVM 回归：`536/536` 通过，`0` 失败、`0` 错误、`0` 跳过。
- 云端 V9 健康检查返回 HTTP `200`。
- DashScope `qwen3-vl-plus` 轻量真实调用返回 HTTP `200`，本次观测延迟约 `899 ms`。
- 云端 V9 声纹资料读取返回 HTTP `200`，状态为 `enrolling`，证明声纹生命周期已启用；本轮未上传录音、未新增声纹样本。
- `git diff --check` 无空白错误。

## 未验证

- 2026-08-05 `adb devices -l` 无在线 Air3，未执行 APK 安装、双击声纹监听、本人/旁人验证、抢麦、耗电和温升实机验收。
- 正式市场签名仍受 Release keystore 缺失阻断。
