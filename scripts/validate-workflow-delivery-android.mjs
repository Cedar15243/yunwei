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
  /new WorkflowCapabilityRegistry\(\s*new HashMap<String, WorkflowCapabilityRegistry\.Handler>\(\)\)/,
  /new WorkflowAssignmentRepository\(/,
  /new WorkflowAssignmentSyncCoordinator\(client, repository, packageCache, 4\)/,
  /new WorkflowSyncTriggerCoordinator\(/,
  /new AndroidNetworkAvailabilityMonitor\(getApplicationContext\(\)\)/,
  /workflowDeliveryController\.start\(\)/,
  /workflowDeliveryController\.onForeground\(\)/,
  /workflowDeliveryController\.close\(\)/,
]) {
  assert.match(main, contract);
}

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
