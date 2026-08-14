# V9 Android 工具链升级与 Air3 回归验证（2026-08-07）

## 变更范围

- Android Gradle Plugin 从 `7.4.2` 升级到 `8.2.2`。
- Gradle Wrapper 从 `7.6` 升级到 `8.2`。
- 删除 `android.suppressUnsupportedCompileSdk=34`，不再隐藏 API 34 兼容提示。
- 显式启用 `buildFeatures.buildConfig=true`，保持现有生成配置机制。
- 未修改 V9 `applicationId`、版本、HUD/UI、Camera2、专家协同、AI 对话、语音模型或失败关闭边界。

## 隔离探针

- 在 `tmp/android-toolchain-probe-20260807/` 隔离副本先验证升级，不直接试错主工程。
- Gradle 8.2 分发包 SHA-256：`38F66CD6EEF217B4C35855BB11EA4E9FBC53594CCCCB5FB82DFD317EF8C2C5A3`，与官方校验值一致。
- Temurin JDK 17、上一版讯飞 AIKit、Sherpa 完整源码集下执行 `testDebugUnitTest + assembleDebug --rerun-tasks`。
- 真实退出码 `0`，`39` 个任务全部执行，`95` 个测试报告、`561/561` 通过，Debug APK 生成成功。

## 主工程与正式包

- 主工程同等回归真实退出码 `0`，`39` 个任务全部执行，`95` 个 Debug 测试报告、`561/561` 通过。
- 正式 Release 使用原有受控签名材料构建，`45` 个任务执行完成，APK v2 签名有效。
- 包身份：`com.codex.air3nativecamera.dingdangexpert.v9`，`900000 / 9.0.0`。
- 正式 APK SHA-256：`01793623E4F353513B13DEC79E0F956DECDA23D2178066F6F04DF59D84DA6574`。
- 模型合同仍为 `qwen3-vl-plus`、`fun-asr-realtime`、上一版讯飞 AIKit、讯飞声纹 `s1aa729d0`；secure runtime 启用，direct GPT 关闭。
- APK 包名/版本、签名、禁用模型和长期供应商密钥扫描通过。
- `validate:native-build`、`validate:native-hud`、`validate:v9-release` 和 `git diff --check` 通过。
- 原 API 34/D8 与 Kotlin 元数据兼容提示不再出现；仅保留源码使用过时 API、部分第三方原生库不能 strip 的非阻断说明。

## Air3 实机

- 设备：`YM00FCF3NW0031 / IMA301`。
- `adb install -r` 并存更新成功；安装前后 `com.codex.air3nativecamera*` 包集合 SHA-256 均为 `7755F09B70BFFF203A40F03C8A97B2E209F390485F1654E9FFBA834A9119C384`，未卸载或覆盖 V8。
- 从设备拉回的 `base.apk` 与正式 APK SHA-256 完全一致。
- 冷启动 `272 ms`；首页保持原确认版 HUD，未激活时明确显示“设备待激活”。
- Camera2 三轮均为 `opened=true / released=true / returnedHome=true`，真实预览可见；最终 `Active Camera Clients=[]`、`Device 0 is closed`。
- 待命页连续三轮 15 秒均为 `0` 绘制帧、`0` janky frames、`0%` jank；最终 `TOTAL PSS=109983 KB`。
- V9 音频引用 `0`，Crash buffer `0`，FATAL/ANR `0`，OpenClaw 日志命中 `0`，进程持续存活。
- 证据目录：`output/air3-v9-toolchain-20260807/`。

## 外部放行边界

- 设备仍未激活且受管服务不可用，本轮不能替代真实双语音、声纹本人/旁人、弱网、长时功耗和温升验收。
- 生产 Supabase 凭据、部署权限、真实账号/设备绑定和线上 RLS smoke 尚未提供。
- MVS 正式上传、附件绑定、动态 Form schema、操作字典和写回 DTO 尚未提供，写操作继续失败关闭。
- 生产恢复、监控通知、授权 DAST/第三方渗透和讯飞 AIKit 供应商审批仍为外部门禁。
