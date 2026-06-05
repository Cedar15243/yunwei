import fs from "node:fs";

const base = "https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses";
const key = fs.readFileSync("tmp/ops_glasses_api_key.local", "utf8").trim();
const imagePath = "tmp/air3-preview-43-upload.jpg";
const photoEvidencePath = "tmp/air3-v2-photo-response.json";
const voiceEvidencePath = "tmp/air3-v2-voice-response.json";

function assertField(value, name) {
  if (value === undefined || value === null || value === "") {
    throw new Error(`missing response field: ${name}`);
  }
}

async function postJson(path, payload) {
  const response = await fetch(`${base}${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json; charset=utf-8",
      "x-ops-glasses-key": key,
    },
    body: JSON.stringify(payload),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${path} failed ${response.status}: ${text.slice(0, 500)}`);
  }
  return JSON.parse(text);
}

function verifyStructuredResponse(name, response) {
  for (const field of [
    "sessionId",
    "step",
    "resultType",
    "displayTitle",
    "displayText",
    "displayHint",
    "canUseVoice",
    "canHumanEscalate",
  ]) {
    assertField(response[field], `${name}.${field}`);
  }
}

const imageBase64 = fs.readFileSync(imagePath).toString("base64");
const photoResponse = await postJson("/sessions/events", {
  taskType: "ssh_console_recovery",
  step: "locate_server",
  action: "console_photo_uploaded",
  imageKind: "console",
  imageBase64: `data:image/jpeg;base64,${imageBase64}`,
  source: "manual-contract-test",
  timestamp: Date.now(),
});
verifyStructuredResponse("photo", photoResponse);
fs.writeFileSync(photoEvidencePath, `${JSON.stringify(photoResponse, null, 2)}\n`);

const transcript = "我已经输入完成了，现在屏幕显示 inactive";
const voiceResponse = await postJson(`/sessions/${photoResponse.sessionId}/voice`, {
  transcript,
  audioFormat: "text-only-test",
  source: "manual-contract-test",
  timestamp: Date.now(),
});
verifyStructuredResponse("voice", voiceResponse);
assertField(voiceResponse.transcript, "voice.transcript");
if (voiceResponse.transcript !== transcript) {
  throw new Error(`voice transcript mismatch: ${voiceResponse.transcript}`);
}
fs.writeFileSync(voiceEvidencePath, `${JSON.stringify(voiceResponse, null, 2)}\n`);

console.log(JSON.stringify({
  photoSessionId: photoResponse.sessionId,
  photoStep: photoResponse.step,
  photoResultType: photoResponse.resultType,
  photoFeedbackCode: photoResponse.feedbackCode,
  photoDisplayTitle: photoResponse.displayTitle,
  voiceStep: voiceResponse.step,
  voiceResultType: voiceResponse.resultType,
  voiceFeedbackCode: voiceResponse.feedbackCode,
  voiceTranscript: voiceResponse.transcript,
  voiceDisplayTitle: voiceResponse.displayTitle,
}, null, 2));
