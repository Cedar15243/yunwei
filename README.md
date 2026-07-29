# 叮当 AI 运维眼镜

面向现场工程师的 AR AI 运维助手。系统以 Air3 智能眼镜为主要交互终端，通过离线唤醒、语音转写、现场照片、AI 多轮对话和专家协同，把“发现异常、采集证据、分析判断、维修指导、结果复核”组织成一个可追踪的现场任务。

## 项目定位

叮当不是手机聊天页面，也不是后台工单系统。它把 AI 保持在眼镜视野中：

- 首页提供白雾灰绿 HUD 待命界面和“小叮当”离线唤醒。
- 首次诊断支持照片、语音描述和现场文字，自动创建独立维护任务。
- 首轮分析后持续留在全屏 AI 运维对话页，不因后续语音、拍照或分页返回首页。
- 每轮对话同时展示用户照片、语音转写/文字和 AI 回复，便于现场人员核对证据。
- 长回复按页呈现；维修指导按单步推进，支持“下一步、我已完成、异常、补拍近景、重新分析、呼叫专家”。
- 专家协同保留现场视频、专家窗口、AI 标注和指导字幕，退出后回到原任务和原维修步骤。

## 当前能力

### 现场 AI 诊断

维护任务保存设备信息、故障描述、全量照片、语音转写、用户文字、AI 回复、风险、维修步骤、当前进度和专家协同快照。后续 AI 请求组合使用结构化任务事实、会话摘要、最近对话和相关图片证据，避免长对话后遗忘设备型号、已确认现象和已排除项。

AI 回复以任务上下文为中心，不强制展示固定栏目；根据现场问题给出简洁判断、当前依据和一个可执行动作。证据不足时要求补拍或补充描述，不伪造结论或检测框。

### 语音交互

- 前台使用“小叮当”唤醒，唤醒和命令分两个节拍，降低误识别。
- 待命时由离线唤醒占用麦克风；唤醒后切换到 ASR；AI 完成后自动恢复离线唤醒。
- 任务页、分页、维修步骤、能力中心、巡检和帮助页面都支持当前页面内语音控制。
- 内置命令优先处理；普通问题和现场描述交给 AI，不因包含“返回”等词而误导航。
- 专家通话期间暂停唤醒和 ASR，退出后恢复。

### 现场巡检与知识能力

能力中心按真实可用性排序，包含 AI 故障诊断、专家协同、现场拍照、视频取证、AI 运维技能、巡检任务、维修任务、设备记忆、华方知识库和 AI Agent 技能包入口。巡检任务支持实训室设备巡检，以及水电暖、空调、消防等行业场景的逐点拍摄、识别、前后值比较、人工确认和结果汇总。

环境诊断演示通过独立技能开关隔离。开关开启后，现场平台异常照片和描述可以进入指定诊断流程；关闭后，普通 AI 问答和其他设备诊断不继承演示上下文。

## 技术结构

```text
Air3 Android App
  ├─ VoiceEventStateMachine / VoiceAsrSessionGate
  ├─ VoiceCommandRouter / Offline Wake Word Engine
  ├─ MaintenanceTask / TaskSessionManager
  ├─ Inspection Tasks / Operation Catalogs / Knowledge Catalogs
  ├─ HUD Web Presentation
  └─ TRTC Expert Collaboration

AI 服务与协同服务
  ├─ Direct AI / SSE 对话接口
  ├─ 图片证据与检测标记协议
  └─ 专家协同 WebSocket / TRTC 链路
```

Presentation Layer 负责 HUD、状态、分页、证据呈现和语音路由；AI 接口、照片上传、任务数据和专家通信保持独立，便于在不破坏稳定版本的情况下构建隔离的投资演示包。

## 本地验证

要求 Node.js 20+、Android SDK、JDK 和项目配置的 Gradle 环境。

```powershell
npm install
npm run validate:native-hud
npm run validate:native-build
npm run validate:investor-v8-isolation
npm --prefix expert-collab-server test
```

构建隔离版投资演示包：

```powershell
.\scripts\build-dingdang-investor-v8.ps1 -OfflineWake -DirectAi
```

安装和真机回归前，使用带有明确设备序列号的安装脚本；脚本会校验隔离包身份并检查已有包版本不被覆盖。真实 Air3 验收仍需要现场执行小叮当连续唤醒、真实照片 AI 判断、弱网重试、专家接听和长时间佩戴测试。

## 安全边界

- `.env`、私钥、API 密钥、设备密钥和本地 `agent_memory` 不进入 Git。
- 不在源码和 README 中保存生产服务密钥；AI 和协同服务凭据通过运行环境注入。
- 演示技能只在显式启用并满足现场证据条件时生效，不把预设话术伪装成通用诊断能力。
- 自动化测试不能替代真人声学唤醒、现场照片语义准确率、专家双向音视频和温升验收。

## 文档

- `docs/releases/`：版本验证记录和发布边界。
- `docs/releases/v8.0.40-marker-correction.md`：当前 Air3 标注修正版说明与验收结果。
- `docs/superpowers/specs/`：产品和交互设计规格。
- `D:\Users\59979\Desktop\叮当AI运维眼镜_完整演示操作手册_v8.0.25.docx`：现场完整演示手册。
