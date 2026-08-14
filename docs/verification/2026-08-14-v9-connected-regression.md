# V9 Air3 连接态回归

日期：2026-08-14

## 范围

- 设备：Air3 `IMA301`
- 连接：无线 ADB `192.168.30.244:5555`
- 已安装包：`com.codex.air3nativecamera.dingdangexpert.v9`
- 版本：`9.0.0` / `900000`

## 结果

- 冷启动后 V9 `MainActivity` 成为前台活动。
- 首页正确显示“Air3 已连接”和“设备待激活”。
- 设置页正确显示设备需要激活、当前语音模式服务不可用、受管声纹录入 `0/3`。
- 未激活状态没有启用 AI、语音或声纹的本地假成功路径。
- 本轮未安装、未覆盖 V8 稳定包，也未修改 V9 已安装 APK。

## 证据

- `output/air3-v9-connected-regression-20260814/home.png`
- `output/air3-v9-connected-regression-20260814/home.xml`
- `output/air3-v9-connected-regression-20260814/settings.png`

## 限制

- 设备尚未完成服务端激活，真人小叮当唤醒、声纹录入/1:1 验证和真实云端任务执行不能以本地状态替代。
- USB ADB 在一次日志抓取期间断开；无线 ADB 保持在线并完成本记录中的冷启动和页面回归。
