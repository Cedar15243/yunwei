alter table public.work_orders
add column external_workflow_code text
  check (
    external_workflow_code is null
    or char_length(btrim(external_workflow_code)) between 1 and 160
  );

create index work_orders_external_workflow_code_idx
on public.work_orders(organization_id, source_system, external_workflow_code)
where external_workflow_code is not null;

create table public.workflow_resolution_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  work_order_id uuid not null,
  assignment_id uuid,
  actor_profile_id uuid not null,
  outcome text not null check (outcome in ('assigned', 'none', 'conflict')),
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  result jsonb not null check (jsonb_typeof(result) = 'object'),
  reason text not null,
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, idempotency_key),
  constraint workflow_resolution_events_order_fk
    foreign key (organization_id, work_order_id)
    references public.work_orders(organization_id, id) on delete cascade,
  constraint workflow_resolution_events_assignment_fk
    foreign key (organization_id, assignment_id)
    references public.workflow_assignments(organization_id, id) on delete restrict,
  constraint workflow_resolution_events_actor_fk
    foreign key (organization_id, actor_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.workflow_assignment_status_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  assignment_id uuid not null,
  actor_profile_id uuid,
  device_id uuid,
  command_type text not null check (command_type in ('claim', 'status', 'execution_start')),
  previous_status text not null,
  new_status text not null,
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, idempotency_key),
  constraint workflow_assignment_status_events_assignment_fk
    foreign key (organization_id, assignment_id)
    references public.workflow_assignments(organization_id, id) on delete cascade,
  constraint workflow_assignment_status_events_actor_fk
    foreign key (organization_id, actor_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_assignment_status_events_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict
);

create table public.workflow_step_status_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  step_execution_id uuid not null,
  execution_id uuid not null,
  actor_profile_id uuid not null,
  device_id uuid not null,
  previous_status text,
  new_status text not null,
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, idempotency_key),
  constraint workflow_step_status_events_step_fk
    foreign key (organization_id, step_execution_id)
    references public.workflow_step_executions(organization_id, id) on delete cascade,
  constraint workflow_step_status_events_execution_fk
    foreign key (organization_id, execution_id)
    references public.workflow_executions(organization_id, id) on delete cascade,
  constraint workflow_step_status_events_actor_fk
    foreign key (organization_id, actor_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint workflow_step_status_events_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict
);

alter table public.workflow_executions
add column start_idempotency_key text
  check (
    start_idempotency_key is null
    or char_length(btrim(start_idempotency_key)) between 1 and 200
  );

create unique index workflow_executions_start_idempotency_idx
on public.workflow_executions(organization_id, start_idempotency_key)
where start_idempotency_key is not null;

create index workflow_resolution_events_order_created_idx
on public.workflow_resolution_events(organization_id, work_order_id, created_at desc);

create index workflow_assignment_status_events_assignment_created_idx
on public.workflow_assignment_status_events(organization_id, assignment_id, created_at desc);

create index workflow_step_status_events_execution_created_idx
on public.workflow_step_status_events(organization_id, execution_id, created_at desc);

create or replace function public.publish_workflow_version(
  target_workflow_definition_id uuid,
  compiled_execution_package jsonb,
  compiled_content_sha256 text,
  compiled_package_signature text,
  compiled_signature_key_id text,
  compiled_required_capabilities text[],
  required_min_app_version_code integer,
  publication_idempotency_key text,
  actor_id uuid,
  publication_reason text
)
returns public.workflow_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  definition public.workflow_definitions;
  published_version public.workflow_versions;
  previous_publication public.workflow_versions;
  next_version_number integer;
begin
  if char_length(btrim(coalesce(publication_reason, ''))) < 3 then
    raise exception 'workflow publication reason is required';
  end if;
  if char_length(btrim(coalesce(publication_idempotency_key, ''))) not between 1 and 200 then
    raise exception 'workflow publication idempotency key is required';
  end if;
  if jsonb_typeof(compiled_execution_package) <> 'object'
     or jsonb_typeof(compiled_execution_package -> 'nodes') <> 'array'
     or jsonb_typeof(compiled_execution_package -> 'transitions') <> 'array' then
    raise exception 'compiled workflow package is invalid';
  end if;
  if compiled_content_sha256 !~ '^[0-9a-f]{64}$'
     or compiled_execution_package ->> 'contentSha256' is distinct from compiled_content_sha256 then
    raise exception 'compiled workflow package digest is invalid';
  end if;
  if char_length(btrim(coalesce(compiled_package_signature, ''))) < 32
     or nullif(btrim(compiled_signature_key_id), '') is null then
    raise exception 'compiled workflow signature is invalid';
  end if;
  if required_min_app_version_code < 9000 then
    raise exception 'workflow package requires V9 or newer';
  end if;
  if compiled_required_capabilities is null
     or exists (
       select 1
       from unnest(compiled_required_capabilities) capability
       where nullif(btrim(capability), '') is null
     ) then
    raise exception 'compiled workflow capabilities are invalid';
  end if;

  select workflow.* into definition
  from public.workflow_definitions workflow
  where workflow.id = target_workflow_definition_id
  for update;
  if definition.id is null then
    raise exception 'workflow definition not found';
  end if;
  if definition.status = 'archived' then
    raise exception 'archived workflow cannot be published';
  end if;
  if compiled_execution_package ->> 'workflowId' is distinct from definition.id::text
     or (compiled_execution_package ->> 'schemaVersion')::integer is distinct from definition.schema_version then
    raise exception 'compiled workflow package does not match definition';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = actor_id
      and profile.organization_id = definition.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role in ('super_admin', 'ops_admin')
  ) then
    raise exception 'workflow publication is not authorized';
  end if;

  select version.* into previous_publication
  from public.workflow_versions version
  where version.organization_id = definition.organization_id
    and version.publication_idempotency_key = btrim(publication_idempotency_key);
  if previous_publication.id is not null then
    if previous_publication.workflow_definition_id <> definition.id
       or previous_publication.content_sha256 <> compiled_content_sha256
       or previous_publication.min_app_version_code <> required_min_app_version_code
       or previous_publication.published_by <> actor_id
       or not exists (
         select 1
         from public.audit_events event
         where event.organization_id = definition.organization_id
           and event.action = 'workflow_version.published'
           and event.target_type = 'workflow_version'
           and event.target_id = previous_publication.id::text
           and event.metadata ->> 'reason' = btrim(publication_reason)
       ) then
      raise exception 'workflow publication idempotency key was already used';
    end if;
    return previous_publication;
  end if;

  next_version_number := definition.latest_version_number + 1;
  insert into public.workflow_versions (
    organization_id,
    workflow_definition_id,
    version_number,
    schema_version,
    execution_package,
    content_sha256,
    package_signature,
    signature_key_id,
    required_capabilities,
    min_app_version_code,
    publication_idempotency_key,
    published_by
  ) values (
    definition.organization_id,
    definition.id,
    next_version_number,
    definition.schema_version,
    compiled_execution_package,
    compiled_content_sha256,
    compiled_package_signature,
    btrim(compiled_signature_key_id),
    compiled_required_capabilities,
    required_min_app_version_code,
    btrim(publication_idempotency_key),
    actor_id
  ) returning * into published_version;

  update public.workflow_definitions
  set latest_version_number = next_version_number,
      status = 'published',
      updated_by = actor_id,
      updated_at = now()
  where id = definition.id;

  update public.field_apps
  set status = 'published',
      updated_by = actor_id,
      updated_at = now()
  where id = definition.field_app_id
    and organization_id = definition.organization_id
    and status in ('draft', 'review_pending', 'published');

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    definition.organization_id,
    actor_id,
    'workflow_version.published',
    'workflow_version',
    published_version.id::text,
    jsonb_build_object(
      'workflowDefinitionId', definition.id,
      'versionNumber', next_version_number,
      'contentSha256', compiled_content_sha256,
      'signatureKeyId', btrim(compiled_signature_key_id),
      'reason', btrim(publication_reason)
    )
  );

  return published_version;
end;
$$;

create or replace function public.apply_work_order_workflow_resolution(
  target_work_order_id uuid,
  resolution_kind text,
  resolved_mode text,
  resolved_workflow_version_id uuid,
  resolved_rule_id uuid,
  resolved_source text,
  resolved_evidence jsonb,
  target_assigned_profile_id uuid,
  target_assigned_device_id uuid,
  resolution_idempotency_key text,
  actor_id uuid,
  resolution_reason text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  work_order public.work_orders;
  active_assignment public.workflow_assignments;
  created_assignment public.workflow_assignments;
  previous_resolution public.workflow_resolution_events;
  resolved_version public.workflow_versions;
  resolution_result jsonb;
begin
  if resolution_kind not in ('assigned', 'none', 'conflict') then
    raise exception 'unsupported workflow resolution kind';
  end if;
  if resolved_mode not in ('required', 'optional', 'none') then
    raise exception 'unsupported workflow binding mode';
  end if;
  if nullif(btrim(resolution_idempotency_key), '') is null then
    raise exception 'workflow resolution idempotency key is required';
  end if;
  if char_length(btrim(coalesce(resolution_reason, ''))) < 3 then
    raise exception 'workflow resolution reason is required';
  end if;
  if resolved_evidence is null or jsonb_typeof(resolved_evidence) <> 'object' then
    raise exception 'workflow resolution evidence must be an object';
  end if;

  select item.* into work_order
  from public.work_orders item
  where item.id = target_work_order_id
  for update;
  if work_order.id is null then
    raise exception 'work order not found';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = actor_id
      and profile.organization_id = work_order.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role in ('super_admin', 'ops_admin')
  ) then
    raise exception 'workflow resolution is not authorized';
  end if;

  select event.* into previous_resolution
  from public.workflow_resolution_events event
  where event.organization_id = work_order.organization_id
    and event.idempotency_key = btrim(resolution_idempotency_key);
  if previous_resolution.id is not null then
    if previous_resolution.work_order_id <> work_order.id then
      raise exception 'workflow resolution idempotency key was already used';
    end if;
    return previous_resolution.result;
  end if;

  select assignment.* into active_assignment
  from public.workflow_assignments assignment
  where assignment.organization_id = work_order.organization_id
    and assignment.work_order_id = work_order.id
    and assignment.status not in ('completed', 'failed', 'revoked')
  order by assignment.delivery_sequence desc
  limit 1
  for update;

  if work_order.status in ('in_progress', 'completed', 'closed')
     or active_assignment.status in ('active', 'completed')
     or exists (
       select 1
       from public.workflow_executions execution
       where execution.organization_id = work_order.organization_id
         and execution.work_order_id = work_order.id
         and execution.status <> 'cancelled'
     ) then
    raise exception 'started work order workflow cannot be rebound';
  end if;

  if resolution_kind = 'assigned' then
    if resolved_mode not in ('required', 'optional')
       or resolved_workflow_version_id is null
       or resolved_source not in ('manual', 'trusted_external', 'project', 'asset_order_type', 'organization_default')
       or target_assigned_profile_id is null then
      raise exception 'assigned workflow resolution is incomplete';
    end if;
  elsif resolution_kind = 'none' then
    if resolved_mode <> 'none' or resolved_workflow_version_id is not null then
      raise exception 'none workflow resolution is invalid';
    end if;
  else
    if resolved_mode <> 'none' or resolved_workflow_version_id is not null
       or resolved_rule_id is not null or resolved_source is not null then
      raise exception 'conflicting workflow resolution is invalid';
    end if;
  end if;

  if resolved_workflow_version_id is not null then
    select version.* into resolved_version
    from public.workflow_versions version
    where version.id = resolved_workflow_version_id
      and version.organization_id = work_order.organization_id
      and version.status = 'published';
    if resolved_version.id is null then
      raise exception 'published workflow version not found';
    end if;
  end if;
  if resolved_rule_id is not null and not exists (
    select 1
    from public.workflow_binding_rules rule
    where rule.id = resolved_rule_id
      and rule.organization_id = work_order.organization_id
      and rule.enabled = true
      and rule.mode = resolved_mode
      and rule.source = resolved_source
      and rule.workflow_version_id is not distinct from resolved_workflow_version_id
  ) then
    raise exception 'workflow binding rule does not match resolution';
  end if;
  if target_assigned_profile_id is not null and not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_assigned_profile_id
      and profile.organization_id = work_order.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role = 'field_engineer'
  ) then
    raise exception 'assigned workflow engineer is not active';
  end if;
  if work_order.project_id is not null and target_assigned_profile_id is not null
     and not exists (
       select 1
       from public.ops_project_memberships membership
       where membership.organization_id = work_order.organization_id
         and membership.project_id = work_order.project_id
         and membership.profile_id = target_assigned_profile_id
         and membership.status = 'active'
     ) then
    raise exception 'assigned workflow engineer lacks project access';
  end if;
  if target_assigned_device_id is not null and not exists (
    select 1
    from public.device_bindings binding
    join public.glasses_devices device
      on device.id = binding.device_id
     and device.organization_id = binding.organization_id
    where binding.organization_id = work_order.organization_id
      and binding.device_id = target_assigned_device_id
      and binding.profile_id = target_assigned_profile_id
      and binding.status = 'active'
      and (binding.project_id is null or binding.project_id is not distinct from work_order.project_id)
      and device.revoked_at is null
      and device.status <> 'disabled'
  ) then
    raise exception 'assigned workflow device is not bound to engineer';
  end if;

  if active_assignment.id is not null then
    update public.workflow_assignments
    set status = 'revoked',
        delivery_sequence = nextval('public.workflow_assignment_sequence'::regclass),
        revoked_at = now(),
        revoked_reason = 'replaced before execution: ' || btrim(resolution_reason)
    where id = active_assignment.id;
  end if;

  update public.work_orders
  set assigned_profile_id = coalesce(target_assigned_profile_id, assigned_profile_id),
      binding_mode = resolved_mode,
      binding_status = case when resolution_kind = 'conflict' then 'conflict' else 'resolved' end,
      binding_source = resolved_source,
      bound_workflow_version_id = resolved_workflow_version_id,
      binding_evidence = resolved_evidence || jsonb_build_object(
        'resolutionKind', resolution_kind,
        'resolutionIdempotencyKey', btrim(resolution_idempotency_key),
        'resolvedAt', now()
      ),
      updated_at = now()
  where id = work_order.id;

  if resolution_kind = 'assigned'
     or (
       resolution_kind = 'none'
       and target_assigned_profile_id is not null
       and resolved_source is not null
     ) then
    insert into public.workflow_assignments (
      organization_id,
      work_order_id,
      project_id,
      assigned_profile_id,
      assigned_device_id,
      binding_rule_id,
      workflow_version_id,
      mode,
      status,
      idempotency_key,
      resolution_source,
      resolution_evidence,
      assigned_by
    ) values (
      work_order.organization_id,
      work_order.id,
      work_order.project_id,
      target_assigned_profile_id,
      target_assigned_device_id,
      resolved_rule_id,
      resolved_workflow_version_id,
      resolved_mode,
      case when resolved_mode = 'none' then 'ready' else 'queued' end,
      btrim(resolution_idempotency_key),
      resolved_source,
      resolved_evidence,
      actor_id
    ) returning * into created_assignment;
  end if;

  resolution_result := jsonb_build_object(
    'kind', resolution_kind,
    'workOrderId', work_order.id,
    'assignmentId', created_assignment.id,
    'mode', resolved_mode,
    'workflowVersionId', resolved_workflow_version_id,
    'bindingStatus', case when resolution_kind = 'conflict' then 'conflict' else 'resolved' end,
    'resolutionSource', resolved_source,
    'matchedRuleId', resolved_rule_id,
    'conflictRuleIds', case
      when resolution_kind = 'conflict'
        and jsonb_typeof(resolved_evidence -> 'candidateIds') = 'array'
      then resolved_evidence -> 'candidateIds'
      else '[]'::jsonb
    end
  );

  insert into public.workflow_resolution_events (
    organization_id,
    work_order_id,
    assignment_id,
    actor_profile_id,
    outcome,
    idempotency_key,
    result,
    reason
  ) values (
    work_order.organization_id,
    work_order.id,
    created_assignment.id,
    actor_id,
    resolution_kind,
    btrim(resolution_idempotency_key),
    resolution_result,
    btrim(resolution_reason)
  );

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    work_order.organization_id,
    actor_id,
    'workflow_resolution.applied',
    'work_order',
    work_order.id::text,
    jsonb_build_object(
      'kind', resolution_kind,
      'mode', resolved_mode,
      'assignmentId', created_assignment.id,
      'workflowVersionId', resolved_workflow_version_id,
      'reason', btrim(resolution_reason)
    )
  );

  return resolution_result;
end;
$$;

create or replace function public.claim_workflow_assignment(
  target_assignment_id uuid,
  target_profile_id uuid,
  target_device_id uuid,
  claim_idempotency_key text
)
returns public.workflow_assignments
language plpgsql
security definer
set search_path = ''
as $$
declare
  assignment public.workflow_assignments;
  previous_event public.workflow_assignment_status_events;
begin
  if nullif(btrim(claim_idempotency_key), '') is null then
    raise exception 'workflow assignment claim idempotency key is required';
  end if;

  select item.* into assignment
  from public.workflow_assignments item
  where item.id = target_assignment_id
  for update;
  if assignment.id is null then
    raise exception 'workflow assignment not found';
  end if;

  select event.* into previous_event
  from public.workflow_assignment_status_events event
  where event.organization_id = assignment.organization_id
    and event.idempotency_key = btrim(claim_idempotency_key);
  if previous_event.id is not null then
    if previous_event.assignment_id <> assignment.id
       or previous_event.command_type <> 'claim' then
      raise exception 'workflow assignment idempotency key was already used';
    end if;
    return assignment;
  end if;

  if assignment.status in ('completed', 'failed', 'revoked') then
    raise exception 'terminal workflow assignment cannot be claimed';
  end if;
  if assignment.assigned_profile_id <> target_profile_id
     or (assignment.assigned_device_id is not null and assignment.assigned_device_id <> target_device_id) then
    raise exception 'workflow assignment ownership mismatch';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_profile_id
      and profile.organization_id = assignment.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role = 'field_engineer'
  ) then
    raise exception 'workflow assignment engineer is not active';
  end if;
  if not exists (
    select 1
    from public.device_bindings binding
    join public.glasses_devices device
      on device.id = binding.device_id
     and device.organization_id = binding.organization_id
    where binding.organization_id = assignment.organization_id
      and binding.device_id = target_device_id
      and binding.profile_id = target_profile_id
      and binding.status = 'active'
      and (binding.project_id is null or binding.project_id is not distinct from assignment.project_id)
      and device.revoked_at is null
      and device.status <> 'disabled'
  ) then
    raise exception 'workflow assignment device is not bound to engineer';
  end if;

  update public.workflow_assignments
  set assigned_device_id = target_device_id
  where id = assignment.id
  returning * into assignment;

  insert into public.workflow_assignment_status_events (
    organization_id,
    assignment_id,
    actor_profile_id,
    device_id,
    command_type,
    previous_status,
    new_status,
    idempotency_key,
    metadata
  ) values (
    assignment.organization_id,
    assignment.id,
    target_profile_id,
    target_device_id,
    'claim',
    assignment.status,
    assignment.status,
    btrim(claim_idempotency_key),
    jsonb_build_object('deliverySequence', assignment.delivery_sequence)
  );

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    assignment.organization_id,
    target_profile_id,
    'workflow_assignment.claimed',
    'workflow_assignment',
    assignment.id::text,
    jsonb_build_object('deviceId', target_device_id, 'status', assignment.status)
  );

  return assignment;
end;
$$;

create or replace function public.report_workflow_assignment_status(
  target_assignment_id uuid,
  target_profile_id uuid,
  target_device_id uuid,
  new_status text,
  report_idempotency_key text,
  reported_failure_stage text default null,
  reported_failure_reason text default null
)
returns public.workflow_assignments
language plpgsql
security definer
set search_path = ''
as $$
declare
  assignment public.workflow_assignments;
  previous_event public.workflow_assignment_status_events;
  previous_status text;
begin
  if nullif(btrim(report_idempotency_key), '') is null then
    raise exception 'workflow assignment status idempotency key is required';
  end if;
  if new_status not in ('delivered', 'verified', 'ready', 'active', 'completed', 'failed') then
    raise exception 'unsupported workflow assignment device status';
  end if;
  if new_status = 'revoked' then
    raise exception 'device cannot revoke workflow assignment';
  end if;

  select item.* into assignment
  from public.workflow_assignments item
  where item.id = target_assignment_id
  for update;
  if assignment.id is null then
    raise exception 'workflow assignment not found';
  end if;

  select event.* into previous_event
  from public.workflow_assignment_status_events event
  where event.organization_id = assignment.organization_id
    and event.idempotency_key = btrim(report_idempotency_key);
  if previous_event.id is not null then
    if previous_event.assignment_id <> assignment.id
       or previous_event.command_type <> 'status'
       or previous_event.new_status <> new_status then
      raise exception 'workflow assignment idempotency key was already used';
    end if;
    return assignment;
  end if;

  if assignment.assigned_profile_id <> target_profile_id
     or assignment.assigned_device_id <> target_device_id then
    raise exception 'workflow assignment ownership mismatch';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_profile_id
      and profile.organization_id = assignment.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role = 'field_engineer'
  ) or not exists (
    select 1
    from public.device_bindings binding
    where binding.organization_id = assignment.organization_id
      and binding.device_id = target_device_id
      and binding.profile_id = target_profile_id
      and binding.status = 'active'
      and (binding.project_id is null or binding.project_id is not distinct from assignment.project_id)
  ) then
    raise exception 'workflow assignment reporter is not authorized';
  end if;

  previous_status := assignment.status;
  if not (
    (assignment.status in ('queued', 'notified') and new_status = 'delivered')
    or (assignment.status = 'delivered' and new_status = 'verified')
    or (assignment.status = 'verified' and new_status = 'ready')
    or (assignment.status = 'ready' and new_status in ('active', 'completed'))
    or (assignment.status = 'active' and new_status = 'completed')
    or (
      assignment.status in ('queued', 'notified', 'delivered', 'verified', 'ready', 'active')
      and new_status = 'failed'
    )
  ) then
    raise exception 'invalid workflow assignment status transition';
  end if;
  if new_status = 'failed'
     and nullif(btrim(coalesce(reported_failure_reason, '')), '') is null then
    raise exception 'workflow assignment failure reason is required';
  end if;

  update public.workflow_assignments
  set status = new_status,
      delivered_at = case when new_status = 'delivered' then now() else delivered_at end,
      verified_at = case when new_status = 'verified' then now() else verified_at end,
      ready_at = case when new_status = 'ready' then now() else ready_at end,
      activated_at = case when new_status = 'active' then now() else activated_at end,
      completed_at = case when new_status = 'completed' then now() else completed_at end,
      failed_at = case when new_status = 'failed' then now() else failed_at end,
      failure_stage = case when new_status = 'failed' then nullif(btrim(reported_failure_stage), '') else failure_stage end,
      failure_reason = case when new_status = 'failed' then btrim(reported_failure_reason) else failure_reason end
  where id = assignment.id
  returning * into assignment;

  insert into public.workflow_assignment_status_events (
    organization_id,
    assignment_id,
    actor_profile_id,
    device_id,
    command_type,
    previous_status,
    new_status,
    idempotency_key,
    metadata
  ) values (
    assignment.organization_id,
    assignment.id,
    target_profile_id,
    target_device_id,
    'status',
    previous_status,
    new_status,
    btrim(report_idempotency_key),
    jsonb_strip_nulls(jsonb_build_object(
      'failureStage', nullif(btrim(reported_failure_stage), ''),
      'failureReason', nullif(btrim(reported_failure_reason), '')
    ))
  );

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    assignment.organization_id,
    target_profile_id,
    'workflow_assignment.status_reported',
    'workflow_assignment',
    assignment.id::text,
    jsonb_build_object('deviceId', target_device_id, 'previousStatus', previous_status, 'newStatus', new_status)
  );

  return assignment;
end;
$$;

create or replace function public.start_workflow_execution(
  target_assignment_id uuid,
  target_project_id uuid,
  target_task_id uuid,
  initial_node_id text,
  execution_snapshot jsonb,
  start_idempotency_key text,
  target_profile_id uuid,
  target_device_id uuid
)
returns public.workflow_executions
language plpgsql
security definer
set search_path = ''
as $$
declare
  assignment public.workflow_assignments;
  task public.maintenance_tasks;
  existing_execution public.workflow_executions;
  created_execution public.workflow_executions;
  previous_assignment_status text;
begin
  if nullif(btrim(start_idempotency_key), '') is null then
    raise exception 'workflow execution start idempotency key is required';
  end if;
  if nullif(btrim(initial_node_id), '') is null then
    raise exception 'workflow execution initial node is required';
  end if;
  if execution_snapshot is null or jsonb_typeof(execution_snapshot) <> 'object' then
    raise exception 'workflow execution snapshot must be an object';
  end if;

  select item.* into assignment
  from public.workflow_assignments item
  where item.id = target_assignment_id
  for update;
  if assignment.id is null then
    raise exception 'workflow assignment not found';
  end if;

  select execution.* into existing_execution
  from public.workflow_executions execution
  where execution.organization_id = assignment.organization_id
    and execution.start_idempotency_key = btrim(start_idempotency_key);
  if existing_execution.id is not null then
    if existing_execution.assignment_id <> assignment.id
       or existing_execution.operator_profile_id <> target_profile_id
       or existing_execution.device_id <> target_device_id then
      raise exception 'workflow execution idempotency key was already used';
    end if;
    return existing_execution;
  end if;

  if assignment.status not in ('ready', 'active') then
    raise exception 'workflow assignment is not ready for execution';
  end if;
  if assignment.mode = 'none' or assignment.workflow_version_id is null then
    raise exception 'workflow-free assignment cannot start an execution';
  end if;
  if assignment.project_id is distinct from target_project_id
     or assignment.assigned_profile_id <> target_profile_id
     or assignment.assigned_device_id <> target_device_id then
    raise exception 'workflow execution assignment ownership mismatch';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_profile_id
      and profile.organization_id = assignment.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role = 'field_engineer'
  ) or not exists (
    select 1
    from public.ops_project_memberships membership
    where membership.organization_id = assignment.organization_id
      and membership.project_id = target_project_id
      and membership.profile_id = target_profile_id
      and membership.status = 'active'
  ) or not exists (
    select 1
    from public.device_bindings binding
    where binding.organization_id = assignment.organization_id
      and binding.device_id = target_device_id
      and binding.profile_id = target_profile_id
      and binding.status = 'active'
      and (binding.project_id is null or binding.project_id = target_project_id)
  ) then
    raise exception 'workflow execution operator or device is not authorized';
  end if;

  select item.* into task
  from public.maintenance_tasks item
  where item.id = target_task_id
    and item.organization_id = assignment.organization_id
    and item.project_id = target_project_id;
  if task.id is null or task.status <> 'active' then
    raise exception 'active workflow maintenance task not found';
  end if;
  if not exists (
    select 1
    from public.workflow_versions version,
      jsonb_array_elements(version.execution_package -> 'nodes') node
    where version.id = assignment.workflow_version_id
      and version.organization_id = assignment.organization_id
      and version.status = 'published'
      and node ->> 'nodeId' = btrim(initial_node_id)
      and node ->> 'type' = 'start'
  ) then
    raise exception 'workflow execution initial node is not in signed package';
  end if;
  if exists (
    select 1
    from public.workflow_executions execution
    where execution.organization_id = assignment.organization_id
      and execution.assignment_id = assignment.id
  ) then
    raise exception 'workflow assignment already has an execution';
  end if;

  previous_assignment_status := assignment.status;
  if assignment.status = 'ready' then
    update public.workflow_assignments
    set status = 'active',
        activated_at = coalesce(activated_at, now())
    where id = assignment.id;
  end if;

  insert into public.workflow_executions (
    organization_id,
    assignment_id,
    work_order_id,
    project_id,
    task_id,
    workflow_version_id,
    operator_profile_id,
    device_id,
    status,
    current_node_id,
    runtime_snapshot,
    started_at,
    start_idempotency_key
  ) values (
    assignment.organization_id,
    assignment.id,
    assignment.work_order_id,
    target_project_id,
    target_task_id,
    assignment.workflow_version_id,
    target_profile_id,
    target_device_id,
    'active',
    btrim(initial_node_id),
    execution_snapshot,
    now(),
    btrim(start_idempotency_key)
  ) returning * into created_execution;

  if previous_assignment_status = 'ready' then
    insert into public.workflow_assignment_status_events (
      organization_id,
      assignment_id,
      actor_profile_id,
      device_id,
      command_type,
      previous_status,
      new_status,
      idempotency_key,
      metadata
    ) values (
      assignment.organization_id,
      assignment.id,
      target_profile_id,
      target_device_id,
      'execution_start',
      previous_assignment_status,
      'active',
      btrim(start_idempotency_key),
      jsonb_build_object('executionId', created_execution.id)
    );
  end if;

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    assignment.organization_id,
    target_profile_id,
    'workflow_execution.started',
    'workflow_execution',
    created_execution.id::text,
    jsonb_build_object(
      'assignmentId', assignment.id,
      'taskId', target_task_id,
      'deviceId', target_device_id,
      'initialNodeId', btrim(initial_node_id)
    )
  );

  return created_execution;
end;
$$;

create or replace function public.append_workflow_step_execution(
  target_execution_id uuid,
  step_node_id text,
  step_attempt_number integer,
  new_step_status text,
  step_idempotency_key text,
  step_input_data jsonb,
  step_output_data jsonb,
  step_evidence_asset_ids uuid[],
  step_transition_result jsonb,
  step_failure_code text,
  step_failure_reason text,
  next_node_id text,
  execution_runtime_snapshot jsonb,
  target_profile_id uuid,
  target_device_id uuid
)
returns public.workflow_step_executions
language plpgsql
security definer
set search_path = ''
as $$
declare
  execution public.workflow_executions;
  version public.workflow_versions;
  existing_step public.workflow_step_executions;
  created_step public.workflow_step_executions;
  previous_event public.workflow_step_status_events;
  previous_status text;
  current_node jsonb;
  current_node_type text;
  completing_workflow boolean;
begin
  if nullif(btrim(step_node_id), '') is null
     or nullif(btrim(step_idempotency_key), '') is null then
    raise exception 'workflow step node and idempotency key are required';
  end if;
  if step_attempt_number < 1 then
    raise exception 'workflow step attempt must be positive';
  end if;
  if new_step_status not in ('pending', 'active', 'draft_saved', 'waiting_upload', 'waiting_server', 'completed', 'skipped', 'failed') then
    raise exception 'unsupported workflow step status';
  end if;
  if jsonb_typeof(step_input_data) <> 'object'
     or jsonb_typeof(step_output_data) <> 'object'
     or jsonb_typeof(step_transition_result) <> 'object'
     or jsonb_typeof(execution_runtime_snapshot) <> 'object' then
    raise exception 'workflow step payloads must be objects';
  end if;
  if step_evidence_asset_ids is null then
    raise exception 'workflow step evidence identifiers are required';
  end if;
  if new_step_status = 'failed'
     and nullif(btrim(coalesce(step_failure_reason, '')), '') is null then
    raise exception 'workflow step failure reason is required';
  end if;

  select item.* into execution
  from public.workflow_executions item
  where item.id = target_execution_id
  for update;
  if execution.id is null then
    raise exception 'workflow execution not found';
  end if;

  select event.* into previous_event
  from public.workflow_step_status_events event
  where event.organization_id = execution.organization_id
    and event.idempotency_key = btrim(step_idempotency_key);
  if previous_event.id is not null then
    select item.* into existing_step
    from public.workflow_step_executions item
    where item.organization_id = execution.organization_id
      and item.id = previous_event.step_execution_id;
    if existing_step.id is null
       or existing_step.execution_id <> execution.id
       or existing_step.node_id <> btrim(step_node_id)
       or existing_step.attempt_number <> step_attempt_number
       or previous_event.new_status <> new_step_status then
      raise exception 'workflow step idempotency key was already used';
    end if;
    return existing_step;
  end if;

  if execution.status in ('completed', 'cancelled') then
    raise exception 'completed or cancelled workflow execution cannot accept steps';
  end if;
  if execution.operator_profile_id <> target_profile_id
     or execution.device_id <> target_device_id then
    raise exception 'workflow step execution ownership mismatch';
  end if;
  if execution.current_node_id is distinct from btrim(step_node_id) then
    raise exception 'workflow step does not match current node';
  end if;
  if not exists (
    select 1
    from public.ops_profiles profile
    where profile.id = target_profile_id
      and profile.organization_id = execution.organization_id
      and profile.active = true
      and profile.status = 'active'
      and profile.role = 'field_engineer'
  ) or not exists (
    select 1
    from public.device_bindings binding
    where binding.organization_id = execution.organization_id
      and binding.device_id = target_device_id
      and binding.profile_id = target_profile_id
      and binding.status = 'active'
      and (binding.project_id is null or binding.project_id = execution.project_id)
  ) then
    raise exception 'workflow step operator or device is not authorized';
  end if;

  select item.* into existing_step
  from public.workflow_step_executions item
  where item.organization_id = execution.organization_id
    and item.execution_id = execution.id
    and item.node_id = btrim(step_node_id)
    and item.attempt_number = step_attempt_number
  for update;
  previous_status := existing_step.status;

  select item.* into version
  from public.workflow_versions item
  where item.id = execution.workflow_version_id
    and item.organization_id = execution.organization_id;
  if version.id is null or version.status not in ('published', 'deprecated') then
    raise exception 'workflow execution package is unavailable';
  end if;
  select node into current_node
  from jsonb_array_elements(version.execution_package -> 'nodes') node
  where node ->> 'nodeId' = btrim(step_node_id)
  limit 1;
  if current_node is null then
    raise exception 'workflow step node is not in signed package';
  end if;
  current_node_type := current_node ->> 'type';
  completing_workflow := current_node_type = 'complete' and new_step_status = 'completed';

  if next_node_id is not null and nullif(btrim(next_node_id), '') is null then
    raise exception 'workflow next node cannot be blank';
  end if;
  if next_node_id is not null and not exists (
    select 1
    from jsonb_array_elements(version.execution_package -> 'transitions') transition
    where transition ->> 'fromNodeId' = btrim(step_node_id)
      and transition ->> 'toNodeId' = btrim(next_node_id)
  ) then
    raise exception 'workflow step transition is not in signed package';
  end if;
  if new_step_status in ('completed', 'skipped')
     and not completing_workflow
     and next_node_id is null then
    raise exception 'completed workflow step requires a signed next node';
  end if;
  if completing_workflow and next_node_id is not null then
    raise exception 'workflow completion node cannot transition further';
  end if;
  if new_step_status not in ('completed', 'skipped') and next_node_id is not null then
    raise exception 'unfinished workflow step cannot advance';
  end if;
  if cardinality(step_evidence_asset_ids) <> (
    select count(*)::integer
    from public.media_assets asset
    where asset.organization_id = execution.organization_id
      and asset.task_id = execution.task_id
      and asset.id = any(step_evidence_asset_ids)
      and asset.upload_status <> 'deleted'
  ) then
    raise exception 'workflow step evidence does not belong to task';
  end if;

  if existing_step.id is not null and not (
    (existing_step.status = 'pending' and new_step_status in ('active', 'draft_saved', 'waiting_upload', 'waiting_server', 'completed', 'skipped', 'failed'))
    or (existing_step.status = 'active' and new_step_status in ('draft_saved', 'waiting_upload', 'waiting_server', 'completed', 'skipped', 'failed'))
    or (existing_step.status = 'draft_saved' and new_step_status in ('active', 'waiting_upload', 'waiting_server', 'completed', 'skipped', 'failed'))
    or (existing_step.status in ('waiting_upload', 'waiting_server') and new_step_status in ('active', 'completed', 'failed'))
  ) then
    raise exception 'invalid workflow step status transition';
  end if;

  if existing_step.id is null then
    insert into public.workflow_step_executions (
      organization_id,
      execution_id,
      operator_profile_id,
      project_id,
      node_id,
      attempt_number,
      status,
      idempotency_key,
      input_data,
      output_data,
      evidence_asset_ids,
      transition_result,
      failure_code,
      failure_reason,
      started_at,
      completed_at
    ) values (
      execution.organization_id,
      execution.id,
      target_profile_id,
      execution.project_id,
      btrim(step_node_id),
      step_attempt_number,
      new_step_status,
      btrim(step_idempotency_key),
      step_input_data,
      step_output_data,
      step_evidence_asset_ids,
      step_transition_result,
      nullif(btrim(step_failure_code), ''),
      nullif(btrim(step_failure_reason), ''),
      case when new_step_status in ('active', 'completed', 'skipped', 'failed') then now() else null end,
      case when new_step_status in ('completed', 'skipped', 'failed') then now() else null end
    ) returning * into created_step;
  else
    update public.workflow_step_executions
    set status = new_step_status,
        idempotency_key = btrim(step_idempotency_key),
        input_data = step_input_data,
        output_data = step_output_data,
        evidence_asset_ids = step_evidence_asset_ids,
        transition_result = step_transition_result,
        failure_code = nullif(btrim(step_failure_code), ''),
        failure_reason = nullif(btrim(step_failure_reason), ''),
        started_at = case
          when new_step_status in ('active', 'completed', 'skipped', 'failed') then coalesce(started_at, now())
          else started_at
        end,
        completed_at = case
          when new_step_status in ('completed', 'skipped', 'failed') then now()
          else null
        end,
        updated_at = now()
    where id = existing_step.id
    returning * into created_step;
  end if;

  insert into public.workflow_step_status_events (
    organization_id,
    step_execution_id,
    execution_id,
    actor_profile_id,
    device_id,
    previous_status,
    new_status,
    idempotency_key,
    metadata
  ) values (
    execution.organization_id,
    created_step.id,
    execution.id,
    target_profile_id,
    target_device_id,
    previous_status,
    new_step_status,
    btrim(step_idempotency_key),
    jsonb_build_object(
      'nodeId', btrim(step_node_id),
      'attemptNumber', step_attempt_number,
      'nextNodeId', next_node_id,
      'evidenceCount', cardinality(step_evidence_asset_ids)
    )
  );

  update public.workflow_executions
  set status = case
        when completing_workflow then 'completed'
        when new_step_status = 'failed' then 'blocked'
        when new_step_status in ('waiting_upload', 'waiting_server') and current_node_type = 'expert_call' then 'waiting_expert'
        when new_step_status in ('waiting_upload', 'waiting_server') then 'waiting_network'
        when new_step_status in ('active', 'draft_saved', 'completed', 'skipped') then 'active'
        else status
      end,
      current_node_id = case when next_node_id is not null then btrim(next_node_id) else current_node_id end,
      runtime_snapshot = execution_runtime_snapshot,
      completed_at = case when completing_workflow then now() else completed_at end,
      updated_at = now()
  where id = execution.id;

  if completing_workflow then
    update public.workflow_assignments
    set status = 'completed',
        completed_at = now()
    where id = execution.assignment_id
      and status = 'active';
  end if;

  insert into public.audit_events (
    organization_id,
    actor_profile_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    execution.organization_id,
    target_profile_id,
    'workflow_step.appended',
    'workflow_step_execution',
    created_step.id::text,
    jsonb_build_object(
      'executionId', execution.id,
      'nodeId', btrim(step_node_id),
      'attemptNumber', step_attempt_number,
      'previousStatus', previous_status,
      'status', new_step_status,
      'nextNodeId', next_node_id,
      'evidenceCount', cardinality(step_evidence_asset_ids)
    )
  );

  return created_step;
end;
$$;

alter table public.workflow_resolution_events enable row level security;
alter table public.workflow_assignment_status_events enable row level security;
alter table public.workflow_step_status_events enable row level security;

create policy "authorized members can read workflow resolutions"
on public.workflow_resolution_events for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or work_order_id in (
    select assignment.work_order_id
    from public.workflow_assignments assignment
    where assignment.organization_id = workflow_resolution_events.organization_id
      and assignment.assigned_profile_id = (select private.current_workflow_field_profile_id())
      and assignment.project_id in (select private.current_workflow_project_ids())
  )
);

create policy "authorized members can read workflow assignment events"
on public.workflow_assignment_status_events for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or assignment_id in (
    select assignment.id
    from public.workflow_assignments assignment
    where assignment.organization_id = workflow_assignment_status_events.organization_id
      and assignment.assigned_profile_id = (select private.current_workflow_field_profile_id())
      and assignment.project_id in (select private.current_workflow_project_ids())
  )
);

create policy "authorized members can read workflow step events"
on public.workflow_step_status_events for select to authenticated
using (
  organization_id = (select private.current_workflow_admin_organization_id())
  or execution_id in (
    select execution.id
    from public.workflow_executions execution
    where execution.organization_id = workflow_step_status_events.organization_id
      and execution.operator_profile_id = (select private.current_workflow_field_profile_id())
      and execution.project_id in (select private.current_workflow_project_ids())
  )
);

revoke all on table public.workflow_resolution_events from public, anon, authenticated;
revoke all on table public.workflow_assignment_status_events from public, anon, authenticated;
revoke all on table public.workflow_step_status_events from public, anon, authenticated;
grant select on table public.workflow_resolution_events to authenticated;
grant select on table public.workflow_assignment_status_events to authenticated;
grant select on table public.workflow_step_status_events to authenticated;
grant all on table public.workflow_resolution_events to service_role;
grant all on table public.workflow_assignment_status_events to service_role;
grant all on table public.workflow_step_status_events to service_role;

revoke all on function public.publish_workflow_version(uuid, jsonb, text, text, text, text[], integer, text, uuid, text) from public, anon, authenticated;
revoke all on function public.apply_work_order_workflow_resolution(uuid, text, text, uuid, uuid, text, jsonb, uuid, uuid, text, uuid, text) from public, anon, authenticated;
revoke all on function public.claim_workflow_assignment(uuid, uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.report_workflow_assignment_status(uuid, uuid, uuid, text, text, text, text) from public, anon, authenticated;
revoke all on function public.start_workflow_execution(uuid, uuid, uuid, text, jsonb, text, uuid, uuid) from public, anon, authenticated;
revoke all on function public.append_workflow_step_execution(uuid, text, integer, text, text, jsonb, jsonb, uuid[], jsonb, text, text, text, jsonb, uuid, uuid) from public, anon, authenticated;

grant execute on function public.publish_workflow_version(uuid, jsonb, text, text, text, text[], integer, text, uuid, text) to service_role;
grant execute on function public.apply_work_order_workflow_resolution(uuid, text, text, uuid, uuid, text, jsonb, uuid, uuid, text, uuid, text) to service_role;
grant execute on function public.claim_workflow_assignment(uuid, uuid, uuid, text) to service_role;
grant execute on function public.report_workflow_assignment_status(uuid, uuid, uuid, text, text, text, text) to service_role;
grant execute on function public.start_workflow_execution(uuid, uuid, uuid, text, jsonb, text, uuid, uuid) to service_role;
grant execute on function public.append_workflow_step_execution(uuid, text, integer, text, text, jsonb, jsonb, uuid[], jsonb, text, text, text, jsonb, uuid, uuid) to service_role;
