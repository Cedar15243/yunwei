create or replace function public.register_device_project_from_task_start(
  target_organization_id uuid,
  target_actor_profile_id uuid,
  target_device_id uuid,
  target_local_project_id text,
  target_project_title text
)
returns table (
  project_id uuid,
  project_status text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  registered_project_id uuid;
  registered_project_status text;
  project_was_created boolean := false;
begin
  if nullif(btrim(target_local_project_id), '') is null then
    raise exception 'project_not_registered';
  end if;

  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_actor_profile_id
      and profile.organization_id = target_organization_id
      and profile.active = true
      and profile.status = 'active'
  ) then
    raise exception 'project_access_forbidden';
  end if;

  if not exists (
    select 1
    from public.device_bindings binding
    join public.glasses_devices device
      on device.id = binding.device_id
     and device.organization_id = binding.organization_id
    where binding.organization_id = target_organization_id
      and binding.device_id = target_device_id
      and binding.profile_id = target_actor_profile_id
      and binding.status = 'active'
      and binding.project_id is null
      and device.status <> 'disabled'
  ) then
    raise exception 'project_access_forbidden';
  end if;

  insert into public.ops_projects (
    organization_id,
    created_by,
    local_project_id,
    title
  ) values (
    target_organization_id,
    target_actor_profile_id,
    btrim(target_local_project_id),
    coalesce(target_project_title, '')
  )
  on conflict (organization_id, local_project_id) do nothing
  returning id, status
  into registered_project_id, registered_project_status;

  project_was_created := registered_project_id is not null;

  if not project_was_created then
    select project.id, project.status
    into registered_project_id, registered_project_status
    from public.ops_projects project
    where project.organization_id = target_organization_id
      and project.local_project_id = btrim(target_local_project_id);

    if registered_project_id is null then
      raise exception 'project_not_registered';
    end if;
    if registered_project_status <> 'active' then
      raise exception 'project_inactive';
    end if;
    if not exists (
      select 1
      from public.ops_project_memberships membership
      where membership.organization_id = target_organization_id
        and membership.project_id = registered_project_id
        and membership.profile_id = target_actor_profile_id
        and membership.status = 'active'
    ) then
      raise exception 'project_access_forbidden';
    end if;
  else
    insert into public.ops_project_memberships (
      organization_id,
      project_id,
      profile_id,
      access_role,
      status,
      reason,
      granted_by
    ) values (
      target_organization_id,
      registered_project_id,
      target_actor_profile_id,
      'engineer',
      'active',
      'device_project_created',
      target_actor_profile_id
    );

    insert into public.audit_events (
      organization_id,
      actor_profile_id,
      action,
      target_type,
      target_id,
      metadata
    ) values (
      target_organization_id,
      target_actor_profile_id,
      'device_project_created',
      'ops_project',
      registered_project_id::text,
      jsonb_build_object(
        'deviceId', target_device_id,
        'localProjectId', btrim(target_local_project_id)
      )
    );
  end if;

  return query
  select registered_project_id, registered_project_status;
end;
$$;

revoke all on function public.register_device_project_from_task_start(uuid, uuid, uuid, text, text) from public;
grant execute on function public.register_device_project_from_task_start(uuid, uuid, uuid, text, text) to service_role;
