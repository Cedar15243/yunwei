# V9.0.25 任务结束记录 Air3 验证

## 范围

- 补齐任务结束摘要、人工二次确认、完成/关闭切换、项目记忆修订、完成任务不可恢复和下一任务隔离。
- 保持现有首页、HUD 布局、专家协同、Camera2、语音主链路和 V8 稳定 APK 不变。
- 任务结束写入只发生在人工确认之后，不进入 AI 首响、实时 ASR、声纹验证或音频采集路径。

## 模型边界

- 主 AI/视觉固定使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 固定使用上一版 `fun-asr-realtime`，Sherpa 只作断网或云端失败回退。
- 小叮当唤醒继续使用上一版讯飞 AIKit。
- 声纹只使用刚申请的讯飞新版 `s1aa729d0`。
- V9 不使用 OpenClaw 或其他新增模型；长期供应商密钥只保存在服务器 root-only 环境文件中。

## 修复

- Android `ExecutionContextDeviceClient` 和 V9 网关允许任务结束摘要包含正常 `CR/LF/Tab`，继续拒绝其他隐藏控制字符。
- 修复专家协同退出后任务结束草稿、页码和维修步骤丢失的问题。
- 实机复跑发现仪器测试的等待条件可在主线程完成最终首页回执前提前满足；只收紧测试的最终状态等待，未改变产品运行顺序或 UI。

## 产物

- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9.taskend.displaytext.audit`
- 版本：`9025 / 9.0.25-task-end-display-text-audit`
- 主 APK：`output/air3-v9-task-end-display-text-audit-20260803/air3-v9.0.25-task-end-display-text-audit-debug.apk`
- 主 APK SHA256：`3C59EA38C69E49A684F022033A41A6AA8114C60952F6125BD5120B33283E571E`
- Test APK SHA256：`F5F69EE1BF7103D7DBAECADB0C38B0AD72088DAEC0769B57B22A222BA9B77792`
- 网关包 SHA256：`BB0B5C52931D0B3E57B249428446175FCE34BA5E36E1E99B391DBD58264C6D6C`
- targetSdk/compileSdk：`34 / 34`；明文流量关闭；v2 Debug 签名有效，不能作为正式签名发布包。

## 自动化验证

- Android JVM：`78` 个套件、`465/465`，失败 `0`、错误 `0`、跳过 `0`。
- V9 网关：`106/106`。
- `npm run validate:native-hud`、`npm run validate:native-build` 通过。
- `assembleDebugAndroidTest` 构建成功；D8 对 API 34 的既有工具链警告仍存在，未阻断构建。
- 修复后的 `ManagedTaskEndRecordDeviceTest` 连续三次均为 `OK (1 test)`。

## 云端证据

- 服务：`dingdang-v9-gateway.service` 为 `active`。
- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T1459Z-task-end-display-text`。
- previous：`/opt/dingdang-v9-gateway/releases/20260803T1414Z-task-end-record`。
- `/etc/dingdang-v9-gateway.env`、`/etc/dingdang-v9-voiceprint.env` 均为 `0600 root:root`。
- 运行值：`qwen3-vl-plus`、`fun-asr-realtime`、讯飞声纹 `s1aa729d0`。
- V9 网关和现有专家协同健康端点均为 HTTP `200`。
- 最新结束任务：`task-b65170fe-04d9-4851-9eff-9a247b504b91`；项目：`project-1785770376293`。
- 服务端状态为 `completed`，摘要为 5 行原文，项目记忆修订为 `1`，项目记忆与结束摘要一致。
- `task_end_summary` 幂等命令记录为 `1` 条，响应 `duplicate=false`；未发生重复写入。

## Air3 实机证据

- 设备：`YM00FCF3NW0031 / IMA301 / Android 14 / API 34`。
- V8.0.48、V9.0.23、V9.0.24、V9.0.25 均并存并可独立启动，未覆盖稳定包。
- Air3 上 V9.0.25 主 APK SHA256 与本地产物一致；Test APK SHA256 也与本地产物一致。
- 覆盖摘要分页、完成/关闭切换、项目记忆核对、专家退出返回原任务和步骤、取消结束继续任务、人工确认结束、完成任务不可恢复、下一输入创建全新项目和任务。
- crash buffer 与 ANR 扫描为空；退出记录仅为仪器测试启动/结束导致的 `USER REQUESTED / FORCE STOP`，没有应用 crash。

## 可视证据

- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end/01-task-end-summary-page-1.png`
- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end/02-task-end-summary-page-2.png`
- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end/03-task-end-memory-review.png`
- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end/04-expert-returned-to-task-end.png`
- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end/05-task-end-standby-receipt.png`
- `output/air3-v9-task-end-display-text-audit-20260803/qa-task-end-record.txt`
- `output/air3-v9-task-end-display-text-audit-20260803/air3-logcat.txt`
- `output/air3-v9-task-end-display-text-audit-20260803/air3-exit-info.txt`

## 剩余发布门槛

- 本阶段完成任务结束和下一任务隔离，不代表 V9 已达到正式市场发布标准。
- 仍需本人实时完成声纹三段录入、独立第四段 `1:1`、旁人/噪声/误拒/误受、双击模式和长时温升耗电验收。
- 仍需真实 Supabase 迁移、Edge Function、管理 Web、企业身份、生产 Skill/知识授权、生产签名工作流推送及 MVS 工单联调。
- 讯飞原厂 AIKit 二进制材料仍需供应商用途说明或安全清理版 SDK；正式发布签名尚未执行。
