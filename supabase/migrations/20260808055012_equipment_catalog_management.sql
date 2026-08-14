alter table public.audit_events
add column if not exists idempotency_key text;

create unique index if not exists audit_events_equipment_idempotency_idx
on public.audit_events(organization_id, action, idempotency_key)
where idempotency_key is not null
  and action in ('equipment_created', 'equipment_updated');

create or replace function public.manage_ops_equipment(
  operation_name text,
  target_equipment_id uuid,
  target_equipment_key text,
  target_system text,
  target_brand text,
  target_model text,
  target_quantity integer,
  target_status text,
  target_last_inspection_at timestamptz,
  target_fault_count integer,
  target_repair_count integer,
  target_key_parameter text,
  target_project_ids uuid[],
  expected_updated_at timestamptz,
  actor_id uuid,
  reason text,
  command_idempotency_key text,
  command_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_organization_id uuid;
  effective_equipment_id uuid;
  current_updated_at timestamptz;
  action_name text;
  existing_target_id text;
  existing_command_hash text;
  requested_project_count integer;
  distinct_project_count integer;
  valid_project_count integer;
begin
  if operation_name is null or operation_name not in ('create', 'update') then
    raise exception 'equipment_operation_invalid' using errcode = 'P0001';
  end if;
  action_name := case when operation_name = 'create' then 'equipment_created' else 'equipment_updated' end;

  select profile.organization_id
  into target_organization_id
  from public.ops_profiles profile
  where profile.id = actor_id
    and profile.role in ('super_admin', 'ops_admin')
    and profile.active = true
    and profile.status = 'active';

  if target_organization_id is null then
    raise exception 'equipment_management_forbidden' using errcode = 'P0001';
  end if;
  if nullif(btrim(reason), '') is null or char_length(btrim(reason)) not between 3 and 240 then
    raise exception 'equipment_reason_invalid' using errcode = 'P0001';
  end if;
  if command_idempotency_key is null
      or command_idempotency_key !~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{7,199}$' then
    raise exception 'equipment_idempotency_key_invalid' using errcode = 'P0001';
  end if;
  if command_hash is null or command_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'equipment_command_hash_invalid' using errcode = 'P0001';
  end if;
  if target_equipment_key is null
      or target_equipment_key !~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,119}$'
      or nullif(btrim(target_system), '') is null or char_length(btrim(target_system)) > 120
      or nullif(btrim(target_brand), '') is null or char_length(btrim(target_brand)) > 120
      or nullif(btrim(target_model), '') is null or char_length(btrim(target_model)) > 120
      or target_quantity is null or target_quantity < 0 or target_quantity > 100000
      or target_status is null
      or target_status not in ('normal', 'attention', 'maintenance', 'decommissioned')
      or target_fault_count is null or target_fault_count < 0 or target_fault_count > 1000000
      or target_repair_count is null or target_repair_count < 0 or target_repair_count > 1000000
      or char_length(coalesce(target_key_parameter, '')) > 1000 then
    raise exception 'equipment_payload_invalid' using errcode = 'P0001';
  end if;

  requested_project_count := coalesce(cardinality(target_project_ids), 0);
  select count(distinct project_id)::integer
  into distinct_project_count
  from unnest(coalesce(target_project_ids, array[]::uuid[])) as requested(project_id);
  if requested_project_count < 1 or requested_project_count > 100
      or distinct_project_count <> requested_project_count then
    raise exception 'equipment_projects_invalid' using errcode = 'P0001';
  end if;

  select count(*)::integer
  into valid_project_count
  from public.ops_projects project
  where project.organization_id = target_organization_id
    and project.id = any(target_project_ids);
  if valid_project_count <> requested_project_count then
    raise exception 'equipment_project_not_found' using errcode = 'P0001';
  end if;

  perform pg_advisory_xact_lock(hashtextextended(
    target_organization_id::text || ':' || action_name || ':' || command_idempotency_key,
    0
  ));

  select audit.target_id, audit.metadata ->> 'commandHash'
  into existing_target_id, existing_command_hash
  from public.audit_events audit
  where audit.organization_id = target_organization_id
    and audit.action = action_name
    and audit.idempotency_key = command_idempotency_key
  limit 1;

  if existing_target_id is not null then
    if existing_command_hash is distinct from command_hash then
      raise exception 'equipment_idempotency_conflict' using errcode = 'P0001';
    end if;
    return existing_target_id::uuid;
  end if;

  if operation_name = 'create' then
    if target_equipment_id is not null or expected_updated_at is not null then
      raise exception 'equipment_create_contract_invalid' using errcode = 'P0001';
    end if;
    begin
      insert into public.ops_equipment (
        organization_id,
        equipment_key,
        system,
        brand,
        model,
        quantity,
        status,
        last_inspection_at,
        fault_count,
        repair_count,
        key_parameter,
        updated_at
      ) values (
        target_organization_id,
        target_equipment_key,
        btrim(target_system),
        btrim(target_brand),
        btrim(target_model),
        target_quantity,
        target_status,
        target_last_inspection_at,
        target_fault_count,
        target_repair_count,
        btrim(coalesce(target_key_parameter, '')),
        now()
      )
      returning id into effective_equipment_id;
    exception when unique_violation then
      raise exception 'equipment_key_conflict' using errcode = 'P0001';
    end;
  else
    if target_equipment_id is null or expected_updated_at is null then
      raise exception 'equipment_update_contract_invalid' using errcode = 'P0001';
    end if;

    select equipment.updated_at
    into current_updated_at
    from public.ops_equipment equipment
    where equipment.id = target_equipment_id
      and equipment.organization_id = target_organization_id
    for update;

    if current_updated_at is null then
      raise exception 'equipment_not_found' using errcode = 'P0001';
    end if;
    if current_updated_at is distinct from expected_updated_at then
      raise exception 'equipment_version_conflict' using errcode = 'P0001';
    end if;

    begin
      update public.ops_equipment
      set equipment_key = target_equipment_key,
          system = btrim(target_system),
          brand = btrim(target_brand),
          model = btrim(target_model),
          quantity = target_quantity,
          status = target_status,
          last_inspection_at = target_last_inspection_at,
          fault_count = target_fault_count,
          repair_count = target_repair_count,
          key_parameter = btrim(coalesce(target_key_parameter, '')),
          updated_at = now()
      where id = target_equipment_id
        and organization_id = target_organization_id;
    exception when unique_violation then
      raise exception 'equipment_key_conflict' using errcode = 'P0001';
    end;
    effective_equipment_id := target_equipment_id;
  end if;

  delete from public.ops_equipment_projects
  where organization_id = target_organization_id
    and equipment_id = effective_equipment_id;

  insert into public.ops_equipment_projects (organization_id, equipment_id, project_id)
  select target_organization_id, effective_equipment_id, requested.project_id
  from unnest(target_project_ids) as requested(project_id);

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    idempotency_key,
    metadata
  ) values (
    target_organization_id,
    actor_id,
    action_name,
    'ops_equipment',
    effective_equipment_id::text,
    command_idempotency_key,
    jsonb_build_object(
      'reason', btrim(reason),
      'commandHash', command_hash,
      'equipmentKey', target_equipment_key,
      'status', target_status,
      'projectIds', to_jsonb(target_project_ids),
      'expectedUpdatedAt', expected_updated_at
    )
  );

  return effective_equipment_id;
end;
$$;

revoke all on function public.manage_ops_equipment(
  text, uuid, text, text, text, text, integer, text, timestamptz,
  integer, integer, text, uuid[], timestamptz, uuid, text, text, text
) from public;
revoke all on function public.manage_ops_equipment(
  text, uuid, text, text, text, text, integer, text, timestamptz,
  integer, integer, text, uuid[], timestamptz, uuid, text, text, text
) from anon;
revoke all on function public.manage_ops_equipment(
  text, uuid, text, text, text, text, integer, text, timestamptz,
  integer, integer, text, uuid[], timestamptz, uuid, text, text, text
) from authenticated;
grant execute on function public.manage_ops_equipment(
  text, uuid, text, text, text, text, integer, text, timestamptz,
  integer, integer, text, uuid[], timestamptz, uuid, text, text, text
) to service_role;
