create table public.knowledge_version_attachments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_version_id uuid not null,
  status text not null default 'uploaded' check (
    status in ('uploaded', 'parsing', 'parsed', 'processing_unavailable', 'failed', 'replaced')
  ),
  original_file_name text not null check (
    char_length(btrim(original_file_name)) between 1 and 240
    and original_file_name !~ '[\\/]'
  ),
  content_type text not null check (
    content_type in (
      'text/plain',
      'text/markdown',
      'application/pdf',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    )
  ),
  byte_size bigint not null check (byte_size between 1 and 8388608),
  file_sha256 text not null check (file_sha256 ~ '^[0-9a-f]{64}$'),
  extracted_content_sha256 text not null default '' check (
    extracted_content_sha256 = '' or extracted_content_sha256 ~ '^[0-9a-f]{64}$'
  ),
  storage_bucket text not null default 'ops-knowledge-attachments' check (
    storage_bucket = 'ops-knowledge-attachments'
  ),
  storage_path text not null check (
    storage_path ~ '^knowledge/[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}/source\.[a-z0-9]+$'
  ),
  processing_error text not null default '' check (char_length(processing_error) <= 4000),
  is_current boolean not null default false,
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  created_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, idempotency_key),
  constraint knowledge_version_attachments_version_fk
    foreign key (organization_id, knowledge_version_id)
    references public.knowledge_versions(organization_id, id) on delete restrict,
  constraint knowledge_version_attachments_creator_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_version_attachments_ready_check check (
    status <> 'parsed'
    or (
      content_type in ('text/plain', 'text/markdown')
      and extracted_content_sha256 ~ '^[0-9a-f]{64}$'
      and processing_error = ''
      and is_current = true
    )
  )
);

create unique index knowledge_version_attachments_one_current_idx
  on public.knowledge_version_attachments(organization_id, knowledge_version_id)
  where is_current = true;

create index knowledge_version_attachments_version_history_idx
  on public.knowledge_version_attachments(organization_id, knowledge_version_id, created_at desc, id desc);

alter table public.knowledge_version_attachments enable row level security;
revoke all on table public.knowledge_version_attachments from public, anon, authenticated;
grant all on table public.knowledge_version_attachments to service_role;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'ops-knowledge-attachments',
  'ops-knowledge-attachments',
  false,
  8388608,
  array[
    'text/plain',
    'text/markdown',
    'application/pdf',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  ]
)
on conflict (id) do update set
  public = false,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

do $$
begin
  create policy "service_role_manage_ops_knowledge_attachments"
    on storage.objects for all
    to service_role
    using (bucket_id = 'ops-knowledge-attachments')
    with check (bucket_id = 'ops-knowledge-attachments');
exception
  when duplicate_object then null;
end
$$;

alter table public.knowledge_versions
  drop constraint if exists knowledge_versions_content_check;

alter table public.knowledge_versions
  add constraint knowledge_versions_content_check check (
    (
      source_type = 'uploaded_file'
      and status in ('uploaded', 'scanning', 'parsing')
      and char_length(btrim(content)) between 0 and 512000
    )
    or (
      (source_type <> 'uploaded_file' or status not in ('uploaded', 'scanning', 'parsing'))
      and char_length(btrim(content)) between 1 and 512000
    )
  );

create or replace function public.prevent_published_knowledge_attachment_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_status public.knowledge_version_status;
begin
  select version.status into parent_status
  from public.knowledge_versions version
  where version.organization_id = coalesce(new.organization_id, old.organization_id)
    and version.id = coalesce(new.knowledge_version_id, old.knowledge_version_id);
  if parent_status in ('published', 'expired', 'deprecated', 'archived') then
    raise exception 'Published knowledge attachments are immutable';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create trigger knowledge_version_attachments_published_immutable
before insert or update or delete on public.knowledge_version_attachments
for each row execute function public.prevent_published_knowledge_attachment_mutation();

create or replace function public.create_knowledge_draft(
  draft_knowledge_key text,
  draft_title text,
  draft_summary text,
  draft_source_type text,
  draft_source_reference text,
  draft_language text,
  draft_sensitivity text,
  draft_license text,
  draft_project_ids uuid[],
  draft_device_models text[],
  draft_skill_ids text[],
  draft_knowledge_scopes text[],
  draft_content text,
  draft_version integer,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.knowledge_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  entry public.knowledge_entries;
  previous_event public.skill_knowledge_command_events;
  created_version public.knowledge_versions;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  initial_status public.knowledge_version_status;
begin
  if btrim(draft_knowledge_key) !~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'
     or char_length(btrim(draft_title)) not between 1 and 300
     or char_length(btrim(draft_summary)) not between 1 and 4000
     or draft_source_type not in ('manual', 'uploaded_file', 'case', 'external_link')
     or char_length(btrim(draft_source_reference)) not between 1 and 1000
     or (draft_source_type = 'external_link' and btrim(draft_source_reference) !~ '^https://')
     or (draft_source_type <> 'external_link' and btrim(draft_source_reference) ~* '(<\s*/?\s*[a-z][^>]*>|(https?|wss?|file)://|(data|javascript):)')
     or btrim(draft_language) !~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'
     or draft_sensitivity not in ('public', 'internal', 'confidential', 'restricted')
     or char_length(btrim(draft_license)) not between 1 and 300
     or cardinality(coalesce(draft_project_ids, '{}'::uuid[])) > 100
     or cardinality(coalesce(draft_device_models, '{}'::text[])) > 100
     or cardinality(coalesce(draft_skill_ids, '{}'::text[])) > 100
     or cardinality(coalesce(draft_knowledge_scopes, '{}'::text[])) > 100
     or exists (
       select 1 from unnest(coalesce(draft_knowledge_scopes, '{}'::text[])) scope_value
       where char_length(btrim(scope_value)) not between 1 and 200
     )
     or (
       draft_source_type = 'uploaded_file'
       and char_length(btrim(coalesce(draft_content, ''))) <> 0
     )
     or (
       draft_source_type <> 'uploaded_file'
       and char_length(btrim(coalesce(draft_content, ''))) not between 1 and 512000
     )
     or draft_version <= 0
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Knowledge draft command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge draft command is not authorized';
  end if;

  if exists (
    select 1 from unnest(coalesce(draft_project_ids, '{}'::uuid[])) project_id
    where not exists (
      select 1 from public.ops_projects project
      where project.organization_id = profile.organization_id and project.id = project_id
    )
  ) then raise exception 'Knowledge project scope is invalid'; end if;

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.create_draft' then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into created_version from public.knowledge_versions item
    where item.organization_id = profile.organization_id and item.id = previous_event.target_id;
    return created_version;
  end if;

  select item.* into entry
  from public.knowledge_entries item
  where item.organization_id = profile.organization_id
    and item.knowledge_key = btrim(draft_knowledge_key)
  for update;
  if entry.id is null then
    insert into public.knowledge_entries(
      organization_id, knowledge_key, title, created_by, updated_by
    ) values (
      profile.organization_id, btrim(draft_knowledge_key), btrim(draft_title), actor_id, actor_id
    ) returning * into entry;
  else
    update public.knowledge_entries
    set title = btrim(draft_title), updated_by = actor_id, updated_at = now()
    where id = entry.id returning * into entry;
  end if;

  initial_status := case when draft_source_type = 'uploaded_file' then 'uploaded'::public.knowledge_version_status else 'parsed'::public.knowledge_version_status end;

  insert into public.knowledge_versions(
    organization_id, knowledge_entry_id, version, status, title, summary,
    source_type, source_reference, language, sensitivity, license,
    project_ids, device_models, skill_ids, knowledge_scopes, content, author_profile_id, lifecycle_reason
  ) values (
    profile.organization_id,
    entry.id,
    draft_version,
    initial_status,
    btrim(draft_title),
    btrim(draft_summary),
    draft_source_type,
    btrim(draft_source_reference),
    btrim(draft_language),
    draft_sensitivity,
    btrim(draft_license),
    coalesce(draft_project_ids, '{}'::uuid[]),
    coalesce(draft_device_models, '{}'::text[]),
    coalesce(draft_skill_ids, '{}'::text[]),
    coalesce(draft_knowledge_scopes, '{}'::text[]),
    btrim(coalesce(draft_content, '')),
    actor_id,
    normalized_reason
  ) returning * into created_version;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.create_draft', 'knowledge_version',
    created_version.id, normalized_idempotency_key,
    jsonb_build_object(
      'knowledgeKey', entry.knowledge_key,
      'version', created_version.version,
      'sourceType', created_version.source_type
    ),
    to_jsonb(created_version) - 'content', normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_version_draft_created', 'knowledge_version',
    created_version.id::text,
    jsonb_build_object(
      'knowledgeKey', entry.knowledge_key,
      'version', created_version.version,
      'sourceType', created_version.source_type,
      'initialStatus', created_version.status,
      'reason', normalized_reason
    )
  );
  return created_version;
end;
$$;

create or replace function public.complete_knowledge_attachment_processing(
  target_attachment_id uuid,
  expected_status text,
  new_status text,
  extracted_content text,
  extracted_content_sha256 text,
  new_processing_error text,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.knowledge_version_attachments
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  attachment public.knowledge_version_attachments;
  knowledge_version public.knowledge_versions;
  previous_event public.skill_knowledge_command_events;
  normalized_content text := btrim(coalesce(extracted_content, ''));
  normalized_content_sha256 text := btrim(coalesce(extracted_content_sha256, ''));
  normalized_error text := btrim(coalesce(new_processing_error, ''));
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
begin
  if expected_status not in ('uploaded', 'parsing', 'failed', 'processing_unavailable')
     or new_status not in ('parsed', 'processing_unavailable', 'failed')
     or char_length(normalized_error) > 4000
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Knowledge attachment processing command is invalid';
  end if;
  if new_status = 'parsed' and (
    char_length(normalized_content) not between 1 and 512000
    or normalized_content_sha256 !~ '^[0-9a-f]{64}$'
    or normalized_error <> ''
  ) then
    raise exception 'Parsed knowledge attachment content is invalid';
  end if;
  if new_status <> 'parsed' and char_length(normalized_error) < 3 then
    raise exception 'Knowledge attachment failure reason is required';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge attachment processing is not authorized';
  end if;

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.attachment.complete'
       or previous_event.target_id <> target_attachment_id then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into attachment
    from public.knowledge_version_attachments item
    where item.organization_id = profile.organization_id and item.id = target_attachment_id;
    return attachment;
  end if;

  select item.* into attachment
  from public.knowledge_version_attachments item
  where item.organization_id = profile.organization_id and item.id = target_attachment_id
  for update;
  if attachment.id is null then raise exception 'Knowledge attachment not found'; end if;
  if attachment.status <> expected_status then
    raise exception 'Knowledge attachment status conflict' using errcode = '40001';
  end if;

  select item.* into knowledge_version
  from public.knowledge_versions item
  where item.organization_id = profile.organization_id
    and item.id = attachment.knowledge_version_id
  for update;
  if knowledge_version.id is null then raise exception 'Knowledge version not found'; end if;
  if knowledge_version.source_type <> 'uploaded_file'
     or knowledge_version.status not in ('uploaded', 'parsed') then
    raise exception 'Knowledge version does not accept attachment processing';
  end if;

  if new_status = 'parsed' then
    if attachment.content_type not in ('text/plain', 'text/markdown') then
      raise exception 'Knowledge attachment processor is unavailable';
    end if;
    update public.knowledge_version_attachments
    set status = 'replaced', is_current = false, updated_at = now()
    where organization_id = profile.organization_id
      and knowledge_version_id = attachment.knowledge_version_id
      and id <> attachment.id
      and is_current = true;

    update public.knowledge_version_attachments
    set status = 'parsed',
        extracted_content_sha256 = normalized_content_sha256,
        processing_error = '',
        is_current = true,
        updated_at = now()
    where id = attachment.id returning * into attachment;

    update public.knowledge_versions
    set status = 'parsed',
        source_reference = attachment.original_file_name,
        content = normalized_content,
        processing_error = '',
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  else
    update public.knowledge_version_attachments
    set status = new_status,
        extracted_content_sha256 = '',
        processing_error = normalized_error,
        is_current = false,
        updated_at = now()
    where id = attachment.id returning * into attachment;

    if knowledge_version.status = 'uploaded' then
      update public.knowledge_versions
      set processing_error = normalized_error,
          lifecycle_reason = normalized_reason,
          updated_at = now()
      where id = knowledge_version.id returning * into knowledge_version;
    end if;
  end if;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.attachment.complete', 'knowledge_attachment',
    attachment.id, normalized_idempotency_key,
    jsonb_build_object(
      'expectedStatus', expected_status,
      'newStatus', new_status,
      'fileSha256', attachment.file_sha256
    ),
    to_jsonb(attachment) - 'storage_bucket' - 'storage_path', normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_attachment_processing_completed', 'knowledge_attachment',
    attachment.id::text,
    jsonb_build_object(
      'knowledgeVersionId', attachment.knowledge_version_id,
      'status', attachment.status,
      'fileSha256', attachment.file_sha256,
      'reason', normalized_reason
    )
  );
  return attachment;
end;
$$;

create or replace function public.transition_knowledge_version(
  target_knowledge_version_id uuid,
  expected_status public.knowledge_version_status,
  new_status public.knowledge_version_status,
  published_content_sha256 text,
  new_processing_error text,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.knowledge_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  knowledge_version public.knowledge_versions;
  previous_event public.skill_knowledge_command_events;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  review_decision public.content_review_decision;
begin
  if char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200
     or char_length(coalesce(new_processing_error, '')) > 4000 then
    raise exception 'Knowledge transition command is invalid';
  end if;
  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge transition command is not authorized';
  end if;
  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.transition'
       or previous_event.target_id <> target_knowledge_version_id then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into knowledge_version from public.knowledge_versions item
    where item.organization_id = profile.organization_id and item.id = target_knowledge_version_id;
    return knowledge_version;
  end if;
  select item.* into knowledge_version
  from public.knowledge_versions item
  where item.organization_id = profile.organization_id and item.id = target_knowledge_version_id
  for update;
  if knowledge_version.id is null then raise exception 'Knowledge version not found'; end if;
  if knowledge_version.status <> expected_status then
    raise exception 'Knowledge version status conflict' using errcode = '40001';
  end if;

  if knowledge_version.source_type = 'uploaded_file'
     and (
       expected_status = 'parsed' and new_status = 'review_pending'
       or expected_status = 'review_pending' and new_status = 'published'
     )
     and not exists (
       select 1
       from public.knowledge_version_attachments attachment
       where attachment.organization_id = profile.organization_id
         and attachment.knowledge_version_id = knowledge_version.id
         and attachment.is_current = true
         and attachment.status = 'parsed'
     ) then
    raise exception 'Knowledge attachment is not ready for review or publication';
  end if;

  if expected_status = 'uploaded' and new_status = 'scanning'
     or expected_status = 'scanning' and new_status = 'parsing'
     or expected_status = 'parsing' and new_status = 'parsed' then
    update public.knowledge_versions
    set status = new_status,
        processing_error = btrim(coalesce(new_processing_error, '')),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  elsif expected_status = 'parsed' and new_status = 'review_pending' then
    review_decision := 'submitted';
    update public.knowledge_versions
    set status = new_status,
        submitted_by = actor_id,
        submitted_at = now(),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  elsif expected_status = 'review_pending' and new_status = 'parsed' then
    review_decision := 'rejected';
    update public.knowledge_versions
    set status = new_status,
        reviewed_by = actor_id,
        reviewed_at = now(),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  elsif expected_status = 'review_pending' and new_status = 'published' then
    if actor_id = knowledge_version.author_profile_id
       or btrim(published_content_sha256) !~ '^[0-9a-f]{64}$' then
      raise exception 'Knowledge publication requires independent review and a content hash';
    end if;
    review_decision := 'approved';
    update public.knowledge_versions
    set status = new_status,
        content_sha256 = btrim(published_content_sha256),
        reviewed_by = actor_id,
        reviewed_at = now(),
        published_by = actor_id,
        published_at = now(),
        valid_from = coalesce(valid_from, now()),
        processing_error = '',
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  elsif expected_status = 'published' and new_status in ('expired', 'deprecated')
     or expected_status in ('expired', 'deprecated') and new_status = 'archived' then
    update public.knowledge_versions
    set status = new_status,
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = knowledge_version.id returning * into knowledge_version;
  else
    raise exception 'Knowledge version transition is not allowed';
  end if;

  if review_decision is not null then
    insert into public.knowledge_version_reviews(
      organization_id, knowledge_version_id, reviewer_profile_id, decision, reason
    ) values (
      profile.organization_id, knowledge_version.id, actor_id, review_decision, normalized_reason
    );
  end if;
  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.transition', 'knowledge_version',
    knowledge_version.id, normalized_idempotency_key,
    jsonb_build_object('expectedStatus', expected_status, 'newStatus', new_status),
    to_jsonb(knowledge_version) - 'content', normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_version_status_changed', 'knowledge_version',
    knowledge_version.id::text,
    jsonb_build_object('status', knowledge_version.status, 'reason', normalized_reason)
  );
  return knowledge_version;
end;
$$;

revoke all on function public.create_knowledge_draft(
  text, text, text, text, text, text, text, text, uuid[], text[], text[], text[], text,
  integer, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.create_knowledge_draft(
  text, text, text, text, text, text, text, text, uuid[], text[], text[], text[], text,
  integer, uuid, text, text
) to service_role;

revoke all on function public.complete_knowledge_attachment_processing(
  uuid, text, text, text, text, text, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.complete_knowledge_attachment_processing(
  uuid, text, text, text, text, text, uuid, text, text
) to service_role;

revoke all on function public.transition_knowledge_version(
  uuid, public.knowledge_version_status, public.knowledge_version_status, text, text, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.transition_knowledge_version(
  uuid, public.knowledge_version_status, public.knowledge_version_status, text, text, uuid, text, text
) to service_role;
