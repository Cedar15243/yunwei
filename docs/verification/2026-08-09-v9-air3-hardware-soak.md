# V9 Air3 硬件耐久验证

验证日期：2026-08-12

## 验证范围

- 正式 V9：`com.codex.air3nativecamera.dingdangexpert.v9 / 900000 / 9.0.0`。
- Air3 型号：`IMA301`；仓库原始证据保留完整设备标识，正式交付仅保留掩码 `YM00...0031`。
- 场景：未激活状态下三轮 Camera2 打开、拍摄、返回和释放，随后完成 20 分钟待命采样。
- 本次没有调用生产 AI、ASR、讯飞声纹、Supabase 或 MVS，不把本地失败关闭状态解释为在线业务验收。

## 自动化门禁

- `npm run test:v9-air3-soak-contract`
- `npm run test:v9-air3-soak-summary`
- `npm run test:v9-air3-soak-delivery-contract`
- `npm run test:v9-delivery-self-verifier`
- `npm run validate:v9-air3-soak-summary`
- runner：`scripts/run-v9-air3-hardware-soak.ps1`

摘要校验器会拒绝时长不足、Camera2 少于三轮、相机或音频泄漏、Crash、ANR、热状态超标、PSS 增量超标、jank 超标、V8 变化，以及外接供电时错误宣称功耗测量有效。

正式 ZIP 内的 `security/verify-v9-delivery.ps1` 同样执行上述核心语义校验，并额外验证摘要与 APK release manifest 的绑定、采集时间、设备标识掩码和原始快照/logcat 排除。即使攻击者重新计算 `SHA256SUMS.txt` 与 `DELIVERY_MANIFEST.json`，语义失真的耐久摘要仍会被拒绝。

## 2026-08-12 正式结果

| 指标 | 实测 |
| --- | --- |
| 请求/观测时长 | `1200 / 1203 s` |
| 快照 | `41` 次；热传感器解析 `41` 次 |
| Camera2 | `3/3` 打开、拍摄、返回、释放通过 |
| 电池温度峰值 | `29.0°C` |
| Android thermal status | 最大 `0` |
| CPU/GPU/skin 峰值 | `50.9°C / 49.2°C / 48.622°C` |
| PSS | `98827 KB -> 98827 KB`，增量 `0 KB` |
| jank | `3.03%` |
| 资源与异常 | Camera2 无泄漏、音频无泄漏、Crash buffer 空、无 ANR |
| 前台与并存 | 最终仍在前台；受保护 V8 存在且版本未变化 |

权威相机空闲判定只接受当前 `Active Camera Clients: []`，不再使用历史 `Device closed` 事件作为宽松条件。

## 功耗边界

本次 Air3 全程 `USB powered=true`，电量 `100% -> 100%`。摘要明确记录 `externalPowerConnected=true`、`powerMeasurementValid=false`，因此本次可以评价温度、资源释放、稳定性和帧表现，不能作为电池续航或真实离线功耗结论。

## 证据位置

- 短回归：`output/air3-v9-hardware-soak-smoke3/`
- 正式耐久：`output/air3-v9-hardware-soak-20260813-final-r3/`；目录名是本地运行标识，摘要权威时间为 2026-08-12。
- 正式交付只纳入 `verification/air3-soak/summary.json` 和本报告，不纳入原始序列号、绝对路径、`snapshots.json` 或 logcat。

## 未放行项

- 设备激活后的真实小叮当与声纹监听双模式。
- 讯飞声纹本人/旁人、噪声误受误拒与延迟。
- 弱网恢复、真实云端 AI/ASR、专家通话抢麦。
- 拔除 USB 后的电池续航、真实功耗和长时佩戴温升。

以上项目继续保留为外部验收门禁，不能由本次未激活耐久替代。
