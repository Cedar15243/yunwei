import {
  assertEquals,
  assertRejects,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import { createEd25519WorkflowSigner } from "./workflow-signing.ts";

Deno.test("signs a workflow content hash with an Ed25519 server key", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "Ed25519" },
    true,
    ["sign", "verify"],
  ) as CryptoKeyPair;
  const privateKey = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  const signer = createEd25519WorkflowSigner({
    privateKeyPkcs8Base64: base64(privateKey),
    keyId: "workflow-key-2026-08",
  });

  const contentHash = "a".repeat(64);
  const signature = await signer!.sign(contentHash);
  const verified = await crypto.subtle.verify(
    { name: "Ed25519" },
    pair.publicKey,
    fromBase64(signature),
    new TextEncoder().encode(contentHash),
  );

  assertEquals(signer!.keyId, "workflow-key-2026-08");
  assertEquals(verified, true);
  assertEquals(signature.includes(base64(privateKey)), false);
});

Deno.test("returns unavailable for missing workflow signing configuration", () => {
  assertEquals(createEd25519WorkflowSigner({}), null);
  assertEquals(
    createEd25519WorkflowSigner({ privateKeyPkcs8Base64: "value" }),
    null,
  );
  assertEquals(createEd25519WorkflowSigner({ keyId: "key-a" }), null);
});

Deno.test("fails closed for malformed workflow signing key material", async () => {
  const signer = createEd25519WorkflowSigner({
    privateKeyPkcs8Base64: "not-valid-base64!",
    keyId: "workflow-key-a",
  });

  await assertRejects(
    () => signer!.sign("b".repeat(64)),
    Error,
    "workflow_signing_key_invalid",
  );
});

function base64(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value)));
}

function fromBase64(value: string): ArrayBuffer {
  return Uint8Array.from(
    atob(value),
    (character) => character.charCodeAt(0),
  ).buffer as ArrayBuffer;
}
