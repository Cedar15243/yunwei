const SHA256_PATTERN = /\b[A-F0-9]{64}\b/gi;

export function validateReleaseBoundDocuments({ apkSha256, documents }) {
  const expected = String(apkSha256 ?? "").trim().toUpperCase();
  if (!/^[A-F0-9]{64}$/.test(expected)) {
    throw new Error("release APK SHA-256 is invalid");
  }
  for (const [name, content] of Object.entries(documents ?? {})) {
    const apkStatements = String(content ?? "")
      .split(/\r?\n/)
      .flatMap((line) => line.split(/[；;]/))
      .filter((statement) => /APK/i.test(statement));
    const hashes = apkStatements.flatMap((statement) => (
      statement.match(SHA256_PATTERN)?.map((value) => value.toUpperCase()) ?? []
    ));
    const hasCurrent = hashes.includes(expected);
    const stale = hashes.find((value) => value !== expected);
    if (stale) {
      throw new Error(`${name} contains stale release SHA-256 ${stale}`);
    }
    if (!hasCurrent) {
      throw new Error(`${name} does not reference the current release SHA-256`);
    }
  }
}
