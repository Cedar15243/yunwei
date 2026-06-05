# Air3 运维眼镜架构规划

## 当前结论

最新 UI 已经改为 Air3 眼镜内全屏 Camera2 HUD，不再是普通手机 App 页面。主应用路线应以 `air3-native-camera-test` 原生 Android APK 为准，Expo 项目作为 UI 原型、流程模拟或后续管理端/手机辅助端参考。

## 产品目标

现场人员佩戴 Air3 后，只看眼镜 HUD 完成服务器 SSH 恢复演示：

1. 对准服务器本地控制台或终端窗口。
2. 单击拍照上传。
3. AI/后端识别控制台画面。
4. 眼镜显示下一步安全命令。
5. 现场人员输入命令并再次拍摄输出。
6. 后端复测远程访问。
7. 眼镜显示完成、重拍、新问题分诊或人工接管。

## 总体架构

```text
Air3 原生 APK
  - Camera2 预览/拍照
  - HUD 指令层
  - 取景框与裁剪
  - 触控/按键/语音入口
  - sessionId/currentStep 本地保存
        |
        | HTTPS + x-ops-glasses-key
        v
Supabase Edge Function: ops-glasses
  - 运维会话状态机
  - 安全命令 allowlist
  - AI 视觉识别编排
  - 语音意图处理
  - 远程复测
        |
        v
Supabase Postgres + Storage
  - ops_sessions
  - ops_events
  - ops_images
  - ai_requests
  - ai_observations
  - voice_inputs
  - remote_probes
```

## 眼镜端职责

主线工程：`air3-native-camera-test`

眼镜端负责真实设备能力，不负责运维决策：

- 打开 Air3 真实 Camera2 相机。
- 以全屏预览作为 HUD 背景。
- 显示顶部任务栏、中央主指令、绿色取景框、底部操作栏。
- 单击拍照/下一步，长按语音入口，返回键重拍，相机键拍照。
- 拍照后按 HUD 取景框比例裁剪上传图。
- 上传 `sessionId`、`step`、`action`、`imageBase64`。
- 显示后端返回的 `text` 和 `step`。
- 保存最近一次 `sessionId/currentStep/last_ops_response.json` 便于排查。
- 不显示 HTTP、bytes、session、异常类名等调试信息。

当前 HUD 结构：

- 全屏 Camera2 预览层。
- 顶部：`AI 运维眼镜 | 服务器 SSH 恢复`。
- 右上角：当前步骤标签，例如 `需要重拍`。
- 中央：绿色取景框与主指令面板。
- 底部：短状态和操作提示。

## Camera Adapter

相机适配层必须独立对待，不能混在业务状态机里。

职责：

- 选择拍照 JPEG 尺寸，优先使用硬件支持的高分辨率。
- 选择预览 SurfaceTexture 尺寸，优先接近 16:9。
- 对 `TextureView` 应用 Matrix，避免拉伸。
- 处理 Air3 相机传感器方向与横屏 HUD 的关系。
- 处理上传给 AI 的图片旋转方向。
- 记录 camera id、sensor orientation、JPEG sizes、preview sizes。

当前已完成：

- 预览尺寸和拍照尺寸分离。
- 取景框比例和上传裁剪比例统一。
- 普通 HUD 文案已去掉英文调试信息。

当前待解决：

- 真机截图显示 HUD 是正的，但 Camera2 预览背景疑似旋转方向不正确。下一步应通过真机日志确认 sensor orientation 和预览 buffer 方向后修正。

## 后端职责

主线工程：`supabase/functions/ops-glasses`

后端是运维编排层，不是简单图片转文字服务。

职责：

- 创建和维护 `ops_sessions`。
- 接收眼镜端事件 `POST /sessions/events`。
- 根据 `step/action` 推进状态机。
- 保存图片到 Storage。
- 写入事件、图片、AI 请求、AI 观察结果。
- 调用视觉模型识别控制台照片。
- 只从 `safe_commands` 下发命令。
- 支持 `/sessions/:id/voice`、`/sessions/:id/probe`、`/sessions/:id/escalate`。
- 支持 automigrate 自动补齐数据库结构。

安全边界：

- 眼镜端不直接调用 AI。
- AI 不允许直接返回 shell 命令。
- APK 内置 `OPS_GLASSES_API_KEY` 只适合 PoC/演示。
- 正式版应改为设备注册、短期 token 或 MDM/手机端下发凭证。

## AI 职责

AI 是观察者，不是执行者。

视觉模型输出结构化 JSON：

```json
{
  "screenType": "command_output",
  "recognizedText": "Active: inactive (dead)",
  "photoQuality": "readable",
  "workflowSignal": "ssh_service_stopped",
  "riskLevel": "low",
  "confidence": 0.86,
  "needsBetterPhoto": false,
  "needsHumanExpert": false
}
```

允许的 `workflowSignal`：

- `login_screen`
- `shell_prompt`
- `ssh_service_running`
- `ssh_service_stopped`
- `ssh_service_unknown`
- `photo_unclear`
- `unexpected_error`

后端根据这些信号决定下一步，而不是让 AI 自由发挥。

## Expo 的定位

工程：`air3-ops-expo-app`

Expo 不作为当前 Air3 真机主 APK，原因：

- 真实 Air3 Camera2 链路已经在原生 APK 跑通。
- Air3 标准 Android SpeechRecognizer 不可用。
- Air3 物理按键和相机方向需要原生层精确控制。

Expo 适合作为：

- HUD 页面原型。
- 流程状态模拟器。
- 给客户/团队看的交互演示。
- 后续手机辅助端或管理端基础。

## 测试与验收

### 眼镜端验收

- APK 可安装并启动。
- HUD 正常显示，无英文调试信息。
- 相机预览不明显拉伸，方向正确。
- 绿色取景框与上传裁剪一致。
- 单击可拍照上传。
- 后端返回 `needs_better_photo`、诊断命令、恢复命令等状态时，HUD 能显示中文业务文案。
- 返回键/相机键绑定必须有真机 KeyEvent 日志依据。

### 后端验收

- `/health` 返回 `ok=true`。
- `/sessions/events` 可创建会话。
- 图片写入 Storage。
- `ops_sessions/ops_events/ops_images/ai_requests/ai_observations` 写入记录。
- AI 返回结构化观察结果。
- 状态机能推进到诊断、恢复、复测、完成或人工。

### 演示验收

- 物理屏幕前景必须是真正服务器控制台或终端窗口。
- 控制台文字必须位于绿色取景框内。
- 至少完成一轮：拍控制台 -> 返回诊断命令 -> 拍诊断输出 -> 返回恢复命令。
- 若现场画面不合格，眼镜必须显示“照片不够清晰/请重拍”，而不是崩溃或显示调试内容。

## 下一步实施顺序

1. 修正 Camera Adapter 的预览方向问题。
2. 用 Air3 真机重新截图验证 HUD 和预览方向。
3. 用真实服务器控制台画面做一次正向拍摄，而不是拍聊天/网页/桌面。
4. 补录按键 KeyEvent 映射，确认返回键/相机键是否能正式放进 HUD。
5. 完善 APK 构建脚本，构建后自动清理 generated-src/classes/dex。
6. GitHub 授权后推送本地 Git 仓库。
