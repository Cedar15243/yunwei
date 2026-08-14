# V9 固定模型与声纹实机检查

日期：2026-08-03

## 固定模型合同

- 主 AI/视觉：上一稳定版 `qwen3-vl-plus`。
- 实时 ASR：上一版 `fun-asr-realtime`；本地 Sherpa 仅用于云端失败或断网回退。
- 声纹：只使用刚申请的讯飞“声纹识别（新）”`s1aa729d0`。
- 小叮当离线唤醒：继续使用上一版讯飞 AIKit。
- 不使用 OpenClaw，不启用新增大模型。

## 云端真实检查

- `dingdang-v9-gateway.service`：`active`。
- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T0200Z-execution-context`。
- `/etc/dingdang-v9-gateway.env`：`0600 root:root`，运行值为 `V9_AI_MODEL=qwen3-vl-plus`、`V9_ASR_MODEL=fun-asr-realtime`。
- `/etc/dingdang-v9-voiceprint.env`：`0600 root:root`，声纹地址为 `https://api.xf-yun.com/v1/private/s1aa729d0`。
- 网关公网健康和现有专家协同健康均返回 HTTP 200；V9 网关仍只监听 `127.0.0.1:8790`。

## 自动化

- `py -3.13 -m unittest discover -s v9-ops-gateway -p 'test_*.py' -v`：`77/77` 通过。
- 覆盖固定模型拒绝、讯飞新版合同、三段录入、独立 `1:1` 验证、反重放、三次失败锁定、重录、删除、撤销、脱敏和幂等。
- Android `:app:testDebugUnitTest --rerun-tasks`：`424/424` 通过，21 个 Gradle 任务全部实际执行；生成配置为 `secureRuntime=true`、`directGptEnabled=false`、`localAsrFallbackEnabled=true`，客户端主 AI/ASR/讯飞长期密钥为空。
- 本轮第一次 Android 验证只启用了安全模式，没有启用讯飞离线唤醒和 Sherpa source set，测试编译因缺少对应类型失败；按 V9.0.15 实际构建组合重跑后全量通过，确认是验证参数问题而非产品回归。

## Air3 状态

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14`。
- 当前已安装验证包：`com.codex.air3nativecamera.dingdangexpert.v9.managedcontext.audit`，`9015 / 9.0.15-managed-context-audit`；V9.0.14 和 V9.0.12 继续并存。
- V8 与既有 V9 审计包保持并存，未覆盖稳定 APK。
- 已从真实设置入口提交本人授权，当前状态为 `录入中 · 录入 0/3`。
- 下一步需要本人实时连续说话至少 3 秒，依次完成三段录入，再用独立第四段语音完成 `1:1` 验证。

## 未完成

- 本人、旁人、噪声、重复音频和三次失败锁定实机验证。
- 双击开启/关闭、单击原行为、相机/录像/专家通话抢占及释放后不自动恢复。
- 声纹通过后进入 `fun-asr-realtime`，再由 `qwen3-vl-plus` 回复的端到端延迟、温升、耗电和长时稳定性。

## 2026-08-03 本轮复核边界

- V9 与专家协同公网健康端点均新鲜返回 HTTP 200。
- 本轮本机 SSH 凭据未能重新通过线上服务器公钥认证，因此没有把前述 root-only 环境值表述为本轮重新读取；模型值仍由源码强制合同、部署模板、全量测试和同日既有线上检查共同约束。
