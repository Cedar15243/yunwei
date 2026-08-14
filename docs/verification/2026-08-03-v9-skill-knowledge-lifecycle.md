# V9 Skill/知识生命周期本地验证

## 固定模型合同

- 主 AI/视觉固定为上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 固定为上一版 `fun-asr-realtime`，Sherpa 只作断网或云端失败回退。
- 声纹只使用刚申请的讯飞新版 `s1aa729d0`。
- V9 不使用 OpenClaw 或其他新增模型；安全 APK 不保存供应商长期密钥。

## 本轮完成

- 网关新增短期设备会话保护的 `GET /device-sync/content-manifest`，按项目返回最小 Skill/知识元数据，支持强 ETag、`If-None-Match`、`304`、版本和有效期响应头。
- Android 新增项目级原子清单缓存。缓存只用于快速只读展示，只有本轮 `200/304` 网络确认后才允许进入真实 Skill 启停；后端不可用、项目不匹配、缓存过期或授权失败均明确拒绝。
- 新清单完整替换旧清单，撤销的 Skill/知识不会继续残留。知识页无活跃任务时返回 `active_task_required`，不显示本地假目录。
- 客户端严格校验字段白名单、项目、版本、时间、SHA-256、响应大小和 ETag。审查中新增失败用例并修复 `304` 响应 ETag 与缓存不一致仍被确认的问题。
- 眼镜仍使用既有首页、九宫格、能力详情和 HUD 布局；清单同步只在 Skill/知识页面打开或刷新时发生，不进入每轮 AI、声纹、相机或录像请求。

## 新鲜验证证据

- Android JVM：`447/447`，失败 `0`，错误 `0`，跳过 `0`。
- V9 网关：`86/86`。
- Edge Function：`142/142`；`deno check` 通过。
- 管理 Web：`64/64`；TypeScript、生产构建通过。
- `npm run validate:supabase`、`npm run validate:native-hud`、`npm run validate:native-build` 通过。
- V9 网关 Python `py_compile` 通过；常见源码秘密模式未发现命中。

## 未完成边界

- Supabase 迁移、Edge Function、管理 Web 和 Supabase 到 V9 网关的清单同步运输尚未生产部署。
- 尚未使用真实生产账号在 Air3 完成 Skill 发布、授权、启停、撤销、回滚和知识引用端到端验收。
- 本人三段实时声纹录入、独立第四段 `1:1`、旁人/噪声/反重放和长时性能仍未完成，不能宣称声纹闭环或市场发布完成。
