create table if not exists public.ops_equipment (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  equipment_key text not null,
  system text not null default '',
  brand text not null default '',
  model text not null default '',
  quantity integer not null default 1 check (quantity >= 0),
  status text not null default 'normal' check (status in ('normal', 'attention', 'maintenance', 'decommissioned')),
  last_inspection_at timestamptz,
  fault_count integer not null default 0 check (fault_count >= 0),
  repair_count integer not null default 0 check (repair_count >= 0),
  key_parameter text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, equipment_key)
);

create table if not exists public.ops_equipment_projects (
  organization_id uuid not null references public.organizations(id) on delete cascade,
  equipment_id uuid not null references public.ops_equipment(id) on delete cascade,
  project_id uuid not null references public.ops_projects(id) on delete cascade,
  linked_at timestamptz not null default now(),
  primary key (equipment_id, project_id)
);

create index if not exists ops_equipment_org_idx
  on public.ops_equipment(organization_id, system, equipment_key);
create index if not exists ops_equipment_projects_project_idx
  on public.ops_equipment_projects(organization_id, project_id);

alter table public.ops_equipment enable row level security;
alter table public.ops_equipment_projects enable row level security;

drop policy if exists "organization members can read equipment" on public.ops_equipment;
create policy "organization members can read equipment"
on public.ops_equipment for select to authenticated
using (public.is_ops_organization_member(organization_id));

drop policy if exists "organization members can read equipment project links" on public.ops_equipment_projects;
create policy "organization members can read equipment project links"
on public.ops_equipment_projects for select to authenticated
using (public.is_ops_organization_member(organization_id));
