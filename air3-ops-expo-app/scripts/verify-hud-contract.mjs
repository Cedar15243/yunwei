import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const source = fs.readFileSync(path.join(root, "app", "index.tsx"), "utf8");

const requiredSnippets = [
  "type ChatMessage =",
  "type ProjectThread =",
  "type VoiceState =",
  '"idle" | "listening" | "partial" | "final" | "analyzing"',
  "叮当运维AI",
  "会话记录",
  "新建项目",
  "现场诊断",
  "照片已在输入框",
  "正在听",
  "实时转写",
  "已听清，自动发送",
  "AI 正在生成",
  "handleCameraPress",
  "handleVoicePress",
  "handleSend",
  "simulatePartialTranscript",
  "autoSendFinalTranscript",
  "backgroundColor: \"#ffffff\"",
  "accessibilityLabel=\"发送\"",
  "accessibilityLabel=\"拍照\"",
  "accessibilityLabel=\"语音\"",
];

const forbiddenSnippets = [
  "叮当X AI 运维眼镜",
  "服务器 SSH 恢复",
  "对准服务器本地控制台",
  "把服务器控制台文字放入绿色框内",
  "调试信息仅写入日志",
  "拍照 / 下一步",
  "长按中心",
  "复测分诊",
  "诊断命令",
  "HudState",
  "GuideFrame",
];

const missing = requiredSnippets.filter((snippet) => !source.includes(snippet));
const forbidden = forbiddenSnippets.filter((snippet) => source.includes(snippet));

if (missing.length || forbidden.length) {
  console.error(
    JSON.stringify(
      {
        ok: false,
        missing,
        forbidden,
      },
      null,
      2,
    ),
  );
  process.exit(1);
}

console.log(
  JSON.stringify(
    {
      ok: true,
      chatPrototype: true,
      lowOperationVoiceFlow: true,
    },
    null,
    2,
  ),
);
