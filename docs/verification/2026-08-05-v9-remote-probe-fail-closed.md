# V9 远程探针失败关闭验证

## 目标

- 删除 Supabase 运维链路中默认 `mock` 探针和自动创建演示资产的生产风险。
- 新任务只允许绑定数据库中已登记的真实资产。
- 未配置真实探针、误配探针模式或使用历史演示资产时明确拒绝，不产生成功复测记录。
- 保持 V8 兼容模型路径、V9 固定模型合同、眼镜 UI、Camera2 和专家协同不变。

## 实现

- `REMOTE_PROBE_MODE` 只接受 `disabled`、`tcp`、`http`，缺省为 `disabled`，旧 `mock` 值返回可诊断的配置错误。
- `http` 模式必须提供不含账号密码的 HTTPS `REMOTE_PROBE_URL`。
- 新任务必须通过 `DEFAULT_ASSET_TAG` 查找 `ops_assets` 中的真实资产；缺少、找不到或使用历史演示标签时返回可恢复的 503。
- 探测目标和端口从任务会话绑定的资产元数据读取，不接受客户端临时目标。
- 自动迁移不再插入 `ASSET-CONSOLE-001`；清理迁移只删除未被历史任务引用的旧演示记录。
- 生产就绪审计要求真实资产标签、`tcp|http` 探针模式，以及 `http` 模式下的合规 HTTPS 地址。

## TDD 证据

- RED：新增 `mock` 配置错误映射和历史演示标签变体测试后，`2` 项按预期失败。
- GREEN：补齐策略后，`remote-probe-policy.test.ts` 为 `3/3` 通过。

## 验证

```text
deno test --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/remote-probe-policy.test.ts
3 passed, 0 failed

deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts
passed

npm run validate:supabase
passed

npm test -- --run  (ops-management-web)
20 test files, 87 tests passed

npm run typecheck  (ops-management-web)
passed

npm run build  (ops-management-web)
passed

npm run validate:v9-release
passed
```

线上就绪审计实测：未配置真实资产和探针时 `remote-probe-production-config=missing`；使用真实格式资产标签、`http` 模式和合规 HTTPS 地址时为 `ready`，且不输出密钥值。

## 剩余阻断

- 当前没有生产 Supabase 项目 ref、登录凭据和完整生产 secrets，迁移与 Edge Function 尚未部署到生产。
- 尚未获得并登记真实资产标签和已验收的企业内网探针地址，因此生产远程复测仍必须保持不可用。
- 本阶段没有在线 Air3，未执行实机远程复测和弱网体验验证。
