# V9 MVS 当前节点表单草稿验证

## 本轮范围

- V9 模型合同保持不变：主 AI/视觉 `qwen3-vl-plus`，实时 ASR `fun-asr-realtime`，小叮当继续上一版讯飞 AIKit，声纹录入与 1:1 使用讯飞 `s1aa729d0`。
- 新增 MVS 当前节点动态表单的受控解析和本机草稿，不修改稳定 V8、首页 HUD、专家协同、Camera2 或 AI 对话主链路。
- MVS 写回字段、可执行操作和真实流程推进尚未完成联调，因此本轮禁止提交表单、推进流程或显示假成功。

## 已实现

1. `MvsWorkOrderNodeForm` 只解析白名单字段：文本、数值、单选、多选、日期、照片、签名和定位。
2. 解析结果绑定 `orderId + formId + nodeCode + schemaFingerprint`；未知控件、重复字段、非法选项和不受支持的类型不会执行。
3. `MvsWorkOrderFormDraftStore` 使用应用私有目录和原子文件保存草稿；重启后只恢复同一工单和同一 schema 指纹的数据。
4. 数值范围、选项集合、日期格式、照片草稿 ID、签名引用和坐标范围均在落盘前校验；单份表单值限制为 16 KiB。
5. 当前节点表单沿用既有工单 HUD；文本、数值、日期、单选和多选可编辑并保存本机草稿。
6. 照片字段可选择当前工单已有照片草稿，也可调用 Camera2 新拍照片，保存后自动绑定到当前表单字段。
7. 签名、定位和未知控件当前明确提示“需手机/PC完成”；没有执行任意 HTML、脚本或未知插件。
8. 表单页明确显示“仅本机草稿、尚未提交 MVS”，且没有表单提交或流程推进动作。

## 验证结果

- Android JVM：`535/535`，失败 `0`，错误 `0`，跳过 `0`。
- V9 Python 网关：`126/126`。
- `npm run validate:native-hud`：通过。
- `npm run validate:native-build`：通过。
- `npm run validate:v9-release`：通过。
- `git diff --check`：通过，仅有工作树既有 LF/CRLF 提示。
- 完整 JVM 回归最初使用 Unity OpenJDK 时因缺少 `Ed25519` 出现 10 项既有签名测试环境失败；切换到本地忽略目录的 JDK 17 后，`535/535` 全部通过。

## 审计 APK

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.mvs.formdraft.audit`
- 版本：`9036 / 9.0.36-mvs-form-draft-audit`
- 文件：`output/v9.0.36-mvs-form-draft-audit/dingdang-v9.0.36-mvs-form-draft-audit.apk`
- SHA-256：`59A80E994F25597D6B818FAD551DC72F689F11945DA920B378D6F99797242EDB`
- 签名：APK Signature Scheme v2，有效；Android Debug 证书，不是正式市场签名。
- APK 字符串扫描：`fun-asr-realtime=1`，仅为眼镜到我方网关的受控协议常量；`OpenClaw`、GPT-5、DeepSeek、Claude、Qwen Max 均为 `0`。
- 主 AI 和讯飞声纹服务 ID 留在服务器运行时合同中，不写入 APK，因此 APK 内 `qwen3-vl-plus=0`、`s1aa729d0=0` 是预期安全结果。

## 未完成与停止条件

- 当前 ADB 列表无在线设备，V9.0.36 未安装到 Air3，不能声明表单编辑、照片绑定、返回链、权限拒绝、耗电、温升或 V8 并存实机通过。
- MVS 尚未提供真实 `Form` 完整 schema、节点必填规则、操作字典、上传完成/附件绑定协议和最小权限联调环境；真实返回与当前适配层不一致时必须明确失败并新增适配，不得猜测字段。
- 表单提交与流程推进只有在服务端 `task/operations` 授权、写回 DTO、幂等语义和真实 MVS 回执全部验证后才可启用，且仍必须二次确认。
