# V9 设备激活配置失败关闭验证

## 目标

- 设备激活后端地址和策略版本必须由生产环境显式配置。
- 缺失或非法配置不能使用源码硬编码值冒充已配置状态。
- 配置错误必须返回明确的设备激活阶段、网络状态和恢复动作。
- 不改变正式云地址、设备激活协议、V8 稳定包、眼镜 UI 或供应商密钥边界。

## 实现

- 删除 `V9_DEVICE_ACTIVATION_BACKEND_BASE_URL` 和 `V9_DEVICE_ACTIVATION_POLICY_VERSION` 的运行时硬编码缺省值。
- `createDeviceActivationGateway` 继续强制 HTTPS 后端和有效策略版本。
- 新增配置错误识别，缺失或非法配置统一返回 HTTP `503`：
  - `stage=device_activation`
  - `networkStatus=not_configured`
  - `recoverableAction=configure_device_activation_backend`
- 管理端运行状态现在能在环境未配置时正确显示设备激活后端不可用，不再因源码缺省值误报已配置。
- 静态合同验证禁止重新加入硬编码生产 URL 或 `v9-production-1` 兜底。

## TDD 证据

- RED：新增配置错误识别测试时，类型检查因缺少导出函数按预期失败。
- GREEN：实现后设备激活测试 `7/7` 通过。

## 验证

```text
deno test --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/device-activation.test.ts
7 passed, 0 failed

node scripts/validate-device-activation.mjs
passed

deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
passed

npm run validate:supabase
passed

deno test --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses
170 passed, 0 failed
```

## 剩余阻断

- 生产 Supabase 尚未完成项目关联、登录、secrets 设置、迁移和 Edge Function 部署。
- 正式部署时必须明确设置当前生产 V9 网关 HTTPS 地址和策略版本；本地验证不会代替生产部署证据。
- 当前无在线 Air3，尚未执行真实激活码兑换、重启恢复、撤销和弱网实机验收。
