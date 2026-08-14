# V9 运行时模型合同验证

## 固定合同

- 主 AI/视觉：上一稳定版 `qwen3-vl-plus`
- 实时 ASR：上一稳定版 `fun-asr-realtime`
- 小叮当唤醒：上一版讯飞 AIKit
- 声纹录入与 1:1：新申请的讯飞 `s1aa729d0`

## 本轮实现

- Supabase Edge Function 新增服务端 `model-contract.ts`，启动时校验主模型、ASR 模型和 DashScope Fun-ASR 模型。
- 环境变量配置为其他模型时，Edge Function 返回 HTTP `503`、`stage=configuration` 和 `configure_previous_stable_models`，不继续调用错误模型。
- 声纹服务 ID 作为受控合同常量保留在服务端，眼镜 APK 不保存供应商长期密钥或声纹服务凭据。

## 验证证据（2026-08-05）

- `deno test --allow-env --allow-net --no-check supabase/functions/ops-glasses`：`176 passed / 0 failed`
- `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`：通过
- `npm run validate:supabase`：通过
- `npm run validate:native-build`：通过
- `npm run validate:v9-release`：通过
- `npm run validate:v9-delivery`：通过，`165` 个 manifest artifacts、`166` 个校验文件
- 交付 ZIP SHA256：`FF1642A40739F483DD61277B952C0D12BA59ECED8420F9733C0D9070276FE3E3`

## 未验证边界

- 当前仍没有在线 Air3、生产 Supabase 凭据或真实 MVS 写回协议，因此以上是代码合同和本地交付验证，不等于生产部署或实机验收。
