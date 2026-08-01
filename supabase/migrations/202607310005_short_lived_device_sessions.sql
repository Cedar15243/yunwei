create table public.glasses_device_sessions (
  id uuid primary key default gen_random_uuid(),
  bootstrap_token_id uuid not null references public.glasses_device_tokens(id) on delete cascade,
  device_id uuid not null references public.glasses_devices(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  actor_profile_id uuid not null references public.ops_profiles(id) on delete cascade,
  token_hash text not null unique,
  status text not null default 'active' check (status in ('active', 'revoked', 'expired')),
  expires_at timestamptz not null,
  issued_at timestamptz not null default now(),
  last_used_at timestamptz,
  revoked_at timestamptz,
  revoke_reason text not null default ''
);

create index glasses_device_sessions_active_token_idx
on public.glasses_device_sessions(token_hash, expires_at)
where status = 'active';

create index glasses_device_sessions_bootstrap_idx
on public.glasses_device_sessions(bootstrap_token_id, status);

alter table public.glasses_device_sessions enable row level security;

create or replace function public.resolve_glasses_bootstrap_credential(candidate_token_hash text)
returns table (
  bootstrap_token_id uuid,
  device_id uuid,
  organization_id uuid,
  actor_profile_id uuid
)
language sql
security definer
set search_path = public
as $$
  select
    bootstrap.id,
    device.id,
    device.organization_id,
    profile.id
  from public.glasses_device_tokens bootstrap
  join public.glasses_devices device
    on device.id = bootstrap.device_id
  join public.device_bindings binding
    on binding.device_id = device.id
   and binding.organization_id = device.organization_id
   and binding.status = 'active'
  join public.ops_profiles profile
    on profile.id = binding.profile_id
   and profile.organization_id = device.organization_id
   and profile.active = true
   and profile.status = 'active'
  where bootstrap.token_hash = candidate_token_hash
    and bootstrap.status = 'active'
    and bootstrap.expires_at > now()
    and device.status <> 'disabled'
    and device.revoked_at is null
    and device.assigned_profile_id = binding.profile_id
    and (
      binding.project_id is null
      or exists (
        select 1
        from public.ops_project_memberships membership
        where membership.project_id = binding.project_id
          and membership.profile_id = binding.profile_id
          and membership.organization_id = binding.organization_id
          and membership.status = 'active'
      )
    )
  limit 1;
$$;

create or replace function public.issue_glasses_device_session(
  source_bootstrap_token_id uuid,
  new_token_hash text,
  new_expires_at timestamptz
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  resolved_device_id uuid;
  resolved_organization_id uuid;
  resolved_actor_profile_id uuid;
begin
  if new_token_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid device session token hash';
  end if;
  if not (new_expires_at > now() and new_expires_at <= now() + interval '15 minutes') then
    raise exception 'invalid device session expiry';
  end if;

  select device.id, device.organization_id, profile.id
  into resolved_device_id, resolved_organization_id, resolved_actor_profile_id
  from public.glasses_device_tokens bootstrap
  join public.glasses_devices device
    on device.id = bootstrap.device_id
  join public.device_bindings binding
    on binding.device_id = device.id
   and binding.organization_id = device.organization_id
   and binding.status = 'active'
  join public.ops_profiles profile
    on profile.id = binding.profile_id
   and profile.organization_id = device.organization_id
   and profile.active = true
   and profile.status = 'active'
  where bootstrap.id = source_bootstrap_token_id
    and bootstrap.status = 'active'
    and bootstrap.expires_at >= new_expires_at
    and device.status <> 'disabled'
    and device.revoked_at is null
    and device.assigned_profile_id = binding.profile_id
    and (
      binding.project_id is null
      or exists (
        select 1
        from public.ops_project_memberships membership
        where membership.project_id = binding.project_id
          and membership.profile_id = binding.profile_id
          and membership.organization_id = binding.organization_id
          and membership.status = 'active'
      )
    )
  limit 1;

  if resolved_device_id is null then
    raise exception 'bootstrap credential is not authorized';
  end if;

  insert into public.glasses_device_sessions (
    bootstrap_token_id,
    device_id,
    organization_id,
    actor_profile_id,
    token_hash,
    expires_at
  ) values (
    source_bootstrap_token_id,
    resolved_device_id,
    resolved_organization_id,
    resolved_actor_profile_id,
    new_token_hash,
    new_expires_at
  );
end;
$$;

create or replace function public.resolve_glasses_device_session(candidate_token_hash text)
returns table (
  device_id uuid,
  organization_id uuid,
  actor_profile_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
  resolved_session_id uuid;
  resolved_device_id uuid;
  resolved_organization_id uuid;
  resolved_actor_profile_id uuid;
begin
  select session.id, device.id, device.organization_id, profile.id
  into resolved_session_id, resolved_device_id, resolved_organization_id, resolved_actor_profile_id
  from public.glasses_device_sessions session
  join public.glasses_device_tokens bootstrap
    on bootstrap.id = session.bootstrap_token_id
   and bootstrap.status = 'active'
   and bootstrap.expires_at > now()
  join public.glasses_devices device
    on device.id = session.device_id
   and device.organization_id = session.organization_id
  join public.device_bindings binding
    on binding.device_id = device.id
   and binding.organization_id = device.organization_id
   and binding.profile_id = session.actor_profile_id
   and binding.status = 'active'
  join public.ops_profiles profile
    on profile.id = session.actor_profile_id
   and profile.organization_id = device.organization_id
   and profile.active = true
   and profile.status = 'active'
  where session.token_hash = candidate_token_hash
    and session.status = 'active'
    and session.expires_at > now()
    and device.status <> 'disabled'
    and device.revoked_at is null
    and device.assigned_profile_id = session.actor_profile_id
    and (
      binding.project_id is null
      or exists (
        select 1
        from public.ops_project_memberships membership
        where membership.project_id = binding.project_id
          and membership.profile_id = binding.profile_id
          and membership.organization_id = binding.organization_id
          and membership.status = 'active'
      )
    )
  limit 1;

  if resolved_session_id is null then
    return;
  end if;

  update public.glasses_device_sessions
  set last_used_at = now()
  where id = resolved_session_id
    and (last_used_at is null or last_used_at < now() - interval '5 minutes');

  return query select resolved_device_id, resolved_organization_id, resolved_actor_profile_id;
end;
$$;

create or replace function public.revoke_device_sessions_for_bootstrap()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if old.status = 'active' and new.status <> 'active' then
    update public.glasses_device_sessions
    set status = 'revoked',
        revoked_at = coalesce(revoked_at, now()),
        revoke_reason = coalesce(nullif(new.revoke_reason, ''), 'bootstrap_revoked')
    where bootstrap_token_id = new.id and status = 'active';
  end if;
  return new;
end;
$$;

create trigger revoke_device_sessions_with_bootstrap
after update of status on public.glasses_device_tokens
for each row execute function public.revoke_device_sessions_for_bootstrap();

revoke all on function public.resolve_glasses_bootstrap_credential(text) from public;
revoke all on function public.issue_glasses_device_session(uuid, text, timestamptz) from public;
revoke all on function public.resolve_glasses_device_session(text) from public;
revoke all on function public.revoke_device_sessions_for_bootstrap() from public;

grant execute on function public.resolve_glasses_bootstrap_credential(text) to service_role;
grant execute on function public.issue_glasses_device_session(uuid, text, timestamptz) to service_role;
grant execute on function public.resolve_glasses_device_session(text) to service_role;
