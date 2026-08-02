import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const main = read(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
);
const restrictions = read(
  "air3-dingdang-expert-integrated-app/app/src/main/res/xml/app_restrictions.xml",
);
const keySource = read(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/workflow/ManagedWorkflowPublicKeySource.java",
);
const monitor = read(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/AndroidNetworkAvailabilityMonitor.java",
);
const controller = read(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/WorkflowDeliveryController.java",
);

assert.match(restrictions, /android:key="workflow_trusted_public_keys"/);

for (const contract of [
  /ManagedWorkflowPublicKeySource\.fromManagedJson\(managedKeySet\)/,
  /new WorkflowPackageVerifier\(\s*publicKeys,\s*BuildConfig\.VERSION_CODE,\s*1,/,
  /handlers\.put\("camera\.photo",\s*new WorkflowCapabilityRegistry\.Handler\(\)/,
  /handlers\.put\("camera\.video",\s*new WorkflowCapabilityRegistry\.Handler\(\)/,
  /handlers\.put\("audio\.voice_input",\s*new WorkflowCapabilityRegistry\.Handler\(\)/,
  /beginWorkflowPhotoCapture\(request, callback\)/,
  /beginWorkflowVideoCapture\(request, callback\)/,
  /beginWorkflowVoiceInput\(request, callback\)/,
  /new WorkflowCapabilityRegistry\(handlers\)/,
  /new WorkflowAssignmentRepository\(/,
  /new WorkflowAssignmentSyncCoordinator\(client, repository, packageCache, 4\)/,
  /new WorkflowSyncTriggerCoordinator\(/,
  /new WorkflowEvidenceUploadCoordinator\(/,
  /client\.uploadEvidence\(/,
  /executionCoordinator\.onEvidenceUploaded\(/,
  /workflowEvidenceUploadCoordinator\.request\(\)/,
  /shouldFailPendingImageUpload\(\s*sendAfterImageUpload,\s*voiceStreamState == VoiceStreamState\.AI_PENDING\)/,
  /failAssistantStreamingMessage\(error, null, "照片上传阶段"\)/,
  /recoverableAiFailureMessage\(stage, detail, requestId\)/,
  /new AndroidNetworkAvailabilityMonitor\(getApplicationContext\(\)\)/,
  /workflowDeliveryController\.start\(\)/,
  /workflowDeliveryController\.onForeground\(\)/,
  /workflowDeliveryController\.close\(\)/,
]) {
  assert.match(main, contract);
}

assert.equal(
  (main.match(/handlers\.put\(/g) ?? []).length,
  3,
  "only the implemented photo, video, and workflow voice capabilities may be advertised",
);
assert.match(main, /VoiceSessionPurpose \{ NONE, WAKE, COMMAND, OFFLINE_WAKE_COMMAND, WORKFLOW_INPUT \}/);
assert.match(main, /"workflow_voice_input"\.equals\(value\)[\s\S]*?dispatchWorkflowVoiceInput\(\)/);
assert.match(main, /private void completeWorkflowVoiceInput\(String transcript\)/);
assert.match(main, /private void failWorkflowVoiceInput\(String reason, String message\)/);
assert.match(main, /workflowVoiceInputMaximumDurationMillis\(\)/);
assert.match(
  main,
  /private void onAsrFinal[\s\S]*?voiceSessionPurpose == VoiceSessionPurpose\.WORKFLOW_INPUT[\s\S]*?completeWorkflowVoiceInput\(finalText\)/,
);
assert.match(
  main,
  /private void onVoiceUnclear[\s\S]*?voiceSessionPurpose == VoiceSessionPurpose\.WORKFLOW_INPUT[\s\S]*?failWorkflowVoiceInput\(/,
);
assert.match(main, /private void resetManagedWorkflowRuntime\(\)[\s\S]*?cancelWorkflowVoiceInput\(/);
assert.match(
  main,
  /private void startToggleVoiceRecording\(\)[\s\S]*?catch \(Exception error\) \{[\s\S]*?stopVoiceRecording\(false, "workflow_voice_start_failed"\)[\s\S]*?failWorkflowVoiceInput\(/,
);
assert.match(
  main,
  /private void cancelWorkflowVoiceInput\(String reason\)[\s\S]*?voiceStreamState = VoiceStreamState\.IDLE;[\s\S]*?composerTranscript = "";/,
);
assert.match(main, /workflowNavigationBackAction\(/);
assert.match(
  main,
  /private void returnToHudHomeFromVoice\(\)[\s\S]*?cancelWorkflowPhotoCapture\("workflow_photo_home"\)/,
);
assert.match(
  main,
  /private void retryAiWithVoice\(\)[\s\S]*?composerImageUploadFailed = false;[\s\S]*?sendAfterImageUpload = false;/,
);

assert.match(keySource, /KeyFactory\.getInstance\("Ed25519"\)/);
assert.match(keySource, /exactPositiveInt\(root\.opt\("schemaVersion"\)\)/);
assert.match(keySource, /accepted\.containsKey\(keyId\)/);
assert.doesNotMatch(keySource, /PRIVATE KEY|password|secret/i);

assert.match(monitor, /registerDefaultNetworkCallback\(callback\)/);
assert.match(monitor, /NET_CAPABILITY_INTERNET/);
assert.match(monitor, /NET_CAPABILITY_VALIDATED/);
assert.match(monitor, /unregisterNetworkCallback\(callback\)/);

assert.match(controller, /trigger\(true, 0L\)/);
assert.match(controller, /trigger\(false, hintedSequence\)/);
assert.match(controller, /if \(!started \|\| closed\) return/);
assert.match(controller, /networkMonitor\.close\(\)/);
assert.match(controller, /shutdown\.run\(\)/);

console.log("Android workflow delivery lifecycle contract passed");
