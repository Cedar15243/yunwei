// Paste this code into the Figma MCP `use_figma` tool for file pDX9LEKARKp5GGchwuFLz4.
// It creates the five Dingdang Ops AI frames required by docs/figma/dingdang-ops-ai-frame-blueprint.json.

const createdNodeIds = [];
const removedNodeNames = [];
const frameNames = [];

const pageName = "叮当运维AI - 聊天式现场诊断";
let page = figma.root.children.find((candidate) => candidate.name === pageName);
if (!page) {
  page = figma.createPage();
  page.name = pageName;
}
await figma.setCurrentPageAsync(page);

await figma.loadFontAsync({ family: "Inter", style: "Regular" });
await figma.loadFontAsync({ family: "Inter", style: "Bold" });

for (const child of [...page.children]) {
  if (child.name.startsWith("Dingdang Ops AI /")) {
    removedNodeNames.push(child.name);
    child.remove();
  }
}

function rgb(hex) {
  const value = hex.replace("#", "");
  return {
    r: parseInt(value.slice(0, 2), 16) / 255,
    g: parseInt(value.slice(2, 4), 16) / 255,
    b: parseInt(value.slice(4, 6), 16) / 255,
  };
}

function solid(hex) {
  return [{ type: "SOLID", color: rgb(hex) }];
}

function addFrame(name, x, y) {
  const frame = figma.createFrame();
  frame.name = name;
  frame.resize(1440, 900);
  frame.x = x;
  frame.y = y;
  frame.cornerRadius = 0;
  frame.clipsContent = true;
  frame.fills = solid("#FFFFFF");
  frame.strokes = solid("#D8DEE8");
  frame.strokeWeight = 1;
  page.appendChild(frame);
  createdNodeIds.push(frame.id);
  frameNames.push(name);
  return frame;
}

function addRect(parent, name, x, y, width, height, fill, stroke = null, radius = 8) {
  const node = figma.createRectangle();
  node.name = name;
  node.resize(width, height);
  node.x = x;
  node.y = y;
  node.cornerRadius = radius;
  node.fills = solid(fill);
  node.strokes = stroke ? solid(stroke) : [];
  node.strokeWeight = stroke ? 1 : 0;
  parent.appendChild(node);
  createdNodeIds.push(node.id);
  return node;
}

function addText(parent, name, text, x, y, width, size = 24, color = "#111827", bold = false) {
  const node = figma.createText();
  node.name = name;
  node.fontName = { family: "Inter", style: bold ? "Bold" : "Regular" };
  node.characters = text;
  node.fontSize = size;
  node.lineHeight = { unit: "AUTO" };
  node.fills = solid(color);
  node.x = x;
  node.y = y;
  node.resize(width, Math.max(size * 1.6, 32));
  parent.appendChild(node);
  createdNodeIds.push(node.id);
  return node;
}

function addPill(parent, text, x, y, width, fill = "#F7F9FC", stroke = "#D8DEE8", color = "#374151") {
  addRect(parent, `Pill / ${text}`, x, y, width, 42, fill, stroke, 21);
  addText(parent, `Label / ${text}`, text, x + 18, y + 10, width - 36, 14, color, false);
}

function addBubble(parent, name, text, x, y, width, height, fill, stroke, alignRight = false) {
  addRect(parent, `${name} bubble`, x, y, width, height, fill, stroke, 12);
  addText(parent, `${name} text`, text, x + 20, y + 18, width - 40, 18, "#111827", false);
  if (alignRight) {
    addPill(parent, "已附现场图片 image_id", x + width - 206, y + height - 52, 186, "#ECFDF5", "#BBF7D0", "#047857");
  }
}

function addTopBar(parent, title, subtitle) {
  addText(parent, "Top title", title, 340, 34, 620, 24, "#111827", true);
  addText(parent, "Top subtitle", subtitle, 340, 68, 760, 14, "#6B7280", false);
  addPill(parent, "白底聊天 / GPT 输出诊断", 1116, 36, 230, "#F7F9FC", "#D8DEE8", "#374151");
}

function buildChatMain(frame) {
  addRect(frame, "Left session rail", 0, 0, 296, 900, "#F8FAFC", "#E5E7EB", 0);
  addText(frame, "Brand", "华方智联", 32, 32, 180, 18, "#334155", true);
  addText(frame, "Product", "叮当运维AI", 32, 66, 220, 28, "#111827", true);
  addText(frame, "Rail title", "会话记录", 32, 128, 180, 16, "#475569", true);
  addPill(frame, "新建现场", 32, 166, 180, "#111827", "#111827", "#FFFFFF");
  addRect(frame, "Selected project", 24, 234, 248, 80, "#FFFFFF", "#CBD5E1", 8);
  addText(frame, "Project name", "当前项目", 44, 252, 160, 16, "#111827", true);
  addText(frame, "Project status", "现场诊断进行中", 44, 282, 180, 13, "#64748B", false);
  addRect(frame, "Project 2", 24, 326, 248, 72, "#F1F5F9", "#E2E8F0", 8);
  addText(frame, "Project 2 text", "空调机房复查", 44, 350, 160, 14, "#475569", false);

  addTopBar(frame, "新对话", "INMO 助手式语音入口，叮当运维AI 增加现场拍照识别");
  addRect(frame, "Last conversation card", 356, 126, 420, 88, "#FFFFFF", "#E2E8F0", 12);
  addText(frame, "Last conversation label", "上次对话", 380, 146, 120, 15, "#64748B", false);
  addText(frame, "Last conversation title", "风机柜过载排查", 380, 174, 220, 20, "#111827", true);
  addRect(frame, "Logo mark", 670, 284, 78, 78, "#108060", null, 39);
  addText(frame, "Logo text", "叮", 694, 300, 42, 34, "#FFFFFF", true);
  addText(frame, "Home title", "叮当运维AI", 548, 384, 340, 42, "#111827", true);
  addText(frame, "Home subtitle", "拍照看现场，语音说问题，AI 给出下一步", 452, 446, 520, 22, "#64748B", false);
  addRect(frame, "Home action photo", 464, 650, 210, 80, "#FFFFFF", "#B5E0CF", 24);
  addText(frame, "Home action photo label", "点我拍照", 522, 672, 120, 24, "#167D60", true);
  addRect(frame, "Home action voice", 704, 650, 210, 80, "#16A36E", "#16A36E", 24);
  addText(frame, "Home action voice label", "点我说话", 762, 672, 120, 24, "#FFFFFF", true);
  addText(frame, "Home note", "没有输入框；ASR final 后自动发送，图片作为上下文进入 GPT", 450, 766, 560, 16, "#64748B", false);
}

function buildCamera(frame) {
  addTopBar(frame, "拍摄现场图", "独立相机页，确认后压缩上传并返回 image_id");
  addRect(frame, "Camera preview", 156, 124, 1128, 612, "#111827", "#1F2937", 12);
  addText(frame, "Preview text", "Camera preview\n对准现场后拍照", 508, 378, 420, 34, "#F9FAFB", true);
  addPill(frame, "返回", 180, 780, 100, "#F8FAFC", "#CBD5E1", "#334155");
  addPill(frame, "拍照", 636, 776, 160, "#111827", "#111827", "#FFFFFF");
  addPill(frame, "使用照片", 1096, 780, 132, "#2563EB", "#2563EB", "#FFFFFF");
  addText(frame, "Upload state", "正在上传现场图片，成功后写入 composer：image_id", 424, 832, 520, 16, "#64748B", false);
}

function buildAsr(frame) {
  addTopBar(frame, "语音实时转文字", "Fun-ASR 只负责 transcript，诊断结论必须由 GPT 返回");
  addBubble(frame, "Photo context", "已附现场图片 image_id：img_现场_001", 384, 142, 520, 78, "#EEF6FF", "#BFDBFE", true);
  addRect(frame, "Voice active", 388, 278, 640, 190, "#FFFFFF", "#CBD5E1", 16);
  addText(frame, "Voice state", "正在听", 420, 310, 160, 24, "#111827", true);
  addText(frame, "Partial transcript", "正在转文字：风机柜现在报警，屏幕显示过载...", 420, 362, 560, 22, "#1F2937", false);
  addPill(frame, "结束提问", 420, 414, 148, "#111827", "#111827", "#FFFFFF");
  addBubble(frame, "Final transcript", "识别完成：风机柜报警，屏幕显示过载，帮我判断先查哪里。", 620, 548, 560, 112, "#EEF6FF", "#BFDBFE", false);
  addText(frame, "Auto send", "已自动发送给 GPT，不需要输入框或发送按钮", 620, 684, 420, 16, "#047857", true);
}

function buildGpt(frame) {
  addTopBar(frame, "GPT 流式诊断建议", "App 边接收 delta 边更新聊天气泡，降低等待感");
  addBubble(frame, "User context", "final_text + image_id 已发送", 628, 140, 420, 84, "#EEF6FF", "#BFDBFE", true);
  addRect(frame, "Streaming answer", 356, 286, 756, 318, "#FFFFFF", "#CBD5E1", 16);
  addText(frame, "Streaming title", "AI 正在分析", 388, 318, 220, 24, "#111827", true);
  addText(frame, "Streaming content", "结合现场图片和问题，建议按这个顺序处理：\n\n1. 先确认过载保护器状态，记录当前电流。\n2. 检查风机柜接线端子温升和异味。\n3. 若电流持续偏高，先停机再查轴承或叶轮卡滞。\n\n继续追问时保持同一会话记录。", 388, 370, 660, 19, "#111827", false);
  addPill(frame, "继续追问", 388, 640, 128, "#F8FAFC", "#CBD5E1", "#334155");
  addPill(frame, "重新生成", 536, 640, 128, "#F8FAFC", "#CBD5E1", "#334155");
  addText(frame, "First delta", "GPT first delta from logcat，目标小于 5 秒", 728, 642, 320, 14, "#64748B", false);
}

function buildShortcut(frame) {
  addTopBar(frame, "低操作快捷键", "优先使用 App 可稳定消费的 KeyEvent，系统相机键只记录风险");
  const rows = [
    ["ENTER / DPAD_CENTER", "语音开始 / 结束", "核心动作，适合眼镜场景"],
    ["FOCUS / F9", "打开 App 内相机", "避开系统相机键争抢"],
    ["RIGHT / MENU / F12", "发送", "可作为备选确认键"],
    ["BACK / LEFT / F10", "返回聊天页", "避免误退出应用"],
    ["CAMERA / DVR", "系统相机保留键", "普通 APK 不承诺改绑"],
  ];
  rows.forEach((row, index) => {
    const y = 150 + index * 112;
    const reserved = row[0].includes("CAMERA");
    addRect(frame, `Shortcut row ${index + 1}`, 258, y, 924, 78, reserved ? "#FFF7ED" : "#FFFFFF", reserved ? "#FDBA74" : "#CBD5E1", 12);
    addText(frame, `Shortcut key ${index + 1}`, row[0], 294, y + 18, 260, 19, reserved ? "#9A3412" : "#111827", true);
    addText(frame, `Shortcut action ${index + 1}`, row[1], 588, y + 18, 220, 19, "#111827", true);
    addText(frame, `Shortcut note ${index + 1}`, row[2], 836, y + 20, 300, 15, "#64748B", false);
  });
  addText(frame, "Shortcut footer", "目标：少操作、少误触、语音优先、拍照作为上下文。", 376, 748, 560, 22, "#111827", true);
}

const startX = 120;
const startY = 120;
const gap = 120;
const frames = [
  ["Dingdang Ops AI / 01 Chat main screen", buildChatMain],
  ["Dingdang Ops AI / 02 Camera capture screen", buildCamera],
  ["Dingdang Ops AI / 03 ASR partial and final state", buildAsr],
  ["Dingdang Ops AI / 04 GPT streaming answer", buildGpt],
  ["Dingdang Ops AI / 05 AR glasses shortcut flow", buildShortcut],
];

frames.forEach(([name, builder], index) => {
  const frame = addFrame(name, startX + index * (1440 + gap), startY);
  builder(frame);
});

return {
  pageId: page.id,
  pageName,
  createdNodeIds,
  removedNodeNames,
  frameNames,
  frameCount: frameNames.length,
};
