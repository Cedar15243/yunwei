# V9 正式交付包与回归验证

## 产物

- 正式 APK：`output/v9.0.0-formal-delivery/android/DingdangAI-V9-9.0.0-release.apk`
- applicationId：`com.codex.air3nativecamera.dingdangexpert.v9`
- versionCode/versionName：`900000 / 9.0.0`
- APK SHA256：`91180152BF4B293475A9C5AB8E771BA9BE26E223E936F3FEDF3F1890C419DB20`
- 交付压缩包：`output/DingdangAI-V9-9.0.0-formal-delivery.zip`
- 压缩包 SHA256：以同目录自动生成的 `DingdangAI-V9-9.0.0-formal-delivery.zip.sha256` 为唯一准据

## 本轮通过

- `npm run validate:supabase`
- `npm run validate:v9-release`
- `npm run validate:v9-delivery`：`192` 个 manifest artifacts、`193` 个 checksummed files
- `npm run validate:native-hud`
- `npm run validate:native-build`
- `npm run test:expert-collab`：服务端 `31/31`、Web `39/39`
- 管理 Web：`25` 个测试文件、`122/122`，TypeScript 检查和生产构建通过；包含任务记录、项目记忆、媒体中心、账号邀请、角色调整、身份恢复、项目授权、知识附件和独立“系统设置”入口
- 管理 Edge Function：`48/48`，覆盖记录中心、项目记忆、设备健康、Auth Admin 邀请、角色 RPC、身份恢复和审计失败回滚
- Android JVM：`93` 个报告、`548/548`，`0` 失败、`0` 错误、`0` 跳过；使用 Temurin JDK 17、上一版讯飞 AIKit 与 Sherpa 依赖，`21` 个 Gradle 任务全部实际执行
- Supabase Edge Function：`240/240`；Deno `index.ts` 类型检查通过，覆盖项目原子创建、跨组织隔离、直接内容清单授权和知识附件处理回归
- V9 网关 Python：`155/155`，`0` 失败、`0` 错误；覆盖聊天会话与任务错配拒绝、新任务不继承旧对话、项目记忆继续生效
- V9 项目恢复边界：历史项目导航只浏览记录；受管恢复必须二次确认并刷新服务端状态；异步结束回执只完成目标旧任务，不切换或结束当前新任务
- 项目身份控制面：新项目和工程师成员关系由单一事务 RPC 创建；已关闭项目、撤销成员、设备不兼容和身份映射冲突均失败关闭
- 管理 Web 真实浏览器：任务筛选、媒体切换、失败证据重试、项目记忆和任务时间线通过；`1440x900` 与 `390x844` 均无横向溢出，最终控制台 0 错误、0 警告

## 安全边界

- V9 主 AI/视觉：`qwen3-vl-plus`
- 实时 ASR：`fun-asr-realtime`
- 小叮当唤醒：上一版讯飞 AIKit
- 声纹：讯飞 `s1aa729d0`
- 交付包不含生产 `.env`、供应商长期密钥、service role、管理员 token、声纹原始音频或开发缓存。
- APK 仍保留讯飞原厂 SDK 的既有 PEM 材料，已作为供应商安全审批事项记录，未擅自修改二进制。

## 未完成的外部验收

- Supabase project ref、登录令牌、真实生产 secrets、真实资产和探针配置尚未提供。
- MVS 正式上传/附件绑定、真实 Form schema 和写回协议尚未联调。
- 当前 SSH 22 端口连接超时，无法从本工作区执行服务器部署；未修改现有专家站点。
- Air3 `YM00FCF3NW0031` 已并存安装 V9；冷启动 `301 ms`，设备 APK 哈希与正式 APK 一致，Camera2 三轮打开/释放、0 Crash/ANR、0 异常录音、0 OpenClaw，稳态 61 帧 0% jank；因设备未激活且受管服务不可用，真实双语音、本人/旁人声纹、弱网、耗电和温升仍待验收。

结论：本地代码、自动化、正式构建和交付文件已收口；线上发布和市场验收继续保持失败关闭，不能把本地通过误报为已上市。
