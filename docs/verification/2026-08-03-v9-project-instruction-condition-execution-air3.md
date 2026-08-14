# V9.0.27 项目指令条件执行与固定模型验证

日期：2026-08-03

## 范围

- 项目现场指令从自然语言提取受控 `containsAny` 条件。
- 二次确认页明确展示触发条件，取消不写服务端，确认后保存版本化项目记录。
- 服务端只把匹配当前问题的项目指令注入 AI `ExecutionContext`。
- 固定使用上一稳定版主 AI/视觉和实时 ASR，声纹只使用刚申请的讯飞新版服务。
- 不修改现有 HUD 布局、专家协同、V8 稳定 APK 或远程运维网页主链路。

## 实现

- “以后在这个项目遇到控制器报警先……”生成 `{"containsAny":["控制器报警"]}`。
- “以后先……”且没有明确问题对象时保持空条件，代表用户显式确认的项目级通用规则。
- 二次确认页增加用户可见的“触发条件”，继续沿用现有 HUD 卡片、分页和确认按钮。
- 网关系统合同明确要求模型执行当前 `executionContext` 中已匹配且已授权的 Skill/项目指令，不得套用未出现在本轮上下文中的规则。
- 条件筛选和上下文解析仍在网关本地 SQLite 完成，不新增模型调用或额外网络往返。

## 固定模型与云端

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 声纹：`https://api.xf-yun.com/v1/private/s1aa729d0`。
- OpenClaw、新增 GPT、DeepSeek、Claude 和其他新增 Qwen 模型均未接入 V9 运行链路。
- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T1620Z-project-instruction-execution`。
- previous：`/opt/dingdang-v9-gateway/releases/20260803T1459Z-task-end-display-text`。
- `dingdang-v9-gateway.service` 为 `active`；两个环境文件均为 `0600 root:root`。
- V9 公网健康与现有专家协同健康均返回 HTTP 200。

## 自动化验证

- 项目指令确认页条件显示按 TDD 完成红灯与绿灯。
- 网关模型执行合同按 TDD 完成红灯与绿灯。
- Android JVM：`467/467`，无失败、错误或跳过。
- V9 网关本地：`106/106`；部署前远端：`106/106`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:supabase`：通过。
- AndroidTest 编译、主 APK 和 Test APK 构建通过。

## V9.0.27 产物

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.projectinstruction.execution.audit`
- 版本：`9027 / 9.0.27-project-instruction-execution-audit`
- 主 APK：`output/air3-v9-project-instruction-execution-audit-20260803/air3-v9.0.27-project-instruction-execution-audit-debug.apk`
- 主 APK SHA256：`2975646C1BCA8A6B2E1258ACBD1740B108439ECF01AE08F264BDCBDC87DAD62C`
- Test APK SHA256：`8F9CF6FB3B9C806FB6DA055DBC159AE8BE6F837799BB9781D3B6108A7A206F55`
- `compileSdk/minSdk/targetSdk`：`34/34/34`。
- `usesCleartextTraffic=false`，主包和 Test 包的 APK v2 Debug 签名有效。
- 安全生成配置为 `SECURE_RUNTIME=true`、`DIRECT_GPT_ENABLED=false`；客户端主 AI、直连 ASR和讯飞长期密钥字段均为空。
- APK 扫描：OpenClaw、`s1aa729d0`、`qwen3-vl-plus`、GPT 5.5/5.6、DeepSeek、Claude 和五项本地长期凭据均为零命中；`fun-asr-realtime` 仅命中受管客户端协议常量。

## Air3 真实闭环

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14`。
- 主 APK 与 Test APK 并存安装成功，两份 Debug 私有投放文件权限为 `0600`，设备中转副本已删除。
- `ManagedProjectInstructionConditionDeviceTest`：`OK (1 test)`，总时间 `6.579s`。
- 取消草稿后，服务端项目记录不存在该指令 ID。
- 人工确认后，服务端保存 `version=1`、`status=active`、原始 action 和 `containsAny=[控制器报警]`。
- 匹配 trace `8b2ef825-7558-4715-ba9f-660fe13aa421`：执行审计使用 `qwen3-vl-plus`，指令版本列表包含本次 `instruction@v1`，回复包含“规则9027已应用”。
- 不匹配 trace `abf25ee2-7389-46df-b480-b26822d9ea1a`：执行审计仍使用 `qwen3-vl-plus`，指令版本列表为空，回复未包含规则标记。
- 设备已安装 APK 哈希与本地主产物一致。
- V8.0.48、V9.0.26、V9.0.27 均可独立启动；目标包 crash buffer 为空，退出记录只有测试/强制停止产生的 `USER REQUESTED`。
- 确认页、项目记录页和最终首页截图无文本越界、按钮遮挡或第二套 UI 风格。

## 性能边界

- 本次改动不增加任何额外 AI/ASR/声纹请求；项目指令匹配仍是服务端本地字符串与属性判断。
- 实机完整测试包含两次真实 AI 调用并在 `6.579s` 内完成，但这不是长期 p95、温升或耗电结论。
- 声纹录入、本人/旁人/噪声、双击监听、抢麦、3 秒 p95、温升和耗电仍需单独实机验收。

## 剩余门槛

- 已生效项目指令的眼镜端改版、停用/删除和服务端版本冲突恢复仍需完整验收。
- 真实 Skill/知识生产授权、撤销传播、管理 Web 和 Supabase 生产部署仍未完成。
- 讯飞本人声纹三段录入和独立第四段 `1:1` 验证仍未完成。
- 当前仍为 Debug 审计包，正式签名、生产身份与市场发布状态仍为 `NO-GO`。

## 最终模型收口复核

- 2026-08-03 重新执行 Android JVM `467/467`、V9 网关 `106/106`、`validate:native-hud`、`validate:native-build` 和 `validate:supabase`，均无失败。
- 线上仍运行 `/opt/dingdang-v9-gateway/releases/20260803T1620Z-project-instruction-execution`；主 AI/视觉为 `qwen3-vl-plus`，实时 ASR 为 `fun-asr-realtime`，声纹为讯飞 `s1aa729d0`，两个环境文件均为 `0600 root:root`，V9 与专家健康均为 HTTP `200`。
- 本次发布在服务器 `/tmp` 的临时目录和压缩包已删除，不影响当前 release 与 previous。
- Air3 当前前台运行 V9.0.27，V8.0.48 继续并存；设备 APK 与本地产物 SHA256 一致，历史退出记录只有测试产生的 `USER REQUESTED / FORCE STOP`。
- 安全 APK 中 OpenClaw、讯飞声纹服务 ID、服务端主模型、GPT 5.5/5.6、DeepSeek、Claude 和六项本地长期凭据均为零命中；`fun-asr-realtime` 只命中一次受管客户端协议常量。六个本地 `.local` 凭据文件均保留且被 Git 忽略。
