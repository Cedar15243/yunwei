export type WorkflowPackageSigner = {
  keyId: string;
  sign(contentSha256: string): Promise<string>;
};

export type Ed25519WorkflowSigningConfiguration = {
  privateKeyPkcs8Base64?: string;
  keyId?: string;
};

export function createEd25519WorkflowSigner(
  configuration: Ed25519WorkflowSigningConfiguration,
): WorkflowPackageSigner | null {
  const privateKeyPkcs8Base64 = configuration.privateKeyPkcs8Base64?.trim();
  const keyId = configuration.keyId?.trim();
  if (!privateKeyPkcs8Base64 || !keyId) return null;

  return {
    keyId,
    async sign(contentSha256) {
      if (!/^[0-9a-f]{64}$/.test(contentSha256)) {
        throw new Error("workflow_content_hash_invalid");
      }
      try {
        const privateKey = await crypto.subtle.importKey(
          "pkcs8",
          decodeBase64(privateKeyPkcs8Base64),
          { name: "Ed25519" },
          false,
          ["sign"],
        );
        const signature = await crypto.subtle.sign(
          { name: "Ed25519" },
          privateKey,
          new TextEncoder().encode(contentSha256),
        );
        return encodeBase64(signature);
      } catch (error) {
        if (
          error instanceof Error &&
          error.message === "workflow_content_hash_invalid"
        ) throw error;
        throw new Error("workflow_signing_key_invalid");
      }
    },
  };
}

function decodeBase64(value: string): ArrayBuffer {
  const bytes = Uint8Array.from(
    atob(value),
    (character) => character.charCodeAt(0),
  );
  return bytes.buffer as ArrayBuffer;
}

function encodeBase64(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value)));
}
