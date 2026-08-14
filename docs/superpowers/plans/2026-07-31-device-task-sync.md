# 设备任务同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 安全地把眼镜本地任务事件同步到组织隔离的管理平台。

**Architecture:** 管理员为设备签发一次性明文令牌，数据库只存哈希。设备事件入口从令牌推导设备、组织和操作者，再以本地项目/任务 ID 与事件幂等键落库。Android 使用已存在的文件队列在既有操作成功后异步发送。

**Tech Stack:** Supabase Postgres、Deno Edge Functions、Android Java、JUnit、Deno test。

---

### Task 1: 设备凭据与事件入口

**Files:**
- Create: `supabase/functions/ops-glasses/device-sync.test.ts`
- Create: `supabase/functions/ops-glasses/device-sync.ts`
- Modify: `supabase/migrations/202607310003_device_task_sync.sql`
- Modify: `supabase/functions/ops-glasses/index.ts`
- Modify: `supabase/functions/ops-glasses/management.ts`
- Modify: `supabase/functions/ops-glasses/management.test.ts`

- [ ] 写入失败测试：缺少令牌返回 `401`，无绑定操作者返回 `403`，同一幂等键只持久化一次。
- [ ] 实现 `routeDeviceSync`，只接受 `POST /device-sync/events` 与 Bearer 设备令牌。
- [ ] 新增 `glasses_device_tokens`，保存哈希、状态、过期时间、签发/撤销审计字段；添加设备凭据轮换所需的 RLS/索引。
- [ ] 为 `super_admin` 和 `ops_admin` 新增设备凭据签发/轮换入口；令牌明文只出现在单次 `POST /management/devices/:deviceId/credentials` 响应中。
- [ ] 运行 `deno test --allow-env supabase/functions/ops-glasses/device-sync.test.ts` 与 `deno check --config supabase/functions/ops-glasses/deno.json supabase/functions/ops-glasses/index.ts`。

### Task 2: Android 队列验证与传输

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/test/java/com/codex/air3nativecamera/sync/TaskSyncQueueTest.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncEvent.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncQueue.java`
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/TaskSyncClient.java`

- [ ] 写入失败测试：重启后恢复待发送事件，第二次失败延迟 1 秒，连续失败最大延迟 60 秒。
- [ ] 在工作区 JDK 17 下运行目标测试，确认失败。
- [ ] 实现并运行测试至通过；不接入 UI 或 `MainActivity`。

### Task 3: 运行时接入与回归

**Files:**
- Modify: `air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`

- [ ] 仅在现有操作成功后建立事件并入队；通过注入的设备令牌与端点发送。
- [ ] 运行队列测试、任务会话测试、全量 Android 单测和 HUD 冻结检查。
- [ ] 构建独立候选 APK 后，在 Air3 弱网下验证不阻塞拍照、语音或 AI。
