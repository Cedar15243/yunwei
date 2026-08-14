# V9.0.0 正式候选包与 Air3 实机验证

验证日期：2026-08-09

云端验证更新：2026-08-12

## 产物身份

- `applicationId=com.codex.air3nativecamera.dingdangexpert.v9`
- `versionCode=900000`
- `versionName=9.0.0`
- 正式 APK：`output/v9.0.0-formal-release/DingdangAI-V9-9.0.0-release.apk`
- APK SHA-256：`02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`
- 模型合同：`qwen3-vl-plus`、`fun-asr-realtime`、上一版讯飞 AIKit、小叮当声纹服务 `s1aa729d0`；`secureRuntime=true`、`directGptEnabled=false`。

## 自动化验证

- Android JVM 使用 Temurin JDK 17、AIKit、Sherpa 和安全运行时配置完整执行 `:app:testDebugUnitTest --rerun-tasks`：21 个 Gradle 任务成功，96 个报告、563/563，0 失败、0 错误、0 跳过。
- `AIAbilityConfig` 已将能力详情明确标记为 `Route.OPERATION_DETAIL`；主代码中 `Route.PLACEHOLDER` 搜索结果为 0，路由语义测试进入上述 `563/563` 全量回归。
- 管理 Web：25 个测试文件、133/133；TypeScript 和 Vite 生产构建通过。
- V9 网关 Python：165/165；Supabase Edge：250/250；管理 Edge：55/55，设备记忆：3/3。
- 专家协同服务端：31/31；Web：39/39。
- Native HUD/Build、AI brain、V8 隔离、Supabase、SBOM、依赖审计和运行就绪门禁均使用当前源码执行。
- Air3 耐久工具链通过三层门禁：runner 静态契约、`summary.json` 正/负向行为测试和正式交付脱敏白名单契约；正式摘要由独立校验器再次验证。
- 正式交付隐私门禁拒绝开发者账号标识、本机用户目录、临时目录、完整设备序列号、现场内网地址和 Wi-Fi 名称；网关目录只交付运行模块，不携带仓库内 `test_*.py` 测试夹具，设备示例统一使用部署占位符。

## 2026-08-12 管理 Web 云端隔离合同

- 目标服务器现有专家协同运行于回环 `8787`，V9 网关运行于回环 `8790`；管理站预留独立 `8788`，验证期间未监听也未写入 Caddy。
- 使用 SHA-256 为 `37B5457A287F567D7D1298DD357FCD735762A3EE1A1DD4FDE31346A601CA43D3` 的临时源码归档执行真实 Docker 构建，构建参数仅使用 `.invalid` 合同值，不含生产 Supabase 或供应商密钥。
- 首次冷构建因运行时 `nginx:1.27-alpine` 基础镜像缺失且 Docker Hub registry/auth 瞬时连接超时，在 15 分钟上限终止；trap 成功删除临时目录、容器、镜像和上传归档，专家/Caddy/V9 健康及保护指纹均未变化。该尝试不计为通过。
- 同一哈希资产在基础镜像链路恢复后重新执行并通过：前端 `tsc -b && vite build` 完成，临时 Nginx 容器使用只读根文件系统、`no-new-privileges`、`cap-drop ALL` 和最小补充 capabilities，无主机端口映射，`/health` 返回 `dingdang-ops-management-web`。
- 验证后再次确认临时文件、容器和 image 均不存在；专家容器、Caddy 容器及 Caddyfile SHA-256 与验证前一致，公网 `/health`、`/v9-ops/health`、`/v9-ops/ready` 均正常，`8788` 仍未监听。
- 本轮只证明管理 Web 镜像和隔离运行合同可在目标云主机成立。Supabase CLI 仍返回 `Unauthorized`，候选独立管理域名没有可用 A 记录，所以没有生成生产 env、release/candidate/current 指针、Caddy 管理站块或正式容器。

## Air3 `YM00...0031`

- 设备型号：`IMA301`。
- `adb install -r -g` 只更新独立 V9 包并返回成功；`follow.preview` 并存包数量安装前后均为 `24`，旧稳定包未被卸载。安装前集合哈希因旧脚本调用当前 PowerShell 不支持的 `SHA256.HashData` 未取得，本记录不据此声称集合哈希前后一致；安装后集合哈希为 `DD629C76CD51CDCCB7BFC07BCEB2485FD897F1CD09E5304AC305A338D90F7148`。
- 安装后设备 V9 APK 与本地正式 APK SHA-256 均为 `02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5`，设备版本仍为 `900000 / 9.0.0`。
- 冷启动为 `272 ms`，首页保持既有 HUD，未激活时明确显示“设备待激活”，没有伪造 AI 可用状态。
- 点击“AI 能力中心”再进入“巡检任务”，真实显示“实训室设备巡检”“水电暖系统巡检”“空调系统巡检”“消防系统巡检”，未出现“该能力没有可展示的本地内容”；返回链“巡检详情 -> AI 能力中心 -> 首页”通过。
- 权威相机状态为当前 `Active Camera Clients=[]`；历史 `Device closed` 事件不再作为空闲判定条件。Crash buffer 为空。
- 实机证据目录：`output/air3-v9-operation-detail-20260813-current/`，包含 `inspection.png`、`home.png` 与 UI XML；原始 logcat 不进入正式交付包。
- 正式交付 ZIP 将脱敏后的两张截图、四份页面 UI 树和 release-bound `verification/air3/manifest.json` 一并交付；原始 `logcat.txt` 含设备环境信息，不进入正式包。
- 包内 `security/verify-v9-delivery.ps1` 已用有效夹具、普通哈希篡改和重新计算全套哈希后的耐久语义篡改夹具验证；它会校验耐久摘要的 release 绑定、时长、Camera2、热传感器、PSS、jank、供电声明、泄漏、Crash/ANR、V8 和隐私边界。正式交付门禁会实际运行包内脚本，并另外真实解压 ZIP、逐文件比较暂存目录与归档内容。

## 2026-08-12 Air3 耐久

- 正式耐久请求 `1200 s`、实际观测 `1203 s`，按 30 秒绝对调度取得 `41/41` 次采样；Camera2 三轮打开、拍摄、返回和释放全部通过。
- 电池温度峰值 `29.0°C`，Android thermal status 最大 `0`，CPU/GPU/skin 峰值分别为 `50.9°C / 49.2°C / 48.622°C`。
- PSS `98827 KB -> 98827 KB`，增量 `0 KB`；最终 jank `3.03%`。
- Camera2 和音频均无泄漏，Crash buffer 空、无 ANR，最终仍在前台，受保护 V8 存在且版本未变化。
- 设备全程 `USB powered=true`，摘要明确记录 `powerMeasurementValid=false`；该结果不能作为电池续航或真实离线功耗证据。
- 原始证据位于 `output/air3-v9-hardware-soak-20260813-final-r3/`；该目录名来自本地运行标识，摘要权威时间为 `2026-08-12T19:14:16Z`。正式交付仅包含 release-bound 脱敏摘要和 `AIR3_HARDWARE_SOAK.md`，不包含完整序列号、绝对路径、快照或 logcat。

## 外部边界

本次验证证明当前正式候选包可构建、可安装、与既有 `follow.preview` 包并存，并完成能力详情路由、巡检目录、返回链、Camera2 三轮释放、未激活状态 20 分钟耐久和管理 Web 云端隔离容器合同。Air3 尚未激活且受管服务不可用，真实双语音、本人/旁人声纹、弱网恢复、拔除 USB 后的续航/功耗、长时佩戴温升和真实 AI 请求仍需生产服务与设备授权到位后验收；管理站生产激活仍待 Supabase 登录/公开配置和独立域名 DNS；MVS 写回继续受正式协议阻断。
