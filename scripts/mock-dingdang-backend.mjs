import http from "node:http";
import crypto from "node:crypto";

const portArg = process.argv.find((arg) => arg.startsWith("--port="));
const port = Number(portArg ? portArg.slice("--port=".length) : process.env.PORT || 18080);

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
  });
  res.end(body);
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function estimateBase64Bytes(value) {
  if (!value || typeof value !== "string") {
    return 0;
  }
  const comma = value.indexOf(",");
  const base64 = comma >= 0 ? value.slice(comma + 1) : value;
  return Math.floor((base64.length * 3) / 4);
}

function writeSse(res, event, data) {
  res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(data)}\n\n`);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "127.0.0.1"}`);
  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, { ok: true, service: "mock-dingdang-backend" });
    return;
  }

  const imageMatch = url.pathname.match(/^\/sessions\/([^/]+)\/images$/);
  if (req.method === "POST" && imageMatch) {
    const requestedSessionId = imageMatch[1];
    const raw = await readBody(req);
    const payload = raw ? JSON.parse(raw) : {};
    const sessionId = requestedSessionId === "_" ? "mock-session-001" : requestedSessionId;
    sendJson(res, 200, {
      ok: true,
      session_id: sessionId,
      image_id: `mock-image-${Date.now()}`,
      image_bytes: estimateBase64Bytes(payload.image_base64 || payload.imageBase64 || ""),
    });
    return;
  }

  const diagnoseMatch = url.pathname.match(/^\/sessions\/([^/]+)\/diagnose\/stream$/);
  if (req.method === "POST" && diagnoseMatch) {
    await readBody(req);
    res.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    const deltas = [
      "我已经看到现场图片和你的问题。先确认报警灯状态，",
      "再看压力表是否低于设定值；如果压力低，优先检查进水阀、过滤器和泵入口。",
      "\n下一步请把镜头对准压力表，我会继续判断。",
    ];
    let index = 0;
    const tick = () => {
      if (index < deltas.length) {
        writeSse(res, "delta", { text: deltas[index] });
        index += 1;
        setTimeout(tick, 280);
        return;
      }
      writeSse(res, "done", { message_id: `mock-ai-${Date.now()}` });
      res.end();
    };
    tick();
    return;
  }

  sendJson(res, 404, { ok: false, error: "not_found", path: url.pathname });
});

server.on("upgrade", (req, socket) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "127.0.0.1"}`);
  if (!url.pathname.match(/^\/sessions\/([^/]+)\/asr$/)) {
    socket.destroy();
    return;
  }
  const key = req.headers["sec-websocket-key"];
  if (!key) {
    socket.destroy();
    return;
  }
  const accept = crypto
    .createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${accept}`,
    "",
    "",
  ].join("\r\n"));

  let buffer = Buffer.alloc(0);
  let finalSent = false;
  const sendFinal = () => {
    if (finalSent || socket.destroyed) {
      return;
    }
    finalSent = true;
    sendTextFrame(socket, JSON.stringify({
      type: "final",
      text: "这个水泵为什么一直报警",
    }));
    setTimeout(() => sendCloseFrame(socket), 120);
  };

  sendTextFrame(socket, JSON.stringify({ type: "ready" }));
  setTimeout(() => sendTextFrame(socket, JSON.stringify({
    type: "partial",
    text: "这个水泵",
  })), 260);
  setTimeout(() => sendTextFrame(socket, JSON.stringify({
    type: "partial",
    text: "这个水泵为什么一直报警",
  })), 620);
  const fallbackTimer = setTimeout(sendFinal, 2200);

  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    for (;;) {
      const parsed = readClientFrame(buffer);
      if (!parsed) {
        break;
      }
      buffer = parsed.rest;
      if (parsed.opcode === 0x8) {
        clearTimeout(fallbackTimer);
        sendCloseFrame(socket);
        return;
      }
      if (parsed.opcode === 0x1) {
        const text = parsed.payload.toString("utf8");
        if (text.includes("\"type\":\"finish\"") || text.includes("\"type\": \"finish\"")) {
          clearTimeout(fallbackTimer);
          sendFinal();
        }
      }
    }
  });
  socket.on("error", () => clearTimeout(fallbackTimer));
  socket.on("close", () => clearTimeout(fallbackTimer));
});

function sendTextFrame(socket, text) {
  sendFrame(socket, 0x1, Buffer.from(text, "utf8"));
}

function sendCloseFrame(socket) {
  if (socket.destroyed) {
    return;
  }
  sendFrame(socket, 0x8, Buffer.alloc(0));
  socket.end();
}

function sendFrame(socket, opcode, payload) {
  const length = payload.length;
  let header;
  if (length < 126) {
    header = Buffer.from([0x80 | opcode, length]);
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  socket.write(Buffer.concat([header, payload]));
}

function readClientFrame(input) {
  if (input.length < 2) {
    return null;
  }
  const opcode = input[0] & 0x0f;
  const masked = (input[1] & 0x80) !== 0;
  let length = input[1] & 0x7f;
  let offset = 2;
  if (length === 126) {
    if (input.length < offset + 2) {
      return null;
    }
    length = input.readUInt16BE(offset);
    offset += 2;
  } else if (length === 127) {
    if (input.length < offset + 8) {
      return null;
    }
    length = Number(input.readBigUInt64BE(offset));
    offset += 8;
  }
  let mask = null;
  if (masked) {
    if (input.length < offset + 4) {
      return null;
    }
    mask = input.subarray(offset, offset + 4);
    offset += 4;
  }
  if (input.length < offset + length) {
    return null;
  }
  const payload = Buffer.from(input.subarray(offset, offset + length));
  if (mask) {
    for (let i = 0; i < payload.length; i += 1) {
      payload[i] ^= mask[i % 4];
    }
  }
  return {
    opcode,
    payload,
    rest: input.subarray(offset + length),
  };
}

server.listen(port, "127.0.0.1", () => {
  console.log(`mock-dingdang-backend listening on http://127.0.0.1:${port}`);
});
