# V9 讯飞声纹公网生命周期验证

## 固定合同

- 主 AI/视觉：上一稳定版 `qwen3-vl-plus`。
- 实时 ASR：上一版 `fun-asr-realtime`，本地 Sherpa 仅作断网/失败回退。
- 声纹：只使用讯飞“声纹识别（新）”`s1aa729d0`。
- 不使用 OpenClaw，不启用新增大模型。
- 长期供应商密钥不进入 APK、Git、网页 bundle 或日志。

## 自动化

- 命令：工作区 Python 运行 `python -m unittest discover -s v9-ops-gateway -p 'test_*.py' -v`。
- 结果：`60/60` 通过。
- 覆盖：固定模型拒绝、讯飞新版 HMAC-SHA256 合同、16 kHz 单声道 WAV 门禁、三段录入、即时 `1:1` 验证、幂等、反重放、三次失败锁定、重录、删除、管理员撤销、不可变审计和错误脱敏。

## 公网端到端

- 入口：`https://bb.chinacedar.top:2305/v9-ops`。
- 设备：Air3 `YM00FCF3NW0031`，独立 V9.0.11 Debug 审计包。
- 凭据：从应用私有 `0600` 投放文件读取到进程内，仅换取 15 分钟短会话，未输出或落盘。
- 音频：Air3 真实录音，16 kHz、16 bit、单声道、8.54 秒；生成四份不同字节但同一说话人的受控测试样本。

结果：

1. 短会话签发成功。
2. 初始声纹状态为 `voiceprint_not_enrolled`。
3. 明示同意成功，进入 `enrolling`。
4. 三段样本依次成功，进入 `pending_verification`。
5. 讯飞 `1:1` 验证成功，进入 `active`；公网验证请求约 `3.34s`。
6. 使用 Unicode 安全确认词删除成功，最终状态为 `deleted`。

## 服务端安全复核

- 当前 release：`/opt/dingdang-v9-gateway/releases/20260802T2227Z-voiceprint`，服务状态 `active`。
- `V9_AI_MODEL=qwen3-vl-plus`、`V9_ASR_MODEL=fun-asr-realtime`、讯飞服务 URL `s1aa729d0` 均锁定。
- AI/讯飞环境文件为 `0600 root:root`；声纹数据库为 `0600` 且由 systemd DynamicUser 持有。
- 最终 profile 为 `deleted`，供应商 group/feature 引用均已清空。
- 6 条审计事件哈希链校验有效。
- 数据库不含 `RIFF`、`audioBase64`；网关 journal 不含音频字段或已配置密钥值。
- 现有专家协同健康端点保持 HTTP `200`。

## 尚未完成

- Android `AudioCaptureCoordinator`、双语音互斥、实体按键双击、声纹录入/删除 HUD 尚未接线。
- 真实现场噪声的误拒/误受、声纹模式 p95、20 分钟温升和耗电尚未验收；在这些门槛通过前声纹监听不得默认开启。
