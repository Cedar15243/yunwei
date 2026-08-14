import path from "node:path";

import { writeV9Sbom } from "./v9-sbom.mjs";

const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
const destination = path.resolve(process.argv[3] || path.join(root, "output", "v9-security-audit", "SBOM.cdx.json"));
const sbom = writeV9Sbom(root, destination);
console.log(`V9 SBOM generated (${sbom.components.length} components).`);
