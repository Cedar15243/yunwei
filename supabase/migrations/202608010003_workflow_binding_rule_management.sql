alter table public.workflow_binding_rules
add column version integer not null default 1 check (version > 0);

create table public.workflow_binding_rule_command_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  workflow_binding_rule_id uuid not null,
  actor_profile_id uuid not null,
  command_type text not null check (command_type in ('create', 'update')),
  idempotency_key text not null
    check (char_length(btrim(idempotency_key)) between 1 and 200),
  request_payload jsonb not null check (jsonb_typeof(request_payload) = 'object'),
  result_snapshot jsonb not null check (jsonb_typeof(result_snapshot) = 'object'),
  result_version integer not null check (result_version > 0),
  reason text not null check (char_length(btrim(reason)) between 3 and 1000),
  created_at timestamptz not null default now(),
  unique (organization_id, idempotency_key),
  constraint workflow_binding_rule_command_events_rule_fk
    foreign key (organization_id, workflow_binding_rule_id)
    references public.workflow_binding_rules(organization_id, id) on delete restrict,
  constraint workflow_binding_rule_command_events_actor_fk
    foreign key (organization_id, actor_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create index workflow_binding_rule_command_events_rule_created_idx
on public.workflow_binding_rule_command_events(
  organization_id,
  workflow_binding_rule_id,
  created_at desc
);

create or replace function private.workflow_binding_conditions_valid(
  binding_source text,
  binding_match_conditions jsonb
)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  condition jsonb;
  item jsonb;
  condition_identity text;
  condition_identities text[] := '{}'::text[];
begin
  if binding_source not in (
    'manual',
    'trusted_external',
    'project',
    'asset_order_type',
    'organization_default'
  ) or jsonb_typeof(binding_match_conditions) <> 'array'
     or jsonb_array_length(binding_match_conditions) > 20 then
    return false;
  end if;

  for condition in
    select value from jsonb_array_elements(binding_match_conditions)
  loop
    if jsonb_typeof(condition) <> 'object'
       or not (condition ?& array['field', 'operator', 'value'])
       or condition - array['field', 'operator', 'value'] <> '{}'::jsonb
       or condition ->> 'field' not in (
         'workOrderId',
         'externalSystem',
         'externalWorkflowCode',
         'customerId',
         'projectId',
         'workOrderType',
         'assetCategory',
         'assetBrand',
         'assetModel',
         'faultType',
         'priority',
         'riskLevel',
         'tags'
       )
       or condition ->> 'operator' not in ('eq', 'in', 'contains') then
      return false;
    end if;

    if condition ->> 'operator' = 'eq' then
      if condition ->> 'field' = 'tags'
         or jsonb_typeof(condition -> 'value') <> 'string'
         or char_length(btrim(condition ->> 'value')) not between 1 and 240
         or condition ->> 'value' ~* '(<\s*/?\s*[a-z][^>]*>|(https?|wss?|file)://|(data|javascript):)' then
        return false;
      end if;
    elsif condition ->> 'operator' = 'contains' then
      if condition ->> 'field' <> 'tags'
         or jsonb_typeof(condition -> 'value') <> 'string'
         or char_length(btrim(condition ->> 'value')) not between 1 and 240
         or condition ->> 'value' ~* '(<\s*/?\s*[a-z][^>]*>|(https?|wss?|file)://|(data|javascript):)' then
        return false;
      end if;
    else
      if condition ->> 'field' = 'tags'
         or jsonb_typeof(condition -> 'value') <> 'array'
         or jsonb_array_length(condition -> 'value') not between 1 and 50 then
        return false;
      end if;
      for item in select value from jsonb_array_elements(condition -> 'value')
      loop
        if jsonb_typeof(item) <> 'string'
           or char_length(btrim(item #>> '{}')) not between 1 and 240
           or item #>> '{}' ~* '(<\s*/?\s*[a-z][^>]*>|(https?|wss?|file)://|(data|javascript):)' then
          return false;
        end if;
      end loop;
      if (
        select count(*) <> count(distinct value)
        from jsonb_array_elements_text(condition -> 'value') value
      ) then
        return false;
      end if;
    end if;

    condition_identity := condition::text;
    if condition_identity = any(condition_identities) then
      return false;
    end if;
    condition_identities := array_append(condition_identities, condition_identity);
  end loop;

  if binding_source = 'manual' then
    return exists (
      select 1 from jsonb_array_elements(binding_match_conditions) value
      where value ->> 'field' = 'workOrderId'
        and value ->> 'operator' = 'eq'
    );
  elsif binding_source = 'trusted_external' then
    return exists (
      select 1 from jsonb_array_elements(binding_match_conditions) value
      where value ->> 'field' = 'externalSystem'
        and value ->> 'operator' = 'eq'
    ) and exists (
      select 1 from jsonb_array_elements(binding_match_conditions) value
      where value ->> 'field' = 'externalWorkflowCode'
        and value ->> 'operator' = 'eq'
    );
  elsif binding_source = 'project' then
    return exists (
      select 1 from jsonb_array_elements(binding_match_conditions) value
      where value ->> 'field' in ('customerId', 'projectId')
    );
  elsif binding_source = 'asset_order_type' then
    return jsonb_array_length(binding_match_conditions) > 0
      and not exists (
        select 1 from jsonb_array_elements(binding_match_conditions) value
        where value ->> 'field' not in (
          'workOrderType',
          'assetCategory',
          'assetBrand',
          'assetModel',
          'faultType',
          'priority',
          'riskLevel',
          'tags'
        )
      );
  end if;
  return jsonb_array_length(binding_match_conditions) = 0;
end;
$$;

revoke all on function private.workflow_binding_conditions_valid(text, jsonb)
from public, anon, authenticated;
grant execute on function private.workflow_binding_conditions_valid(text, jsonb)
to service_role;

create or replace function public.create_workflow_binding_rule(
  rule_key text,
  binding_source text,
  binding_mode text,
  target_workflow_version_id uuid,
  binding_match_conditions jsonb,
  binding_enabled boolean,
  binding_active_from timestamptz,
  binding_active_until timestamptz,
  binding_idempotency_key text,
  actor_id uuid,
  command_reason text
)
returns public.workflow_binding_rules
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  workflow_version public.workflow_versions;
  previous_event public.workflow_binding_rule_command_events;
  previous_rule public.workflow_binding_rules;
  created_rule public.workflow_binding_rules;
  normalized_rule_key text := btrim(rule_key);
  normalized_idempotency_key text := btrim(binding_idempotency_key);
  normalized_reason text := btrim(command_reason);
  command_payload jsonb;
begin
  if normalized_rule_key !~ '^[a-z][a-z0-9_-]{2,63}$'
     or binding_source not in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')
     or binding_mode not in ('required', 'optional', 'none')
     or char_length(normalized_idempotency_key) not between 1 and 200
     or char_length(normalized_reason) not between 3 and 1000
     or binding_enabled is null
     or jsonb_typeof(binding_match_conditions) <> 'array'
     or not private.workflow_binding_conditions_valid(binding_source, binding_match_conditions)
     or (binding_active_from is not null and binding_active_until is not null and binding_active_until <= binding_active_from)
     or (binding_mode = 'none' and target_workflow_version_id is not null)
     or (binding_mode in ('required', 'optional') and target_workflow_version_id is null) then
    raise exception 'workflow binding rule command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id
    and item.active = true
    and item.status = 'active';
  if profile.id is null
     or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'workflow binding rule command is not authorized';
  end if;

  perform 1
  from public.organizations organization
  where organization.id = profile.organization_id
  for update;

  if target_workflow_version_id is not null then
    select version.* into workflow_version
    from public.workflow_versions version
    where version.id = target_workflow_version_id
      and version.organization_id = profile.organization_id
      and version.status = 'published';
    if workflow_version.id is null then
      raise exception 'published workflow version not found';
    end if;
  end if;

  command_payload := jsonb_build_object(
    'ruleKey', normalized_rule_key,
    'source', binding_source,
    'mode', binding_mode,
    'workflowVersionId', target_workflow_version_id,
    'matchConditions', binding_match_conditions,
    'enabled', binding_enabled,
    'activeFrom', binding_active_from,
    'activeUntil', binding_active_until,
    'reason', normalized_reason
  );
  select event.* into previous_event
  from public.workflow_binding_rule_command_events event
  where event.organization_id = profile.organization_id
    and event.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'create'
       or previous_event.request_payload <> command_payload then
      raise exception 'workflow binding rule idempotency key was already used';
    end if;
    select populated.* into previous_rule
    from jsonb_populate_record(
      null::public.workflow_binding_rules,
      previous_event.result_snapshot
    ) populated;
    return previous_rule;
  end if;

  insert into public.workflow_binding_rules (
    organization_id,
    rule_key,
    source,
    mode,
    workflow_version_id,
    match_conditions,
    enabled,
    active_from,
    active_until,
    reason,
    version,
    created_by,
    updated_by
  ) values (
    profile.organization_id,
    normalized_rule_key,
    binding_source,
    binding_mode,
    target_workflow_version_id,
    binding_match_conditions,
    binding_enabled,
    binding_active_from,
    binding_active_until,
    normalized_reason,
    1,
    actor_id,
    actor_id
  ) returning * into created_rule;

  insert into public.workflow_binding_rule_command_events (
    organization_id,
    workflow_binding_rule_id,
    actor_profile_id,
    command_type,
    idempotency_key,
    request_payload,
    result_snapshot,
    result_version,
    reason
  ) values (
    profile.organization_id,
    created_rule.id,
    actor_id,
    'create',
    normalized_idempotency_key,
    command_payload,
    to_jsonb(created_rule),
    created_rule.version,
    normalized_reason
  );

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    profile.organization_id,
    actor_id,
    'workflow_binding_rule.created',
    'workflow_binding_rule',
    created_rule.id::text,
    jsonb_build_object(
      'ruleKey', created_rule.rule_key,
      'source', created_rule.source,
      'mode', created_rule.mode,
      'workflowVersionId', created_rule.workflow_version_id,
      'version', created_rule.version,
      'reason', normalized_reason
    )
  );

  return created_rule;
end;
$$;

create or replace function public.update_workflow_binding_rule(
  target_rule_id uuid,
  rule_key text,
  binding_source text,
  binding_mode text,
  target_workflow_version_id uuid,
  binding_match_conditions jsonb,
  binding_enabled boolean,
  binding_active_from timestamptz,
  binding_active_until timestamptz,
  binding_idempotency_key text,
  actor_id uuid,
  command_reason text,
  expected_version integer
)
returns public.workflow_binding_rules
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  workflow_version public.workflow_versions;
  current_rule public.workflow_binding_rules;
  previous_event public.workflow_binding_rule_command_events;
  previous_rule public.workflow_binding_rules;
  updated_rule public.workflow_binding_rules;
  normalized_rule_key text := btrim(rule_key);
  normalized_idempotency_key text := btrim(binding_idempotency_key);
  normalized_reason text := btrim(command_reason);
  command_payload jsonb;
begin
  if target_rule_id is null
     or expected_version is null or expected_version < 1
     or normalized_rule_key !~ '^[a-z][a-z0-9_-]{2,63}$'
     or binding_source not in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')
     or binding_mode not in ('required', 'optional', 'none')
     or char_length(normalized_idempotency_key) not between 1 and 200
     or char_length(normalized_reason) not between 3 and 1000
     or binding_enabled is null
     or jsonb_typeof(binding_match_conditions) <> 'array'
     or not private.workflow_binding_conditions_valid(binding_source, binding_match_conditions)
     or (binding_active_from is not null and binding_active_until is not null and binding_active_until <= binding_active_from)
     or (binding_mode = 'none' and target_workflow_version_id is not null)
     or (binding_mode in ('required', 'optional') and target_workflow_version_id is null) then
    raise exception 'workflow binding rule command is invalid';
  end if;

  select rule.* into current_rule
  from public.workflow_binding_rules rule
  where rule.id = target_rule_id
  for update;
  if current_rule.id is null then
    raise exception 'workflow binding rule not found';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id
    and item.organization_id = current_rule.organization_id
    and item.active = true
    and item.status = 'active';
  if profile.id is null
     or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'workflow binding rule command is not authorized';
  end if;

  perform 1
  from public.organizations organization
  where organization.id = profile.organization_id
  for update;

  if target_workflow_version_id is not null then
    select version.* into workflow_version
    from public.workflow_versions version
    where version.id = target_workflow_version_id
      and version.organization_id = profile.organization_id
      and version.status = 'published';
    if workflow_version.id is null then
      raise exception 'published workflow version not found';
    end if;
  end if;

  command_payload := jsonb_build_object(
    'ruleId', target_rule_id,
    'ruleKey', normalized_rule_key,
    'source', binding_source,
    'mode', binding_mode,
    'workflowVersionId', target_workflow_version_id,
    'matchConditions', binding_match_conditions,
    'enabled', binding_enabled,
    'activeFrom', binding_active_from,
    'activeUntil', binding_active_until,
    'expectedVersion', expected_version,
    'reason', normalized_reason
  );
  select event.* into previous_event
  from public.workflow_binding_rule_command_events event
  where event.organization_id = profile.organization_id
    and event.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'update'
       or previous_event.workflow_binding_rule_id <> target_rule_id
       or previous_event.request_payload <> command_payload then
      raise exception 'workflow binding rule idempotency key was already used';
    end if;
    select populated.* into previous_rule
    from jsonb_populate_record(
      null::public.workflow_binding_rules,
      previous_event.result_snapshot
    ) populated;
    return previous_rule;
  end if;

  if current_rule.version <> expected_version then
    raise exception using
      errcode = '40001',
      message = 'workflow binding rule version conflict';
  end if;

  update public.workflow_binding_rules
  set rule_key = normalized_rule_key,
      source = binding_source,
      mode = binding_mode,
      workflow_version_id = target_workflow_version_id,
      match_conditions = binding_match_conditions,
      enabled = binding_enabled,
      active_from = binding_active_from,
      active_until = binding_active_until,
      reason = normalized_reason,
      version = version + 1,
      updated_by = actor_id,
      updated_at = now()
  where id = current_rule.id
    and organization_id = current_rule.organization_id
  returning * into updated_rule;

  insert into public.workflow_binding_rule_command_events (
    organization_id,
    workflow_binding_rule_id,
    actor_profile_id,
    command_type,
    idempotency_key,
    request_payload,
    result_snapshot,
    result_version,
    reason
  ) values (
    profile.organization_id,
    updated_rule.id,
    actor_id,
    'update',
    normalized_idempotency_key,
    command_payload,
    to_jsonb(updated_rule),
    updated_rule.version,
    normalized_reason
  );

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    profile.organization_id,
    actor_id,
    'workflow_binding_rule.updated',
    'workflow_binding_rule',
    updated_rule.id::text,
    jsonb_build_object(
      'ruleKey', updated_rule.rule_key,
      'source', updated_rule.source,
      'mode', updated_rule.mode,
      'workflowVersionId', updated_rule.workflow_version_id,
      'previousVersion', current_rule.version,
      'version', updated_rule.version,
      'reason', normalized_reason
    )
  );

  return updated_rule;
end;
$$;

alter table public.workflow_binding_rule_command_events enable row level security;

revoke all on table public.workflow_binding_rule_command_events from public, anon, authenticated;
grant all on table public.workflow_binding_rule_command_events to service_role;

revoke all on function public.create_workflow_binding_rule(
  text,
  text,
  text,
  uuid,
  jsonb,
  boolean,
  timestamptz,
  timestamptz,
  text,
  uuid,
  text
) from public, anon, authenticated;
revoke all on function public.update_workflow_binding_rule(
  uuid,
  text,
  text,
  text,
  uuid,
  jsonb,
  boolean,
  timestamptz,
  timestamptz,
  text,
  uuid,
  text,
  integer
) from public, anon, authenticated;

grant execute on function public.create_workflow_binding_rule(
  text,
  text,
  text,
  uuid,
  jsonb,
  boolean,
  timestamptz,
  timestamptz,
  text,
  uuid,
  text
) to service_role;
grant execute on function public.update_workflow_binding_rule(
  uuid,
  text,
  text,
  text,
  uuid,
  jsonb,
  boolean,
  timestamptz,
  timestamptz,
  text,
  uuid,
  text,
  integer
) to service_role;
