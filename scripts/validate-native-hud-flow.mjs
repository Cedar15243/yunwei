import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const activityPath = path.join(
  root,
  "air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
);
const code = fs.readFileSync(activityPath, "utf8");

function mustInclude(marker, message = marker) {
  if (!code.includes(marker)) {
    throw new Error(`native HUD flow missing marker: ${message}`);
  }
}

function mustNotInclude(marker, message = marker) {
  if (code.includes(marker)) {
    throw new Error(`native HUD flow keeps forbidden marker: ${message}`);
  }
}

for (const marker of [
  "private static final class HudResponse",
  "final String rawResponse;",
  "final String sessionId;",
  "final String step;",
  "final String resultType;",
  "final String feedbackCode;",
  "final String displayTitle;",
  "final String displayText;",
  "final String displayHint;",
  "final boolean humanEscalationSuggestion;",
  "HudResponse(JSONObject response, String fallbackSessionId, String fallbackStep)",
  "resultType = response.optString(\"resultType\", \"instruction\")",
  "feedbackCode = response.optString(\"feedbackCode\", \"\")",
  "displayTitle = response.optString(\"displayTitle\", firstInstructionLine(legacyText))",
  "displayText = response.optString(\"displayText\", legacyText)",
  "displayHint = response.optString(\"displayHint\", \"\")",
  "humanEscalationSuggestion = response.optBoolean(\"humanEscalationSuggestion\", false)",
  "private void applyHudResponse(final HudResponse hud)",
  "persistSession(hud.sessionId, hud.step)",
  "persistLastResponse(hud.rawResponse)",
  "stepText.setText(stepLabel(hud.step))",
  "fallbackHint(HudResponse hud)",
  "statusForHud(HudResponse hud)",
  "HudResponse hud = new HudResponse(response, sessionId, currentStep)",
  "applyHudResponse(hud)",
  "return \"等待 AI 指导\";",
]) {
  mustInclude(marker);
}

mustNotInclude("setResultText(voiceIntentLabel(intent))", "voiceIntent must not drive the V2 primary HUD result");
mustNotInclude("setHintForStep(nextStep, text)", "image responses must render structured HUD fields");
mustNotInclude("setStatus(statusForStep(nextStep))", "image responses must render status by resultType/feedbackCode");

console.log("Native HUD flow validation passed.");
