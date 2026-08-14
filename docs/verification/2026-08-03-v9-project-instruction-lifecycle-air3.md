# V9.0.28 项目指令全生命周期与固定模型验证

日期：2026-08-03

## 范围

- 补齐项目指令查看、改版、停用、启用、审计删除和版本冲突恢复。
- 保持现有眼镜 HUD、专家协同、远程运维网页和 V8.0.48 稳定包不变。
- 主 AI、视觉和实时 ASR 全部沿用上一版模型；声纹只使用刚申请的讯飞新版服务。

## 固定模型合同

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`；Sherpa 仅作为断网或云端失败回退。
- 小叮当唤醒：继续使用上一版讯飞 AIKit。
- 声纹录入与 `1:1` 验证：讯飞 `https://api.xf-yun.com/v1/private/s1aa729d0`。
- V9 网关启动时拒绝其他主模型和 ASR 模型；讯飞声纹配置拒绝其他服务 URL。
- V9 安全 APK 使用服务端受管链路，`SECURE_RUNTIME=true`、`DIRECT_GPT_ENABLED=false`，客户端直连 AI、ASR 和讯飞长期凭据字段均为空。
- OpenClaw、GPT 5.5/5.6、DeepSeek、Claude 和其他新增模型均未进入 V9 运行链路。

## 生命周期实现

- 网关不可变版本状态机：`active -> active/disabled/deleted`、`disabled -> disabled/active/deleted`，`deleted` 为终态。
- 同状态只有规则、条件或例外真实变化时才能生成新版本；无变化与非法迁移返回 `409`。
- 删除只生成 `deleted` 墓碑版本，不物理删除历史审计记录。
- Android 严格读取服务端版本、状态、条件和例外，复用现有 HUD 提供查看、改版、停用、启用和审计删除。
- 所有写操作必须二次确认；编辑等待态只接受完整项目规则或取消/返回，不会把编辑内容误发给 AI。
- 版本冲突时清除旧草稿并刷新服务端权威版本，不自动重放旧写入。

## 自动化验证

- V9 网关：`107/107`，无失败。
- Android JVM：`79` 个测试报告、`474/474`，失败 `0`、错误 `0`、跳过 `0`；21 个 Gradle 任务全部实际执行。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:supabase`：通过。
- Android 安全生成配置确认直连模型与长期供应商凭据为空，本地 ASR 回退开启。
- 第一次 Android 验证命令引用了错误的 `IFLYTEK_AIKIT_ROOT`，在 Gradle 配置阶段明确失败；改用真实讯飞 SDK 根目录后全量通过，属于验证参数问题，不是产品回归。

## V9.0.28 产物

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.projectinstruction.lifecycle.audit`。
- 版本：`9028 / 9.0.28-project-instruction-lifecycle-audit`。
- 主 APK：`output/air3-v9-project-instruction-lifecycle-audit-20260803/air3-v9.0.28-project-instruction-lifecycle-audit-debug.apk`。
- 主 APK SHA256：`87E1C58E1DEA79B47E5C22800AB72E63FAA0ADF72AD8260DFA1838F04F33B2BE`。
- Test APK SHA256：`AAB00B223AC8F48176D87478DE3E7A9681B67402D9AB015D824967A8A815A437`。
- `compileSdk/minSdk/targetSdk`：`34/34/34`。
- `usesCleartextTraffic=false`，APK v2 Debug 签名有效。
- APK 解包扫描：OpenClaw、`s1aa729d0`、`qwen3-vl-plus`、GPT 5.5/5.6、DeepSeek、Claude 和六项本地长期凭据均为零命中；`fun-asr-realtime` 仅命中一次受管客户端协议常量。

## 云端状态

- 已部署记录中的当前 release：`/opt/dingdang-v9-gateway/releases/20260803T172503Z-project-instruction-lifecycle`。
- previous：`/opt/dingdang-v9-gateway/releases/20260803T1620Z-project-instruction-execution`。
- 2026-08-03 本轮新鲜公网检查：V9 健康返回 `200 {"ok":true,"service":"dingdang-v9-gateway"}`，现有专家协同返回 `200 {"ok":true,"service":"expert-collab"}`。
- 本轮两把已有 SSH 私钥均被服务器以 `Permission denied (publickey,password,keyboard-interactive)` 拒绝，因此没有把旧的 root-only 环境权限和 release 读取记录冒充为本轮新鲜 SSH 结果；需要后续恢复服务器运维认证。

## Air3 真实闭环

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14`。
- V9.0.28 主包和 Test 包已并存安装，未覆盖 V8.0.48。
- `ManagedProjectInstructionLifecycleDeviceTest`：`OK (1 test)`，用时 `10.21s`。
- 同一真实项目依次生成 `active@v1`、`active@v2`、`disabled@v3`、`active@v4`、`deleted@v5`。
- 停用与删除后规则不进入 AI 上下文；重新启用后再次进入。
- 旧版本冲突不会覆盖服务端新版本，刷新后显示权威 v2。
- 设备结果文件记录模型基线 `qwen3-vl-plus/fun-asr-realtime` 和声纹服务 `s1aa729d0`。
- 录音权限预授权后重新跑测试并重新拉取三张截图，均无权限弹窗、文本越界、按钮遮挡或第二套 UI 风格。
- 设备已安装主 APK SHA256 与本地产物一致。
- V9.0.28 与 V8.0.48 均可独立冷启动；V9 冷启动 `719ms`，V8 冷启动 `1008ms`。最终前台恢复为 V9.0.28。
- crash buffer 为空；退出记录仅包含测试与主动验证产生的 `USER REQUESTED / FORCE STOP`，没有 crash 或 ANR。

## 当前结论与剩余门槛

- V9 的模型选择已按用户要求锁定，不需要修改现有运行时代码，也没有删除或替换供应商密钥。
- V9.0.28 项目指令生命周期达到开发与 Air3 审计验收，但仍是 Debug 审计包，不是正式市场发布包。
- 本人三段实时声纹录入、独立第四段 `1:1` 验证、旁人/噪声/回放、双击监听、抢麦、长时温升和耗电仍未完成。
- 真实 Supabase、管理 Web、生产 Skill/知识授权、签名工作流推送、正式签名与生产身份仍是发布阻断，市场发布状态继续为 `NO-GO`。
