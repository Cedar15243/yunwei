# V9.0.34 MVS 工单照片草稿验证记录

日期：2026-08-04

## 范围

- 维修工单详情增加现场照片草稿计数和 Camera2 拍摄入口。
- 照片只保存到应用私有目录 `mvs-work-order-evidence/{orderId}/`。
- 草稿索引保存工单号、相对路径、字节数、SHA-256、捕获时间和 `draft` 状态。
- MVS 上传/附件绑定协议缺失，界面明确显示“仅本机保存、尚未上传 MVS”。
- 返回、返回首页、权限拒绝、暂停和销毁均清理捕获状态。
- 打开工单时在后台清理文件缺失或大小不匹配的失效草稿索引。

## 模型合同

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 小叮当：上一版讯飞 AIKit。
- 声纹录入与 `1:1`：讯飞 `s1aa729d0`。
- V9 禁止 OpenClaw 和其他新增模型。

## 自动化结果

- Android JVM：`530/530`。
- V9 Python 网关：`126/126`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:v9-release`：通过。
- `npm run validate:supabase`：通过。

## 审计包

- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9.mvs.evidence.audit`
- versionCode：`9034`
- versionName：`9.0.34-mvs-evidence-draft-audit`
- APK：`output/v9.0.34-mvs-evidence-draft-audit/dingdang-v9.0.34-mvs-evidence-draft-audit.apk`
- SHA256：`5A928ADA2A739D62975E798E29C84EF9E9B287C2875764B07357B92901642E2A`
- 签名：Android Debug，v2 有效；不是正式市场签名。
- APK 扫描：主模型、声纹服务 ID、OpenClaw 和禁用模型均为 `0` 命中；`fun-asr-realtime` 为 1 个受控协议常量。

## 未完成

- ADB 无在线 Air3，本包未安装，未执行本轮 Camera2、UI、重启恢复、V8 并存或 crash/ANR 实机验收。
- 草稿单张查看、删除二次确认和正式 MVS 上传仍待后续阶段。
- 正式 Release 签名和真实 MVS 联调条件未具备。
