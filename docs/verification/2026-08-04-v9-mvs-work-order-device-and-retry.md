# V9.0.33 MVS 工单与模型合同验证记录

日期：2026-08-04

## 模型合同

- 主 AI/视觉：上一版 `qwen3-vl-plus`。
- 实时 ASR：上一版 `fun-asr-realtime`。
- 小叮当唤醒：上一版讯飞 AIKit。
- 声纹录入与 `1:1` 校验：新申请的讯飞 `s1aa729d0`。
- V9 `secureRuntime` 禁止 OpenClaw、GPT-5.x、DeepSeek、Claude、Qwen Max 和其他新增模型；历史 V8/直连脚本不进入 V9 运行链路。

## MVS 工单一期

- Android 已接入受管工单列表、详情、当前节点表单、流程/执行/签到记录和现有 HUD。
- 签到/签退要求方向专属二次确认；定位只在确认后按需申请，并要求两分钟内且精度不差于 500 米。
- 网关使用 HTTPS、短期设备会话、工程师/项目绑定、DTO 白名单、幂等键、追踪号、持久化 outbox 和不可变审计。
- outbox 支持指数退避、进程重启恢复、最多 5 次重试和 `mvs_retry_exhausted` 终态；重试保持原 `idempotencyKey` 与 `traceId`。
- 非法签到方向只返回 `mvs_checkin_direction_invalid`，不会触发 MVS 写操作。
- MVS 不可用时明确失败，不显示假工单；已签名受管工作流仍可进入。AI 只能提供建议或草稿，不能自行写入 MVS。

## 自动化证据

- Android JVM：`525/525`。
- V9 Python 网关：`125/125`。
- MVS 聚焦测试：`9/9`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:v9-release`：通过。
- `node scripts/validate-supabase-assets.mjs`：通过。
- `git diff --check`：通过。
- 发布包：`output/v9.0.33-mvs-work-order-audit/dingdang-v9.0.33-mvs-work-order-audit.apk`。
- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.mvs.workorder.audit`。
- 版本：`9033 / 9.0.33-mvs-work-order-audit`。
- SHA256：`FB7EFA81277A8D267FA3C9BAE5562644FBE09AF1CCF27298C23D010E0E957FEF`。

## 未完成

- 2026-08-04 ADB 无在线 Air3，V9.0.33 尚未安装；首页/HUD、工单入口、定位权限时机、非法方向拒绝、V8 并存和 crash/ANR 的本轮实机回归未完成。
- 真实 MVS HTTPS 测试环境、最小权限工程师凭据、状态字典和对象权限尚未联调。
- Android 工单证据草稿上传、真人声纹录入/验证、生产 Supabase 和正式发布签名仍是发布阻断。
