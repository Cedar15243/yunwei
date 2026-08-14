import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.join(import.meta.dirname, "..");
const packager = fs.readFileSync(
  path.join(root, "scripts/package-v9-formal-delivery.ps1"),
  "utf8",
).replace(/^\uFEFF/, "");
const validator = fs.readFileSync(
  path.join(root, "scripts/validate-v9-formal-delivery.mjs"),
  "utf8",
).replace(/^\uFEFF/, "");

assert.match(packager, /air3-v9-hardware-soak-\*-final\*/);
assert.match(packager, /Resolve-LatestEvidenceDirectory/);
assert.match(packager, /RequiredFiles/);
assert.doesNotMatch(packager, /air3-v9-operation-detail-20260808T230000Z/);
assert.match(packager, /air3-v9-operation-detail-\*/);
assert.match(packager, /Sort-Object LastWriteTimeUtc -Descending/);
assert.match(packager, /inspection\.png/);
assert.match(packager, /validate-v9-air3-soak-summary\.mjs/);
assert.match(packager, /verification\\air3-soak\\summary\.json/);
assert.match(packager, /docs\\AIR3_HARDWARE_SOAK\.md/);
assert.match(packager, /serialMasked/);
assert.match(packager, /powerMeasurementValid/);
assert.match(packager, /thermalSensorPeaksC/);
assert.match(packager, /sampleIntervalSeconds/);
assert.match(packager, /requiredSampleCount/);
assert.match(packager, /sampleCoveragePassed/);
assert.doesNotMatch(packager, /Copy-RequiredFile[^\r\n]+snapshots\.json/);
assert.doesNotMatch(packager, /Copy-RequiredFile[^\r\n]+logcat-(?:all|crash|filtered)\.txt/);

assert.match(validator, /verification\/air3-soak\/summary\.json/);
assert.match(validator, /docs\/AIR3_HARDWARE_SOAK\.md/);
assert.match(validator, /local_air3_hardware_soak/);
assert.match(validator, /usb_powered_power_measurement_invalid/);
assert.match(validator, /thermalSensorSampleCount/);
assert.match(validator, /powerMeasurementValid/);
assert.match(validator, /sampleIntervalSeconds/);
assert.match(validator, /requiredSampleCount/);
assert.match(validator, /sampleCoveragePassed/);
assert.match(validator, /raw Air3 soak evidence must not be shipped/);

console.log("V9 Air3 soak delivery contract tests passed.");
