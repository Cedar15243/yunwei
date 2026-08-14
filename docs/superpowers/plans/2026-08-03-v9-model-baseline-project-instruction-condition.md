# V9 模型基线与项目指令条件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 锁定 V9 主 AI、实时 ASR 和讯飞声纹运行合同，并让“以后在这个项目遇到某问题……”只在匹配现场问题时注入后续 AI 上下文。

**Architecture:** 继续由 V9 网关强制拒绝非上一稳定版主模型和 ASR 模型；声纹只接受讯飞新版 `s1aa729d0` 端点。眼镜端在人工确认项目指令前，从自然语言中提取明确的“遇到”触发短语，生成受控 `containsAny` 条件；没有明确触发对象的“以后先……”规则保持显式项目级全局规则。

**Tech Stack:** Java Android 原生、JUnit、Python `unittest`、V9 Python 网关、讯飞声纹 HTTPS API。

---

### Task 1: 锁定模型合同

**Files:**
- Verify: `v9-ops-gateway/gateway.py`
- Verify: `v9-ops-gateway/asr_proxy.py`
- Verify: `v9-ops-gateway/voiceprint_proxy.py`
- Verify: `v9-ops-gateway/deploy/dingdang-v9-gateway.env.example`
- Verify: `v9-ops-gateway/deploy/dingdang-v9-voiceprint.env.example`

- [x] 确认主 AI/视觉为 `qwen3-vl-plus`、实时 ASR 为 `fun-asr-realtime`、声纹端点为 `https://api.xf-yun.com/v1/private/s1aa729d0`。

### Task 2: 项目指令条件红测

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/governance/ProjectGovernanceDraftTest.java`

- [x] 增加测试：从“以后在这个项目遇到控制器报警先记录故障码”提取 `containsAny=[控制器报警]`。
- [x] 增加测试：没有“遇到”对象的显式“以后先……”保持空条件，代表项目级规则。
- [x] 运行目标测试，确认当前 `condition()` 空对象实现导致第一条测试失败。

### Task 3: 最小实现

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/governance/ProjectGovernanceDraft.java`

- [x] 在项目指令工厂中保存从指令提取的条件。
- [x] 只接受首个“遇到/碰到/出现”后的短语，并在“先/必须/不要/需要/应当/记得/优先/按照”等动作词前截断。
- [x] 清理标点、空白和控制字符；提取失败时返回空对象，不改变显式全局规则语义。
- [x] 二次确认页展示用户可核对的触发条件，不改变现有 HUD 布局和操作链。
- [x] 网关系统合同明确执行当前已匹配、已授权的 Skill/项目指令，拒绝套用本轮未下发规则。

### Task 4: 回归验证

**Files:**
- Update: `agent_memory/context.md`
- Update: `agent_memory/progress.md`
- Update: `agent_memory/bugs.md`

- [x] 运行 Android governance 单测和全量 JVM 单测。
- [x] 运行 V9 网关全量测试、模型固定测试和 APK/构建合同验证。
- [x] 扫描 APK/生产路径，确认无 OpenClaw、非基线模型和长期供应商密钥。
- [x] 部署独立 V9 网关 release，并验证 V9/专家健康和 root-only 模型环境。
- [x] 构建并存 V9.0.27 主/Test APK，在 Air3 验证取消、确认、条件命中和不命中 AI 上下文。

---
