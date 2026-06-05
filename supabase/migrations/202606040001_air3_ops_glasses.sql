create extension if not exists "pgcrypto";

create type public.ops_session_status as enum (
  'running',
  'completed',
  'escalated',
  'aborted'
);

create type public.ops_task_type as enum (
  'ssh_console_recovery'
);

create type public.ops_step as enum (
  'locate_server',
  'inspect_console',
  'run_diagnostic_command',
  'confirm_diagnostic_output',
  'run_recovery_command',
  'verify_remote_access',
  'new_issue_triage',
  'completed',
  'needs_better_photo',
  'needs_human_expert'
);

create type public.ops_event_action as enum (
  'start_task',
  'console_photo_uploaded',
  'diagnostic_output_uploaded',
  'recovery_command_ready',
  'recovery_output_uploaded',
  'voice_intent',
  'remote_probe_requested',
  'escalate',
  'finish_task'
);

create type public.ai_request_status as enum (
  'success',
  'failed',
  'skipped'
);

create type public.remote_probe_result_type as enum (
  'recovered',
  'same_issue_unresolved',
  'new_issue_detected',
  'needs_human_expert'
);

create type public.voice_intent as enum (
  'start_task',
  'confirm_done',
  'retake',
  'escalate',
  'describe_scene',
  'unknown'
);

create table public.ops_assets (
  id uuid primary key default gen_random_uuid(),
  asset_tag text not null unique,
  display_name text not null,
  host text not null,
  ssh_port integer not null default 22 check (ssh_port > 0 and ssh_port < 65536),
  app_port integer check (app_port > 0 and app_port < 65536),
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.safe_commands (
  id uuid primary key default gen_random_uuid(),
  command_key text not null unique,
  command_text text not null unique,
  description text not null default '',
  task_type public.ops_task_type not null default 'ssh_console_recovery',
  enabled boolean not null default true,
  created_at timestamptz not null default now()
);

create table public.ops_sessions (
  id uuid primary key default gen_random_uuid(),
  task_type public.ops_task_type not null default 'ssh_console_recovery',
  target_asset_id uuid references public.ops_assets(id),
  target_asset text not null,
  target_host text not null,
  status public.ops_session_status not null default 'running',
  current_step public.ops_step not null default 'locate_server',
  operator_label text,
  last_instruction text not null default '',
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

create table public.ops_events (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.ops_sessions(id) on delete cascade,
  step public.ops_step not null,
  action public.ops_event_action not null,
  image_id uuid,
  voice_input_id uuid,
  instruction_text text not null default '',
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.ops_images (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.ops_sessions(id) on delete cascade,
  event_id uuid references public.ops_events(id) on delete set null,
  bucket text not null default 'ops-glasses-captures',
  file_path text not null unique,
  image_bytes integer not null default 0 check (image_bytes >= 0),
  image_kind text not null default 'console',
  content_type text not null default 'image/jpeg',
  created_at timestamptz not null default now()
);

alter table public.ops_events
  add constraint ops_events_image_id_fkey
  foreign key (image_id) references public.ops_images(id) on delete set null;

create table public.ai_requests (
  id uuid primary key default gen_random_uuid(),
  session_id uuid references public.ops_sessions(id) on delete cascade,
  image_id uuid references public.ops_images(id) on delete set null,
  provider text not null default 'openai',
  model text not null,
  prompt_version text not null,
  status public.ai_request_status not null,
  latency_ms integer check (latency_ms is null or latency_ms >= 0),
  error_message text,
  raw_response jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.ai_observations (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.ops_sessions(id) on delete cascade,
  image_id uuid references public.ops_images(id) on delete set null,
  ai_request_id uuid references public.ai_requests(id) on delete set null,
  screen_type text not null,
  recognized_text text not null default '',
  workflow_signal text not null,
  risk_level text not null default 'low',
  confidence numeric(4, 3) check (confidence is null or (confidence >= 0 and confidence <= 1)),
  raw_json jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.voice_inputs (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.ops_sessions(id) on delete cascade,
  event_id uuid references public.ops_events(id) on delete set null,
  bucket text not null default 'ops-glasses-captures',
  file_path text,
  audio_bytes integer not null default 0 check (audio_bytes >= 0),
  audio_format text not null default 'm4a',
  transcript text not null default '',
  voice_intent public.voice_intent not null default 'unknown',
  confidence numeric(4, 3) check (confidence is null or (confidence >= 0 and confidence <= 1)),
  created_at timestamptz not null default now()
);

alter table public.ops_events
  add constraint ops_events_voice_input_id_fkey
  foreign key (voice_input_id) references public.voice_inputs(id) on delete set null;

create table public.remote_probes (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references public.ops_sessions(id) on delete cascade,
  host text not null,
  ssh_port integer not null default 22,
  app_port integer,
  ping_reachable boolean not null default false,
  ssh_reachable boolean not null default false,
  app_port_reachable boolean,
  result_type public.remote_probe_result_type not null,
  summary text not null,
  raw_json jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index ops_sessions_status_idx on public.ops_sessions(status);
create index ops_sessions_created_at_idx on public.ops_sessions(created_at desc);
create index ops_events_session_id_created_at_idx on public.ops_events(session_id, created_at desc);
create index ops_images_session_id_idx on public.ops_images(session_id);
create index ai_observations_session_id_idx on public.ai_observations(session_id, created_at desc);
create index voice_inputs_session_id_idx on public.voice_inputs(session_id, created_at desc);
create index remote_probes_session_id_idx on public.remote_probes(session_id, created_at desc);

alter table public.ops_assets enable row level security;
alter table public.safe_commands enable row level security;
alter table public.ops_sessions enable row level security;
alter table public.ops_events enable row level security;
alter table public.ops_images enable row level security;
alter table public.ai_requests enable row level security;
alter table public.ai_observations enable row level security;
alter table public.voice_inputs enable row level security;
alter table public.remote_probes enable row level security;

create policy "service role can manage ops assets"
on public.ops_assets for all
to service_role
using (true)
with check (true);

create policy "service role can manage safe commands"
on public.safe_commands for all
to service_role
using (true)
with check (true);

create policy "service role can manage ops sessions"
on public.ops_sessions for all
to service_role
using (true)
with check (true);

create policy "service role can manage ops events"
on public.ops_events for all
to service_role
using (true)
with check (true);

create policy "service role can manage ops images"
on public.ops_images for all
to service_role
using (true)
with check (true);

create policy "service role can manage ai requests"
on public.ai_requests for all
to service_role
using (true)
with check (true);

create policy "service role can manage ai observations"
on public.ai_observations for all
to service_role
using (true)
with check (true);

create policy "service role can manage voice inputs"
on public.voice_inputs for all
to service_role
using (true)
with check (true);

create policy "service role can manage remote probes"
on public.remote_probes for all
to service_role
using (true)
with check (true);

insert into public.ops_assets (asset_tag, display_name, host, ssh_port, app_port)
values ('ASSET-CONSOLE-001', 'SSH console recovery demo server', '192.168.1.50', 22, null)
on conflict (asset_tag) do nothing;

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

create policy "service role can manage ops glasses captures"
on storage.objects for all
to service_role
using (bucket_id = 'ops-glasses-captures')
with check (bucket_id = 'ops-glasses-captures');
