# 叮当运维AI最终验收 Runbook

本文档用于把长期目标收口到可验证状态：Superpowers 计划、Figma 页面、Expo 原型、Android APK、AR 眼镜快捷操作、流式 ASR、GPT 流式回复和真实 provider smoke 都必须有证据。

## 0. 当前本地门禁

在任何真实部署或 Figma 写入前，先跑本地门禁：

```powershell
npm run validate:figma-blueprint
npm run validate:figma-write-script
npm run validate:figma-frames-evidence -- --self-test
npm run validate:mock-smoke-latency
npm run validate:real-smoke-summary -- --self-test
npm run validate:supabase
npm run audit:dingdang-supabase
npm run audit:dingdang-goal
rg -n "sk-[A-Za-z0-9_-]{12,}" .
```

`rg` 退出码为 1 且无输出表示未发现 `sk-...` 形态的 key。

## 1. Figma 真实画板收口

前置条件：Figma MCP 额度恢复，`get_metadata` 不再返回 Starter tool call limit。

1. 按 `figma-use` skill 调用 `use_figma`。
2. 文件 key：`pDX9LEKARKp5GGchwuFLz4`。
3. 将 `docs/figma/dingdang-ops-ai-use-figma-script.js` 的完整内容作为 `code` 传给 `use_figma`。
4. `use_figma` 返回后，保存 `pageId`、5 个 frame 的 nodeId 和 frame 名称。
5. 对每个 frame 调用 `get_metadata` 与 `get_screenshot`，确认：
   - `chat-main`
   - `camera-capture`
   - `asr-streaming`
   - `gpt-streaming`
   - `shortcut-flow`
6. 将验证结果写入 `tmp/dingdang-figma-frames-<timestamp>/evidence.json`：

```json
{
  "ok": true,
  "figmaFileKey": "pDX9LEKARKp5GGchwuFLz4",
  "metadataVerified": true,
  "frames": [
    {
      "id": "chat-main",
      "nodeId": "123:456",
      "metadataVerified": true,
      "screenshotVerified": true,
      "width": 1440,
      "height": 900
    }
  ]
}
```

`frames` 必须包含 5 个 required frame。然后运行：

```powershell
npm run validate:figma-frames-evidence -- tmp\dingdang-figma-frames-<timestamp>\evidence.json
npm run audit:dingdang-goal
```

## 2. Supabase 真实 provider 部署

前置条件：

- 已轮换此前对话中暴露过的 DashScope/GPT key。
- 新 key 只进入 Supabase secrets 或 ignored env 文件。
- 本地存在 Supabase 登录态或 `SUPABASE_ACCESS_TOKEN`。
- 已确定 Supabase project ref。

准备 ignored env 文件，例如 `supabase/.env.production.local`，只写 secret 名称和值，不提交：

```dotenv
SUPABASE_URL=
SUPABASE_SERVICE_ROLE_KEY=
OPS_GLASSES_API_KEY=
OPENAI_API_KEY=
OPENAI_BASE_URL=
OPENAI_VISION_MODEL=
DASHSCOPE_API_KEY=
DASHSCOPE_FUNASR_URL=
DASHSCOPE_FUNASR_MODEL=
```

部署：

```powershell
npx supabase login
powershell -ExecutionPolicy Bypass -File scripts\deploy-dingdang-supabase-prod.ps1 -ProjectRef <project-ref> -SecretsEnvFile supabase\.env.production.local
npm run audit:dingdang-supabase
```

## 3. Air3 真实 live smoke

前置条件：

- Air3 通过 ADB 可见。
- 已安装部署脚本生成的真实后端配置 APK。
- 现场人员可以在脚本提示窗口内真实说话。

运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-dingdang-real-live-smoke.ps1 -BackendBaseUrl https://<project-ref>.supabase.co/functions/v1/ops-glasses -OutDir tmp\real-live-smoke-<timestamp>
npm run validate:real-smoke-summary -- tmp\real-live-smoke-<timestamp>\summary.json
```

验收必须证明：

- `provider=real`
- 后端 `/health` 通过
- 拍照返回聊天输入上下文
- ASR partial/final 来自 logcat
- GPT first delta 来自 logcat
- 延迟预算通过
- 最终仍在 `叮当运维AI`

## 4. Air3 快捷键与 APK 回归

真实 provider smoke 通过后，再跑 APK 与快捷键回归：

```powershell
npm run validate:native-hud
npm run validate:native-build
powershell -ExecutionPolicy Bypass -File scripts\install-and-verify-dingdang-ops-ai.ps1 -WaitSeconds 30
powershell -ExecutionPolicy Bypass -File scripts\test-dingdang-air3-shortcuts.ps1 -WaitSeconds 30 -OutDir tmp\sidekey-final-<timestamp>
```

快捷键必须保持：

- `ENTER(66)` / `DPAD_CENTER(23)`：语音开始或结束
- `FOCUS(80)` / `F9(139)`：App 内相机
- `RIGHT(22)` / `MENU(82)` / `F12(142)`：发送
- `BACK(4)` / `LEFT(21)` / `F10(140)`：返回或保持聊天页
- `VOLUME_UP(24)` / `VOLUME_DOWN(25)`：App 内消费
- `CAMERA(27)` / `DVR(173)`：继续判定为系统相机保留键，不作为普通 APK 可改绑承诺

## 5. 最终完成判定

只有以下命令都通过，才能把长期目标判定完成：

```powershell
npm run validate:figma-frames-evidence -- tmp\dingdang-figma-frames-<timestamp>\evidence.json
npm run validate:real-smoke-summary -- tmp\real-live-smoke-<timestamp>\summary.json
npm run validate:mock-smoke-latency
npm run validate:supabase
npm run audit:dingdang-supabase
npm run audit:dingdang-goal
```

`npm run audit:dingdang-goal` 必须输出 `allComplete=true`，且不能有 `nextRequiredActions`。
