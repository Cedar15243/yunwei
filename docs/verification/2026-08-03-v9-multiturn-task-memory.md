# V9 多轮任务记忆修复验证

## 范围

- 修复首轮 AI 回复含换行或 Markdown 时，第二轮被网关以 `recent_messages_invalid` 拒绝的问题。
- 保持 V9.0.19 APK、现有 HUD、专家协同和 V8 稳定包不变，只前向更新独立 V9 网关。
- 核对主 AI/视觉、实时 ASR 和声纹供应商继续使用已确认版本。

## 根因与修复

- 根因：`ExecutionContextRepository._recent_messages()` 复用了单行严格文本校验，任何字符码小于 32 的内容都会拒绝，因此正常 `LF/CRLF/Tab` 也被当成非法控制字符。
- 修复：新增仅用于对话正文的显示文本校验，允许 `Tab/LF/CR` 和 Markdown 正文，继续拒绝空字节、ESC、DEL 与其他控制字符；ID、属性、Skill、工单等字段仍使用原严格校验。
- TDD 红灯同时复现正常 Markdown 历史被拒绝，以及旧实现漏过 `DEL (0x7F)`；修复后两个用例均通过。

## 固定模型

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 声纹：讯飞新版 `s1aa729d0`。
- V9 安全生成配置为 `secureRuntime=true`、`directGptEnabled=false`；客户端 AI、ASR 和讯飞长期密钥字段为空，OpenClaw 运行时扫描为 0。

## 自动化

- V9 网关本地：`106/106` 通过。
- V9 网关远端部署前：`106/106` 通过。
- Android JVM：`457/457`，77 个测试套件，无失败、错误或跳过；21 个 Gradle 任务全部实际执行。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `git diff --check`：通过，仅有既有 LF/CRLF 提示。

## 云端部署

- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T115438Z-recent-messages-r2`。
- 上一 release：`/opt/dingdang-v9-gateway/releases/20260803T113409Z-task-memory`。
- 部署脚本在切换前备份 `gateway.db`、`voiceprint.db` 和 Caddy；失败会恢复旧 release。
- 本机 `127.0.0.1:8790/health`、V9 公网 `/v9-ops/health` 和现有专家 `/health` 均返回 HTTP 200。
- 首个部署包遗漏远端合同测试夹具，在安装前停止且线上未切换；补齐后的 `r2` 包通过全部远端测试后才安装。

## Air3 实机

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- 包：`com.codex.air3nativecamera.dingdangexpert.v9.taskmemory.audit`，`9019 / 9.0.19-task-memory-audit`。
- APK SHA256：`515A9B84C501DF635F5365ECC31E4BB1EB0944A5A1C1C6F8F772D86C23AAFD7A`；从设备拉取的已安装 APK 与发布包一致。
- 第一轮：`服务器无法启动，设备型号为 H3C UniServer R4900 G5。`
- 第一轮 AI 回复为多段 Markdown 列表，能够进入第二轮历史上下文。
- 第二轮：`刚才的设备型号和故障是什么？只回答设备型号和故障。`
- 第二轮真实返回：`设备型号：H3C UniServer R4900 G5 / 故障：服务器无法启动`。
- 同一可见会话为 4 条消息；普通任务未绑定演示 Skill。返回首页后任务状态为 `PAUSED`，可恢复且未丢弃。
- `MultiTurnTaskDeviceTest`：`OK (1 test)`；logcat 和应用退出记录无 crash/ANR，测试结束的 force-stop 属于 instrumentation 正常收尾。
- V8.0.48 仍并存：`848 / 8.0.48-sensor-wiring-flow`，未被覆盖。

## 剩余门槛

1. 完成真实长回复分页、底部遮挡和“下一页/下一步”语义实机验收。
2. 部署 Supabase Skill/知识、身份设备和管理 Web 控制面，并验证真实授权、撤销传播和角色负向权限。
3. 由本人完成三段实时声纹录入、独立第四段 `1:1` 验证、旁人/噪声/重放/锁定、双击模式和温升耗电验收。
4. 投放生产签名工作流与真实工单连接器，完成照片、视频、语音、步骤推进、离线恢复和服务端回执闭环。
