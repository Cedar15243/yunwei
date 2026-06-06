# Air3 V2 Version Log

本文档用于记录第二版 APK 的版本区分规则和 GitHub 存储检查点。APK 构建产物不提交到 Git；代码、脚本、文档、标签和提交记录才是可追溯来源。

## Versioning Rules

- `versionName` 使用人工可读版本号，例如 `2.0.8`。
- `versionCode` 使用递增整数，例如 `208` 对应 `2.0.8`。
- 正式检查点标签使用 `v<versionName>-task<taskNumber>-<purpose>`，例如 `v2.0.8-task8-versioned-build`。
- APK 输出文件必须带版本号和 Git 短 SHA：`Air3NativeCameraTest-v<versionName>-<gitSha>.apk`。
- 基础构建产物仍保留为 `Air3NativeCameraTest.apk`，仅供当前构建脚本和安装流程使用。
- `air3-native-camera-test/build/` 和 `*.apk` 必须保持 ignored，不得提交 APK、签名中间产物、generated source 或本地密钥。
- 如需临时覆盖版本号，使用环境变量 `AIR3_APK_VERSION_CODE` 和 `AIR3_APK_VERSION_NAME`，但正式检查点必须同步更新本文档和 Git tag。

## Current Checkpoint

| Field | Value |
| --- | --- |
| Checkpoint | `v2.0.8-task8-versioned-build` |
| Date | `2026-06-06` |
| Branch | `air3-v2-task1-docs` |
| APK versionCode | `208` |
| APK versionName | `2.0.8` |
| APK naming rule | `Air3NativeCameraTest-v<versionName>-<gitSha>.apk` |
| Purpose | Task 8 GitHub Storage / versioned APK build checkpoint |

## Included Local Commits

- `8f89a02 feat: route Air3 context through AI brain`
- `a11bdea feat: render structured AI brain HUD responses`
- `54133b1 test: verify Air3 V2 backend contract`
- `36788c3 chore: protect Air3 build artifacts`
- `0bcdbd3 docs: record GitHub storage checkpoint status`

## GitHub Storage Status

本地 Git 检查点已建立。远端推送依赖 GitHub CLI 或 HTTPS 认证；如果 `gh auth status` 仍显示未登录，则浏览器登录不等于 CLI 已登录，需要先完成 `gh auth login` 或提供可用的远端认证方式。
