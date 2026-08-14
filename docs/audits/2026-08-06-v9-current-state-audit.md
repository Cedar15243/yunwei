# V9 当前状态与发布前审计

审计日期：2026-08-12

## 结论

本地工程已达到“可构建、可回归、可交付”的正式候选版本；生产上线和市场 GA 仍受真实凭据、MVS 协议、设备激活及供应商审批约束。任何未具备真实外部条件的能力继续失败关闭，不使用 mock 或静态成功文案冒充完成。

## 当前证据

| 范围 | 当前证据 |
| --- | --- |
| 模型合同 | 主 AI/视觉 `qwen3-vl-plus`；实时 ASR `fun-asr-realtime`；上一版讯飞 AIKit 唤醒；讯飞 `s1aa729d0` 声纹；V9 构建拒绝非白名单模型。 |
| Android 正式包 | `com.codex.air3nativecamera.dingdangexpert.v9`，`900000 / 9.0.0`，AGP `8.2.2` + Gradle `8.2` + JDK 17，正式 v2 签名，APK SHA-256 `02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`，长期供应商密钥未落包。 |
| 工作流终态 | 服务端 execution、签名 `complete` 节点、assignment/maintenance task/work order 同步和失败收口均由迁移与 Edge 测试覆盖；设备不能直接伪造完成。 |
| Android 可靠性 | 同步队列损坏保留旁证文件并暴露持久化错误；照片/视频证据继续支持可恢复上传和显式取消。 |
| Air3 实机 | `adb install -r -g` 成功，设备 APK 与本地 APK 哈希一致；`follow.preview` 并存包数量保持不变，V8 稳定包未被覆盖。巡检详情四类任务与两级返回链通过，专家协同二次确认/取消/进入等待页/挂断返回原入口通过，最终相机关闭且 Crash buffer 为空。 |
| Air3 性能 | 当前正式耐久 `1203 s / 41 samples`，Camera2 `3/3`，PSS `98827 -> 98827 KB`、增量 `0 KB`、jank `3.03%`，无音频/相机泄漏、Crash 或 ANR；USB 供电使真实功耗与续航继续待外部验收。 |
| 自动化回归 | Android JVM 正式构建基线 `563/563`；本轮网关 `172/172`；管理 Edge `55/55`、设备记忆 `3/3`；Supabase 合同回归通过；管理 Web `133/133`；专家协同服务端 `31/31`、Web `39/39`。 |
| Web 交付 | 管理 Web TypeScript 与生产构建通过；真实浏览器回归继续覆盖记录中心、媒体、项目记忆、任务时间线和窄屏布局。 |
| 管理 Web 云端合同 | 2026-08-12 在现有云服务器使用与正式源码一致的临时归档完成 Docker 冷构建；容器无主机端口、根文件系统只读、`no-new-privileges`、最小 capabilities，`/health` 返回 `dingdang-ops-management-web`。验证前后专家协同、Caddy 容器指纹和 Caddyfile SHA-256 完全一致，临时归档、容器和镜像均已清理，`8788` 未监听。该证据只证明隔离运行合同，不代表管理站已生产激活。 |
| 安全供应链 | 正式包包含 CycloneDX 1.5 SBOM（309 个组件）；npm 根工具链、管理 Web 运行时、Python 网关依赖审计均为 0 个已知漏洞，清单和审计文件由交付门禁强制校验。 |
| SRE 与恢复控制 | 网关提供不访问外部供应商的 `/health` 存活与 `/ready` 本地 SQLite quick-check；双库和 `evidence/` 媒体备份带 manifest、SHA-256 和 quick-check，部署含每日 timer、恢复演练脚本和 Prometheus 告警规则；本地控制通过，生产演练待验收。 |
| 仓库安全扫描 | V9 相关网关/Web/Edge/Android 主源码的项目级规则扫描通过；2026-08-12 已对授权生产目标 `https://bb.chinacedar.top:2305` 执行 OWASP ZAP 2.17.0 被动 DAST r4，`/v9-ops` 范围内 High/Medium/Low 均为 0，仅保留对 `Cache-Control: no-store` 的信息级复核；第三方渗透、独立安全审批和供应商 SDK 审批仍未完成。 |

## 外部阻断

1. Supabase 项目链接元数据存在，但 CLI 远端请求仍返回 `Unauthorized`；生产公开 anon/publishable key、secrets、真实账号/设备绑定和部署权限尚未注入。管理站候选独立域名仍无可用 A 记录，因此不能执行 `activate.sh`。
2. MVS 正式 HTTPS、上传/附件绑定、完整 Form schema、操作字典和写回 DTO 尚未提供；工单写操作继续拒绝。
3. Air3 当前未激活且受管服务不可用，真实双语音、声纹本人/旁人、弱网、功耗和温升仍未完成市场放行验收。
4. 讯飞 AIKit 原厂 AAR 的正式用途/安全审批待完成。
5. 生产恢复和监控通知仍待真实演练；开发方被动 DAST r4 已纳入正式交付包，但第三方渗透、独立安全审批和外部门禁仍为 `pending`，不能把 DAST 结果或 `OPERATIONAL_READINESS` 的本地控制解释为 GA 通过。

## 发布判定

- 本地正式交付：通过。
- 生产部署：待外部凭据与权限。
- 市场 GA：暂不通过，必须完成上述真实外部验收。
