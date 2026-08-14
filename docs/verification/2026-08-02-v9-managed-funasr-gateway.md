# V9 受管 Fun-ASR 网关与 Air3 验证

日期：2026-08-02

## 固定边界

- 主 AI 与视觉只使用上一稳定版 `qwen3-vl-plus`。
- 实时 ASR 只使用上一版 `fun-asr-realtime`，断网或云端失败时保留本地 Sherpa 回退。
- 不使用 OpenClaw，不启用新增大模型。
- DashScope 长期密钥只保存在服务器 root-only 环境文件；APK、Git、网页 bundle 和日志不携带长期密钥。
- 声纹不复用 ASR 或 LLM，只接刚申请的讯飞声纹服务；本阶段尚未开放声纹监听。

## 网关实现

- 新增纯标准库 RFC 6455 帧编解码，拒绝未掩码客户端帧、未知 opcode、分片、扩展和超限帧。
- 新增短会话保护的 `GET /sessions/{sessionId}/asr` WebSocket 路由。
- 网关通过 IPv4/TLS 连接 DashScope WSS，不修改服务器全局网络。
- Android 协议固定为 `start`、binary PCM、`finish`；网关返回 `ready`、`partial`、`final`、`error`。
- `run-task` 与 `finish-task` 使用同一 `task_id`；供应商 `task-started` 前的 PCM 使用 512 KiB 有界 FIFO。
- `result-generated` 只产生 partial，只有 `task-finished` 产生 final；供应商错误统一为 `asr_unavailable`，不返回供应商报文。
- Android 在 TLS 建连前的 PCM 同样使用 512 KiB 有界 FIFO，并在后端 `start` 发出后立即冲刷，避免吞掉句首。

## 自动化

- Python 网关：`38/38`，包含协议状态机、真实 socket 双向代理、HTTP 401/101、供应商握手、部署合同、AI、任务事件和工作流回归。
- Python 语法检查：`gateway.py`、`asr_proxy.py` 及对应测试全部通过。
- Android JVM：`371/371`，失败 `0`，错误 `0`；新增早到 PCM FIFO 和弱网内存上限测试。
- Android Debug 构建成功；已知 D8 对 API 34 的提示仍存在，但编译、DEX、打包与签名任务均成功。

## 云端

- V9 release：`/opt/dingdang-v9-gateway/releases/20260802T1245Z`。
- systemd：`dingdang-v9-gateway.service` active，仅监听 `127.0.0.1:8790`。
- 公网：`https://bb.chinacedar.top:2305/v9-ops`。
- `/etc/dingdang-v9-gateway.env` 为 `0600 root:root`，显式锁定 `V9_ASR_MODEL=fun-asr-realtime`；ASR 复用服务器内既有 DashScope 凭据，不要求重新提供密钥。
- 服务器直接 WSS 握手成功；公网短会话链路将中文 PCM 识别为“今天是星期几？”，事件顺序为 `ready -> partial -> final`。
- 现有专家协同 `https://bb.chinacedar.top:2305/health` 保持 HTTP 200，`127.0.0.1:8787` 未修改。

## V9.0.11 Air3

- 包名：`com.codex.air3nativecamera.dingdangexpert.v9.asr.gateway.audit`。
- 版本：`9011 / 9.0.11-stable-model-asr-gateway-audit`。
- APK：`output/air3-v9-stable-model-asr-gateway-audit-20260802/DingdangAI-v9.0.11-stable-model-asr-gateway-audit.apk`。
- SHA-256：`BF8717F4D37D4301B34B63EFA4384D334879B4969C1BCD736D48C7128ED89A3D`。
- `usesCleartextTraffic=false`，v2 Debug 签名有效；该包只能用于审计，不能作为正式签名发布包。
- APK 解包检查：私有 bootstrap 精确值未出现，Debug provisioning JSON 未出现，OpenClaw 未出现，`fun-asr-realtime` 固定模型存在；生成配置中的主 AI、直连 ASR 和讯飞长期密钥均为空。
- 已并存安装到 Air3 `YM00FCF3NW0031 / IMA301 / Android 14`，V8.0.48 与 V9.0.10 未覆盖或卸载。
- App 私有 `v9-debug-provisioning.json` 为 `0600`。
- 真实声学回灌“服务器无法启动，电源指示灯不亮”：云端连续返回 7 次 partial，最终仲裁日志为 `Realtime ASR selected source=cloud`，final 完整保留句首；随后 AI 首增量 `772ms` 并返回单步检查建议。
- 首页和任务 HUD 与原版布局一致；目标进程存活，crash buffer 为空。

## 剩余边界

- 真人佩戴、现场噪声、弱网切换、长时耗电与温升仍需正式验收。
- 声纹目前只有服务器密钥保管，尚无本人同意、三段录入、即时 1:1 验证、重录、删除、失败锁定、撤销和审计接口。
- V9.0.11 是 Debug 审计包；正式签名、生产设备身份和 MDM 投放尚未完成。
