# V9 ExecutionContext Runtime 验证

## 范围

- 服务端受管组织、用户、设备、项目和任务身份。
- 不可变 Skill 版本、授权、任务快照、项目记忆和项目指令。
- AI 结构化上下文、执行审计、设备 Skill/项目 API、幂等和二次确认。
- SQLite 发布前一致性备份、失败恢复和上下文性能门槛。
- 严格版本已独立部署并通过 V9.0.14 实机验证；未修改当前稳定 APK、HUD 或专家协同链路。

## 模型边界

- 主 AI/视觉：`qwen3-vl-plus`。
- 实时 ASR：`fun-asr-realtime`。
- 声纹：讯飞新版 `s1aa729d0`。
- 源码扫描未发现 V9 运行链路中的 OpenClaw 或其他新增模型路由。

## 自动化证据

- 独立网关全量：严格部署前 `77/77` 通过。
- Python：`python -m compileall -q v9-ops-gateway` 通过。
- 部署脚本：Git for Windows Bash 对 `install.sh`、`rollback.sh` 的 `-n` 语法检查通过。
- TDD 真实红灯包括：缺少受管身份字段、设备 API 404、同设备跨项目 Skill 激活串线、20 条历史消息超过 12 条上下文上限、安装脚本缺少模块/数据库备份。
- Android：`418/418` 通过，`npm run validate:native-hud` 与 `npm run validate:native-build` 通过；V9.0.14 v2 签名有效，安全生成配置不包含设备 bootstrap、主 AI/ASR/讯飞长期密钥或 OpenClaw。

## 性能证据

- 测试数据：1000 条历史消息、100 条已确认事实、50 条生效项目指令。
- 最近会话固定截取 12 条，正文总量继续受 `ExecutionContext` 上限约束。
- 20 轮本地端到端上下文解析与假 Provider 流式完成：p95 `6.794ms`。
- Provider 调用数 `20`，与 AI 请求轮数一致；上下文解析没有新增模型网络往返。
- 结论：服务端解析不会成为当前 AI 首 token 的主要延迟来源。

## 云端只读复核

- `dingdang-v9-gateway.service`：`active`。
- 当前 release：`/opt/dingdang-v9-gateway/releases/20260803T0200Z-execution-context`。
- `/etc/dingdang-v9-gateway.env`、`/etc/dingdang-v9-voiceprint.env`：`0600 root:root`。
- 线上模型仍为 `qwen3-vl-plus`、`fun-asr-realtime`，声纹 URL 为讯飞 `s1aa729d0`。
- V9 健康与现有专家健康均返回 HTTP 200。
- 服务端受管组织/用户身份已由 root-only 环境提供，具体值不进入文档、APK、Git 或日志。

## Air3 真实 AI 验证

- 包名/版本：`com.codex.air3nativecamera.dingdangexpert.v9.executioncontext.fix.audit`，`9014 / 9.0.14-execution-context-fix-audit`。
- APK SHA256：`C2CC61B7B3B7E243BD66930AF772BE5BE9698F2A0C7631C5B69804EF4CCAFD66`。
- 安全运行时只发送无控制字符的原始用户问题，Skill、项目记忆和最近上下文由服务端解析，修复了 V9.0.13 的 `user_text_invalid`。
- 真实 Camera2 照片首轮 AI：首个 delta 约 `1237ms`，完整回复约 `3s`；服务端 trace `6c76737b-8c68-41f1-9075-35617974f93c` 状态为 `success`，模型为 `qwen3-vl-plus`，项目/任务 ID 与眼镜一致。
- 返回首页后 `active_task_id` 清空，任务变为 `PAUSED`，项目、照片和 AI 回复保留；无 crash/ANR。

## 剩余门槛

1. 接通眼镜端 Skill 启停、项目记录、任务结束摘要和显式历史恢复；已完成的任务必须保持不可复用。
2. 完成管理 Web 的 Skill、知识库、项目记忆、声纹和审计闭环，并执行真实角色/权限负向验收。
3. 由本人完成声纹三段录入、独立 `1:1` 验证、旁人/噪声/重放/锁定、双击模式切换和温升耗电验收。
4. 修复 AI Markdown 符号在 HUD 中按原文显示的问题，并回归长文分页、维修步骤分页和底部 HUD 遮挡。
