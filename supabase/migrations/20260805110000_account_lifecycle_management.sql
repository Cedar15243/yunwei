alter table public.ops_profiles
add column if not exists email text not null default '';

create index if not exists ops_profiles_organization_email_idx
on public.ops_profiles(organization_id, lower(email))
where email <> '';

create or replace function public.set_ops_profile_role(
  target_profile_id uuid,
  new_role text,
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
  current_role text;
  active_super_admins bigint;
begin
  if new_role not in ('super_admin', 'ops_admin', 'field_engineer', 'remote_expert', 'viewer') then
    raise exception 'invalid account role';
  end if;
  if nullif(btrim(reason), '') is null then
    raise exception 'account role reason is required';
  end if;

  select organization_id, role into target_organization_id, current_role
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
    raise exception 'account role change forbidden';
  end if;

  if target_profile_id = actor_id then
    raise exception 'administrators cannot change their own role';
  end if;

  if current_role = 'super_admin' and new_role <> 'super_admin' then
    select count(*) into active_super_admins
    from public.ops_profiles
    where organization_id = target_organization_id
      and role = 'super_admin'
      and active = true
      and status = 'active';
    if active_super_admins <= 1 then
      raise exception 'organization must retain an active super administrator';
    end if;
  end if;

  update public.ops_profiles
  set role = new_role,
      updated_at = now()
  where id = target_profile_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'profile_role_changed',
    'ops_profile',
    target_profile_id::text,
    jsonb_build_object('role', new_role, 'reason', btrim(reason))
  );
end;
$$;

revoke all on function public.set_ops_profile_role(uuid, text, uuid, text) from public;
grant execute on function public.set_ops_profile_role(uuid, text, uuid, text) to service_role;
