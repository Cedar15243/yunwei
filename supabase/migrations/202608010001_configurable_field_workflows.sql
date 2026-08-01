create schema if not exists private;
revoke all on schema private from public, anon;

create unique index if not exists ops_profiles_organization_id_id_idx
on public.ops_profiles(organization_id, id);

create unique index if not exists glasses_devices_organization_id_id_idx
on public.glasses_devices(organization_id, id);

create unique index if not exists ops_projects_organization_id_id_idx
on public.ops_projects(organization_id, id);

create unique index if not exists maintenance_tasks_organization_id_id_idx
on public.maintenance_tasks(organization_id, id);

create unique index if not exists maintenance_tasks_organization_project_id_idx
on public.maintenance_tasks(organization_id, project_id, id);

create table public.field_apps (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  app_key text not null check (app_key ~ '^[a-z][a-z0-9_-]{2,63}$'),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  description text not null default '',
  icon_key text,
  entry_mode text not null default 'both'
    check (entry_mode in ('independent', 'work_order', 'both')),
  status text not null default 'draft'
    check (status in ('draft', 'review_pending', 'published', 'deprecated', 'archived')),
  default_workflow_definition_id uuid,
  created_by uuid not null,
  updated_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, app_key),
  constraint field_apps_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint field_apps_updated_by_fk
    foreign key (organization_id, updated_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.workflow_definitions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  field_app_id uuid not null,
  workflow_key text not null check (workflow_key ~ '^[a-z][a-z0-9_-]{2,63}$'),
  title text not null check (char_length(btrim(title)) between 1 and 160),
  description text not null default '',
  status text not null default 'draft'
    check (status in ('draft', 'validating', 'review_pending', 'published', 'deprecated', 'archived')),
  schema_version integer not null default 1 check (schema_version > 0),
  draft_graph jsonb not null default '{}'::jsonb
    check (jsonb_typeof(draft_graph) = 'object'),
  latest_version_number integer not null default 0 check (latest_version_number >= 0),
  created_by uuid not null,
  updated_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (field_app_id, workflow_key),
  constraint workflow_definitions_field_app_fk
    foreign key (organization_id, field_app_id)
    references public.field_apps(organization_id, id) on delete cascade,
  constraint workflow_definitions_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_definitions_updated_by_fk
    foreign key (organization_id, updated_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create unique index workflow_definitions_org_app_id_idx
on public.workflow_definitions(organization_id, field_app_id, id);

alter table public.field_apps
add constraint field_apps_default_workflow_definition_fk
foreign key (organization_id, id, default_workflow_definition_id)
references public.workflow_definitions(organization_id, field_app_id, id) on delete restrict;

create table public.workflow_versions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  workflow_definition_id uuid not null,
  version_number integer not null check (version_number > 0),
  status text not null default 'published'
    check (status in ('published', 'deprecated', 'revoked', 'archived')),
  schema_version integer not null check (schema_version > 0),
  execution_package jsonb not null
    check (jsonb_typeof(execution_package) = 'object'),
  content_sha256 text not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
  package_signature text not null check (char_length(package_signature) between 32 and 4096),
  signature_key_id text not null check (char_length(btrim(signature_key_id)) between 1 and 160),
  required_capabilities text[] not null default '{}'::text[],
  min_app_version_code integer not null default 9000 check (min_app_version_code > 0),
  published_by uuid not null,
  published_at timestamptz not null default now(),
  status_changed_at timestamptz not null default now(),
  unique (workflow_definition_id, version_number),
  unique (organization_id, id),
  constraint workflow_versions_definition_fk
    foreign key (organization_id, workflow_definition_id)
    references public.workflow_definitions(organization_id, id) on delete restrict,
  constraint workflow_versions_published_by_fk
    foreign key (organization_id, published_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.workflow_binding_rules (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  rule_key text not null check (rule_key ~ '^[a-z][a-z0-9_-]{2,63}$'),
  source text not null
    check (source in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')),
  mode text not null check (mode in ('required', 'optional', 'none')),
  workflow_version_id uuid,
  match_conditions jsonb not null default '[]'::jsonb
    check (jsonb_typeof(match_conditions) = 'array'),
  enabled boolean not null default true,
  active_from timestamptz,
  active_until timestamptz,
  reason text not null default '',
  created_by uuid not null,
  updated_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, rule_key),
  check (active_until is null or active_from is null or active_until > active_from),
  check (
    (mode = 'none' and workflow_version_id is null)
    or (mode in ('required', 'optional') and workflow_version_id is not null)
  ),
  constraint workflow_binding_rules_version_fk
    foreign key (organization_id, workflow_version_id)
    references public.workflow_versions(organization_id, id) on delete restrict,
  constraint workflow_binding_rules_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_binding_rules_updated_by_fk
    foreign key (organization_id, updated_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.work_orders (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  source_system text not null default 'internal'
    check (char_length(btrim(source_system)) between 1 and 80),
  external_work_order_id text,
  external_revision text,
  project_id uuid,
  assigned_profile_id uuid,
  title text not null check (char_length(btrim(title)) between 1 and 240),
  description text not null default '',
  customer_id text,
  work_order_type text,
  asset_id text,
  asset_category text,
  asset_brand text,
  asset_model text,
  fault_type text,
  priority text,
  risk_level text,
  tags text[] not null default '{}'::text[],
  status text not null default 'received'
    check (status in ('received', 'accepted', 'in_progress', 'completed', 'closed', 'cancelled')),
  binding_mode text not null default 'none'
    check (binding_mode in ('required', 'optional', 'none')),
  binding_status text not null default 'unresolved'
    check (binding_status in ('unresolved', 'resolved', 'conflict', 'unsupported')),
  binding_source text,
  bound_workflow_version_id uuid,
  binding_evidence jsonb not null default '{}'::jsonb
    check (jsonb_typeof(binding_evidence) = 'object'),
  due_at timestamptz,
  received_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz,
  closed_at timestamptz,
  unique (organization_id, id),
  check (binding_source is null or binding_source in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')),
  check (
    (binding_mode = 'none' and bound_workflow_version_id is null)
    or (binding_mode in ('required', 'optional') and bound_workflow_version_id is not null)
  ),
  constraint work_orders_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint work_orders_assigned_profile_fk
    foreign key (organization_id, assigned_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint work_orders_bound_version_fk
    foreign key (organization_id, bound_workflow_version_id)
    references public.workflow_versions(organization_id, id) on delete restrict
);

create sequence public.workflow_assignment_sequence
as bigint minvalue 1 start with 1 increment by 1 no cycle;

create table public.workflow_assignments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  work_order_id uuid not null,
  project_id uuid,
  assigned_profile_id uuid not null,
  assigned_device_id uuid,
  binding_rule_id uuid,
  workflow_version_id uuid,
  mode text not null check (mode in ('required', 'optional', 'none')),
  status text not null default 'queued'
    check (status in ('queued', 'notified', 'delivered', 'verified', 'ready', 'active', 'completed', 'failed', 'revoked')),
  delivery_sequence bigint not null default nextval('public.workflow_assignment_sequence'::regclass),
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  resolution_source text not null,
  resolution_evidence jsonb not null default '{}'::jsonb
    check (jsonb_typeof(resolution_evidence) = 'object'),
  assigned_by uuid,
  assigned_at timestamptz not null default now(),
  notified_at timestamptz,
  delivered_at timestamptz,
  verified_at timestamptz,
  ready_at timestamptz,
  activated_at timestamptz,
  completed_at timestamptz,
  failed_at timestamptz,
  revoked_at timestamptz,
  failure_stage text,
  failure_reason text,
  revoked_reason text,
  unique (organization_id, id),
  unique (organization_id, delivery_sequence),
  unique (organization_id, idempotency_key),
  unique (organization_id, id, work_order_id, project_id, assigned_profile_id, workflow_version_id),
  check (resolution_source in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')),
  check (
    (mode = 'none' and workflow_version_id is null and status in ('ready', 'completed', 'revoked'))
    or (mode in ('required', 'optional') and workflow_version_id is not null)
  ),
  constraint workflow_assignments_work_order_fk
    foreign key (organization_id, work_order_id)
    references public.work_orders(organization_id, id) on delete cascade,
  constraint workflow_assignments_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint workflow_assignments_profile_fk
    foreign key (organization_id, assigned_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_assignments_device_fk
    foreign key (organization_id, assigned_device_id)
    references public.glasses_devices(organization_id, id) on delete restrict,
  constraint workflow_assignments_rule_fk
    foreign key (organization_id, binding_rule_id)
    references public.workflow_binding_rules(organization_id, id) on delete restrict,
  constraint workflow_assignments_version_fk
    foreign key (organization_id, workflow_version_id)
    references public.workflow_versions(organization_id, id) on delete restrict,
  constraint workflow_assignments_actor_fk
    foreign key (organization_id, assigned_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.workflow_executions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  assignment_id uuid not null,
  work_order_id uuid not null,
  project_id uuid not null,
  task_id uuid not null,
  workflow_version_id uuid not null,
  operator_profile_id uuid not null,
  device_id uuid not null,
  status text not null default 'not_started'
    check (status in ('not_started', 'active', 'paused', 'waiting_network', 'waiting_expert', 'blocked', 'completed', 'cancelled')),
  current_node_id text,
  runtime_snapshot jsonb not null default '{}'::jsonb
    check (jsonb_typeof(runtime_snapshot) = 'object'),
  started_at timestamptz,
  paused_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, assignment_id),
  unique (organization_id, id, operator_profile_id, project_id),
  constraint workflow_executions_assignment_fk
    foreign key (
      organization_id,
      assignment_id,
      work_order_id,
      project_id,
      operator_profile_id,
      workflow_version_id
    ) references public.workflow_assignments(
      organization_id,
      id,
      work_order_id,
      project_id,
      assigned_profile_id,
      workflow_version_id
    ) on delete restrict,
  constraint workflow_executions_work_order_fk
    foreign key (organization_id, work_order_id)
    references public.work_orders(organization_id, id) on delete restrict,
  constraint workflow_executions_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint workflow_executions_task_fk
    foreign key (organization_id, project_id, task_id)
    references public.maintenance_tasks(organization_id, project_id, id) on delete restrict,
  constraint workflow_executions_version_fk
    foreign key (organization_id, workflow_version_id)
    references public.workflow_versions(organization_id, id) on delete restrict,
  constraint workflow_executions_operator_fk
    foreign key (organization_id, operator_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_executions_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict
);

create table public.workflow_step_executions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  execution_id uuid not null,
  operator_profile_id uuid not null,
  project_id uuid not null,
  node_id text not null check (char_length(btrim(node_id)) between 1 and 160),
  attempt_number integer not null default 1 check (attempt_number > 0),
  status text not null default 'pending'
    check (status in ('pending', 'active', 'draft_saved', 'waiting_upload', 'waiting_server', 'completed', 'skipped', 'failed')),
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  input_data jsonb not null default '{}'::jsonb
    check (jsonb_typeof(input_data) = 'object'),
  output_data jsonb not null default '{}'::jsonb
    check (jsonb_typeof(output_data) = 'object'),
  evidence_asset_ids uuid[] not null default '{}'::uuid[],
  transition_result jsonb not null default '{}'::jsonb
    check (jsonb_typeof(transition_result) = 'object'),
  failure_code text,
  failure_reason text,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (execution_id, node_id, attempt_number),
  unique (organization_id, idempotency_key),
  constraint workflow_step_executions_execution_fk
    foreign key (organization_id, execution_id, operator_profile_id, project_id)
    references public.workflow_executions(organization_id, id, operator_profile_id, project_id)
    on delete cascade
);

create unique index work_orders_external_id_idx
on public.work_orders(organization_id, source_system, external_work_order_id)
where external_work_order_id is not null;

create unique index workflow_assignments_one_active_order_idx
on public.workflow_assignments(organization_id, work_order_id)
where status not in ('completed', 'failed', 'revoked');

create index field_apps_organization_status_idx
on public.field_apps(organization_id, status, updated_at desc);
create index workflow_definitions_app_status_idx
on public.workflow_definitions(organization_id, field_app_id, status, updated_at desc);
create index workflow_versions_definition_status_idx
on public.workflow_versions(organization_id, workflow_definition_id, status, version_number desc);
create index workflow_binding_rules_resolution_idx
on public.workflow_binding_rules(organization_id, source, enabled, active_from, active_until);
create index workflow_binding_rules_version_idx
on public.workflow_binding_rules(organization_id, workflow_version_id)
where workflow_version_id is not null;
create index work_orders_assignee_status_idx
on public.work_orders(organization_id, assigned_profile_id, status, received_at desc)
where assigned_profile_id is not null;
create index work_orders_project_status_idx
on public.work_orders(organization_id, project_id, status, received_at desc)
where project_id is not null;
create index work_orders_bound_version_idx
on public.work_orders(organization_id, bound_workflow_version_id)
where bound_workflow_version_id is not null;
create index workflow_assignments_assignee_status_idx
on public.workflow_assignments(organization_id, assigned_profile_id, status, delivery_sequence);
create index workflow_assignments_device_status_idx
on public.workflow_assignments(organization_id, assigned_device_id, status, delivery_sequence)
where assigned_device_id is not null;
create index workflow_assignments_version_idx
on public.workflow_assignments(organization_id, workflow_version_id)
where workflow_version_id is not null;
create index workflow_assignments_rule_idx
on public.workflow_assignments(organization_id, binding_rule_id)
where binding_rule_id is not null;
create index workflow_executions_operator_status_idx
on public.workflow_executions(organization_id, operator_profile_id, status, updated_at desc);
create index workflow_executions_project_task_idx
on public.workflow_executions(organization_id, project_id, task_id);
create index workflow_executions_version_idx
on public.workflow_executions(organization_id, workflow_version_id);
create index workflow_executions_device_idx
on public.workflow_executions(organization_id, device_id);
create index workflow_step_executions_execution_status_idx
on public.workflow_step_executions(organization_id, execution_id, status, attempt_number);
create index workflow_step_executions_operator_project_idx
on public.workflow_step_executions(organization_id, operator_profile_id, project_id, status);

create or replace function public.prevent_workflow_version_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'published workflow versions are immutable';
  end if;

  if (
    new.id,
    new.organization_id,
    new.workflow_definition_id,
    new.version_number,
    new.schema_version,
    new.execution_package,
    new.content_sha256,
    new.package_signature,
    new.signature_key_id,
    new.required_capabilities,
    new.min_app_version_code,
    new.published_by,
    new.published_at
  ) is distinct from (
    old.id,
    old.organization_id,
    old.workflow_definition_id,
    old.version_number,
    old.schema_version,
    old.execution_package,
    old.content_sha256,
    old.package_signature,
    old.signature_key_id,
    old.required_capabilities,
    old.min_app_version_code,
    old.published_by,
    old.published_at
  ) then
    raise exception 'published workflow version content is immutable';
  end if;

  if new.status = old.status then
    if new.status_changed_at is distinct from old.status_changed_at then
      raise exception 'workflow version status timestamp requires a status change';
    end if;
    return new;
  end if;

  if not (
    (old.status = 'published' and new.status in ('deprecated', 'revoked', 'archived'))
    or (old.status = 'deprecated' and new.status in ('published', 'revoked', 'archived'))
    or (old.status = 'revoked' and new.status = 'archived')
  ) then
    raise exception 'invalid workflow version status transition';
  end if;
  return new;
end;
$$;

create trigger workflow_versions_immutable
before update or delete on public.workflow_versions
for each row execute function public.prevent_workflow_version_mutation();

create or replace function public.set_workflow_version_status(
  target_version_id uuid,
  new_status text,
  reason text,
  requested_by uuid
)
returns public.workflow_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  previous_version public.workflow_versions;
  updated_version public.workflow_versions;
begin
  if new_status not in ('published', 'deprecated', 'revoked', 'archived') then
    raise exception 'unsupported workflow version status';
  end if;
  if char_length(btrim(coalesce(reason, ''))) < 3 then
    raise exception 'status change reason is required';
  end if;

  select * into previous_version
  from public.workflow_versions
  where id = target_version_id
  for update;
  if previous_version.id is null then
    raise exception 'workflow version not found';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = requested_by
      and profile.organization_id = previous_version.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role in ('super_admin', 'ops_admin')
  ) then
    raise exception 'workflow version status change is not authorized';
  end if;

  update public.workflow_versions
  set status = new_status,
      status_changed_at = now()
  where id = target_version_id
  returning * into updated_version;

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    previous_version.organization_id,
    requested_by,
    'workflow_version.status_changed',
    'workflow_version',
    target_version_id::text,
    jsonb_build_object(
      'previousStatus', previous_version.status,
      'newStatus', updated_version.status,
      'reason', reason
    )
  );

  return updated_version;
end;
$$;

create or replace function private.current_workflow_admin_organization_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select profile.organization_id
  from public.ops_profiles profile
  where profile.id = (select auth.uid())
    and profile.active = true
    and profile.status = 'active'
    and profile.role in ('super_admin', 'ops_admin')
  limit 1;
$$;

create or replace function private.current_workflow_field_profile_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select profile.id
  from public.ops_profiles profile
  where profile.id = (select auth.uid())
    and profile.active = true
    and profile.status = 'active'
    and profile.role = 'field_engineer'
  limit 1;
$$;

create or replace function private.current_workflow_project_ids()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select membership.project_id
  from public.ops_project_memberships membership
  join public.ops_profiles profile
    on profile.id = membership.profile_id
   and profile.organization_id = membership.organization_id
  where profile.id = (select auth.uid())
    and profile.active = true
    and profile.status = 'active'
    and profile.role = 'field_engineer'
    and membership.status = 'active';
$$;

revoke all on function public.prevent_workflow_version_mutation() from public, anon, authenticated;
revoke all on function public.set_workflow_version_status(uuid, text, text, uuid) from public, anon, authenticated;
grant execute on function public.set_workflow_version_status(uuid, text, text, uuid) to service_role;

revoke all on function private.current_workflow_admin_organization_id() from public, anon;
revoke all on function private.current_workflow_field_profile_id() from public, anon;
revoke all on function private.current_workflow_project_ids() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.current_workflow_admin_organization_id() to authenticated;
grant execute on function private.current_workflow_field_profile_id() to authenticated;
grant execute on function private.current_workflow_project_ids() to authenticated;

alter table public.field_apps enable row level security;
alter table public.workflow_definitions enable row level security;
alter table public.workflow_versions enable row level security;
alter table public.workflow_binding_rules enable row level security;
alter table public.work_orders enable row level security;
alter table public.workflow_assignments enable row level security;
alter table public.workflow_executions enable row level security;
alter table public.workflow_step_executions enable row level security;

create policy "workflow admins can read field apps"
on public.field_apps for select to authenticated
using (organization_id = (select private.current_workflow_admin_organization_id()));

create policy "workflow admins can read definitions"
on public.workflow_definitions for select to authenticated
using (organization_id = (select private.current_workflow_admin_organization_id()));

create policy "workflow admins can read versions"
on public.workflow_versions for select to authenticated
using (organization_id = (select private.current_workflow_admin_organization_id()));

create policy "workflow admins can read binding rules"
on public.workflow_binding_rules for select to authenticated
using (organization_id = (select private.current_workflow_admin_organization_id()));

create policy "authorized users can read work orders"
on public.work_orders for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or (
    assigned_profile_id = (select private.current_workflow_field_profile_id())
    and project_id in (select private.current_workflow_project_ids())
  )
);

create policy "authorized users can read workflow assignments"
on public.workflow_assignments for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or (
    assigned_profile_id = (select private.current_workflow_field_profile_id())
    and project_id in (select private.current_workflow_project_ids())
  )
);

create policy "authorized users can read workflow executions"
on public.workflow_executions for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or (
    operator_profile_id = (select private.current_workflow_field_profile_id())
    and project_id in (select private.current_workflow_project_ids())
  )
);

create policy "authorized users can read workflow step executions"
on public.workflow_step_executions for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or (
    operator_profile_id = (select private.current_workflow_field_profile_id())
    and project_id in (select private.current_workflow_project_ids())
  )
);

revoke all on table
  public.field_apps,
  public.workflow_definitions,
  public.workflow_versions,
  public.workflow_binding_rules,
  public.work_orders,
  public.workflow_assignments,
  public.workflow_executions,
  public.workflow_step_executions
from anon;

revoke insert, update, delete on table public.field_apps from authenticated;
revoke insert, update, delete on table public.workflow_definitions from authenticated;
revoke insert, update, delete on table public.workflow_versions from authenticated;
revoke insert, update, delete on table public.workflow_binding_rules from authenticated;
revoke insert, update, delete on table public.work_orders from authenticated;
revoke insert, update, delete on table public.workflow_assignments from authenticated;
revoke insert, update, delete on table public.workflow_executions from authenticated;
revoke insert, update, delete on table public.workflow_step_executions from authenticated;

grant select on table public.field_apps to authenticated;
grant select on table public.workflow_definitions to authenticated;
grant select on table public.workflow_versions to authenticated;
grant select on table public.workflow_binding_rules to authenticated;
grant select on table public.work_orders to authenticated;
grant select on table public.workflow_assignments to authenticated;
grant select on table public.workflow_executions to authenticated;
grant select on table public.workflow_step_executions to authenticated;

grant select, insert, update, delete on table
  public.field_apps,
  public.workflow_definitions,
  public.workflow_versions,
  public.workflow_binding_rules,
  public.work_orders,
  public.workflow_assignments,
  public.workflow_executions,
  public.workflow_step_executions
to service_role;

revoke all on sequence public.workflow_assignment_sequence from public, anon, authenticated;
grant usage, select on sequence public.workflow_assignment_sequence to service_role;
