import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
loadEnv(path.join(__dirname, "..", ".env"));

const config = {
  appId: process.env.FEISHU_APP_ID,
  appSecret: process.env.FEISHU_APP_SECRET,
  verificationToken: process.env.FEISHU_VERIFICATION_TOKEN || "",
  encryptKey: process.env.FEISHU_ENCRYPT_KEY || "",
  port: Number(process.env.PORT || 8787),
  air3CaptureDir: path.join(__dirname, "..", "tmp", "air3-captures"),
  air3TestReply: process.env.AIR3_TEST_REPLY || "你好",
};

let tenantTokenCache = { token: "", expiresAt: 0 };

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/health") {
      return sendJson(res, 200, { ok: true, service: "feishu-codex-bridge" });
    }

    if (req.method === "GET" && req.url === "/air3/test-page") {
      return sendHtml(res, 200, buildAir3TestPage());
    }

    if (req.method === "POST" && req.url === "/feishu/events") {
      const rawBody = await readBody(req);
      const payload = parseFeishuPayload(rawBody);
      return handleFeishuEvent(payload, res);
    }

    if (req.method === "POST" && req.url === "/air3/vision-test") {
      const rawBody = await readBody(req, 8 * 1024 * 1024);
      const payload = JSON.parse(rawBody || "{}");
      return handleAir3VisionTest(payload, res);
    }

    if (req.method === "POST" && req.url === "/air3/voice-test") {
      const rawBody = await readBody(req, 512 * 1024);
      const payload = JSON.parse(rawBody || "{}");
      return handleAir3VoiceTest(payload, res);
    }

    sendJson(res, 404, { error: "not_found" });
  } catch (error) {
    console.error(error);
    sendJson(res, 500, { error: "internal_error", message: error.message });
  }
});

server.listen(config.port, () => {
  console.log(`Feishu bridge listening on http://127.0.0.1:${config.port}`);
});

async function handleFeishuEvent(payload, res) {
  if (payload.challenge) {
    return sendJson(res, 200, { challenge: payload.challenge });
  }

  if (config.verificationToken && payload.token && payload.token !== config.verificationToken) {
    return sendJson(res, 403, { error: "invalid_verification_token" });
  }

  const event = payload.event || {};
  if (event.message?.message_id) {
    await replyToMessage(event.message.message_id, buildReply(event));
  }

  sendJson(res, 200, { ok: true });
}

function handleAir3VisionTest(payload, res) {
  const imageBase64 = typeof payload.imageBase64 === "string" ? payload.imageBase64 : "";
  const savedCapture = saveAir3Capture(imageBase64);
  sendJson(res, 200, {
    ok: true,
    text: config.air3TestReply,
    imageBytes: estimateBase64Bytes(imageBase64),
    savedCapture,
    timestamp: new Date().toISOString(),
  });
}

function saveAir3Capture(value) {
  const commaIndex = value.indexOf(",");
  const raw = commaIndex === -1 ? value : value.slice(commaIndex + 1);
  const normalized = raw.replace(/\s/g, "");
  if (!normalized) {
    return null;
  }

  fs.mkdirSync(config.air3CaptureDir, { recursive: true });
  const fileName = `air3-${new Date().toISOString().replace(/[:.]/g, "-")}.jpg`;
  const filePath = path.join(config.air3CaptureDir, fileName);
  fs.writeFileSync(filePath, Buffer.from(normalized, "base64"));
  return {
    fileName,
    bytes: fs.statSync(filePath).size,
  };
}

function handleAir3VoiceTest(payload, res) {
  const transcript = typeof payload.transcript === "string" ? payload.transcript : "";
  const voiceIntent = classifyVoiceIntent(transcript);
  sendJson(res, 200, {
    ok: true,
    transcript,
    voiceIntent,
    text: voiceIntentToText(voiceIntent),
    timestamp: new Date().toISOString(),
  });
}

function classifyVoiceIntent(text) {
  const normalized = text.trim().toLowerCase();
  if (!normalized) return "unknown";
  if (/转人工|人工|专家|接管/.test(normalized)) return "escalate";
  if (/重拍|重新拍|看不清|不清楚/.test(normalized)) return "retake";
  if (/开始|启动任务/.test(normalized)) return "start_task";
  if (/已输入|输完|执行完|完成了|好了|确认/.test(normalized)) return "confirm_done";
  if (/情况|说明|看到|显示/.test(normalized)) return "describe_scene";
  return "unknown";
}

function voiceIntentToText(voiceIntent) {
  if (voiceIntent === "confirm_done") return "收到确认，请继续下一步。";
  if (voiceIntent === "retake") return "收到，请重新拍摄。";
  if (voiceIntent === "escalate") return "已转人工。";
  if (voiceIntent === "start_task") return "开始任务。";
  if (voiceIntent === "describe_scene") return "请保持画面，我来分析。";
  return "未识别到明确语音动作。";
}

function buildAir3TestPage() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Air3 测试</title>
  <style>
    html, body {
      margin: 0;
      width: 100%;
      height: 100%;
      background: #101418;
      color: #f5f7fa;
      font-family: sans-serif;
    }
    body {
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
    }
    main {
      max-width: 900px;
      padding: 40px;
    }
    h1 {
      margin: 0 0 24px;
      font-size: 72px;
      font-weight: 700;
    }
    p {
      margin: 0;
      font-size: 28px;
      color: #b8c0cc;
    }
    button {
      margin-top: 36px;
      padding: 18px 32px;
      border: 0;
      border-radius: 8px;
      background: #24c48e;
      color: #07110d;
      font-size: 26px;
      font-weight: 700;
    }
  </style>
</head>
<body>
  <main>
    <h1 id="result">准备测试</h1>
    <p id="status">点击按钮后请求电脑服务</p>
    <button id="button" type="button">请求 AI</button>
  </main>
  <script>
    async function runTest() {
      const result = document.getElementById("result");
      const status = document.getElementById("status");
      result.textContent = "请求中...";
      status.textContent = "正在连接 http://127.0.0.1:8787";
      try {
        const response = await fetch("/air3/vision-test", {
          method: "POST",
          headers: { "Content-Type": "application/json; charset=utf-8" },
          body: JSON.stringify({ imageBase64: "dGVzdA==" })
        });
        const data = await response.json();
        result.textContent = data.text || "无返回文本";
        status.textContent = "服务已返回，imageBytes=" + data.imageBytes;
      } catch (error) {
        result.textContent = "请求失败";
        status.textContent = String(error && error.message ? error.message : error);
      }
    }
    document.getElementById("button").addEventListener("click", runTest);
    runTest();
  </script>
</body>
</html>`;
}

function buildReply(event) {
  const content = parseMessageContent(event.message?.content);
  const text = content.text?.trim() || "";

  if (text === "/help" || text === "帮助") {
    return [
      "已接入本机飞书机器人。",
      "可用指令：",
      "/help 或 帮助",
      "ping",
      "后续可以在这里扩展：整理文件、查询目录、运行项目等受控任务。",
    ].join("\n");
  }

  if (/^ping$/i.test(text)) {
    return "pong";
  }

  return `收到：${text || "(空消息)"}`;
}

async function replyToMessage(messageId, text) {
  const token = await getTenantAccessToken();
  const response = await fetch(
    `https://open.feishu.cn/open-apis/im/v1/messages/${encodeURIComponent(messageId)}/reply`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json; charset=utf-8",
      },
      body: JSON.stringify({
        msg_type: "text",
        content: JSON.stringify({ text }),
      }),
    },
  );

  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.code !== 0) {
    throw new Error(`Feishu reply failed: HTTP ${response.status} ${JSON.stringify(body)}`);
  }
}

async function getTenantAccessToken() {
  const now = Date.now();
  if (tenantTokenCache.token && tenantTokenCache.expiresAt > now + 60_000) {
    return tenantTokenCache.token;
  }

  if (!config.appId || !config.appSecret) {
    throw new Error("FEISHU_APP_ID and FEISHU_APP_SECRET are required");
  }

  const response = await fetch("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({
      app_id: config.appId,
      app_secret: config.appSecret,
    }),
  });
  const body = await response.json();
  if (!response.ok || body.code !== 0) {
    throw new Error(`Feishu token failed: HTTP ${response.status} ${JSON.stringify(body)}`);
  }

  tenantTokenCache = {
    token: body.tenant_access_token,
    expiresAt: now + Math.max(0, Number(body.expire || 0) - 120) * 1000,
  };
  return tenantTokenCache.token;
}

function parseFeishuPayload(rawBody) {
  const body = JSON.parse(rawBody || "{}");
  if (!body.encrypt) {
    return body;
  }
  if (!config.encryptKey) {
    throw new Error("FEISHU_ENCRYPT_KEY is required for encrypted callbacks");
  }

  const key = crypto.createHash("sha256").update(config.encryptKey).digest();
  const encrypted = Buffer.from(body.encrypt, "base64");
  const iv = encrypted.subarray(0, 16);
  const data = encrypted.subarray(16);
  const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
  decipher.setAutoPadding(true);
  const decrypted = Buffer.concat([decipher.update(data), decipher.final()]).toString("utf8");
  return JSON.parse(decrypted);
}

function parseMessageContent(content) {
  if (!content) {
    return {};
  }
  try {
    return JSON.parse(content);
  } catch {
    return { text: content };
  }
}

function estimateBase64Bytes(value) {
  const commaIndex = value.indexOf(",");
  const raw = commaIndex === -1 ? value : value.slice(commaIndex + 1);
  const normalized = raw.replace(/\s/g, "");
  if (!normalized) {
    return 0;
  }

  const padding = normalized.endsWith("==") ? 2 : normalized.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((normalized.length * 3) / 4) - padding);
}

function readBody(req, maxBytes = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let totalBytes = 0;

    req.on("data", (chunk) => {
      totalBytes += chunk.length;
      if (totalBytes > maxBytes) {
        req.destroy(new Error("request_body_too_large"));
        return;
      }

      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function sendHtml(res, status, body) {
  res.writeHead(status, { "Content-Type": "text/html; charset=utf-8" });
  res.end(body);
}

function loadEnv(envPath) {
  if (!fs.existsSync(envPath)) {
    return;
  }

  const env = fs.readFileSync(envPath, "utf8");
  for (const line of env.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const index = trimmed.indexOf("=");
    if (index === -1) {
      continue;
    }
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim().replace(/^["']|["']$/g, "");
    if (!process.env[key]) {
      process.env[key] = value;
    }
  }
}
