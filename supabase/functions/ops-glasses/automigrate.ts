import { Client } from "postgres";

export type AutomigrateEnv = {
  SUPABASE_DB_URL: string;
  AUTO_MIGRATE: boolean;
};

type TableDefinition = {
  name: string;
  columns: string[];
  constraints?: string[];
  indexes?: string[];
  rls?: boolean;
};

let migratePromise: Promise<void> | null = null;

const enums = [
  {
    name: "public.ops_session_status",
    values: ["running", "completed", "escalated", "aborted"],
  },
  {
    name: "public.ops_task_type",
    values: ["ssh_console_recovery"],
  },
  {
    name: "public.ops_step",
    values: [
      "locate_server",
      "inspect_console",
      "run_diagnostic_command",
      "confirm_diagnostic_output",
      "run_recovery_command",
      "verify_remote_access",
      "new_issue_triage",
      "completed",
      "needs_better_photo",
      "needs_human_expert",
    ],
  },
  {
    name: "public.ops_event_action",
    values: [
      "start_task",
      "console_photo_uploaded",
      "diagnostic_output_uploaded",
      "recovery_command_ready",
      "recovery_output_uploaded",
      "voice_intent",
      "remote_probe_requested",
      "escalate",
      "finish_task",
    ],
  },
  {
    name: "public.ai_request_status",
    values: ["success", "failed", "skipped"],
  },
  {
    name: "public.ai_decision_result_type",
    values: ["instruction", "recognition_problem", "network_error", "remote_probe", "completed", "human_suggested"],
  },
  {
    name: "public.ai_feedback_code",
    values: [
      "wrong_target",
      "unclear_photo",
      "insufficient_info",
      "voice_unclear",
      "image_voice_conflict",
      "ai_unavailable",
      "network_error",
    ],
  },
  {
    name: "public.remote_probe_result_type",
    values: ["recovered", "same_issue_unresolved", "new_issue_detected", "needs_human_expert"],
  },
  {
    name: "public.voice_intent",
    values: ["start_task", "confirm_done", "retake", "escalate", "describe_scene", "unknown"],
  },
];

const tables: TableDefinition[] = [
  {
    name: "public.ops_assets",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "asset_tag text not null unique",
      "display_name text not null",
      "host text not null",
      "ssh_port integer not null default 22 check (ssh_port > 0 and ssh_port < 65536)",
      "app_port integer check (app_port > 0 and app_port < 65536)",
      "metadata jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
      "updated_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ops_assets_asset_tag_idx on public.ops_assets(asset_tag)"],
    rls: true,
  },
  {
    name: "public.safe_commands",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "command_key text not null unique",
      "command_text text not null unique",
      "description text not null default ''",
      "task_type public.ops_task_type not null default 'ssh_console_recovery'",
      "enabled boolean not null default true",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists safe_commands_enabled_idx on public.safe_commands(enabled)"],
    rls: true,
  },
  {
    name: "public.ops_sessions",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "task_type public.ops_task_type not null default 'ssh_console_recovery'",
      "target_asset_id uuid references public.ops_assets(id)",
      "target_asset text not null",
      "target_host text not null",
      "status public.ops_session_status not null default 'running'",
      "current_step public.ops_step not null default 'locate_server'",
      "operator_label text",
      "last_instruction text not null default ''",
      "metadata jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
      "updated_at timestamptz not null default now()",
      "completed_at timestamptz",
    ],
    indexes: [
      "create index if not exists ops_sessions_status_idx on public.ops_sessions(status)",
      "create index if not exists ops_sessions_created_at_idx on public.ops_sessions(created_at desc)",
    ],
    rls: true,
  },
  {
    name: "public.ops_events",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "step public.ops_step not null",
      "action public.ops_event_action not null",
      "image_id uuid",
      "voice_input_id uuid",
      "instruction_text text not null default ''",
      "payload jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ops_events_session_id_created_at_idx on public.ops_events(session_id, created_at desc)"],
    rls: true,
  },
  {
    name: "public.ops_images",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "event_id uuid references public.ops_events(id) on delete set null",
      "bucket text not null default 'ops-glasses-captures'",
      "file_path text not null unique",
      "image_bytes integer not null default 0 check (image_bytes >= 0)",
      "image_kind text not null default 'console'",
      "content_type text not null default 'image/jpeg'",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ops_images_session_id_idx on public.ops_images(session_id)"],
    rls: true,
  },
  {
    name: "public.ai_requests",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid references public.ops_sessions(id) on delete cascade",
      "image_id uuid references public.ops_images(id) on delete set null",
      "provider text not null default 'openai'",
      "model text not null",
      "prompt_version text not null",
      "status public.ai_request_status not null",
      "latency_ms integer check (latency_ms is null or latency_ms >= 0)",
      "error_message text",
      "raw_response jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
    ],
    rls: true,
  },
  {
    name: "public.ai_observations",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "image_id uuid references public.ops_images(id) on delete set null",
      "ai_request_id uuid references public.ai_requests(id) on delete set null",
      "screen_type text not null",
      "recognized_text text not null default ''",
      "workflow_signal text not null",
      "risk_level text not null default 'low'",
      "confidence numeric(4, 3) check (confidence is null or (confidence >= 0 and confidence <= 1))",
      "raw_json jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ai_observations_session_id_idx on public.ai_observations(session_id, created_at desc)"],
    rls: true,
  },
  {
    name: "public.voice_inputs",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "event_id uuid references public.ops_events(id) on delete set null",
      "bucket text not null default 'ops-glasses-captures'",
      "file_path text",
      "audio_bytes integer not null default 0 check (audio_bytes >= 0)",
      "audio_format text not null default 'm4a'",
      "transcript text not null default ''",
      "voice_intent public.voice_intent not null default 'unknown'",
      "confidence numeric(4, 3) check (confidence is null or (confidence >= 0 and confidence <= 1))",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists voice_inputs_session_id_idx on public.voice_inputs(session_id, created_at desc)"],
    rls: true,
  },
  {
    name: "public.ai_context_bundles",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "image_id uuid references public.ops_images(id) on delete set null",
      "voice_input_id uuid references public.voice_inputs(id) on delete set null",
      "current_step public.ops_step not null",
      "task_goal text not null default '指导现场人员恢复服务器 SSH 远程访问'",
      "transcript text not null default ''",
      "context_json jsonb not null default '{}'::jsonb",
      "context_version text not null default 'air3-v2-ai-brain-v1'",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ai_context_bundles_session_id_idx on public.ai_context_bundles(session_id, created_at desc)"],
    rls: true,
  },
  {
    name: "public.ai_decisions",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "context_bundle_id uuid references public.ai_context_bundles(id) on delete set null",
      "ai_request_id uuid references public.ai_requests(id) on delete set null",
      "result_type public.ai_decision_result_type not null",
      "feedback_code public.ai_feedback_code",
      "step public.ops_step not null",
      "safe_command_key text",
      "display_title text not null",
      "display_text text not null",
      "full_text text not null default ''",
      "display_pages jsonb not null default '[]'::jsonb check (jsonb_typeof(display_pages) = 'array')",
      "text_overflow_mode text not null default 'single' check (text_overflow_mode in ('single', 'paged'))",
      "page_count integer not null default 1 check (page_count >= 1)",
      "display_hint text not null",
      "human_escalation_suggestion boolean not null default false",
      "raw_json jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists ai_decisions_session_id_idx on public.ai_decisions(session_id, created_at desc)"],
    rls: true,
  },
  {
    name: "public.remote_probes",
    columns: [
      "id uuid primary key default gen_random_uuid()",
      "session_id uuid not null references public.ops_sessions(id) on delete cascade",
      "host text not null",
      "ssh_port integer not null default 22",
      "app_port integer",
      "ping_reachable boolean not null default false",
      "ssh_reachable boolean not null default false",
      "app_port_reachable boolean",
      "result_type public.remote_probe_result_type not null",
      "summary text not null",
      "raw_json jsonb not null default '{}'::jsonb",
      "created_at timestamptz not null default now()",
    ],
    indexes: ["create index if not exists remote_probes_session_id_idx on public.remote_probes(session_id, created_at desc)"],
    rls: true,
  },
];

export async function ensureSchema(env: AutomigrateEnv): Promise<void> {
  if (!env.AUTO_MIGRATE) return;
  if (!env.SUPABASE_DB_URL) {
    throw new Error("SUPABASE_DB_URL is required when AUTO_MIGRATE=true");
  }

  migratePromise ??= runAutomigrate(env.SUPABASE_DB_URL);
  await migratePromise;
}

async function runAutomigrate(databaseUrl: string): Promise<void> {
  const client = new Client(databaseUrl);
  await client.connect();
  try {
    await client.queryArray("create extension if not exists pgcrypto");
    for (const item of enums) {
      await createEnum(client, item.name, item.values);
    }
    for (const table of tables) {
      await createTable(client, table);
    }
    await addLateForeignKeys(client);
    await createStorageBucket(client);
    await seedData(client);
  } finally {
    await client.end();
  }
}

async function createEnum(client: Client, enumName: string, values: string[]): Promise<void> {
  const valueList = values.map((value) => `'${value.replaceAll("'", "''")}'`).join(", ");
  await client.queryArray(`
    do $$
    begin
      create type ${enumName} as enum (${valueList});
    exception
      when duplicate_object then null;
    end
    $$;
  `);

  for (const value of values) {
    await client.queryArray(`
      alter type ${enumName}
      add value if not exists '${value.replaceAll("'", "''")}';
    `);
  }
}

async function createTable(client: Client, table: TableDefinition): Promise<void> {
  await client.queryArray(`
    create table if not exists ${table.name} (
      ${[...table.columns, ...(table.constraints ?? [])].join(",\n      ")}
    );
  `);
  if (table.rls) {
    await client.queryArray(`alter table ${table.name} enable row level security;`);
    await createServiceRolePolicy(client, table.name);
  }
  for (const index of table.indexes ?? []) {
    await client.queryArray(index);
  }
}

async function createServiceRolePolicy(client: Client, tableName: string): Promise<void> {
  const policyName = `service_role_manage_${tableName.replace(/^public\./, "")}`;
  await client.queryArray(`
    do $$
    begin
      create policy ${quoteIdent(policyName)}
      on ${tableName} for all
      to service_role
      using (true)
      with check (true);
    exception
      when duplicate_object then null;
    end
    $$;
  `);
}

async function addLateForeignKeys(client: Client): Promise<void> {
  await addConstraint(client, "public.ops_events", "ops_events_image_id_fkey", `
    foreign key (image_id) references public.ops_images(id) on delete set null
  `);
  await addConstraint(client, "public.ops_events", "ops_events_voice_input_id_fkey", `
    foreign key (voice_input_id) references public.voice_inputs(id) on delete set null
  `);
}

async function addConstraint(client: Client, tableName: string, constraintName: string, sql: string): Promise<void> {
  await client.queryArray(`
    do $$
    begin
      alter table ${tableName}
      add constraint ${quoteIdent(constraintName)}
      ${sql};
    exception
      when duplicate_object then null;
    end
    $$;
  `);
}

async function createStorageBucket(client: Client): Promise<void> {
  await client.queryArray(`
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values (
      'ops-glasses-captures',
      'ops-glasses-captures',
      false,
      10485760,
      array['image/jpeg', 'image/png', 'image/webp', 'audio/mp4', 'audio/mpeg', 'audio/wav', 'audio/webm']
    )
    on conflict (id) do update set
      public = false,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;
  `);

  await client.queryArray(`
    do $$
    begin
      create policy "service_role_manage_ops_glasses_captures"
      on storage.objects for all
      to service_role
      using (bucket_id = 'ops-glasses-captures')
      with check (bucket_id = 'ops-glasses-captures');
    exception
      when duplicate_object then null;
    end
    $$;
  `);
}

async function seedData(client: Client): Promise<void> {
  await client.queryArray(`
    insert into public.safe_commands (command_key, command_text, description)
    values
      ('ssh_status', 'sudo systemctl status ssh --no-pager', '检查 Ubuntu/Debian SSH 服务状态'),
      ('ssh_start', 'sudo systemctl start ssh', '启动 Ubuntu/Debian SSH 服务'),
      ('sshd_status', 'sudo systemctl status sshd --no-pager', '检查 RHEL/CentOS SSHD 服务状态'),
      ('sshd_start', 'sudo systemctl start sshd', '启动 RHEL/CentOS SSHD 服务')
    on conflict (command_key) do update set
      command_text = excluded.command_text,
      description = excluded.description,
      enabled = true;
  `);
}

function quoteIdent(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}
