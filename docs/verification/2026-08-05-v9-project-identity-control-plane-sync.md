# V9 项目身份与控制面同步验证

日期：2026-08-05

## 实现结论

- Supabase 是项目 UUID、项目状态和成员授权的权威来源；网关保留低延迟本地运行时与不可变项目映射。
- 网关设备事件先写本地 SQLite 与 outbox，再由后台同步到 Supabase；同步不增加主 AI 的网络往返，AI 仍只执行一次模型供应商调用。
- 同一组织、人员、设备和本地项目只能绑定一个权威项目 UUID；正向或反向篡改均返回 `project_identity_conflict`。
- 已关闭或归档项目不会被设备事件重新激活；成员撤销、设备绑定不兼容、清单过期或项目身份冲突均失败关闭。
- 新项目只允许由 `task_started` 在组织级设备绑定下创建；`register_device_project_from_task_start` 在同一数据库事务内创建项目、工程师成员关系和审计事件。
- 普通对话、照片、视频等非启动事件不得创建项目；跨组织同名 `localProjectId` 不会串到其他组织项目。
- 直接设备内容清单和网关执行清单都要求 active 项目、active 成员关系和兼容设备绑定，不能绕过授权取得 Skill 或知识。

## 自动化证据

- Supabase 聚焦项目身份/清单测试：`41/41`。
- Supabase Edge Function 全量：`217/217`；Deno `check` 通过。
- `npm run validate:supabase`：通过，包含新事务 RPC 的文件与权限标记校验。
- V9 网关 Python：`146/146`，覆盖 outbox、短期会话、幂等冲突、离线重试和项目身份冲突。
- 管理 Web：`25` 个测试文件、`116/116`；TypeScript 检查和生产构建通过。
- Android JVM：`93` 个报告、`545/545`，0 失败、0 错误、0 跳过；`--rerun-tasks` 的 21 个 Gradle 任务全部实际执行。
- 专家协同：服务端 `31/31`，Web `39/39`。
- Native Build/HUD、AI brain、V8 隔离、V9 release/delivery 与 `git diff --check` 均通过。

## 正式产物与 Air3

- APK：`output/v9.0.0-formal-delivery/android/DingdangAI-V9-9.0.0-release.apk`
- APK SHA256：`91180152BF4B293475A9C5AB8E771BA9BE26E223E936F3FEDF3F1890C419DB20`
- ZIP SHA256：`C3C5A45D5B8C900673531CE68B5FF3097D832A18FAB44F17023830903D2B3445`
- 交付验证：`183` 个 manifest artifacts、`184` 个 checksummed files。
- Air3 `YM00FCF3NW0031 / IMA301` 已通过 `adb install -r` 更新独立 V9；安装前后叮当相关包集合摘要均为 `75FD9689F098575FB53B66AA8ACEB738FD6C68B6EB56471A0177D6EEB0198DF8`，V8 未删除或覆盖。
- 冷启动 `301 ms`；设备 APK 与本地 APK 哈希一致；Crash/ANR、OpenClaw 和异常录音活动均为 0。
- 原首页与设置页视觉保持既有风格；未激活时明确显示“设备待激活”“服务不可用”，没有假联网或自动切换语音模式。
- Camera2 连续三轮均为 `open -> closed`，最后无活动相机客户端，进程持续存活。
- 重置 `gfxinfo` 后首页静置 15 秒：61 帧、0% jank、P50 11ms、P90 12ms，0 missed vsync、0 高输入延迟、0 slow UI/draw；`TOTAL PSS=131134 KB`。

## 失败尝试与边界

- 首次 Android 新鲜回归因 PowerShell 未完整引用 Gradle `-PpreviewApplicationId`，参数被误解析为任务名，未进入编译；修正参数引用后 21 个任务全部执行并通过。
- 首次相机状态提取正则未匹配 `dumpsys media.camera` 的多行结构，但相机已真实打开；改用客户端包名和设备 open/closed 状态后完成三轮验证。
- 生产 Supabase 凭据和部署权限未注入，新迁移尚未在线执行；本地代码和迁移门禁通过不等同于生产 RLS/事务 smoke。
- Air3 尚未激活且受管服务不可用，真实双语音、本人/旁人声纹、弱网恢复、功耗和温升仍未放行。
- MVS 上传、附件、完整动态表单和写回 DTO 未提供；写操作继续拒绝。

结论：项目身份与控制面同步已完成本地实现、全量回归、正式构建和 Air3 失败关闭验收；生产部署与市场 GA 仍受上述外部条件阻断。
