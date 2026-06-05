# Feishu Codex Bridge

本项目是一个本地飞书机器人后端，用来接收飞书事件并回复消息。默认只实现安全的基础指令，后续可以继续扩展成文件整理、项目运行、日报等受控任务入口。

## 配置

1. 在飞书开放平台重置 `App Secret`。
2. 复制 `.env.example` 为 `.env`。
3. 在 `.env` 填入：

```ini
FEISHU_APP_ID=你的 App ID
FEISHU_APP_SECRET=重置后的 App Secret
FEISHU_VERIFICATION_TOKEN=事件订阅里的 Verification Token
FEISHU_ENCRYPT_KEY=如果启用加密就填写 Encrypt Key
PORT=8787
AIR3_TEST_REPLY=你好
```

## 启动

```powershell
npm start
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8787/health
```

Air3 拍照识别测试端点：

```powershell
Invoke-RestMethod http://127.0.0.1:8787/air3/vision-test `
  -Method POST `
  -ContentType "application/json; charset=utf-8" `
  -Body '{"imageBase64":"test"}'
```

返回示例：

```json
{
  "ok": true,
  "text": "你好",
  "imageBytes": 2
}
```

## 飞书开放平台配置

事件订阅请求地址：

```text
https://你的公网域名/feishu/events
```

本机调试时可以用内网穿透工具把 `http://127.0.0.1:8787` 暴露成 HTTPS 地址，再填入飞书开放平台。

需要订阅的事件一般包括“接收消息”。机器人权限至少需要能够读取消息并回复消息。

## 当前指令

```text
帮助
/help
ping
```
