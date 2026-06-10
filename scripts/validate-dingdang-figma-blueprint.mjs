import { readFileSync } from 'node:fs';

const blueprintPath = 'docs/figma/dingdang-ops-ai-frame-blueprint.json';
const blueprint = JSON.parse(readFileSync(blueprintPath, 'utf8'));

const requiredFrameIds = [
  'chat-main',
  'camera-capture',
  'asr-streaming',
  'gpt-streaming',
  'shortcut-flow',
];

const failures = [];

if (blueprint.figmaFileKey !== 'pDX9LEKARKp5GGchwuFLz4') {
  failures.push('Unexpected Figma file key.');
}

if (blueprint.designContract?.background !== 'white') {
  failures.push('Design contract must keep a white background.');
}

if (blueprint.designContract?.primaryMode !== 'chat') {
  failures.push('Design contract must remain chat-first.');
}

if (blueprint.designContract?.diagnosisSource !== 'GPT only') {
  failures.push('Diagnosis source must stay GPT only.');
}

if (blueprint.designContract?.asrRole !== 'transcript only') {
  failures.push('ASR role must stay transcript only.');
}

if (!blueprint.designContract?.lowOperation) {
  failures.push('Low-operation AR glasses contract is missing.');
}

if (!blueprint.designContract?.noHudPanels) {
  failures.push('Blueprint must reject old HUD-style panels.');
}

const frameMap = new Map((blueprint.requiredFrames ?? []).map((frame) => [frame.id, frame]));
for (const id of requiredFrameIds) {
  const frame = frameMap.get(id);
  if (!frame) {
    failures.push(`Missing required frame: ${id}`);
    continue;
  }
  if (!frame.title || !frame.size?.width || !frame.size?.height) {
    failures.push(`Frame ${id} is missing title or size.`);
  }
  if (!Array.isArray(frame.mustShow) || frame.mustShow.length < 5) {
    failures.push(`Frame ${id} must include at least five mustShow requirements.`);
  }
  if (!Array.isArray(frame.uiText) || frame.uiText.length < 4) {
    failures.push(`Frame ${id} must include UI text anchors.`);
  }
}

const shortcutFrame = frameMap.get('shortcut-flow');
const shortcutText = JSON.stringify(shortcutFrame ?? {});
for (const keyName of ['ENTER', 'DPAD_CENTER', 'FOCUS', 'F9', 'RIGHT', 'MENU', 'F12', 'BACK', 'LEFT', 'F10', 'CAMERA', 'DVR']) {
  if (!shortcutText.includes(keyName)) {
    failures.push(`Shortcut frame missing key marker: ${keyName}`);
  }
}

const asrFrameText = JSON.stringify(frameMap.get('asr-streaming') ?? {});
if (!asrFrameText.includes('no diagnostic text from ASR')) {
  failures.push('ASR frame must explicitly prevent ASR-generated diagnosis.');
}

const result = {
  ok: failures.length === 0,
  blueprintPath,
  figmaFileKey: blueprint.figmaFileKey,
  frameCount: blueprint.requiredFrames?.length ?? 0,
  requiredFrameIds,
  failures,
};

console.log(JSON.stringify(result, null, 2));

if (failures.length > 0) {
  process.exit(1);
}
