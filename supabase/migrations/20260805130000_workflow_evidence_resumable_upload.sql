create table if not exists public.workflow_evidence_uploads (
  upload_id uuid primary key default gen_random_uuid(),
  asset_id uuid not null unique references public.media_assets(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  device_id text not null,
  chunk_size integer not null check (chunk_size > 0 and chunk_size <= 1048576),
  chunk_count integer not null check (chunk_count > 0 and chunk_count <= 10000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

create index if not exists workflow_evidence_uploads_device_idx
  on public.workflow_evidence_uploads(device_id, updated_at desc);

create table if not exists public.workflow_evidence_parts (
  upload_id uuid not null references public.workflow_evidence_uploads(upload_id) on delete cascade,
  asset_id uuid not null references public.media_assets(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  chunk_index integer not null check (chunk_index >= 0),
  byte_size integer not null check (byte_size > 0 and byte_size <= 1048576),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  storage_path text not null unique,
  created_at timestamptz not null default now(),
  primary key (upload_id, chunk_index)
);

create index if not exists workflow_evidence_parts_asset_idx
  on public.workflow_evidence_parts(asset_id, chunk_index);

alter table public.workflow_evidence_uploads enable row level security;
alter table public.workflow_evidence_parts enable row level security;

drop policy if exists workflow_evidence_uploads_service_role on public.workflow_evidence_uploads;
create policy workflow_evidence_uploads_service_role
  on public.workflow_evidence_uploads for all to service_role using (true) with check (true);

drop policy if exists workflow_evidence_parts_service_role on public.workflow_evidence_parts;
create policy workflow_evidence_parts_service_role
  on public.workflow_evidence_parts for all to service_role using (true) with check (true);
