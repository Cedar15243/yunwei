# 叮当X Air3 运维 HUD 修复稿

## 画布

- 目标设备：INMO Air3
- 画布尺寸：`1920 x 1080`
- 屏幕方向：横屏
- 页面定位：不是手机 App 页面，是眼镜内全屏 Camera2 运维 HUD
- 核心要求：现场小白只看眼镜，不输入文字，通过拍照、语音、重拍、继续完成闭环

## 页面结构

### 1. 全屏相机预览层

- 背景为实时 Camera2 预览。
- 预览必须按比例填充，不允许横向或纵向拉伸。
- 技术实现要求：
  - `TextureView` 需要 `Matrix` 变换保持比例。
  - 预览尺寸和拍照尺寸分离。
  - 预览优先匹配 `1920x1080` 或最接近 16:9 的 SurfaceTexture 尺寸。
  - 拍照优先使用硬件支持的高分辨率 JPEG，不再强制限制到 `1920x1080`。

### 2. 顶部状态栏

位置：

- `x=32`
- `y=24`
- `w=1856`
- `h=84`

内容：

- 左侧：`叮当X  AI 运维眼镜`
- 右侧：`服务器 SSH 恢复`

视觉：

- 半透明深色底
- 绿色细描边
- 字体大而短，避免眼镜显示阅读压力

### 3. 中央取景框

比例：

- 宽度：屏幕宽度 `72%`
- 高度：屏幕高度 `50%`
- 垂直中心向下偏移约 `20px`

内容：

- 绿色矩形框
- 中心十字线
- 框下提示：`把服务器控制台文字放入绿色框内`

技术要求：

- HUD 取景框比例必须与上传裁剪比例一致。
- 当前裁剪使用 `center-guide-crop`，后续应把比例常量统一为：
  - `GUIDE_FRAME_WIDTH_RATIO = 0.72f`
  - `GUIDE_FRAME_HEIGHT_RATIO = 0.50f`
  - `GUIDE_FRAME_TOP_OFFSET_RATIO = 0.27f`

### 4. 中央主指令面板

位置：

- 居中
- 宽度约 `1100`
- 高度约 `180`

内容：

- 主标题：`对准服务器本地控制台`
- 副标题：`中心点击拍照，AI 会识别画面并给出下一步安全指令`

要求：

- 主指令只显示“下一步要做什么”，不显示系统调试信息。
- 文案必须短，适合眼镜内快速扫读。

### 5. 底部操作栏

位置：

- `x=32`
- `y=914`
- `w=1856`
- `h=138`

操作项：

| 操作 | 文案 | 状态 |
| --- | --- | --- |
| 中心点击 | 拍照 / 下一步 | 已由 INMO Touchpad 文档确认：中心点击等同点击屏幕 |
| 长按中心 | 语音确认 | APK 当前可接语音入口，但 Air3 标准 Android SpeechRecognizer 不可用，应接云端 STT |
| 返回键 | 重拍 / 退出当前步 | INMO Touchpad 文档确认 Back 短按返回；APK 需要真机 KeyEvent 验证后绑定 |
| 相机键 | 拍照 | INMO Touchpad 文档确认有 Camera Function Key；APK 需要真机 KeyEvent 验证后绑定 |

注意：

- `相机键` 在 UI 上可以标记为“待真机确认”，确认 KeyEvent 前不能写死。
- 如果真机 Android APK 无法收到相机键事件，则从正式 HUD 移除该操作项，避免误导现场人员。

### 6. 状态文案

正常状态示例：

- `状态：相机已就绪。请保持 25-35cm 距离，避免反光。`
- `已拍照，AI 正在识别服务器控制台。请保持画面稳定。`
- `AI 已返回下一步，请按屏幕提示操作。中心点击可继续拍照验证。`
- `照片不清晰，请靠近控制台屏幕并重新拍摄。`

禁止显示：

- `OK http=200`
- `serverImageBytes`
- `uploadBytes`
- `rawBytes`
- `session=...`
- `Uploading focused JPEG...`
- `Put screen text inside this frame...`
- 任何乱码或编码损坏文案

## 官方/SDK 依据

- INMO 官方 Touchpad Guide：
  - Center area movement moves the cursor.
  - Center click clicks the screen.
  - Back key short tap returns to previous layer.
  - Home key short press returns home, long press activates voice assistant.
  - Camera Function Key exists.
- 本地 INMO Air3 SDK 样例：
  - `tmp/air3-sdk-unpacked/Assets/InmoAir3SDK/Scenes/SDKSample.cs`
  - 样例使用 `inmolib_common.Input.GetKeyDown(KeyCode.Return)`、`Escape`、`F10`、`LeftArrow`、`RightArrow`、`UpArrow`、`DownArrow` 监听 Touchpad/Ring 事件。
- 结论：
  - Unity SDK 的 KeyCode 只能作为线索。
  - Native Android APK 必须通过 `dispatchKeyEvent`/`onKeyDown` 在 Air3 真机记录实际 KeyEvent 后再绑定正式操作。

## Figma 状态

- 已创建 Figma 文件：`https://www.figma.com/design/zEyO259e18lqpicT6IfNjg`
- 文件名：`叮当X Air3 运维眼镜 HUD 修复稿`
- 当前限制：Figma Starter MCP 调用次数已达上限，页面节点尚未写入。
- 后续动作：限额恢复后，按本文档创建 1920x1080 HUD 画布。

## 验收标准

- 眼镜内页面第一眼能看出这是正式运维会话，不是相机测试页。
- 现场小白能从底部操作栏知道怎么拍照、语音、重拍、继续。
- 截图中没有英文调试文本、乱码、HTTP 状态、字节数、session id。
- 取景框中的服务器控制台文字能被 AI 稳定识别。
- 预览不明显变形，电脑屏幕边缘不被横向拉伸。
