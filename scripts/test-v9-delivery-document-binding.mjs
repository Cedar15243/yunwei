import assert from "node:assert/strict";

import { validateReleaseBoundDocuments } from "./v9-delivery-document-binding.mjs";

const currentHash = "02DABEE95134D8FA9376F415880E08F7AA2872BC43BB2BDE92CA51BCF05806E5";
const staleHash = "0D7A2219389E3F71E6798172A74C00D93853710048DEC6F5490425A81286956C";

assert.doesNotThrow(() => validateReleaseBoundDocuments({
  apkSha256: currentHash,
  documents: {
    "docs/FORMAL_RELEASE.md": `正式 APK SHA-256：\`${currentHash}\``,
    "docs/VERIFICATION.md": `APK SHA-256：\`${currentHash}\``,
    "docs/CURRENT_STATE_AUDIT.md": `APK SHA-256 \`${currentHash}\``,
    "docs/COMPLETION_MATRIX.md": `正式 APK SHA-256 \`${currentHash}\``,
  },
}));

assert.throws(() => validateReleaseBoundDocuments({
  apkSha256: currentHash,
  documents: {
    "docs/FORMAL_RELEASE.md": `正式 APK SHA-256：\`${currentHash}\``,
    "docs/VERIFICATION.md": `APK SHA-256：\`${currentHash}\``,
    "docs/CURRENT_STATE_AUDIT.md": `APK SHA-256 \`${staleHash}\``,
    "docs/COMPLETION_MATRIX.md": `正式 APK SHA-256 \`${currentHash}\``,
  },
}), /CURRENT_STATE_AUDIT\.md.*stale release SHA-256/i);

assert.throws(() => validateReleaseBoundDocuments({
  apkSha256: currentHash,
  documents: {
    "docs/FORMAL_RELEASE.md": "正式发布说明未写 APK 摘要",
    "docs/VERIFICATION.md": `APK SHA-256：\`${currentHash}\``,
    "docs/CURRENT_STATE_AUDIT.md": `APK SHA-256 \`${currentHash}\``,
    "docs/COMPLETION_MATRIX.md": `正式 APK SHA-256 \`${currentHash}\``,
  },
}), /FORMAL_RELEASE\.md.*current release SHA-256/i);

console.log("V9 delivery document release-binding tests passed.");
