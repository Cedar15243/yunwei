import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const source = fs.readFileSync(path.join(root, "app", "index.tsx"), "utf8");

const requiredSnippets = [
  'type ResultType =',
  'type FeedbackCode =',
  'type HudState =',
  'type TextOverflowMode =',
  'fullText?: string;',
  'displayPages?: string[];',
  'textOverflowMode?: TextOverflowMode;',
  'resultType: "ready"',
  'resultType: "uploading"',
  'resultType: "recording_voice"',
  'resultType: "transcribing_voice"',
  'resultType: "ai_analyzing"',
  'resultType: "instruction"',
  'safeCommandKey: "ssh_status"',
  'safeCommandKey: "ssh_start"',
  'id: "instruction-long-guidance"',
  'textOverflowMode: "paged"',
  '中心点击先翻页，最后一页再拍照。',
  '第 ${currentPageIndex + 1}/${displayPages.length} 页',
  'feedbackCode: "wrong_target"',
  'feedbackCode: "unclear_photo"',
  'feedbackCode: "insufficient_info"',
  'feedbackCode: "voice_unclear"',
  'resultType: "network_error"',
  'resultType: "remote_probe"',
  'resultType: "completed"',
  'resultType: "human_suggested"',
  "叮当X AI 运维眼镜",
  "服务器 SSH 恢复",
  "把服务器控制台文字放入绿色框内",
  "调试信息仅写入日志，不显示给现场人员",
  "中心点击",
  "拍照 / 下一步",
  "长按中心",
  "语音确认 / 补充说明",
  "返回键",
  "重拍 / 返回上一步",
];

const forbiddenSnippets = [
  "诊断命令",
  "复测分诊",
  "人工介入",
  "No local STT",
  "HTTP",
  "bytes",
  "session=",
  "Exception",
  "debug",
];

const stateCount = (source.match(/resultType: "/g) || []).length;
const pageCount = (source.match(/displayPages:/g) || []).length;
const uniqueResultTypes = new Set(
  [...source.matchAll(/resultType: "([^"]+)"/g)].map((match) => match[1]),
);
const missing = requiredSnippets.filter((snippet) => !source.includes(snippet));
const forbidden = forbiddenSnippets.filter((snippet) => source.includes(snippet));

if (
  stateCount < 16 ||
  pageCount < 1 ||
  uniqueResultTypes.size !== 11 ||
  missing.length ||
  forbidden.length
) {
  console.error(
    JSON.stringify(
      {
        ok: false,
        stateCount,
        pageCount,
        uniqueResultTypeCount: uniqueResultTypes.size,
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
        stateCount,
        pageCount,
        uniqueResultTypeCount: uniqueResultTypes.size,
      },
    null,
    2,
  ),
);
