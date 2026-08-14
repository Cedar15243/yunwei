# V9 功能闭环审计

审计日期：2026-08-12

审计范围：V9 眼镜端、V9 网关、Supabase Edge Function、管理 Web、可配置工作流、Skill/知识控制面、声纹和 MVS 维修工单适配层。现有稳定 APK、远程专家 Web、专家协同主链路不在 V9 改造范围内。

## 已闭环能力

| 领域 | 当前状态 | 关键证据 |
|---|---|---|
| 模型合同 | 已锁定。主 AI/视觉使用上一版 `qwen3-vl-plus`；实时 ASR 使用上一版 `fun-asr-realtime`；小叮当唤醒使用上一版讯飞 AIKit；声纹录入与 1:1 验证只使用新讯飞 `s1aa729d0`。非白名单模型失败关闭。 | `supabase/functions/ops-glasses/model-contract.ts`、`v9-ops-gateway/gateway.py`、`v9-ops-gateway/voiceprint_proxy.py`、V9 构建验证 |
| 身份与设备 | 已实现组织范围身份、设备短期会话、设备激活、设备绑定/解绑/撤销、一次性凭据、账号邀请/角色 RPC、登录身份恢复、项目范围授权和审计；设备目录一次批量聚合设备凭据、短期会话、Skill/知识清单版本和同步健康，不做逐设备查询。 | Supabase identity/device migrations、账号生命周期迁移、Android `DeviceSessionManager`、管理 Web Devices 页面 |
| 设备记忆 | 已切换为服务端授权设备目录。眼镜端只通过短期设备会话读取当前设备有权访问且已绑定项目的真实设备、品牌、型号、数量、状态、故障/维修计数和项目历史；空目录明确显示“没有授权设备”，服务异常或 DTO 不合法失败关闭。管理 Web 的“设备记忆”页读取同一 `ops_equipment`/项目绑定数据，不再使用本地示例。 | Edge `GET /device-sync/device-memory`、管理 `GET /management/equipment`、Android `DeviceMemoryDeviceClient`/`ExecutionContextHudPresenter`、管理 Web Devices 页面 |
| 项目与任务 | 已实现项目/任务隔离、结束摘要、项目记忆、项目指令版本、显式受管恢复和服务端 `ExecutionContext`。历史导航只浏览、不自动恢复；异步结束回执只收口目标旧任务；聊天会话 ID 必须与任务 ID 一致。 | Android `TaskSessionManager`、`MainActivityProjectBindingTest`、`ExecutionContextDeviceClient`、网关执行上下文测试 |
| 双语音 | 小叮当唤醒与声纹监听互斥；统一音频协调器处理声纹、相机、录像和专家通话抢麦；失败不偷偷切回另一模式。 | Android `AudioCaptureCoordinator`、`VoiceModeDoubleClickPolicy`、声纹 JVM 测试 |
| 声纹生命周期 | 已实现授权说明、三段录入、重录、删除、服务端代理、1:1 验证、三次失败锁定、重放保护和撤销审计。 | 网关 `voiceprint_proxy.py`/`voiceprint_lifecycle.py`、管理 Web Voiceprints 页面 |
| Skill 与知识 | 已实现草稿、审核、发布、授权、撤销、回滚、设备 manifest 同步和 AI 调用上下文注入；附件使用私有 Storage、版本历史和短期签名下载，TXT/Markdown 在 Edge 解析，PDF/DOCX 通过独立凭据调用私有服务端真实解析，失败状态可审计和显式重试；未授权或后端不可用时拒绝执行。 | Supabase Skill/knowledge control-plane、`knowledge-document-parser-client.ts`、网关 `knowledge_document_parser.py`、Android manifest storage、管理 Web 页面 |
| 可配置工作流 | 已实现 DSL 校验、签名发布、工单绑定、自动推送、设备白名单执行、步骤/照片/视频证据回执、可恢复上传与显式取消、离线队列和撤销传播。 | Supabase workflow management、Android `WorkflowExecutionCoordinator`、管理 Web Workflows 页面 |
| MVS 工单一期 | 已实现工程师自身任务列表、详情、SOP、当前节点动态表单解析、签到/签退、流程记录和照片证据草稿；正式写回协议缺失时明确拒绝。 | `MvsWorkOrderDeviceClient`、MVS 网关测试和 Android 工单测试 |
| 云端记录中心 | 任务记录支持组织/项目范围搜索、状态与项目筛选、稳定分页和受控 CSV 导出；媒体中心使用独立分页接口、仅返回短期签名 URL，展示精确失败原因并仅允许管理员重试失败证据，已移除逐任务详情扇出。 | `GET /management/tasks`、`GET /management/tasks/export`、`GET /management/media`、Supabase 真实查询链测试、管理 Web 交互测试 |
| 管理 Web | 已实现设备、人员状态、账号邀请、角色调整、登录身份恢复、项目授权、绑定、声纹、Skill/知识、工作流、工单、记录中心、审计和系统诊断入口，均走真实 API，不提供本地假激活。设备记忆目录已支持管理员受控登记、编辑和项目绑定，包含固定确认词、操作原因、幂等键、乐观锁和失败保留表单；项目与任务新增“任务记录/项目记忆”工作区：仅展示云端已同步且人工确认的项目结束记忆，并可进入对应任务时间线；媒体中心将取消上传显示为独立终态且不提供失败重试。 | 25 个测试文件，133/133 通过；TypeScript、生产构建和 Edge API 回归通过 |
| 安全供应链 | 正式交付包增加 CycloneDX 1.5 SBOM 和 npm/Python 运行时依赖审计，审计结果与 lockfile 哈希一并进入交付清单；隐私门禁拒绝开发账号、本机路径、完整设备序列号、现场网络标识，并阻止网关 `test_*.py` 测试夹具进入交付物。 | `scripts/v9-sbom.mjs`、`scripts/v9-security-audit.mjs`、`validate-v9-formal-delivery.mjs` |
| 外部验收证据门禁 | 已实现两级机器门禁：生产部署 3 项、市场 GA 9 项。每个 gate 必须提交结构化 attestation 并完整通过权威必检项；证据绑定当前 APK/ZIP 并校验路径、字节数和 SHA-256。源码仓库和正式交付包提供 pending 工作区初始化器、带确认词/manifest 乐观锁/路径限制/原子写入/签名保护的单项登记器，以及不接触私钥的离线/HSM/KMS Ed25519 签名附加器。审批使用固定哈希的公钥信任库，生产需要发布负责人签名，市场 GA 需要不同密钥的独立安全审批签名；缺失保持 pending，非法证据失败关闭。 | `scripts/init-v9-external-acceptance.mjs`、`scripts/record-v9-external-attestation.mjs`、`scripts/attach-v9-external-approval-signature.mjs`、`scripts/v9-external-acceptance.mjs`、`currentV9Gate.externalAcceptance` |

## 当前真实缺口与放行条件

| 优先级 | 缺口 | 处理原则 |
|---|---|---|
| P0 外部 | Supabase 生产 project ref、登录令牌、真实 secrets、云服务器部署权限未提供。 | 保持部署脚本和健康检查可用，但不宣称已上线；未配置时失败关闭。 |
| P0 外部 | Air3 已取得 V8/V9 并存、Camera2 打开/释放、前后台、稳态性能和 Crash/ANR 基线；设备仍未激活且受管服务不可用，真实双语音、本人/旁人声纹、弱网上传取消/恢复、功耗和温升尚不能放行。 | 生产服务、真实账号和设备激活到位后补齐联网实机矩阵，完成前不申请市场 GA。 |
| P0 外部 | MVS 正式 HTTPS、上传/附件绑定协议、完整 Form schema 和写回 DTO 未齐。 | 只读 DTO、动态表单草稿和本机证据保留；禁止虚构巡检或写回成功。 |
| P1 外部 | 讯飞 AIKit AAR 正式用途/安全审批及声纹噪声、重放门槛数据未完成。 | 继续使用上一版 AIKit；在供应商审批和实测前不宣称量产合规。 |
| P1 外部 | 账号邀请已接入 Supabase Auth Admin，角色变更已接入受控 RPC；真实邀请邮件、首次登录和多角色线上权限仍需生产 Auth/IdP 验收。 | 不在客户端保存或生成密码；Auth Admin 不可用时邀请明确失败，不创建本地假账号。 |

## 验证基线

- V9、Supabase、Android 契约验证通过。
- V9 网关 Python：165/165 通过。
- 管理 Edge Function：管理路由 `55/55`、设备记忆路由 `3/3` 通过，含设备目录登记/编辑的管理员确认、原因、幂等、项目绑定、乐观锁和稳定错误映射；此前全量基线继续保留。
- 管理 Web：25 个测试文件、133/133 通过。
- 管理 Web 真实浏览器：设备目录创建→刷新→手机编辑→更新闭环通过；`1440x900` 与 `390x844` 无横向溢出，窄屏壳层改为既有单列导航，设备记忆收口为“设备 + 关联项目”两列，编辑按钮真实命中，创建/更新命令和 `expectedUpdatedAt` 正确，控制台 0 错误、0 警告。
- Supabase Edge Function：240/240 通过；其中 6 项独立 Mock Storage 集成测试覆盖首次会话、跨请求断点恢复、缺片拒绝、清理失败重试、幂等取消和部分持久化失败自愈；Deno 类型检查通过。
- Android JVM：本轮完整 `:app:testDebugUnitTest --rerun-tasks` 为 `96` 个报告、`563/563`，0 失败、0 错误、0 跳过；设备记忆定向客户端、路由策略和 HUD 测试通过（以 Temurin JDK 17 和受控 AIKit/Sherpa 测试环境为准）。
- 正式 APK SHA-256 为 `02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`；当前正式交付包包含 `232` 个 manifest artifacts、`233` 个 checksummed files，最终 ZIP SHA-256 以输出目录同名 `.sha256` sidecar 为唯一准据。本轮 Air3 正式耐久证据为 `1203 秒`、`41/41` 个样本、Camera2 `3/3`、PSS 增量 `0 KB`、jank `3.03%`、无音频/相机泄漏、无 Crash/ANR、V8 未变化；原始证据不直接进入交付包，包内仅保留脱敏且 release-bound 的摘要。四份正式交付文档现在必须引用当前 APK 哈希，残留旧发布哈希会由 `test:v9-delivery-document-binding` 与交付校验器拒绝。`verification/air3/`、交付清单、固定模型合同、包内自校验器和真实 ZIP 解压逐文件校验继续有效；不可解压但 sidecar 匹配的伪归档由回归测试明确拒绝。

## 审计结论

### 证据分片续传增量审计

- 照片/视频证据已在 Python 网关、Android 客户端和 Supabase Edge Function 统一接入 `session`、`chunk`、`complete` 协议。
- 上传会话持久化 `uploadId`、分片大小/数量、已接收分片和最终 SHA-256；断网恢复时只补传缺失分片，不重复上传整个视频。
- 完成阶段校验分片顺序、分片摘要、总字节数、媒体格式和总摘要，通过后原子完成；重复完成请求保持幂等，并清理临时分片。
- 旧的一次性 `/device-sync/workflows/evidence` 继续兼容；升级后的 Android 协调器优先使用可续传 Transport。
- Python 网关、Supabase Edge 和 Android 已统一增加 `/device-sync/workflows/evidence/cancel`：取消会清理临时分片并保持幂等，取消后旧分片和完成请求失败关闭，已完成证据禁止取消删除。
- Android 退出工作流会向当前上传发出取消信号；取消标记不会在上传线程启动时被错误复位，覆盖“退出与上传启动同时发生”的竞态窗口。
- Edge 对媒体资产或上传会话任一取消标记均失败关闭；若取消只部分持久化，下次 session 会同时修复媒体状态和上传会话，重新建立干净上传。
- Edge 首次上传会话已修复为原子持久化媒体占位与上传会话；会话响应明确返回 `receivedChunks` 和 `nextChunkIndex`。
- 临时分片清理失败时保留分片元数据并返回可恢复错误；再次完成请求会清理残片后幂等返回，不产生无人可追踪的 Storage 对象。
- 本轮完整回归：Python 网关 `155/155`、Supabase Edge 全量 `240/240`、管理 Web `122/122`、Android JVM `548/548`，Android 主代码编译通过。

### 设备健康控制面增量审计

- `GET /management/devices` 在基础设备查询后按设备集合批量读取 `glasses_device_tokens`、`glasses_device_sessions` 和 `device_content_manifests`，固定为四次查询，不产生逐设备 N+1。
- 返回 DTO 包含凭据状态与有效期、短期会话状态与最后使用时间、Skill/知识清单版本与有效期，以及统一 `sync_health`/`sync_issue`。
- 同步健康按优先级判定：设备撤销、MDM 不合规或凭据无效为阻断；离线、从未上线或超过 20 分钟未同步为离线；MDM 未确认、短期会话无效或清单异常为需处理；其余为正常。
- 管理 Web 保持现有表格式视觉和操作列不变，补充清单版本、同步原因与凭据状态；桌面和窄屏列宽、隐藏逻辑由静态契约保护。
- 本轮当前源码验证：Supabase Edge `240/240`、管理路由 `48/48`、管理 Web `122/122`、Deno 类型检查、Web TypeScript/生产构建和 `npm run validate:supabase` 均通过。
- 2026-08-08 安全增量：`pypdf` 从 `6.14.2` 升级到 `6.15.0`，部署合同、PDF 解析测试、SBOM 合同和全量网关 `165/165` 通过；pip-audit、npm 根工具链和管理 Web 运行时均为 0 已知漏洞。

### 设备记忆授权目录增量审计（2026-08-07）

- 新增 `ops_equipment` 与 `ops_equipment_projects` 表及组织级读取策略；设备端 Edge 仅接受短期设备会话，并通过项目范围再次过滤绑定关系。
- Android `DeviceMemoryDeviceClient` 严格校验 schema、时间戳、标识符、状态、计数和项目历史；网络失败、会话缺失、绑定缺失或 DTO 异常均显示明确错误，不调用 `DeviceMemoryCatalog.defaultCatalog()`。
- 设备记忆沿用现有受管 HUD 的分页、返回和重试动作；相机、语音、任务和专家协同未改变。
- 管理 Web 新增“设备记忆”视图和真实 `GET /management/equipment`，只允许组织管理员读取，按项目授权范围过滤并批量关联任务计数。
- 2026-08-08 增量证据：Android 设备记忆客户端/HUD 定向回归 21 个 Gradle 任务实际执行并通过；Edge 管理/设备记忆 52/52；管理 Web 25 个文件 127/127、typecheck、生产构建和设备页面红绿测试通过。Playwright 使用本机 Edge 验证 `1440x900`/`390x844`，三标签互不重叠、无横向溢出、控制台无应用错误或警告。

### 设备目录管理与窄屏命中区增量审计（2026-08-08）

- 管理 Edge 新增真实 `POST /management/equipment` 与 `PUT /management/equipment/:id`：仅组织管理员可用，固定 `CREATE_EQUIPMENT`/`UPDATE_EQUIPMENT` 确认词，必须提供操作原因、幂等键和项目绑定；更新必须携带 `expectedUpdatedAt`，服务端通过 `manage_ops_equipment` 审计事务 RPC 原子替换项目关联并回读授权 DTO。
- 失败关闭映射覆盖设备编号冲突、幂等冲突、版本冲突、项目不存在、权限不足、载荷非法和服务端不可用；表单失败时保留用户输入，不显示本地假成功。眼镜端仍只读，不把管理写操作下发到设备。
- 管理 Web 在原有 Product Design 表格式风格上增加“登记设备”入口和行内编辑图标；`720px` 以下仅对该页面折叠应用壳层为单列、导航横向滚动，桌面布局不变；设备身份单元固定为图标/内容/操作三列，避免项目文本覆盖编辑命中区。
- 新鲜证据：管理 Edge `55/55`、设备记忆 `3/3`、Supabase 全门禁通过；管理 Web `25` 个测试文件 `133/133`、TypeScript、生产构建通过；Playwright 本机 Edge `1440x900`/`390x844` 创建/编辑真实点击通过，创建命令为 `CREATE_EQUIPMENT`、更新命令为 `UPDATE_EQUIPMENT` 并携带版本值，横向溢出为 `false`、控制台错误/警告为 `0/0`；Android 设备记忆只读定向 JVM `5/5`，Air3 `YM00FCF3NW0031` 保持 V9 `900000 / 9.0.0`。

### 云端项目记录与项目记忆增量审计

- `GET /management/projects/:projectId/record` 只在组织/项目权限范围内聚合 `ops_projects`、`maintenance_tasks` 和 `task_events`，固定批量查询，不按任务逐条扇出。
- 项目记忆只接受服务端校验通过的 `humanConfirmed=true`、`phase=COMPLETED`、真实任务状态一致、合法 `projectMemoryRevision`、摘要/确认事实/排除事实/风险数组；未确认、未结束或伪造状态不会进入管理 Web。
- 管理 Web 在既有“项目与任务”导航内新增“项目记忆”标签，展示项目目录、结束摘要、事实、风险、历史 Skill 版本和项目任务；“查看任务”进入既有任务时间线，不添加无后端依据的网页“继续项目”假操作。
- 真实浏览器交互证据：登录后进入“项目与任务”→“项目记忆”→打开项目记录→查看任务时间线，桌面 `1440x900` 与窄屏 `390x844` 均渲染成功；窄屏文档宽度与视口一致、横向溢出为 `false`，控制台错误/警告为 `0/0`。
- 本轮修复了 Skill 标题被 `space-between` 推到右侧、项目状态标签窄屏拆行，以及任务时间线沿用 `1080px` body 最小宽度的问题；响应式规则只作用于项目/任务工作区，不改变其他页面的桌面布局。

### 项目恢复与任务会话隔离增量审计

- V9 本地项目侧栏和上一个/下一个项目只用于浏览历史记录，不再隐式切换活跃任务；受管恢复只能从项目记录入口发起，经过显式二次确认后重新读取服务端项目、任务和授权状态。
- 任务结束请求使用提交时的目标任务 ID。即使用户在网络回执到达前已经创建新任务，成功回执也只将旧任务标记完成，不暂停、完成或切换当前新任务。
- 网关在读取历史消息和调用主模型前校验聊天会话 ID：显式 ID 必须等于 `localTaskId`，或使用 `_` 由网关解析为任务 ID；不一致时返回 `409 task_session_mismatch`。
- 回归证明关闭旧任务后，新任务的 `recent_messages` 为空；同一项目内人工确认的持久记忆仍进入新任务执行上下文，实现“任务对话隔离、项目经验保留”。
- 独立证据记录见 `docs/verification/2026-08-05-v9-project-memory-task-session-isolation.md`。

V9 已达到“本地正式候选版”的功能闭环和失败关闭要求，模型合同已固定为上一版模型加新讯飞声纹。Air3 已完成包隔离、Camera2、返回链、待命性能和 Crash/ANR 基线；生产部署、Air3 真实双语音/声纹/弱网/功耗温升验收、MVS 正式写回和供应商安全审批仍是独立放行门槛。在这些条件满足前，不把本地测试或静态展示当作市场 GA 证据。
