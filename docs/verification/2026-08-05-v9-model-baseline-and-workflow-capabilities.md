# V9 模型合同与工作流能力验证

日期：2026-08-05

## 本轮变更

- V9 Supabase 默认主 AI/视觉模型固定为 `qwen3-vl-plus`。
- V9 Supabase 默认转写模型固定为 `fun-asr-realtime`，默认兼容端点固定为 DashScope 兼容端点。
- `/sessions/:id/voice` 的 HTTP 转写只使用配置模型；失败直接返回真实错误，不回退到 Whisper、GPT 转写或其他新增模型。
- Android 工作流真实注册五项能力：照片、视频、语音、AI 执行上下文、专家视频；连接器未注册。
- 工作流 AI 使用 assignment 的 `projectId` 和稳定任务 ID `workflow-task:<assignmentId>`，结果在 HUD 展示后由现场人员推进下一步。
- 工作流专家进入既有专家协同页；退出后回到原工作流步骤，连接失败不会伪造完成。

## 验证证据

- `npm run validate:v9-release`：通过。
- `npm run validate:v9-delivery`：通过，交付 ZIP SHA256 `024E94ABE159F54DEEC8726F88E8FA1C9FEF806B74172F2D8504896D709BAF5D`。
- `npm run validate:supabase`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:native-hud`：通过。
- `npm run validate:ai-brain`：通过。
- Android `:app:testDebugUnitTest`：通过。
- `git diff --check`：通过。

## 未完成的外部验收

Air3 实机、云端真实部署、讯飞/ASR/主 AI live smoke 和正式 MVS 连接器协议仍按 `agent_memory/bugs.md` 记录，未用模拟数据替代。
