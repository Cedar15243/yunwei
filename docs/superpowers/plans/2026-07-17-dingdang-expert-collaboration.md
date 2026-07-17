# 叮当专家协同演示版 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改 626 资产的前提下，交付 Air3 一键呼叫、多专家网页接听、TRTC 音视频、主专家实时标注和旁听语音加入的 10 天演示版。

**Architecture:** 新建三个互相隔离的工程：Node WebSocket 服务负责在线状态、第一人抢接、角色、短期 TRTC `UserSig` 和冻结帧；React 专家网页负责联系人、TRTC Web 音视频和 Canvas 标注；独立 Android Gradle 工程负责 Air3 TRTC 本地预览、摄像头/麦克风发布和透明标注层。626 包和 `air3-native-camera-test/` 全程只读。

**Tech Stack:** Node.js 24.15.0、TypeScript 7.0.2、Express 5.2.1、ws 8.21.1、Vitest 4.1.10、React 19.2.7、Vite 8.1.5、TRTC Web SDK 5.18.3、Android Java、Gradle 7.6、Android Gradle Plugin 7.4.2、TRTC Android SDK 13.4.0.20477、JUnit 4.13.2。

---

## 文件结构

```text
expert-collab-server/
  src/config.ts                 # 环境配置与秘密读取
  src/protocol.ts               # WebSocket 消息契约
  src/session-store.ts          # 在线状态、抢接和角色状态机
  src/trtc-user-sig.ts          # 服务端生成短期 UserSig
  src/server.ts                 # HTTP、WebSocket 和截图入口
  test/session-store.test.ts
  test/protocol.test.ts
  package.json
  tsconfig.json

expert-collab-web/
  src/api/collab-socket.ts      # WebSocket 客户端
  src/api/trtc-client.ts        # TRTC Web 适配器
  src/canvas/annotation-model.ts
  src/canvas/AnnotationCanvas.tsx
  src/components/ContactRail.tsx
  src/components/ExpertStage.tsx
  src/components/SessionPanel.tsx
  src/App.tsx
  src/styles.css
  src/*.test.ts(x)
  package.json

air3-expert-collab-app/
  app/src/main/java/com/codex/expercollab/MainActivity.java
  app/src/main/java/com/codex/expercollab/CollabStateMachine.java
  app/src/main/java/com/codex/expercollab/CollabSocketClient.java
  app/src/main/java/com/codex/expercollab/AnnotationOverlayView.java
  app/src/main/java/com/codex/expercollab/TrtcSessionController.java
  app/src/test/java/com/codex/expercollab/*.java
  app/src/main/AndroidManifest.xml
  app/src/main/res/**
  app/build.gradle
  settings.gradle
  gradle.properties
  gradlew / gradlew.bat / gradle/wrapper/**

scripts/
  build-dingdang-expert-collab-apk.ps1
  install-and-verify-dingdang-expert-collab.ps1
  run-expert-collab-demo.ps1
  validate-expert-collab.mjs
```

## Task 1: 建立独立工程与保护门禁

**Files:**
- Create: `scripts/validate-expert-collab.mjs`
- Modify: `package.json`
- Create: `expert-collab-server/package.json`
- Create: `expert-collab-web/package.json`
- Create: `air3-expert-collab-app/settings.gradle`
- Test: `scripts/validate-expert-collab.mjs`

- [ ] **Step 1: 记录 626 文件哈希基线**

运行：

```powershell
git hash-object scripts/build-dingdang-ai-expert-follow-apk.ps1 scripts/install-and-verify-dingdang-ai-expert-follow.ps1 scripts/build-install-dingdang-ai-expert-follow-direct-apk.ps1 air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java
```

预期：输出四个哈希，保存到测试常量，后续验证这些文件没有变化。

- [ ] **Step 2: 写保护门禁测试**

`scripts/validate-expert-collab.mjs` 必须断言：

```js
const required = {
  androidPackage: "com.codex.air3nativecamera.dingdangexpert.collab",
  versionCode: "801",
  versionName: "8.0.1-expert-collab-demo",
};

assert.equal(read("scripts/build-dingdang-ai-expert-follow-apk.ps1"), baselineBuild626);
assert.match(read("scripts/build-dingdang-expert-collab-apk.ps1"), new RegExp(required.androidPackage.replaceAll(".", "\\.")));
```

- [ ] **Step 3: 运行测试确认失败**

运行：`node scripts/validate-expert-collab.mjs`

预期：失败，提示新协同版脚本或工程尚不存在；626 哈希检查通过。

- [ ] **Step 4: 建立三个空工程目录和根脚本**

使用精确版本安装依赖并提交各工程 `package-lock.json`：

```powershell
npm --prefix expert-collab-server install express@5.2.1 ws@8.21.1
npm --prefix expert-collab-server install -D typescript@7.0.2 vitest@4.1.10 @types/express @types/ws @types/node
npm --prefix expert-collab-web install react@19.2.7 react-dom@19.2.7 trtc-sdk-v5@5.18.3
npm --prefix expert-collab-web install -D vite@8.1.5 typescript@7.0.2 vitest@4.1.10 @vitejs/plugin-react jsdom @testing-library/react @testing-library/jest-dom
```

根 `package.json` 只增加：

```json
{
  "scripts": {
    "validate:expert-collab": "node scripts/validate-expert-collab.mjs",
    "test:expert-collab": "npm --prefix expert-collab-server test && npm --prefix expert-collab-web test -- --run"
  }
}
```

不得改动现有脚本值或纳入用户未提交的其他文件。

- [ ] **Step 5: 提交骨架**

```powershell
git add scripts/validate-expert-collab.mjs package.json expert-collab-server expert-collab-web air3-expert-collab-app
git commit -m "chore: scaffold expert collaboration projects"
```

## Task 2: 定义协议与会话状态机

**Files:**
- Create: `expert-collab-server/src/protocol.ts`
- Create: `expert-collab-server/src/session-store.ts`
- Create: `expert-collab-server/test/protocol.test.ts`
- Create: `expert-collab-server/test/session-store.test.ts`

- [ ] **Step 1: 写协议校验失败测试**

```ts
it("rejects annotation points outside normalized coordinates", () => {
  expect(() => parseMessage({
    type: "annotation.append",
    sessionId: "s1",
    senderId: "e1",
    seq: 1,
    sentAt: 1,
    payload: { objectId: "a1", points: [{ x: 1.2, y: 0.5 }] }
  })).toThrow("normalized coordinate");
});
```

- [ ] **Step 2: 写第一人抢接失败测试**

```ts
it("keeps the first expert as primary", () => {
  const store = new SessionStore();
  const session = store.requestCall("glasses-01");
  expect(store.acceptCall(session.id, "expert-wang").role).toBe("primary");
  expect(() => store.acceptCall(session.id, "expert-liu")).toThrow("already accepted");
});
```

- [ ] **Step 3: 运行测试确认失败**

运行：`npm --prefix expert-collab-server test`

预期：`parseMessage` 和 `SessionStore` 未定义导致失败。

- [ ] **Step 4: 实现最小协议和状态机**

核心类型固定为：

```ts
export type ParticipantRole = "glasses" | "primary" | "observer";
export type SessionStatus = "calling" | "connecting" | "in_call" | "ended";
export interface Envelope<T = unknown> {
  type: string;
  sessionId: string | null;
  senderId: string;
  seq: number;
  sentAt: number;
  payload: T;
}
```

`SessionStore.acceptCall()` 必须同步完成状态检查和角色写入，不允许先读后异步写造成双主专家。

- [ ] **Step 5: 运行测试并提交**

运行：`npm --prefix expert-collab-server test`

预期：协议和状态机测试全部通过。

提交：`git commit -m "feat: add collaboration session state machine"`

## Task 3: 实现 TRTC 签名与演示服务

**Files:**
- Create: `expert-collab-server/src/config.ts`
- Create: `expert-collab-server/src/trtc-user-sig.ts`
- Create: `expert-collab-server/src/server.ts`
- Create: `expert-collab-server/.env.example`
- Test: `expert-collab-server/test/trtc-user-sig.test.ts`

- [ ] **Step 1: 写配置和签名测试**

```ts
it("never exposes the TRTC secret in public config", () => {
  const config = createPublicConfig({ sdkAppId: 1600152353, sdkSecret: "secret" });
  expect(config).toEqual({ sdkAppId: 1600152353 });
  expect(JSON.stringify(config)).not.toContain("secret");
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：`npm --prefix expert-collab-server test`

预期：配置与签名模块不存在。

- [ ] **Step 3: 实现安全配置**

服务只读取：

```dotenv
TRTC_SDK_APP_ID=1600152353
TRTC_SDK_SECRET=
COLLAB_HOST=0.0.0.0
COLLAB_PORT=8787
COLLAB_ORIGIN=http://localhost:5173
```

真实密钥放入 ignored 的 `tmp/trtc_sdk_secret.local`，启动脚本读取后注入环境变量。不得将密钥写入 `.env.example`、日志或客户端响应。

- [ ] **Step 4: 实现 HTTP 与 WebSocket 路由**

HTTP：

```text
GET  /health
GET  /api/config
POST /api/trtc/credential
POST /api/sessions/:id/freeze
GET  /api/freezes/:file
```

WebSocket：`/collab`，负责在线状态、呼叫、接听、邀请、标注与结束事件。

- [ ] **Step 5: 验证并提交**

运行：

```powershell
npm --prefix expert-collab-server test
npm --prefix expert-collab-server run typecheck
```

预期：全部通过且测试输出不包含 SDK 密钥。

提交：`git commit -m "feat: add expert collaboration demo service"`

## Task 4: 实现专家网页静态布局与角色状态

**Files:**
- Create: `expert-collab-web/src/App.tsx`
- Create: `expert-collab-web/src/styles.css`
- Create: `expert-collab-web/src/components/ContactRail.tsx`
- Create: `expert-collab-web/src/components/ExpertStage.tsx`
- Create: `expert-collab-web/src/components/SessionPanel.tsx`
- Test: `expert-collab-web/src/App.test.tsx`

- [ ] **Step 1: 写布局和角色测试**

```tsx
it("disables annotation controls for observers", () => {
  render(<App initialRole="observer" />);
  expect(screen.getByRole("button", { name: "箭头" })).toBeDisabled();
  expect(screen.getByText("旁听语音")).toBeVisible();
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：`npm --prefix expert-collab-web test -- --run`

预期：组件不存在。

- [ ] **Step 3: 实现 QQ 式三栏布局**

按设计基准实现：左侧现场与专家列表、中间 16:9 视频舞台、右侧成员与截图记录。工具栏使用图标按钮和 tooltip；主专家显示标注工具，旁听专家工具禁用。

- [ ] **Step 4: 验证响应式布局**

运行：`npm --prefix expert-collab-web run build`

预期：桌面三栏构建成功；小于 1000px 隐藏右侧记录栏但不遮挡视频控制。

- [ ] **Step 5: 提交**

提交：`git commit -m "feat: build expert collaboration console"`

## Task 5: 接入网页呼叫状态与 TRTC Web

**Files:**
- Create: `expert-collab-web/src/api/collab-socket.ts`
- Create: `expert-collab-web/src/api/trtc-client.ts`
- Modify: `expert-collab-web/src/App.tsx`
- Test: `expert-collab-web/src/api/collab-socket.test.ts`
- Test: `expert-collab-web/src/api/trtc-client.test.ts`

- [ ] **Step 1: 写广播来电和接听测试**

```ts
it("sends accept only once for an incoming call", async () => {
  const socket = new FakeSocket();
  const client = new CollabSocket(socket);
  client.accept("session-1");
  client.accept("session-1");
  expect(socket.sent.filter(m => m.type === "call.accepted")).toHaveLength(1);
});
```

- [ ] **Step 2: 写 TRTC 生命周期测试**

测试 `join -> publish microphone -> subscribe glasses video -> leave` 的调用顺序，TRTC SDK 使用接口适配器注入假实现，不在单元测试访问真实云服务。

- [ ] **Step 3: 实现 WebSocket 和 TRTC 适配器**

`CollabSocket` 负责重连和 `seq`；`TrtcClient` 负责使用后端短期凭证创建客户端、加入房间、播放远端眼镜视频、发布本地麦克风和退出清理。

- [ ] **Step 4: 验证双浏览器抢接**

打开两个专家网页，眼镜模拟器发送 `call.requested`。预期：两个页面都有来电，第一位接听成为主专家，第二位显示“已由其他专家接听”。

- [ ] **Step 5: 提交**

提交：`git commit -m "feat: connect expert console to TRTC calls"`

## Task 6: 实现网页实时标注与冻结帧

**Files:**
- Create: `expert-collab-web/src/canvas/annotation-model.ts`
- Create: `expert-collab-web/src/canvas/AnnotationCanvas.tsx`
- Create: `expert-collab-web/src/canvas/video-coordinates.ts`
- Test: `expert-collab-web/src/canvas/annotation-model.test.ts`
- Test: `expert-collab-web/src/canvas/video-coordinates.test.ts`

- [ ] **Step 1: 写归一化坐标测试**

```ts
it("maps pointer coordinates into the contained video rectangle", () => {
  expect(normalizePoint({ x: 500, y: 250 }, { x: 100, y: 50, width: 800, height: 400 }))
    .toEqual({ x: 0.5, y: 0.5 });
});
```

- [ ] **Step 2: 写权限测试**

旁听角色触发 pointer event 时不能创建对象，也不能发送 `annotation.begin`。

- [ ] **Step 3: 实现画笔、箭头、圆圈、撤销和清空**

轨迹采样批量上限为 20 批/秒。所有对象使用稳定 `objectId`；撤销按已完成对象栈处理，不能撤销其他会话对象。

- [ ] **Step 4: 实现冻结帧**

从远端 `<video>` 绘制到离屏 Canvas，压缩为 JPEG 后上传；服务返回 URL 后广播 `freeze.created`。恢复广播 `freeze.cleared` 并清空冻结背景。

- [ ] **Step 5: 测试并提交**

运行：`npm --prefix expert-collab-web test -- --run && npm --prefix expert-collab-web run build`

提交：`git commit -m "feat: add realtime expert annotations"`

## Task 7: 建立独立 Android Gradle 工程

**Files:**
- Create: `air3-expert-collab-app/build.gradle`
- Create: `air3-expert-collab-app/settings.gradle`
- Create: `air3-expert-collab-app/app/build.gradle`
- Create: `air3-expert-collab-app/app/src/main/AndroidManifest.xml`
- Create: `air3-expert-collab-app/app/src/main/res/**`
- Create: `scripts/build-dingdang-expert-collab-apk.ps1`
- Create: `scripts/install-and-verify-dingdang-expert-collab.ps1`

- [ ] **Step 1: 生成 Gradle Wrapper**

使用 Gradle 7.6、Android Gradle Plugin 7.4.2 和本机 JDK 11。Wrapper 分发地址固定到官方 Gradle 7.6 binary zip。

- [ ] **Step 2: 写 Android 身份配置**

```gradle
android {
  namespace "com.codex.expertcollab"
  compileSdk 34
  defaultConfig {
    applicationId "com.codex.air3nativecamera.dingdangexpert.collab"
    minSdk 26
    targetSdk 34
    versionCode 801
    versionName "8.0.1-expert-collab-demo"
  }
}
```

- [ ] **Step 3: 接入 TRTC Maven 依赖**

固定使用 2026-07-03 发布元数据中的稳定版本：

```gradle
dependencies {
  implementation "com.tencent.liteav:LiteAVSDK_TRTC:13.4.0.20477"
  testImplementation "junit:junit:4.13.2"
}
```

仓库使用 `dependencyLocking { lockAllConfigurations() }` 并提交 Gradle lockfile，禁止使用 `latest.release`。

- [ ] **Step 4: 写构建和并装验证脚本**

安装脚本必须验证新包、版本、横屏 Activity、CAMERA/RECORD_AUDIO 权限，并确认 626 包仍存在且版本仍为 626。

- [ ] **Step 5: 构建空壳并提交**

运行：`powershell -ExecutionPolicy Bypass -File scripts/build-dingdang-expert-collab-apk.ps1`

预期：生成被 Git 忽略的 APK，`aapt2 dump badging` 显示独立包与 801 版本。

提交：`git commit -m "chore: scaffold independent Air3 collaboration app"`

## Task 8: 实现 Android 状态机、协议和眼镜 UI

**Files:**
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/CollabStateMachine.java`
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/CollabProtocol.java`
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/MainActivity.java`
- Test: `air3-expert-collab-app/app/src/test/java/com/codex/expertcollab/CollabStateMachineTest.java`

- [ ] **Step 1: 写状态迁移测试**

```java
@Test public void firstTapStartsCallingAndSecondTapEndsCall() {
  CollabStateMachine machine = new CollabStateMachine();
  assertEquals(State.CALLING, machine.onPrimaryAction());
  machine.onAccepted("expert-wang");
  assertEquals(State.IN_CALL, machine.getState());
  assertEquals(State.ENDED, machine.onPrimaryAction());
}
```

- [ ] **Step 2: 运行测试确认失败**

运行：`air3-expert-collab-app\gradlew.bat testDebugUnitTest`

预期：类不存在导致失败。

- [ ] **Step 3: 实现状态机和协议解析**

状态固定为 `IDLE/CALLING/CONNECTING/IN_CALL/RECONNECTING/ENDED/FAILED`，非法迁移抛出明确异常并在 UI 层转换为中文提示。

- [ ] **Step 4: 实现横屏眼镜 UI**

首页只显示连接状态、当前专家和一个主按钮。通话页显示本地第一视角预览、主专家名称、透明标注层和挂断按钮；返回键留在应用内，不直接退出。

- [ ] **Step 5: 测试并提交**

运行：`air3-expert-collab-app\gradlew.bat testDebugUnitTest assembleDebug`

提交：`git commit -m "feat: add Air3 collaboration state and UI"`

## Task 9: 接入 Android TRTC、WebSocket 和标注层

**Files:**
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/TrtcSessionController.java`
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/CollabSocketClient.java`
- Create: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/AnnotationOverlayView.java`
- Modify: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/MainActivity.java`
- Test: `air3-expert-collab-app/app/src/test/java/com/codex/expertcollab/AnnotationMapperTest.java`

- [ ] **Step 1: 写标注坐标测试**

验证 `0..1` 坐标在 1920x1080、1280x720 和带 letterbox 的预览区域都映射到相同视频内容位置。

- [ ] **Step 2: 实现 WebSocket 客户端**

连接 `/collab`，注册 `glasses-01`，发送 `call.requested`，处理接听、成员、标注、冻结和结束事件。日志只输出事件类型和 sessionId，不输出凭证。

- [ ] **Step 3: 实现 TRTC 控制器**

从演示服务获取短期凭证后进入房间，开启本地预览、摄像头和麦克风，订阅专家音频；Activity 销毁和挂断时完整释放 TRTC 实例。

- [ ] **Step 4: 实现透明标注层与冻结背景**

`AnnotationOverlayView` 缓存完成路径并增量重绘；冻结帧下载后显示在预览上方、标注层下方，恢复时释放 Bitmap。

- [ ] **Step 5: 真机门槛验证**

在 Air3 上验证摄像头上行、麦克风上行、专家音频下行和本地预览。若第 2 天门槛失败，执行规格中的低帧率画面降级方案，不继续消耗时间调高阶标注。

- [ ] **Step 6: 提交**

提交：`git commit -m "feat: connect Air3 to expert collaboration room"`

## Task 10: 旁听邀请、截图和端到端演示

**Files:**
- Modify: `expert-collab-server/src/session-store.ts`
- Modify: `expert-collab-web/src/App.tsx`
- Modify: `air3-expert-collab-app/app/src/main/java/com/codex/expertcollab/MainActivity.java`
- Create: `scripts/run-expert-collab-demo.ps1`
- Create: `docs/expert-collab-demo-runbook.md`

- [ ] **Step 1: 写旁听邀请测试**

测试只有主专家可以邀请；受邀专家进入 `observer` 角色；旁听专家离开不结束会话。

- [ ] **Step 2: 实现旁听专家加入同一 TRTC 房间**

主专家点击“邀请专家”，服务发送邀请；被邀请专家接受后获取同一房间凭证，发布麦克风并订阅眼镜视频，标注按钮保持禁用。

- [ ] **Step 3: 实现截图记录**

保存冻结帧与当前标注快照，右侧面板显示缩略图、时间和创建专家。文件保存到 ignored 运行目录。

- [ ] **Step 4: 编写一键演示脚本**

脚本启动 server 和 web，输出局域网 URL，检查 TRTC 密钥文件存在但不打印内容，检查端口占用并选择备用端口。

- [ ] **Step 5: 执行完整演示回归**

顺序：两个专家网页在线 -> 眼镜广播呼叫 -> 第一人抢接 -> 双向音视频 -> 实时标注 -> 邀请旁听 -> 冻结 -> 截图 -> 恢复 -> 挂断。

- [ ] **Step 6: 提交**

提交：`git commit -m "feat: complete multi-expert demo workflow"`

## Task 11: 最终验证与交付收口

**Files:**
- Modify: `agent_memory/context.md`
- Modify: `agent_memory/progress.md`
- Modify: `agent_memory/bugs.md`
- Modify: `docs/expert-collab-demo-runbook.md`

- [ ] **Step 1: 运行全部自动验证**

```powershell
npm run validate:expert-collab
npm run test:expert-collab
npm --prefix expert-collab-web run build
npm --prefix expert-collab-server run typecheck
air3-expert-collab-app\gradlew.bat testDebugUnitTest assembleDebug
git diff --check
```

- [ ] **Step 2: 执行 Android 真机验证**

确认：新包 801、626 仍为 626、两个包同时存在、新包 Activity 前台、权限已授权、20 分钟通话无崩溃。保存 UI、logcat、meminfo、gfxinfo 和 TRTC 关键事件摘要到 ignored `tmp/`。

- [ ] **Step 3: 检查免费服务状态**

只读检查 TRTC 控制台：应用服务正常、后付费仍未开通、未订阅录制/翻译/AI 功能。不得点击付费按钮。

- [ ] **Step 4: 扫描敏感信息与范围**

```powershell
rg -n "TRTC_SDK_SECRET|TLSSigAPIv2|BEGIN PRIVATE|sk-[A-Za-z0-9_-]{12,}" . -g "!tmp/**" -g "!node_modules/**"
git status --short
```

预期：源码无真实秘密；626 四个基线文件哈希未变化；只提交新协同版和必要根脚本修改。

- [ ] **Step 5: 更新记忆并提交**

记录当前版本、真实验证证据、剩余风险和启动命令。

提交：`git commit -m "docs: finalize expert collaboration demo delivery"`
