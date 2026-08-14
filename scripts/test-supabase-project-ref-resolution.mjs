import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { resolveSupabaseProjectRef } from "./supabase-project-ref.mjs";

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "supabase-project-ref-"));

try {
  const linkedOnly = path.join(temporary, "linked-only");
  fs.mkdirSync(path.join(linkedOnly, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(linkedOnly, "supabase/.temp/linked-project.json"),
    `${JSON.stringify({ ref: "linked-project-ref", name: "not-used" })}\n`,
    "utf8",
  );
  assert.deepEqual(resolveSupabaseProjectRef(linkedOnly), {
    projectRef: "linked-project-ref",
    source: "supabase/.temp/linked-project.json",
  });

  const legacyAndLinked = path.join(temporary, "legacy-and-linked");
  fs.mkdirSync(path.join(legacyAndLinked, ".supabase"), { recursive: true });
  fs.mkdirSync(path.join(legacyAndLinked, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(legacyAndLinked, ".supabase/project-ref"),
    "legacy-project-ref\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(legacyAndLinked, "supabase/.temp/linked-project.json"),
    `${JSON.stringify({ ref: "linked-project-ref" })}\n`,
    "utf8",
  );
  assert.deepEqual(resolveSupabaseProjectRef(legacyAndLinked), {
    projectRef: "legacy-project-ref",
    source: ".supabase/project-ref",
  });

  const invalid = path.join(temporary, "invalid");
  fs.mkdirSync(path.join(invalid, "supabase/.temp"), { recursive: true });
  fs.writeFileSync(
    path.join(invalid, "supabase/.temp/linked-project.json"),
    "not-json\n",
    "utf8",
  );
  assert.deepEqual(resolveSupabaseProjectRef(invalid), {
    projectRef: "",
    source: "",
  });

  assert.deepEqual(resolveSupabaseProjectRef(path.join(temporary, "missing")), {
    projectRef: "",
    source: "",
  });
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("Supabase project reference resolution tests passed.");
