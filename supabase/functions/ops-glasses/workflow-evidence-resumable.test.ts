import {
  assertEquals,
  assertRejects,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  createWorkflowDeviceGateway,
  routeWorkflowDevice,
  WorkflowDeviceError,
  type WorkflowEvidenceChunkCommand,
  type WorkflowDeviceGateway,
  type WorkflowEvidenceUploadSessionCommand,
} from "./workflow-device.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "11111111-1111-4111-8111-111111111111",
  actorProfileId: "22222222-2222-4222-8222-222222222222",
};

const assignmentId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";
const taskId = "55555555-5555-4555-8555-555555555555";
const assetId = "66666666-6666-4666-8666-666666666666";
const uploadId = "77777777-7777-4777-8777-777777777777";

class InMemoryQuery {
  private action: "select" | "insert" | "update" | "delete" = "select";
  private payload: Record<string, unknown> | null = null;
  private filters: Array<[string, unknown]> = [];
  private orderBy: string | null = null;

  constructor(
    private readonly database: InMemorySupabase,
    private readonly table: string,
  ) {}

  select(_columns?: string): InMemoryQuery {
    return this;
  }

  insert(value: Record<string, unknown>): InMemoryQuery {
    this.action = "insert";
    this.payload = value;
    return this;
  }

  update(value: Record<string, unknown>): InMemoryQuery {
    this.action = "update";
    this.payload = value;
    return this;
  }

  delete(): InMemoryQuery {
    this.action = "delete";
    return this;
  }

  eq(column: string, value: unknown): InMemoryQuery {
    this.filters.push([column, value]);
    return this;
  }

  order(column: string, _options?: Record<string, unknown>): InMemoryQuery {
    this.orderBy = column;
    return this;
  }

  async maybeSingle(): Promise<{ data: Record<string, unknown> | null; error: null }> {
    const result = await this.execute();
    return { data: result.data[0] ?? null, error: null };
  }

  async single(): Promise<{ data: Record<string, unknown> | null; error: null }> {
    const result = await this.execute();
    return { data: result.data[0] ?? null, error: null };
  }

  then(resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown): Promise<unknown> {
    return this.execute().then(resolve, reject);
  }

  private async execute(): Promise<{ data: Array<Record<string, unknown>>; error: null }> {
    const rows = this.database.table(this.table);
    if (this.action === "insert") {
      const record = this.database.insert(this.table, this.payload ?? {});
      return { data: [record], error: null };
    }

    const matches = rows.filter((row) =>
      this.filters.every(([column, value]) => row[column] === value)
    );
    if (this.action === "update") {
      matches.forEach((row) => Object.assign(row, this.payload));
    } else if (this.action === "delete") {
      this.database.delete(this.table, matches);
    }
    const data = [...matches];
    if (this.orderBy) {
      data.sort((left, right) =>
        Number(left[this.orderBy as string]) - Number(right[this.orderBy as string])
      );
    }
    return { data, error: null };
  }
}

class InMemorySupabase {
  readonly rows = new Map<string, Array<Record<string, unknown>>>([
    ["workflow_executions", [{
      id: executionId,
      assignment_id: assignmentId,
      task_id: taskId,
      organization_id: identity.organizationId,
      operator_profile_id: identity.actorProfileId,
      device_id: identity.deviceId,
    }]],
    ["media_assets", []],
    ["workflow_evidence_uploads", []],
    ["workflow_evidence_parts", []],
  ]);
  readonly objects = new Map<string, Uint8Array>();
  removeFailuresRemaining = 0;

  readonly storage = {
    from: (_bucket: string) => ({
      upload: async (path: string, bytes: Uint8Array) => {
        const copy = new Uint8Array(bytes.length);
        copy.set(bytes);
        this.objects.set(path, copy);
        return { data: { path }, error: null };
      },
      download: async (path: string) => {
        const bytes = this.objects.get(path);
        if (!bytes) return { data: null, error: new Error("not_found") };
        const copy = new Uint8Array(bytes.length);
        copy.set(bytes);
        return { data: new Blob([copy.buffer]), error: null };
      },
      remove: async (paths: string[]) => {
        if (this.removeFailuresRemaining > 0) {
          this.removeFailuresRemaining -= 1;
          return { data: null, error: new Error("temporary_remove_failure") };
        }
        paths.forEach((path) => this.objects.delete(path));
        return { data: paths, error: null };
      },
    }),
  };

  from(table: string): InMemoryQuery {
    return new InMemoryQuery(this, table);
  }

  table(name: string): Array<Record<string, unknown>> {
    const table = this.rows.get(name);
    if (!table) throw new Error(`unknown table: ${name}`);
    return table;
  }

  insert(table: string, value: Record<string, unknown>): Record<string, unknown> {
    const record = { ...value };
    if (table === "media_assets") record.id = assetId;
    if (table === "workflow_evidence_uploads") record.upload_id = uploadId;
    this.table(table).push(record);
    return record;
  }

  delete(table: string, matches: Array<Record<string, unknown>>): void {
    const rows = this.table(table);
    for (const match of matches) {
      const index = rows.indexOf(match);
      if (index >= 0) rows.splice(index, 1);
    }
  }
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", copy.buffer),
  );
  return Array.from(digest)
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

async function photoSessionCommand(): Promise<WorkflowEvidenceUploadSessionCommand> {
  const bytes = photoBytes();
  return {
    assignmentId,
    executionId,
    localEvidenceId: "photo-local-a",
    nodeId: "photo-node-a",
    evidenceKey: "nameplate",
    kind: "photo",
    contentType: "image/jpeg",
    byteSize: bytes.length,
    durationSeconds: 0,
    sha256: await sha256Hex(bytes),
    capturedAt: "2026-08-05T10:00:00.000Z",
    chunkSize: 3,
    chunkCount: 2,
  };
}

function photoBytes(): Uint8Array {
  return new Uint8Array([0xff, 0xd8, 1, 2, 0xff, 0xd9]);
}

function request(path: string, body: Record<string, unknown>): Request {
  return new Request(`https://ops.example${path}`, {
    method: "POST",
    headers: {
      Authorization: "Bearer access-token",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function routeGateway(supabase: InMemorySupabase): WorkflowDeviceGateway {
  return {
    ...createWorkflowDeviceGateway(supabase),
    authenticateDevice: async (token) => token === "access-token" ? identity : null,
  };
}

function base64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

async function chunkCommand(
  index: number,
  bytes: Uint8Array,
): Promise<WorkflowEvidenceChunkCommand> {
  return {
    uploadId,
    chunkIndex: index,
    chunkCount: 2,
    chunkByteSize: bytes.length,
    chunkSha256: await sha256Hex(bytes),
    bytes,
  };
}

Deno.test("creates a durable upload session for first-time workflow evidence", async () => {
  const supabase = new InMemorySupabase();
  const gateway = createWorkflowDeviceGateway(supabase);

  const session = await gateway.createEvidenceUploadSession!(
    identity,
    await photoSessionCommand(),
  );

  assertEquals(session?.id, assetId);
  assertEquals(session?.upload_id, uploadId);
  assertEquals(session?.created, true);
  assertEquals(session?.received_chunks, []);
  assertEquals(session?.next_chunk_index, 0);
  assertEquals(supabase.table("workflow_evidence_uploads").length, 1);
});

Deno.test("resumes received chunks across Edge gateway instances and finalizes once", async () => {
  const supabase = new InMemorySupabase();
  const bytes = photoBytes();
  const digest = await sha256Hex(bytes);
  const sessionCommand = await photoSessionCommand();

  const created = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/session", sessionCommand),
    routeGateway(supabase),
  );
  assertEquals(created.status, 201);
  assertEquals((await created.json()).nextChunkIndex, 0);

  const secondChunk = bytes.slice(3);
  const chunkOne = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/chunk", {
      uploadId,
      chunkIndex: 1,
      chunkCount: 2,
      chunkByteSize: secondChunk.length,
      chunkSha256: await sha256Hex(secondChunk),
      dataBase64: base64(secondChunk),
    }),
    routeGateway(supabase),
  );
  assertEquals(chunkOne.status, 200);
  assertEquals((await chunkOne.json()).nextChunkIndex, 0);

  const resumed = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/session", sessionCommand),
    routeGateway(supabase),
  );
  assertEquals(resumed.status, 200);
  assertEquals((await resumed.json()).receivedChunks, [1]);

  const firstChunk = bytes.slice(0, 3);
  const chunkZero = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/chunk", {
      uploadId,
      chunkIndex: 0,
      chunkCount: 2,
      chunkByteSize: firstChunk.length,
      chunkSha256: await sha256Hex(firstChunk),
      dataBase64: base64(firstChunk),
    }),
    routeGateway(supabase),
  );
  assertEquals(chunkZero.status, 200);
  assertEquals((await chunkZero.json()).nextChunkIndex, 2);

  const completed = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/complete", {
      uploadId,
      chunkCount: 2,
      sha256: digest,
    }),
    routeGateway(supabase),
  );
  assertEquals(completed.status, 201);
  assertEquals((await completed.json()).uploadStatus, "synced");
  const finalPath = String(supabase.table("media_assets")[0].file_path);
  assertEquals(Array.from(supabase.objects.get(finalPath) ?? []), Array.from(bytes));

  const duplicateComplete = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/complete", {
      uploadId,
      chunkCount: 2,
      sha256: digest,
    }),
    routeGateway(supabase),
  );
  assertEquals(duplicateComplete.status, 200);
  assertEquals((await duplicateComplete.json()).uploadStatus, "synced");
});

Deno.test("refuses completion while resumable evidence chunks are missing", async () => {
  const supabase = new InMemorySupabase();
  const gateway = createWorkflowDeviceGateway(supabase);
  const bytes = photoBytes();
  await gateway.createEvidenceUploadSession!(identity, await photoSessionCommand());
  await gateway.storeEvidenceChunk!(identity, await chunkCommand(0, bytes.slice(0, 3)));

  const response = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/complete", {
      uploadId,
      chunkCount: 2,
      sha256: await sha256Hex(bytes),
    }),
    routeGateway(supabase),
  );

  assertEquals(response.status, 409);
  assertEquals((await response.json()).error, "workflow_evidence_chunks_missing");
  assertEquals(supabase.table("media_assets")[0].upload_status, "uploading");
  assertEquals(supabase.table("workflow_evidence_parts").length, 1);
});

Deno.test("retries temporary part cleanup after evidence finalization", async () => {
  const supabase = new InMemorySupabase();
  const gateway = createWorkflowDeviceGateway(supabase);
  const bytes = photoBytes();
  const digest = await sha256Hex(bytes);
  await gateway.createEvidenceUploadSession!(identity, await photoSessionCommand());
  await gateway.storeEvidenceChunk!(identity, await chunkCommand(0, bytes.slice(0, 3)));
  await gateway.storeEvidenceChunk!(identity, await chunkCommand(1, bytes.slice(3)));
  supabase.removeFailuresRemaining = 1;

  await assertRejects(
    () => gateway.completeEvidenceUpload!(identity, {
      uploadId,
      chunkCount: 2,
      sha256: digest,
    }),
    WorkflowDeviceError,
    "workflow_evidence_storage_failed",
  );
  assertEquals(supabase.table("workflow_evidence_parts").length, 2);

  const retry = await gateway.completeEvidenceUpload!(identity, {
    uploadId,
    chunkCount: 2,
    sha256: digest,
  });

  assertEquals(retry?.upload_status, "synced");
  assertEquals(retry?.finalized, false);
  assertEquals(supabase.table("workflow_evidence_parts").length, 0);
  assertEquals(
    [...supabase.objects.keys()].filter((path) => path.includes("/.parts/")),
    [],
  );
});

Deno.test("cancels resumable evidence, removes temporary parts, and permits a clean restart", async () => {
  const supabase = new InMemorySupabase();
  const gateway = routeGateway(supabase);
  const bytes = photoBytes();
  const sessionCommand = await photoSessionCommand();

  await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/session", sessionCommand),
    gateway,
  );
  await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/chunk", {
      uploadId,
      chunkIndex: 0,
      chunkCount: 2,
      chunkByteSize: 3,
      chunkSha256: await sha256Hex(bytes.slice(0, 3)),
      dataBase64: base64(bytes.slice(0, 3)),
    }),
    gateway,
  );
  assertEquals(supabase.table("workflow_evidence_parts").length, 1);
  assertEquals(supabase.objects.size, 1);

  const cancelled = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/cancel", { uploadId, assetId }),
    gateway,
  );
  assertEquals(cancelled.status, 200);
  assertEquals((await cancelled.json()).uploadStatus, "cancelled");
  assertEquals(supabase.table("workflow_evidence_parts").length, 0);
  assertEquals(supabase.objects.size, 0);
  assertEquals(supabase.table("media_assets")[0].upload_status, "cancelled");
  assertEquals(typeof supabase.table("workflow_evidence_uploads")[0].cancelled_at, "string");

  const duplicateCancel = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/cancel", { uploadId, assetId }),
    gateway,
  );
  assertEquals(duplicateCancel.status, 200);
  assertEquals((await duplicateCancel.json()).uploadStatus, "cancelled");

  const rejectedChunk = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/chunk", {
      uploadId,
      chunkIndex: 0,
      chunkCount: 2,
      chunkByteSize: 3,
      chunkSha256: await sha256Hex(bytes.slice(0, 3)),
      dataBase64: base64(bytes.slice(0, 3)),
    }),
    gateway,
  );
  assertEquals(rejectedChunk.status, 409);
  assertEquals((await rejectedChunk.json()).error, "workflow_evidence_upload_cancelled");

  const restarted = await routeWorkflowDevice(
    request("/device-sync/workflows/evidence/session", sessionCommand),
    gateway,
  );
  assertEquals(restarted.status, 200);
  assertEquals((await restarted.json()).uploadStatus, "uploading");
  assertEquals(supabase.table("workflow_evidence_uploads")[0].cancelled_at, null);
});

Deno.test("fails closed and repairs a partially persisted cancellation", async () => {
  const supabase = new InMemorySupabase();
  const gateway = routeGateway(supabase);
  const bytes = photoBytes();
  const sessionCommand = await photoSessionCommand();
  await gateway.createEvidenceUploadSession!(identity, sessionCommand);
  supabase.table("media_assets")[0].upload_status = "cancelled";
  supabase.table("workflow_evidence_uploads")[0].cancelled_at = null;
  const firstChunk = await chunkCommand(0, bytes.slice(0, 3));

  await assertRejects(
    () => gateway.storeEvidenceChunk!(identity, firstChunk),
    WorkflowDeviceError,
    "workflow_evidence_upload_cancelled",
  );

  const restarted = await gateway.createEvidenceUploadSession!(identity, sessionCommand);
  assertEquals(restarted?.upload_status, "uploading");
  assertEquals(supabase.table("workflow_evidence_uploads")[0].cancelled_at, null);
});
