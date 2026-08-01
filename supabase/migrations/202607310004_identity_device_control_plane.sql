create type public.ops_profile_status as enum ('invited', 'active', 'disabled', 'archived');

alter table public.ops_profiles
add column status public.ops_profile_status not null default 'active',
add column lifecycle_reason text not null default '',
add column status_changed_at timestamptz,
add column status_changed_by uuid references public.ops_profiles(id) on delete set null;

alter table public.ops_profiles
add constraint ops_profiles_status_active_consistency
check (active = (status = 'active'));

create table public.ops_project_memberships (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  project_id uuid not null references public.ops_projects(id) on delete cascade,
  profile_id uuid not null references public.ops_profiles(id) on delete cascade,
  access_role text not null check (access_role in ('manager', 'engineer', 'expert', 'viewer')),
  status text not null default 'active' check (status in ('active', 'revoked')),
  reason text not null,
  granted_by uuid not null references public.ops_profiles(id),
  granted_at timestamptz not null default now(),
  revoked_by uuid references public.ops_profiles(id),
  revoked_at timestamptz,
  revoke_reason text not null default ''
);

create unique index ops_project_memberships_active_profile_idx
on public.ops_project_memberships(project_id, profile_id)
where status = 'active';

create index ops_project_memberships_profile_idx
on public.ops_project_memberships(profile_id, status, project_id);

create table public.device_bindings (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  device_id uuid not null references public.glasses_devices(id) on delete cascade,
  profile_id uuid not null references public.ops_profiles(id),
  project_id uuid references public.ops_projects(id) on delete set null,
  status text not null default 'active' check (status in ('active', 'revoked')),
  reason text not null,
  bound_by uuid not null references public.ops_profiles(id),
  bound_at timestamptz not null default now(),
  revoked_by uuid references public.ops_profiles(id),
  revoked_at timestamptz,
  revoke_reason text not null default ''
);

create unique index device_bindings_active_device_idx
on public.device_bindings(device_id)
where status = 'active';

create index device_bindings_profile_idx
on public.device_bindings(profile_id, status, device_id);

alter table public.glasses_devices
add column model text not null default '',
add column app_version text not null default '',
add column serial_number_hash text not null default '',
add column mdm_policy_version text not null default '',
add column mdm_compliance_status text not null default 'unknown'
  check (mdm_compliance_status in ('unknown', 'compliant', 'noncompliant')),
add column revoked_at timestamptz,
add column revoked_by uuid references public.ops_profiles(id) on delete set null,
add column revoke_reason text not null default '';

alter table public.ops_project_memberships enable row level security;
alter table public.device_bindings enable row level security;

create policy "members can read their project grants"
on public.ops_project_memberships for select to authenticated
using (
  exists (
    select 1 from public.ops_profiles actor
    where actor.id = auth.uid()
      and actor.organization_id = ops_project_memberships.organization_id
      and actor.active = true
      and actor.status = 'active'
      and (actor.role in ('super_admin', 'ops_admin') or actor.id = ops_project_memberships.profile_id)
  )
);

create policy "members can read their device bindings"
on public.device_bindings for select to authenticated
using (
  exists (
    select 1 from public.ops_profiles actor
    where actor.id = auth.uid()
      and actor.organization_id = device_bindings.organization_id
      and actor.active = true
      and actor.status = 'active'
      and (actor.role in ('super_admin', 'ops_admin') or actor.id = device_bindings.profile_id)
  )
);

create or replace function public.prevent_audit_event_mutation()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  raise exception 'audit events are immutable';
end;
$$;

create trigger audit_events_immutable
before update or delete on public.audit_events
for each row execute function public.prevent_audit_event_mutation();

drop function public.rotate_glasses_device_token(uuid, text, timestamptz, uuid);

create or replace function public.rotate_glasses_device_token(
  target_device_id uuid,
  new_token_hash text,
  new_expires_at timestamptz,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'device credential reason is required';
  end if;

  select organization_id into target_organization_id
  from public.glasses_devices
  where id = target_device_id and revoked_at is null;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role = 'super_admin'
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'device credential rotation forbidden';
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'rotated'
  where device_id = target_device_id and status = 'active';

  insert into public.glasses_device_tokens (device_id, token_hash, expires_at, issued_by)
  values (target_device_id, new_token_hash, new_expires_at, actor_id);

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'device_credential_rotated',
    'glasses_device',
    target_device_id::text,
    jsonb_build_object('expiresAt', new_expires_at, 'reason', btrim(reason))
  );
end;
$$;

create or replace function public.set_ops_profile_status(
  target_profile_id uuid,
  new_status public.ops_profile_status,
  reason text,
  actor_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'account status reason is required';
  end if;

  select organization_id into target_organization_id
  from public.ops_profiles
  where id = target_profile_id;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role = 'super_admin'
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'account status change forbidden';
  end if;

  if target_profile_id = actor_id and new_status <> 'active' then
    raise exception 'administrators cannot disable their own account';
  end if;

  if new_status in ('disabled', 'archived') then
    update public.glasses_device_tokens
    set status = 'revoked',
        revoked_at = now(),
        revoked_by = actor_id,
        revoke_reason = 'profile_' || new_status::text
    where status = 'active'
      and device_id in (
        select device_id from public.device_bindings
        where profile_id = target_profile_id and status = 'active'
      );

    update public.device_bindings
    set status = 'revoked',
        revoked_at = now(),
        revoked_by = actor_id,
        revoke_reason = 'profile_' || new_status::text
    where profile_id = target_profile_id and status = 'active';

    update public.glasses_devices
    set assigned_profile_id = null, updated_at = now()
    where assigned_profile_id = target_profile_id;
  end if;

  update public.ops_profiles
  set status = new_status,
      active = (new_status = 'active'),
      lifecycle_reason = btrim(reason),
      status_changed_at = now(),
      status_changed_by = actor_id,
      updated_at = now()
  where id = target_profile_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'profile_status_changed',
    'ops_profile',
    target_profile_id::text,
    jsonb_build_object('status', new_status::text, 'reason', btrim(reason))
  );
end;
$$;

create or replace function public.set_ops_project_membership(
  target_project_id uuid,
  target_profile_id uuid,
  new_access_role text,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if new_access_role not in ('manager', 'engineer', 'expert', 'viewer') then
    raise exception 'invalid project access role';
  end if;
  if nullif(btrim(reason), '') is null then
    raise exception 'project membership reason is required';
  end if;

  select organization_id into target_organization_id
  from public.ops_projects
  where id = target_project_id and status = 'active';

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role in ('super_admin', 'ops_admin')
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'project membership grant forbidden';
  end if;

  if not exists (
    select 1 from public.ops_profiles target
    where target.id = target_profile_id
      and target.organization_id = target_organization_id
      and target.active = true
      and target.status = 'active'
  ) then
    raise exception 'project member is not active in this organization';
  end if;

  update public.ops_project_memberships
  set status = 'revoked',
      revoked_by = actor_id,
      revoked_at = now(),
      revoke_reason = 'replaced'
  where project_id = target_project_id
    and profile_id = target_profile_id
    and status = 'active';

  insert into public.ops_project_memberships (
    organization_id, project_id, profile_id, access_role, reason, granted_by
  ) values (
    target_organization_id,
    target_project_id,
    target_profile_id,
    new_access_role,
    btrim(reason),
    actor_id
  );

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'project_membership_granted',
    'ops_project',
    target_project_id::text,
    jsonb_build_object(
      'profileId', target_profile_id,
      'accessRole', new_access_role,
      'reason', btrim(reason)
    )
  );
end;
$$;

create or replace function public.revoke_ops_project_membership(
  target_project_id uuid,
  target_profile_id uuid,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'project membership revoke reason is required';
  end if;

  select organization_id into target_organization_id
  from public.ops_projects
  where id = target_project_id;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role in ('super_admin', 'ops_admin')
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'project membership revoke forbidden';
  end if;

  if not exists (
    select 1 from public.ops_project_memberships membership
    where membership.project_id = target_project_id
      and membership.profile_id = target_profile_id
      and membership.organization_id = target_organization_id
      and membership.status = 'active'
  ) then
    return;
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'project_access_revoked'
  where status = 'active'
    and device_id in (
      select device_id from public.device_bindings
      where project_id = target_project_id
        and profile_id = target_profile_id
        and status = 'active'
    );

  update public.glasses_devices
  set assigned_profile_id = null, updated_at = now()
  where id in (
    select device_id from public.device_bindings
    where project_id = target_project_id
      and profile_id = target_profile_id
      and status = 'active'
  );

  update public.device_bindings
  set status = 'revoked',
      revoked_by = actor_id,
      revoked_at = now(),
      revoke_reason = 'project_access_revoked'
  where project_id = target_project_id
    and profile_id = target_profile_id
    and status = 'active';

  update public.ops_project_memberships
  set status = 'revoked',
      revoked_by = actor_id,
      revoked_at = now(),
      revoke_reason = btrim(reason)
  where project_id = target_project_id
    and profile_id = target_profile_id
    and status = 'active';

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'project_membership_revoked',
    'ops_project',
    target_project_id::text,
    jsonb_build_object('profileId', target_profile_id, 'reason', btrim(reason))
  );
end;
$$;

create or replace function public.bind_glasses_device(
  target_device_id uuid,
  target_profile_id uuid,
  target_project_id uuid,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'device binding reason is required';
  end if;

  select organization_id into target_organization_id
  from public.glasses_devices
  where id = target_device_id and revoked_at is null;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role in ('super_admin', 'ops_admin')
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'device binding forbidden';
  end if;

  if not exists (
    select 1 from public.ops_profiles target
    where target.id = target_profile_id
      and target.organization_id = target_organization_id
      and target.active = true
      and target.status = 'active'
  ) then
    raise exception 'binding target is not an active organization member';
  end if;

  if target_project_id is not null and not exists (
    select 1 from public.ops_projects project
    where project.id = target_project_id
      and project.organization_id = target_organization_id
      and project.status = 'active'
  ) then
    raise exception 'binding project is outside the organization or inactive';
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'device_rebound'
  where device_id = target_device_id and status = 'active';

  update public.device_bindings
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'rebound'
  where device_id = target_device_id and status = 'active';

  insert into public.device_bindings (
    organization_id, device_id, profile_id, project_id, reason, bound_by
  ) values (
    target_organization_id,
    target_device_id,
    target_profile_id,
    target_project_id,
    btrim(reason),
    actor_id
  );

  update public.glasses_devices
  set assigned_profile_id = target_profile_id, updated_at = now()
  where id = target_device_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'device_bound',
    'glasses_device',
    target_device_id::text,
    jsonb_build_object(
      'profileId', target_profile_id,
      'projectId', target_project_id,
      'reason', btrim(reason)
    )
  );
end;
$$;

create or replace function public.unbind_glasses_device(
  target_device_id uuid,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'device unbind reason is required';
  end if;

  select organization_id into target_organization_id
  from public.glasses_devices
  where id = target_device_id;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role in ('super_admin', 'ops_admin')
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'device unbind forbidden';
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'device_unbound'
  where device_id = target_device_id and status = 'active';

  update public.device_bindings
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = btrim(reason)
  where device_id = target_device_id and status = 'active';

  update public.glasses_devices
  set assigned_profile_id = null, updated_at = now()
  where id = target_device_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'device_unbound',
    'glasses_device',
    target_device_id::text,
    jsonb_build_object('reason', btrim(reason))
  );
end;
$$;

create or replace function public.revoke_glasses_device(
  target_device_id uuid,
  actor_id uuid,
  reason text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if nullif(btrim(reason), '') is null then
    raise exception 'device revoke reason is required';
  end if;

  select organization_id into target_organization_id
  from public.glasses_devices
  where id = target_device_id;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role = 'super_admin'
      and actor.active = true
      and actor.status = 'active'
  ) then
    raise exception 'device revoke forbidden';
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'device_revoked'
  where device_id = target_device_id and status = 'active';

  update public.device_bindings
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = actor_id,
      revoke_reason = 'device_revoked'
  where device_id = target_device_id and status = 'active';

  update public.glasses_devices
  set assigned_profile_id = null,
      status = 'disabled',
      revoked_at = coalesce(revoked_at, now()),
      revoked_by = actor_id,
      revoke_reason = btrim(reason),
      updated_at = now()
  where id = target_device_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'device_revoked',
    'glasses_device',
    target_device_id::text,
    jsonb_build_object('reason', btrim(reason))
  );
end;
$$;

revoke all on function public.prevent_audit_event_mutation() from public;
revoke all on function public.rotate_glasses_device_token(uuid, text, timestamptz, uuid, text) from public;
revoke all on function public.set_ops_profile_status(uuid, public.ops_profile_status, text, uuid) from public;
revoke all on function public.set_ops_project_membership(uuid, uuid, text, uuid, text) from public;
revoke all on function public.revoke_ops_project_membership(uuid, uuid, uuid, text) from public;
revoke all on function public.bind_glasses_device(uuid, uuid, uuid, uuid, text) from public;
revoke all on function public.unbind_glasses_device(uuid, uuid, text) from public;
revoke all on function public.revoke_glasses_device(uuid, uuid, text) from public;

grant execute on function public.rotate_glasses_device_token(uuid, text, timestamptz, uuid, text) to service_role;
grant execute on function public.set_ops_profile_status(uuid, public.ops_profile_status, text, uuid) to service_role;
grant execute on function public.set_ops_project_membership(uuid, uuid, text, uuid, text) to service_role;
grant execute on function public.revoke_ops_project_membership(uuid, uuid, uuid, text) to service_role;
grant execute on function public.bind_glasses_device(uuid, uuid, uuid, uuid, text) to service_role;
grant execute on function public.unbind_glasses_device(uuid, uuid, text) to service_role;
grant execute on function public.revoke_glasses_device(uuid, uuid, text) to service_role;
