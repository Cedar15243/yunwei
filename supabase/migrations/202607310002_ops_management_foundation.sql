create table public.organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  slug text not null unique,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.ops_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  display_name text not null default '',
  role text not null check (role in ('super_admin', 'ops_admin', 'field_engineer', 'remote_expert', 'viewer')),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.glasses_devices (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  device_key text not null unique,
  display_name text not null,
  assigned_profile_id uuid references public.ops_profiles(id) on delete set null,
  status text not null default 'offline' check (status in ('online', 'offline', 'disabled')),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.ops_projects (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  created_by uuid not null references public.ops_profiles(id),
  local_project_id text not null,
  title text not null default '',
  status text not null default 'active' check (status in ('active', 'closed', 'archived')),
  summary text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, local_project_id)
);

create table public.maintenance_tasks (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  project_id uuid not null references public.ops_projects(id) on delete cascade,
  created_by uuid not null references public.ops_profiles(id),
  local_task_id text not null,
  title text not null default '',
  status text not null check (status in ('active', 'completed', 'closed', 'aborted')),
  current_step text not null default '',
  skill_version text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz,
  unique (organization_id, local_task_id)
);

create table public.task_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  task_id uuid not null references public.maintenance_tasks(id) on delete cascade,
  actor_profile_id uuid references public.ops_profiles(id) on delete set null,
  device_id uuid references public.glasses_devices(id) on delete set null,
  idempotency_key text not null,
  event_type text not null check (event_type in (
    'task_started', 'user_message', 'voice_transcript', 'photo_captured',
    'video_recorded', 'ai_response', 'step_changed', 'task_completed',
    'task_closed', 'media_upload_failed'
  )),
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create unique index task_events_idempotency_idx on public.task_events(task_id, idempotency_key);
create index task_events_task_created_idx on public.task_events(task_id, created_at);

create table public.media_assets (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  task_id uuid not null references public.maintenance_tasks(id) on delete cascade,
  event_id uuid references public.task_events(id) on delete set null,
  kind text not null check (kind in ('photo', 'video', 'audio', 'annotation')),
  content_type text not null,
  storage_bucket text not null default 'ops-glasses-captures',
  file_path text not null unique,
  sha256 text not null default '',
  byte_size bigint not null default 0 check (byte_size >= 0),
  upload_status text not null check (upload_status in ('local_saved', 'queued', 'uploading', 'synced', 'failed', 'deleted')),
  failure_reason text not null default '',
  captured_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index media_assets_task_created_idx on public.media_assets(task_id, created_at);
create index media_assets_status_idx on public.media_assets(upload_status);

create table public.audit_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  actor_profile_id uuid references public.ops_profiles(id) on delete set null,
  action text not null,
  target_type text not null,
  target_id text not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index audit_events_org_created_idx on public.audit_events(organization_id, created_at desc);

create or replace function public.is_ops_organization_member(target_organization_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.ops_profiles profile
    where profile.id = auth.uid()
      and profile.organization_id = target_organization_id
      and profile.active = true
  );
$$;

grant execute on function public.is_ops_organization_member(uuid) to authenticated;

alter table public.organizations enable row level security;
alter table public.ops_profiles enable row level security;
alter table public.glasses_devices enable row level security;
alter table public.ops_projects enable row level security;
alter table public.maintenance_tasks enable row level security;
alter table public.task_events enable row level security;
alter table public.media_assets enable row level security;
alter table public.audit_events enable row level security;

create policy "organization members can read their organization"
on public.organizations for select to authenticated
using (public.is_ops_organization_member(id));

create policy "organization members can read profiles"
on public.ops_profiles for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read glasses devices"
on public.glasses_devices for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read projects"
on public.ops_projects for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read maintenance tasks"
on public.maintenance_tasks for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read task events"
on public.task_events for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read media assets"
on public.media_assets for select to authenticated
using (public.is_ops_organization_member(organization_id));

create policy "organization members can read audit events"
on public.audit_events for select to authenticated
using (public.is_ops_organization_member(organization_id));

update storage.buckets
set file_size_limit = greatest(coalesce(file_size_limit, 0), 104857600),
    allowed_mime_types = array[
      'image/jpeg', 'image/png', 'image/webp',
      'video/mp4',
      'audio/mp4', 'audio/mpeg', 'audio/wav', 'audio/webm'
    ]
where id = 'ops-glasses-captures';
