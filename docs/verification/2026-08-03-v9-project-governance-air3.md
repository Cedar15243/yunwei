# V9.0.17 项目任务治理与固定模型验证

## 固定运行模型

- 主 AI/视觉：上一稳定版 `qwen3-vl-plus`。
- 实时 ASR：上一版 `fun-asr-realtime`；Sherpa 仅作断网或云端失败回退。
- 声纹：只使用刚申请的讯飞新版 `s1aa729d0`。
- 不使用 OpenClaw，不启用其他新增模型；V9 安全 APK 不保存供应商长期密钥。

## 本轮实现

- V9 的“结束当前任务”“完成当前任务”和维修最后一步进入用户可见二次确认，不再提前完成本地任务。
- “关闭当前任务并返回首页”生成 `closed` 结束草稿；服务端成功后才结束本地任务并返回首页。
- “以后在这个项目遇到……先……”生成项目指令草稿，人工确认后写入服务端项目记录。
- 任务结束前读取最新项目记忆 revision，保留既有确认事实、排除事实和风险；冲突时重新生成，不制造本地假成功。
- 返回首页只暂停并保留项目；结束任务后下一次输入必须创建新任务。
- 语音帮助已区分返回首页、结束任务、关闭任务、项目指令和确认/取消。

## 自动化验证

- Android 全量 JVM：成功，21 个 Gradle 任务实际执行。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- V9 网关：`77/77` 通过，覆盖固定模型、任务结束摘要、项目指令、声纹和工作流合同。
- Gradle `assembleDebug`：成功；既有 D8 API 34 工具链警告仍在，未阻塞构建。

## APK 与 Air3

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.governance.audit`
- 版本：`9017 / 9.0.17-project-governance-audit`
- APK：`output/air3-v9-project-governance-audit-20260803/air3-v9.0.17-project-governance-audit-debug.apk`
- SHA256：`5FED0BC0D1C7E5D2501CE56D6102118C4073A9E5F13B652A9D9D847CBF858896`
- Air3：`YM00FCF3NW0031 / IMA301 / Android 14`，并存安装成功。
- 两份调试投放文件位于应用私有 `files/`，权限均为 `0600`；设备公共暂存已删除。
- 冷启动成功，首页维持现有 HUD，显示 `Air3 已连接`；无 crash/ANR。
- V8.0.48、V9.0.16 与 V9.0.17 包同时存在。

## 尚未替代人工的验收

- 需要一个真实进行中项目任务，实测结束摘要确认、项目指令确认、服务端项目详情刷新及下一任务隔离。
- 讯飞声纹仍需本人完成三段实时录入和独立第四段 `1:1` 验证；不能使用历史录音、TTS 或回放替代。
