import fs from "node:fs";
import path from "node:path";

function readUtf8(filePath) {
  return fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "");
}

export function resolveSupabaseProjectRef(repositoryRoot) {
  const legacyRelative = ".supabase/project-ref";
  const legacyPath = path.join(repositoryRoot, legacyRelative);
  if (fs.statSync(legacyPath, { throwIfNoEntry: false })?.isFile()) {
    const projectRef = readUtf8(legacyPath).trim();
    if (projectRef) return { projectRef, source: legacyRelative };
  }

  const linkedRelative = "supabase/.temp/linked-project.json";
  const linkedPath = path.join(repositoryRoot, linkedRelative);
  if (fs.statSync(linkedPath, { throwIfNoEntry: false })?.isFile()) {
    try {
      const linked = JSON.parse(readUtf8(linkedPath));
      const projectRef = typeof linked.ref === "string" ? linked.ref.trim() : "";
      if (projectRef) return { projectRef, source: linkedRelative };
    } catch {
      // Invalid local CLI metadata is treated as no configured project.
    }
  }

  return { projectRef: "", source: "" };
}
