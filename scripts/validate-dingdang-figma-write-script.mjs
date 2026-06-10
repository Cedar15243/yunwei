import fs from "node:fs";

const scriptPath = "docs/figma/dingdang-ops-ai-use-figma-script.js";
const blueprintPath = "docs/figma/dingdang-ops-ai-frame-blueprint.json";

const code = fs.readFileSync(scriptPath, "utf8").replace(/^\uFEFF/, "");
const blueprint = JSON.parse(fs.readFileSync(blueprintPath, "utf8").replace(/^\uFEFF/, ""));
const failures = [];

function requireMarker(marker, message = `Missing marker: ${marker}`) {
  if (!code.includes(marker)) {
    failures.push(message);
  }
}

function forbidMarker(marker, message = `Forbidden marker: ${marker}`) {
  if (code.includes(marker)) {
    failures.push(message);
  }
}

try {
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  new AsyncFunction("figma", code);
} catch (error) {
  failures.push(`Figma write script has invalid JavaScript syntax: ${error.message}`);
}

[
  "await figma.setCurrentPageAsync(page)",
  "await figma.loadFontAsync",
  "figma.createFrame",
  "figma.createText",
  "figma.createRectangle",
  "return {",
  "createdNodeIds",
  "frameNames",
].forEach((marker) => requireMarker(marker));

[
  "figma.closePlugin",
  "figma.notify",
  "console.log",
  "getPluginData(",
  "setPluginData(",
  "figma.currentPage =",
].forEach((marker) => forbidMarker(marker));

const requiredFrameNames = [
  "Dingdang Ops AI / 01 Chat main screen",
  "Dingdang Ops AI / 02 Camera capture screen",
  "Dingdang Ops AI / 03 ASR partial and final state",
  "Dingdang Ops AI / 04 GPT streaming answer",
  "Dingdang Ops AI / 05 AR glasses shortcut flow",
];
requiredFrameNames.forEach((frameName) => requireMarker(frameName));

const requiredText = [
  "叮当运维AI",
  "华方智联",
  "会话记录",
  "点我拍照",
  "点我说话",
  "没有输入框",
  "拍摄现场图",
  "正在上传现场图片",
  "Fun-ASR 只负责 transcript",
  "已自动发送给 GPT",
  "GPT 流式诊断建议",
  "低操作快捷键",
  "CAMERA / DVR",
  "系统相机保留键",
];
requiredText.forEach((text) => requireMarker(text, `Missing required UI text: ${text}`));

for (const frame of blueprint.requiredFrames ?? []) {
  if (!Array.isArray(frame.mustShow) || frame.mustShow.length < 5) {
    failures.push(`Blueprint frame ${frame.id} is missing mustShow requirements.`);
  }
}

if (!code.includes("solid(\"#FFFFFF\")")) {
  failures.push("Figma write script must preserve a white base background.");
}

if (!code.includes("image_id")) {
  failures.push("Figma write script must show image_id context.");
}

forbidMarker("点一下开始说话", "Figma write script must not keep the old tap-to-speak wording.");
forbidMarker("照片已在输入框", "Figma write script must not show an input-box based photo flow.");

const result = {
  ok: failures.length === 0,
  scriptPath,
  blueprintPath,
  figmaFileKey: blueprint.figmaFileKey,
  frameCount: requiredFrameNames.length,
  failures,
};

console.log(JSON.stringify(result, null, 2));

if (failures.length > 0) {
  process.exit(1);
}
