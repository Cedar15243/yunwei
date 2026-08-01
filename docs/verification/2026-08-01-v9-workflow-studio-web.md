# V9 Workflow Studio Web 阶段验证

日期：2026-08-01

范围：现场应用工作区的草稿保存、服务端校验、签名发布、不可变版本、工单绑定解析及桌面/窄屏登录门禁。该证据不覆盖真实云端迁移、生产密钥、在线推送、Android 运行时或 Air3 实机。

## 自动化结果

| 验证 | 结果 |
| --- | --- |
| `npm --prefix ops-management-web test -- --run` | PASS，11 个文件、54 个测试 |
| `npm --prefix ops-management-web run typecheck` | PASS |
| `npm --prefix ops-management-web run build` | PASS，Vite 生产构建完成 |
| `npm --prefix ops-management-web audit --audit-level=low` | PASS，0 漏洞 |
| `deno test --allow-env supabase/functions/ops-glasses/*.test.ts` | PASS，112/112 |
| `npm run validate:supabase` | PASS，全部合同校验通过 |

## 浏览器证据

- `1440x900`：页面 `1440x900`，登录面板约 `440x521`，页面无滚动溢出，控制台错误/警告为 0。
- `1024x768`：首次检查发现全局 `1080px` 最小宽度错误作用于登录页，产生 56px 横向溢出。
- 修复后 `1024x768`：页面 `scrollWidth=1024`、`scrollHeight=768`，登录面板约 `440x521`，无重叠、裁切和控制台错误/警告。
- 修复只解除登录页的全局最小宽度，并限制登录面板宽度；登录后的桌面工作台最小宽度和布局未改变。

## 关键合同

- 草稿保存成功前保持 dirty；保存成功后使用服务端权威草稿重建编辑基线。
- dirty 时禁止服务端校验和发布。
- 发布要求理由、最低客户端版本、固定确认词和组织范围幂等键。
- 重试复用同一发布幂等键；相同键对应不同命令时服务端拒绝。
- 签名不可用时显示真实失败，不产生已发布假状态。
- 工单解析展示 `required | optional | none`、来源、命中规则、冲突规则和分配状态。
- 已开始、已完成或已关闭工单不允许原地换版。

## 未完成边界

- 未获得真实管理账号，因此登录后的桌面/窄屏全流程浏览器 QA 尚无直接证据。
- 工作流迁移尚未在隔离 Supabase 项目真实执行；RLS、外键、触发器、并发幂等和 service role 权限仍需正负验证。
- Android `WorkflowRuntime`、推送控制通道、SkillRuntime、MVS 连接器和 Air3 端到端验收尚未完成。

阶段结论：本地实现和自动化可进入下一阶段，产品发布仍为 `NO-GO`。
