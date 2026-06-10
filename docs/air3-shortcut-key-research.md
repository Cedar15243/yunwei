# Air3 快捷键可控性结论

## 结论

`叮当运维AI` 应优先绑定 App 内能稳定消费的键：

- `ENTER(66)` / `DPAD_CENTER(23)`：语音开始/结束。
- `FOCUS(80)` / `F9(139)`：进入 App 内相机页或在相机页拍照。
- `RIGHT(22)` / `MENU(82)` / `F12(142)`：发送入口。
- `BACK(4)` / `LEFT(21)` / `F10(140)`：返回聊天页，不在聊天页直接退出 App。
- `VOLUME_UP(24)` / `VOLUME_DOWN(25)`：App 内消费，避免系统音量浮层干扰。

`CAMERA(27)` 和 `DVR(173)` 当前不能作为普通第三方 APK 的稳定快捷键承诺。Air3 实机证据显示 App 能观察到部分事件日志，但系统随后把前台切到 `com.inmo.camera_extreme/.ui.CameraActivity`。若产品必须把实体侧边相机键改成打开 `叮当运维AI`，需要 INMO 厂商设置、厂商 SDK、MDM/系统签名权限或系统 key policy 支持。

## 官方资料依据

- Android `KeyEvent.KEYCODE_CAMERA` 的定义是 Camera key，用于启动相机应用或拍照；`KEYCODE_FOCUS` 是 Camera Focus key，用于相机对焦。参考 Android/AOSP `KeyEvent`：
  https://developer.android.com/reference/android/view/KeyEvent
  https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/KeyEvent.java
- AOSP 输入链路说明：Linux input event 会经 Android `EventHub` / `InputReader` 转换成 Android input event，再由 `InputDispatcher` 分发给窗口；不同设备的映射可受 key map、input device config、系统配置和设备驱动影响。参考：
  https://source.android.com/docs/core/interaction/input
- AOSP key layout 文件说明：`.kl` 文件负责把 Linux key codes / HID usages 映射到 Android key codes，并可带 policy flags；内置设备上的特殊键通常需要设备级 key layout。参考：
  https://source.android.com/docs/core/interaction/input/key-layout-files

这些资料与当前 Air3 现象一致：普通 APK 可以处理派发到自己窗口的 `KeyEvent`，但不能保证接管系统策略层已经保留或重定向的相机键。

## 当前实机证据

- 设备：`YM00FCF3NW0031 / IMA301`。
- App：`com.codex.air3nativecamera.dingdangops`，版本 `601 / 6.0.1-chat`。
- UI 启动证据：
  - `tmp/dingdang-ops-ai-601-ui.xml`
  - `tmp/dingdang-ops-ai-601-ui.png`
- 侧键/快捷键复测证据：
  - `tmp/keytest-final/summary.json`
  - `tmp/sidekey-current-activity/summary.json`
  - `tmp/sidekey-live-20260609/summary.json`
  - `tmp/sidekey-answer-20260609/summary.json`
  - `tmp/sidekey-regression-20260610/summary.json`
  - `tmp/sidekey-live-20260610-now/summary.json`
  - `tmp/sidekey-live-20260610-corrected/summary.json`
  - `tmp/sidekey-shortcut-regression-20260610/summary.json`
- 系统输入与设置证据：
  - `tmp/sidekey-answer-20260609/gpio-keys.kl.txt`
  - `tmp/sidekey-answer-20260609/dumpsys-input.txt`
  - `tmp/sidekey-answer-20260609/settings-global-key-lines.txt`
  - `tmp/sidekey-answer-20260609/settings-secure-key-lines.txt`
  - `tmp/sidekey-answer-20260609/settings-system-key-lines.txt`

## 产品实现建议

1. 默认不要让用户依赖 `CAMERA(27)` / `DVR(173)`；这两个键只能记录日志并给出产品风险提示。
2. 把 `ENTER/DPAD_CENTER` 做成核心语音开关，符合眼镜上“少操作”的目标。
3. 把 `FOCUS/F9` 做成 App 内相机入口，避免和系统相机键争抢。
4. 在聊天页消费 `BACK/LEFT/F10`，防止现场误按后直接退出。
5. 保留 `DingdangKey` 日志，用于后续用户人工按实体侧键时继续确认最终硬件映射。

## 回归命令

每次改动叮当运维AI APK 的按键、语音或相机入口后，运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\test-dingdang-air3-shortcuts.ps1 -WaitSeconds 30
```

脚本会验证当前已安装的 `com.codex.air3nativecamera.dingdangops`，并保存每个按键的前台窗口、UI XML、`DingdangKey` 日志与 `summary.json`。当前固定矩阵包含 `F9(139)`，并显式确认 `F11(141)` 未绑定。

## 编号修正

2026-06-10 复测确认 Android `KEYCODE_F9` 对应 `139`。`141` 在本机日志中为 `KEYCODE_F11`，当前未绑定为 App 内相机快捷键。

## 2026-06-10 官方资料复核与本轮结论

- Android 官方 `KeyEvent` 文档定义 `KEYCODE_CAMERA` 为 Camera key，常量值 `27`，用途是启动相机应用或拍照；`KEYCODE_FOCUS` 为 Camera Focus key，常量值 `80`，用途是相机对焦。参考：https://developer.android.com/reference/android/view/KeyEvent
- AOSP 输入链路说明：Linux evdev 事件会经过 `EventHub`、`InputReader`，按 input device config、keyboard layout files 和映射表转换为 Android input events，再由 `InputDispatcher` 分发到对应窗口。参考：https://source.android.com/docs/core/interaction/input
- AOSP key layout 文档说明：`.kl` 文件负责把 Linux key code / axis code 映射到 Android key code / axis code，并可指定 policy flags；内置输入设备的特殊按键需要设备级 key layout。参考：https://source.android.com/docs/core/interaction/input/key-layout-files
- INMO Air3 官方页面说明 Air3 基于 Android 14，并提供 SDK/开发文档给第三方开发者适配轻量应用。参考：https://www.inmoxr.com/pages/inmo-air3
- 因此，本项目继续采用 App 内可控键方案：`ENTER/DPAD_CENTER` 做语音开关，`FOCUS/F9(139)` 做 App 内相机入口，`RIGHT/MENU/F12` 做发送入口，`BACK/LEFT/F10` 做返回聊天，`VOLUME_UP/DOWN` 在 App 内消费。
- 本轮用户请求复测证据为 `tmp/sidekey-user-request-20260610/summary.json`，结果 `allExpectedOk=true`、`finalInDingdang=true`。`CAMERA(27)` 与 `DVR(173)` 仍最终切到 `com.inmo.camera_extreme/.ui.CameraActivity`，继续判定为系统保留相机键；普通第三方 APK 不能承诺稳定改绑。

## 2026-06-10 实体侧键人工采集入口

如果需要确认“人手实际按眼镜边上的那颗键”最终映射到哪个 Android KeyEvent，运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\capture-air3-physical-sidekey.ps1 -DurationSeconds 20 -OutDir tmp\physical-sidekey-manual
```

采集窗口开始后，现场人员按一次或多次实体侧键。脚本会保存：

- `getevent-lt.txt`：Linux input 层事件。
- `logcat.txt`：`DingdangKey` App 日志。
- `focus-before.txt` / `focus-after.txt`：前后台 Activity。
- `settings-*-key-lines.txt`：系统侧键相关设置线索。
- `summary.json`：解析到的 `keyCode` / `keyName`、是否仍在叮当运维AI、是否切到 INMO 系统相机。

该脚本不修改系统设置；只做采集。若只是验证脚本链路、当前无人按键，可加 `-AllowNoKeyEvent`。
