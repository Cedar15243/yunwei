# V9.0.0 正式交付与 Air3 回归验证（2026-08-06）

## 交付身份

- `applicationId`: `com.codex.air3nativecamera.dingdangexpert.v9`
- `versionCode/versionName`: `900000 / 9.0.0`
- 正式 APK SHA-256：`8EDDC9C860D3B67CE403649C057DDE2301FEEB6F5222A6148C432BDA9C3494E1`
- secure runtime：启用；主 AI、ASR、声纹供应商长期密钥均未写入 APK。
- 模型合同：`qwen3-vl-plus`、`fun-asr-realtime`、上一版讯飞 AIKit、讯飞 `s1aa729d0`。

## 本轮代码收口

- 工作流终态只允许服务端 execution 通过签名 `complete` 节点收口，设备不能直接提交 assignment `completed`；完成/失败同步维护任务、工单和审计事件。
- Android 同步队列损坏时保留 `.corrupt-*` 旁证文件并暴露 `persistenceError()`，不再静默丢弃未上传事件。
- Air3 待命 HUD 停止 250 ms 根节点 CSS 变量重绘；只有监听和分析状态保留低频反馈，保持原有颜色、比例和布局。

## 自动化验证

- Android JVM：`95` 个报告、`561/561`，0 失败、0 错误、0 跳过；Temurin JDK 17、上一版讯飞 AIKit 与 Sherpa 依赖可用。
- Supabase Edge Function：`240/240`；`deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts` 通过。
- V9 网关 Python：`165/165`。
- 管理 Web：`25` 个测试文件、`122/122`；TypeScript 检查与生产构建通过。
- 专家协同：服务端 `31/31`、Web `39/39`。
- Native build/HUD、AI brain、V8 隔离、Supabase 合同门禁均通过。

## 安全物料与依赖审计

- 交付包包含 CycloneDX 1.5 SBOM：`309` 个组件，覆盖根工具链、管理 Web、Python 网关、Android Maven/供应商 SDK、Sherpa 运行资产和 Deno 锁定来源。
- npm 根工具链、管理 Web 运行时和 Python 网关依赖审计均为 0 个已知漏洞；报告记录了 `package-lock.json`、管理 Web lockfile 和 `requirements.txt` 的 SHA-256。
- 交付门禁强制校验 SBOM 版本、组件唯一引用、关键供应商/运行时组件和依赖审计 `clean` 状态；审计失败不会生成正式 ZIP。
- `docs/COMPLETION_MATRIX.md` 随包交付，按功能逐条标注证据范围和外部放行条件。

## Air3 `YM00FCF3NW0031` 实机

- 正式 APK 并存安装成功；安装前后叮当相关包集合哈希均为 `B4C9B01187BC231B4E03D5CD99834888B8FB73BC9D93B1A94EEDE64E9413D288`，V8 未删除或覆盖。
- 最终冷启动 `303 ms`；设备 APK 与本地正式 APK 哈希一致；Crash、ANR、OpenClaw 日志均为 0。
- Camera2 三轮 `open -> released -> returnedHome` 通过；每轮返回首页后确认无活动客户端，最终 `Active Camera Clients=[]`、`Device 0 is closed`，音频服务中无 V9 录音引用。
- 待命页性能连续三轮 15 秒：`0` 额外绘制帧、`0%` jank；最终相机回归后 PSS `118965 KB`。修复前同窗口为 `60/61` 帧、`68.9%–78.3%` jank，根因是待命页根节点 CSS 变量周期性重绘。
- 首页截图保持原确认版 HUD，设备未激活时明确显示“设备待激活”，服务不可用不自动伪造成功。

## 未完成的外部放行条件

- 生产 Supabase 凭据、部署权限、真实管理账号和设备绑定尚未注入。
- MVS 正式上传、附件绑定、完整动态表单 schema、操作字典和写回 DTO 尚未提供；相关写操作继续失败关闭。
- 设备未激活且受管服务不可用，真实双语音、本人/旁人声纹、弱网、功耗和温升验收仍待真实环境。
- 讯飞 AIKit AAR 的供应商 PEM 用途和安全审批待完成。

## 构建提示

- Android Gradle 构建仍输出 API 34/D8 与 Kotlin 元数据兼容性提示，但正式构建、签名、APK 内容扫描和安装验证均成功；该项作为下一轮工具链升级风险记录，不改变当前失败关闭边界。

## 继续回归（2026-08-06）

- Air3 `YM00FCF3NW0031`（`IMA301`）当前安装的 V9 `base.apk` 与 `output/v9.0.0-formal-delivery/android/DingdangAI-V9-9.0.0-release.apk` SHA-256 均为 `8EDDC9C860D3B67CE403649C057DDE2301FEEB6F5222A6148C432BDA9C3494E1`；V8 包仍在设备中。
- 使用应用已定义的 `KEYCODE_FOCUS` 快捷键进入相机，等待稳定预览后截图确认真实 Camera2 画面；返回后 `dumpsys media.camera` 为 `Active Camera Clients: []`、`Device 0 is closed`，事件日志含当前 V9 client 的 `DISCONNECT`，音频服务无 V9 引用。
- 待命页重新冷启动后静置 15 秒，`dumpsys gfxinfo` 为 `Total frames rendered: 0`、`Janky frames: 0 (0.00%)`；同一时刻 `dumpsys meminfo` 的 `TOTAL PSS` 为 `83686 KB`。该单次采样只作为当前回归旁证，不替代长时功耗/温升验收。
- 本轮证据目录：`output/air3-v9-continuation-20260806/`；实机未激活且受管服务不可用，双语音、声纹本人/旁人和真实 AI 请求继续保持未验收，不以本地失败关闭状态冒充线上成功。
