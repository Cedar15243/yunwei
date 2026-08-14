# V9.0.35 MVS 工单照片草稿管理验证记录

日期：2026-08-04

## 功能闭环

- 工单详情显示本机照片草稿数量，并提供“查看草稿”和“拍摄工单现场照片”两个独立动作。
- Camera2 照片保存到应用私有目录，草稿索引记录工单号、相对路径、大小、SHA-256、时间和 `draft` 状态。
- 草稿列表只展示本机文件名、大小和“未上传 MVS”状态，不下发任意 URL。
- 删除草稿必须显式二次确认；删除只影响本机私有文件和索引，不调用 MVS。
- 返回、返回首页、权限拒绝、暂停和销毁均清理捕获状态。
- 打开列表和工单详情时在后台清理文件缺失或大小不匹配的失效索引。

## 模型合同

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 小叮当：上一版讯飞 AIKit。
- 声纹录入与 `1:1`：讯飞 `s1aa729d0`。
- V9 禁止 OpenClaw 和其他新增模型。

## 自动化结果

- Android JVM：`532/532`。
- V9 Python 网关：`126/126`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:v9-release`：通过。
- `npm run validate:supabase`：通过。

## 审计包

- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9.mvs.evidence.management.audit`
- versionCode：`9035`
- versionName：`9.0.35-mvs-evidence-management-audit`
- APK：`output/v9.0.35-mvs-evidence-management-audit/dingdang-v9.0.35-mvs-evidence-management-audit.apk`
- SHA256：`9C0F51FECE19B5476185F1FDEBEBA9F679E5C1D2AAA336D0509FB621CE1CD992`
- 签名：Android Debug，v2 有效；不是正式市场签名。
- APK 扫描：主模型、声纹服务 ID、OpenClaw 和禁用模型均为 `0` 命中；`fun-asr-realtime` 为 1 个受控协议常量。

## 未完成

- ADB 无在线 Air3，本包未安装，未执行本轮 Camera2、草稿管理、V8 并存或 crash/ANR 实机验收。
- MVS 上传完成和附件绑定协议尚未提供，不能实现或宣称照片已写入 MVS。
- 正式 Release 签名和真实 MVS 联调条件未具备。
