# V9 声纹 1:1 校验时延门禁

日期：2026-08-04

## 变更

- `VoiceprintDeviceClient.verify()` 单独使用 `3s` 建连、`5s` 读取超时。
- 声纹档案读取、授权、三段录入、重录和删除继续使用 `5s` 建连、`20s` 读取超时，避免录入流程被不必要收紧。
- 超时或网络失败只返回受控失败，眼镜保持声纹模式，不切回小叮当，不转写、不执行命令。

## 验证

- TDD RED：新增断言先确认旧实现仍使用默认 `20s` 读取超时并失败。
- TDD GREEN：实现按操作覆盖的超时参数后，`VoiceprintDeviceClientTest` 通过。
- Android 全量 JVM：`:app:testDebugUnitTest --rerun-tasks` 通过，21 个 Gradle 任务实际执行。
- V9 网关：`109/109` 通过；模型合同仍为 `qwen3-vl-plus`、`fun-asr-realtime`、`s1aa729d0`。

## 未完成

- 真实讯飞网络时延、本人/旁人/噪声和 Air3 温升耗电仍需实机验收；本记录不替代真实声纹闭环。
