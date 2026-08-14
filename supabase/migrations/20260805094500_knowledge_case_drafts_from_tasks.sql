create table public.knowledge_case_sources (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_version_id uuid not null references public.knowledge_versions(id) on delete cascade,
  task_id uuid not null references public.maintenance_tasks(id) on delete restrict,
  project_id uuid not null references public.ops_projects(id) on delete restrict,
  completion_event_id uuid not null references public.task_events(id) on delete restrict,
  project_memory_revision bigint not null check (project_memory_revision >= 0),
  redaction_status text not null default 'pending'
    check (redaction_status in ('pending', 'approved', 'rejected')),
  created_by uuid not null references public.ops_profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (organization_id, knowledge_version_id),
  unique (organization_id, task_id, knowledge_version_id)
);

create table public.knowledge_case_evidence (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_version_id uuid not null references public.knowledge_versions(id) on delete cascade,
  media_asset_id uuid not null references public.media_assets(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (organization_id, knowledge_version_id, media_asset_id)
);

create index knowledge_case_sources_task_idx
on public.knowledge_case_sources(organization_id, task_id, created_at desc);

create index knowledge_case_evidence_version_idx
on public.knowledge_case_evidence(organization_id, knowledge_version_id, created_at);

alter table public.knowledge_case_sources enable row level security;
alter table public.knowledge_case_evidence enable row level security;

revoke all on table public.knowledge_case_sources from public, anon, authenticated;
revoke all on table public.knowledge_case_evidence from public, anon, authenticated;

create or replace function public.create_knowledge_case_draft_from_task(
  target_task_id uuid,
  draft_knowledge_key text,
  draft_version integer,
  draft_title text,
  draft_sensitivity text,
  draft_license text,
  draft_knowledge_scopes text[],
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  task_item public.maintenance_tasks;
  project_item public.ops_projects;
  completion_event public.task_events;
  previous_event public.skill_knowledge_command_events;
  entry public.knowledge_entries;
  created_version public.knowledge_versions;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  normalized_scopes text[] := coalesce(draft_knowledge_scopes, '{}'::text[]);
  request_snapshot jsonb;
  completion_summary text;
  confirmed_facts text[];
  excluded_facts text[];
  task_risks text[];
  confirmed_section text;
  excluded_section text;
  risks_section text;
  case_content text;
  source_reference text;
  source_device_model text := '';
  source_device_models text[] := '{}'::text[];
  source_skill_ids text[] := '{}'::text[];
  memory_revision bigint;
  synced_evidence_count integer := 0;
  result_item jsonb;
begin
  if btrim(draft_knowledge_key) !~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'
     or draft_version <= 0
     or char_length(btrim(draft_title)) not between 1 and 300
     or draft_sensitivity not in ('public', 'internal', 'confidential', 'restricted')
     or char_length(btrim(draft_license)) not between 1 and 300
     or cardinality(normalized_scopes) > 100
     or exists (
       select 1 from unnest(normalized_scopes) scope_value
       where char_length(btrim(scope_value)) not between 1 and 200
     )
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Knowledge case draft command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge case draft command is not authorized';
  end if;

  request_snapshot := jsonb_build_object(
    'taskId', target_task_id,
    'knowledgeKey', btrim(draft_knowledge_key),
    'version', draft_version,
    'title', btrim(draft_title),
    'sensitivity', draft_sensitivity,
    'license', btrim(draft_license),
    'knowledgeScopes', to_jsonb(normalized_scopes)
  );

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.create_case_draft'
       or previous_event.request_payload <> request_snapshot then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into created_version
    from public.knowledge_versions item
    where item.organization_id = profile.organization_id
      and item.id = previous_event.target_id;
    if created_version.id is null then
      raise exception 'Knowledge case idempotency result is missing';
    end if;
    select jsonb_build_object(
      'id', created_version.id,
      'knowledge_entry_id', created_version.knowledge_entry_id,
      'version', created_version.version,
      'status', created_version.status,
      'title', created_version.title,
      'source_type', created_version.source_type,
      'redaction_status', source.redaction_status
    ) into result_item
    from public.knowledge_case_sources source
    where source.organization_id = profile.organization_id
      and source.knowledge_version_id = created_version.id;
    return jsonb_build_object('status', 'created', 'item', result_item);
  end if;

  select item.* into task_item
  from public.maintenance_tasks item
  where item.organization_id = profile.organization_id and item.id = target_task_id
  for update;
  if task_item.id is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  if task_item.status not in ('completed', 'closed') then
    return jsonb_build_object('status', 'task_not_completed');
  end if;

  select item.* into project_item
  from public.ops_projects item
  where item.organization_id = profile.organization_id and item.id = task_item.project_id;
  if project_item.id is null then
    return jsonb_build_object('status', 'not_found');
  end if;

  select item.* into completion_event
  from public.task_events item
  where item.organization_id = profile.organization_id
    and item.task_id = task_item.id
    and item.event_type in ('task_completed', 'task_closed')
    and item.payload ->> 'humanConfirmed' = 'true'
  order by item.occurred_at desc, item.created_at desc
  limit 1;

  if completion_event.id is null
     or completion_event.payload ->> 'phase' <> 'COMPLETED'
     or completion_event.payload ->> 'taskStatus' <> task_item.status
     or (task_item.status = 'completed' and completion_event.event_type <> 'task_completed')
     or (task_item.status = 'closed' and completion_event.event_type <> 'task_closed')
     or jsonb_typeof(completion_event.payload -> 'summary') <> 'string'
     or char_length(btrim(completion_event.payload ->> 'summary')) not between 1 and 8000
     or jsonb_typeof(completion_event.payload -> 'projectMemoryRevision') <> 'number'
     or completion_event.payload ->> 'projectMemoryRevision' !~ '^[0-9]+$'
     or jsonb_typeof(completion_event.payload -> 'confirmedFacts') <> 'array'
     or jsonb_array_length(completion_event.payload -> 'confirmedFacts') > 100
     or jsonb_typeof(completion_event.payload -> 'excludedFacts') <> 'array'
     or jsonb_array_length(completion_event.payload -> 'excludedFacts') > 100
     or jsonb_typeof(completion_event.payload -> 'risks') <> 'array'
     or jsonb_array_length(completion_event.payload -> 'risks') > 100
     or exists (
       select 1
       from jsonb_array_elements(completion_event.payload -> 'confirmedFacts') value
       where jsonb_typeof(value) <> 'string'
          or char_length(btrim(value #>> '{}')) not between 1 and 1000
     )
     or exists (
       select 1
       from jsonb_array_elements(completion_event.payload -> 'excludedFacts') value
       where jsonb_typeof(value) <> 'string'
          or char_length(btrim(value #>> '{}')) not between 1 and 1000
     )
     or exists (
       select 1
       from jsonb_array_elements(completion_event.payload -> 'risks') value
       where jsonb_typeof(value) <> 'string'
          or char_length(btrim(value #>> '{}')) not between 1 and 1000
     ) then
    return jsonb_build_object('status', 'completion_not_confirmed');
  end if;

  if exists (
    select 1 from public.media_assets asset
    where asset.organization_id = profile.organization_id
      and asset.task_id = task_item.id
      and asset.upload_status <> 'deleted'
      and asset.upload_status <> 'synced'
  ) or exists (
    select 1 from public.task_events evidence_event
    where evidence_event.organization_id = profile.organization_id
      and evidence_event.task_id = task_item.id
      and evidence_event.event_type in ('photo_captured', 'video_recorded')
      and not exists (
        select 1 from public.media_assets asset
        where asset.organization_id = profile.organization_id
          and asset.task_id = task_item.id
          and asset.event_id = evidence_event.id
          and asset.upload_status = 'synced'
      )
  ) then
    return jsonb_build_object('status', 'evidence_pending');
  end if;

  completion_summary := btrim(completion_event.payload ->> 'summary');
  memory_revision := (completion_event.payload ->> 'projectMemoryRevision')::bigint;

  select coalesce(array_agg(btrim(item.value) order by item.ordinality), '{}'::text[])
  into confirmed_facts
  from jsonb_array_elements_text(completion_event.payload -> 'confirmedFacts')
    with ordinality as item(value, ordinality);
  select coalesce(array_agg(btrim(item.value) order by item.ordinality), '{}'::text[])
  into excluded_facts
  from jsonb_array_elements_text(completion_event.payload -> 'excludedFacts')
    with ordinality as item(value, ordinality);
  select coalesce(array_agg(btrim(item.value) order by item.ordinality), '{}'::text[])
  into task_risks
  from jsonb_array_elements_text(completion_event.payload -> 'risks')
    with ordinality as item(value, ordinality);

  select case when cardinality(confirmed_facts) = 0 then '- 无'
    else string_agg('- ' || value, E'\n') end
  into confirmed_section from unnest(confirmed_facts) value;
  select case when cardinality(excluded_facts) = 0 then '- 无'
    else string_agg('- ' || value, E'\n') end
  into excluded_section from unnest(excluded_facts) value;
  select case when cardinality(task_risks) = 0 then '- 无'
    else string_agg('- ' || value, E'\n') end
  into risks_section from unnest(task_risks) value;

  select btrim(device.model) into source_device_model
  from public.glasses_devices device
  where device.organization_id = profile.organization_id
    and device.id = completion_event.device_id;
  if coalesce(source_device_model, '') <> '' then
    source_device_models := array[source_device_model];
  end if;
  if btrim(task_item.skill_version) <> '' then
    source_skill_ids := array[btrim(task_item.skill_version)];
  end if;

  select count(*)::integer into synced_evidence_count
  from public.media_assets asset
  where asset.organization_id = profile.organization_id
    and asset.task_id = task_item.id
    and asset.upload_status = 'synced';

  case_content := format(
    E'# %s\n\n## 任务\n- 项目：%s\n- 任务：%s\n- 任务状态：%s\n- 项目记忆版本：%s\n- 已同步证据：%s 项\n\n## 人工确认摘要\n%s\n\n## 已确认事实\n%s\n\n## 已排除事实\n%s\n\n## 遗留风险\n%s',
    btrim(draft_title),
    coalesce(nullif(btrim(project_item.title), ''), project_item.local_project_id),
    coalesce(nullif(btrim(task_item.title), ''), task_item.local_task_id),
    task_item.status,
    memory_revision,
    synced_evidence_count,
    completion_summary,
    confirmed_section,
    excluded_section,
    risks_section
  );
  source_reference := 'task:' || task_item.id::text || '/event:' || completion_event.id::text;

  insert into public.knowledge_entries(
    organization_id, knowledge_key, title, created_by, updated_by
  ) values (
    profile.organization_id, btrim(draft_knowledge_key), btrim(draft_title), actor_id, actor_id
  )
  on conflict (organization_id, knowledge_key) do update set
    title = excluded.title,
    updated_by = excluded.updated_by,
    updated_at = now()
  returning * into entry;

  insert into public.knowledge_versions(
    organization_id, knowledge_entry_id, version, status, title, summary,
    source_type, source_reference, language, sensitivity, license,
    project_ids, device_models, skill_ids, knowledge_scopes, content,
    author_profile_id, lifecycle_reason
  ) values (
    profile.organization_id,
    entry.id,
    draft_version,
    'uploaded',
    btrim(draft_title),
    completion_summary,
    'case',
    source_reference,
    'zh-CN',
    draft_sensitivity,
    btrim(draft_license),
    array[task_item.project_id],
    source_device_models,
    source_skill_ids,
    normalized_scopes,
    case_content,
    actor_id,
    normalized_reason
  ) returning * into created_version;

  insert into public.knowledge_case_sources(
    organization_id, knowledge_version_id, task_id, project_id,
    completion_event_id, project_memory_revision, redaction_status, created_by
  ) values (
    profile.organization_id, created_version.id, task_item.id, task_item.project_id,
    completion_event.id, memory_revision, 'pending', actor_id
  );

  insert into public.knowledge_case_evidence(
    organization_id, knowledge_version_id, media_asset_id
  )
  select profile.organization_id, created_version.id, asset.id
  from public.media_assets asset
  where asset.organization_id = profile.organization_id
    and asset.task_id = task_item.id
    and asset.upload_status = 'synced';

  result_item := jsonb_build_object(
    'id', created_version.id,
    'knowledge_entry_id', created_version.knowledge_entry_id,
    'version', created_version.version,
    'status', created_version.status,
    'title', created_version.title,
    'source_type', created_version.source_type,
    'redaction_status', 'pending'
  );

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.create_case_draft', 'knowledge_version',
    created_version.id, normalized_idempotency_key, request_snapshot, result_item, normalized_reason
  );

  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_case_draft_created', 'knowledge_version',
    created_version.id::text,
    jsonb_build_object(
      'taskId', task_item.id,
      'projectId', task_item.project_id,
      'completionEventId', completion_event.id,
      'projectMemoryRevision', memory_revision,
      'evidenceCount', synced_evidence_count,
      'redactionStatus', 'pending',
      'reason', normalized_reason
    )
  );

  return jsonb_build_object('status', 'created', 'item', result_item);
end;
$$;

revoke all on function public.create_knowledge_case_draft_from_task(
  uuid, text, integer, text, text, text, text[], uuid, text, text
) from public, anon, authenticated;
grant execute on function public.create_knowledge_case_draft_from_task(
  uuid, text, integer, text, text, text, text[], uuid, text, text
) to service_role;
