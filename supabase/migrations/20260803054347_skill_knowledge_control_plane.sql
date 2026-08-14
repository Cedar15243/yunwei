create type public.skill_version_status as enum (
  'draft',
  'review_pending',
  'published',
  'deprecated',
  'archived'
);

create type public.knowledge_version_status as enum (
  'uploaded',
  'scanning',
  'parsing',
  'parsed',
  'review_pending',
  'published',
  'expired',
  'deprecated',
  'archived'
);

create type public.content_review_decision as enum (
  'submitted',
  'approved',
  'rejected'
);

create type public.content_assignment_status as enum (
  'active',
  'revoked',
  'expired'
);

create type public.content_assignment_scope as enum (
  'organization',
  'project',
  'profile',
  'device',
  'skill_version'
);

create table public.skill_definitions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  skill_key text not null check (skill_key ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'),
  name text not null check (char_length(btrim(name)) between 1 and 240),
  description text not null check (char_length(btrim(description)) between 1 and 4000),
  created_by uuid not null,
  updated_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, skill_key),
  constraint skill_definitions_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_definitions_updated_by_fk
    foreign key (organization_id, updated_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.skill_versions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  skill_definition_id uuid not null,
  version text not null check (
    version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
  ),
  status public.skill_version_status not null default 'draft',
  rules jsonb not null check (jsonb_typeof(rules) = 'object'),
  test_cases jsonb not null default '[]'::jsonb check (jsonb_typeof(test_cases) = 'array'),
  test_result jsonb not null default '{}'::jsonb check (jsonb_typeof(test_result) = 'object'),
  content_sha256 text not null default '' check (
    content_sha256 = '' or content_sha256 ~ '^[0-9a-f]{64}$'
  ),
  created_by uuid not null,
  submitted_by uuid,
  submitted_at timestamptz,
  reviewed_by uuid,
  reviewed_at timestamptz,
  published_by uuid,
  published_at timestamptz,
  lifecycle_reason text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, skill_definition_id, version),
  constraint skill_versions_definition_fk
    foreign key (organization_id, skill_definition_id)
    references public.skill_definitions(organization_id, id) on delete restrict,
  constraint skill_versions_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_versions_submitted_by_fk
    foreign key (organization_id, submitted_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_versions_reviewed_by_fk
    foreign key (organization_id, reviewed_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_versions_published_by_fk
    foreign key (organization_id, published_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_versions_publication_state_check check (
    status <> 'review_pending'
    or (submitted_by is not null and submitted_at is not null)
  ),
  constraint skill_versions_published_state_check check (
    status not in ('published', 'deprecated', 'archived')
    or (
      content_sha256 ~ '^[0-9a-f]{64}$'
      and reviewed_by is not null
      and reviewed_at is not null
      and published_by is not null
      and published_at is not null
    )
  )
);

create table public.skill_version_reviews (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  skill_version_id uuid not null,
  reviewer_profile_id uuid not null,
  decision public.content_review_decision not null,
  reason text not null check (char_length(btrim(reason)) between 3 and 1000),
  test_result jsonb not null default '{}'::jsonb check (jsonb_typeof(test_result) = 'object'),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  constraint skill_version_reviews_version_fk
    foreign key (organization_id, skill_version_id)
    references public.skill_versions(organization_id, id) on delete restrict,
  constraint skill_version_reviews_reviewer_fk
    foreign key (organization_id, reviewer_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.skill_assignments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  skill_version_id uuid not null,
  scope_type public.content_assignment_scope not null,
  project_id uuid,
  profile_id uuid,
  device_id uuid,
  status public.content_assignment_status not null default 'active',
  active_from timestamptz not null default now(),
  expires_at timestamptz,
  assigned_by uuid not null,
  assignment_reason text not null check (char_length(btrim(assignment_reason)) between 3 and 1000),
  assigned_at timestamptz not null default now(),
  revoked_by uuid,
  revoked_at timestamptz,
  revoke_reason text not null default '',
  unique (organization_id, id),
  constraint skill_assignments_version_fk
    foreign key (organization_id, skill_version_id)
    references public.skill_versions(organization_id, id) on delete restrict,
  constraint skill_assignments_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint skill_assignments_profile_fk
    foreign key (organization_id, profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_assignments_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict,
  constraint skill_assignments_assigned_by_fk
    foreign key (organization_id, assigned_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_assignments_revoked_by_fk
    foreign key (organization_id, revoked_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint skill_assignments_scope_check check (
    (scope_type = 'organization' and project_id is null and profile_id is null and device_id is null)
    or (scope_type = 'project' and project_id is not null and profile_id is null and device_id is null)
    or (scope_type = 'profile' and project_id is null and profile_id is not null and device_id is null)
    or (scope_type = 'device' and project_id is null and profile_id is null and device_id is not null)
  ),
  constraint skill_assignments_window_check check (
    expires_at is null or expires_at > active_from
  )
);

create unique index skill_assignments_active_scope_idx
on public.skill_assignments(
  organization_id,
  skill_version_id,
  scope_type,
  coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(profile_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(device_id, '00000000-0000-0000-0000-000000000000'::uuid)
)
where status = 'active';

create index skill_assignments_resolution_idx
on public.skill_assignments(
  organization_id,
  status,
  scope_type,
  project_id,
  profile_id,
  device_id,
  active_from,
  expires_at
);

create table public.knowledge_entries (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_key text not null check (knowledge_key ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'),
  title text not null check (char_length(btrim(title)) between 1 and 300),
  created_by uuid not null,
  updated_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, knowledge_key),
  constraint knowledge_entries_created_by_fk
    foreign key (organization_id, created_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_entries_updated_by_fk
    foreign key (organization_id, updated_by)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.knowledge_versions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_entry_id uuid not null,
  version integer not null check (version > 0),
  status public.knowledge_version_status not null default 'uploaded',
  title text not null check (char_length(btrim(title)) between 1 and 300),
  summary text not null check (char_length(btrim(summary)) between 1 and 4000),
  source_type text not null check (source_type in ('manual', 'uploaded_file', 'case', 'external_link')),
  source_reference text not null check (char_length(btrim(source_reference)) between 1 and 1000),
  language text not null check (language ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  sensitivity text not null check (sensitivity in ('public', 'internal', 'confidential', 'restricted')),
  license text not null check (char_length(btrim(license)) between 1 and 300),
  project_ids uuid[] not null default '{}'::uuid[],
  device_models text[] not null default '{}'::text[],
  skill_ids text[] not null default '{}'::text[],
  knowledge_scopes text[] not null default '{}'::text[],
  content text not null check (char_length(btrim(content)) between 1 and 512000),
  content_sha256 text not null default '' check (
    content_sha256 = '' or content_sha256 ~ '^[0-9a-f]{64}$'
  ),
  processing_error text not null default '',
  author_profile_id uuid not null,
  submitted_by uuid,
  submitted_at timestamptz,
  reviewed_by uuid,
  reviewed_at timestamptz,
  published_by uuid,
  published_at timestamptz,
  valid_from timestamptz,
  expires_at timestamptz,
  lifecycle_reason text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, knowledge_entry_id, version),
  constraint knowledge_versions_entry_fk
    foreign key (organization_id, knowledge_entry_id)
    references public.knowledge_entries(organization_id, id) on delete restrict,
  constraint knowledge_versions_author_fk
    foreign key (organization_id, author_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_versions_submitted_by_fk
    foreign key (organization_id, submitted_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_versions_reviewed_by_fk
    foreign key (organization_id, reviewed_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_versions_published_by_fk
    foreign key (organization_id, published_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_versions_window_check check (
    expires_at is null or valid_from is null or expires_at > valid_from
  ),
  constraint knowledge_versions_review_state_check check (
    status <> 'review_pending'
    or (submitted_by is not null and submitted_at is not null)
  ),
  constraint knowledge_versions_published_state_check check (
    status not in ('published', 'expired', 'deprecated', 'archived')
    or (
      content_sha256 ~ '^[0-9a-f]{64}$'
      and reviewed_by is not null
      and reviewed_at is not null
      and published_by is not null
      and published_at is not null
    )
  )
);

create table public.knowledge_version_reviews (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_version_id uuid not null,
  reviewer_profile_id uuid not null,
  decision public.content_review_decision not null,
  reason text not null check (char_length(btrim(reason)) between 3 and 1000),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  constraint knowledge_version_reviews_version_fk
    foreign key (organization_id, knowledge_version_id)
    references public.knowledge_versions(organization_id, id) on delete restrict,
  constraint knowledge_version_reviews_reviewer_fk
    foreign key (organization_id, reviewer_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create table public.knowledge_grants (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  knowledge_version_id uuid not null,
  scope_type public.content_assignment_scope not null,
  project_id uuid,
  profile_id uuid,
  device_id uuid,
  skill_version_id uuid,
  status public.content_assignment_status not null default 'active',
  active_from timestamptz not null default now(),
  expires_at timestamptz,
  granted_by uuid not null,
  grant_reason text not null check (char_length(btrim(grant_reason)) between 3 and 1000),
  granted_at timestamptz not null default now(),
  revoked_by uuid,
  revoked_at timestamptz,
  revoke_reason text not null default '',
  unique (organization_id, id),
  constraint knowledge_grants_version_fk
    foreign key (organization_id, knowledge_version_id)
    references public.knowledge_versions(organization_id, id) on delete restrict,
  constraint knowledge_grants_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint knowledge_grants_profile_fk
    foreign key (organization_id, profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_grants_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict,
  constraint knowledge_grants_skill_version_fk
    foreign key (organization_id, skill_version_id)
    references public.skill_versions(organization_id, id) on delete restrict,
  constraint knowledge_grants_granted_by_fk
    foreign key (organization_id, granted_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_grants_revoked_by_fk
    foreign key (organization_id, revoked_by)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint knowledge_grants_scope_check check (
    (scope_type = 'organization' and project_id is null and profile_id is null and device_id is null and skill_version_id is null)
    or (scope_type = 'project' and project_id is not null and profile_id is null and device_id is null and skill_version_id is null)
    or (scope_type = 'profile' and project_id is null and profile_id is not null and device_id is null and skill_version_id is null)
    or (scope_type = 'device' and project_id is null and profile_id is null and device_id is not null and skill_version_id is null)
    or (scope_type = 'skill_version' and project_id is null and profile_id is null and device_id is null and skill_version_id is not null)
  ),
  constraint knowledge_grants_window_check check (
    expires_at is null or expires_at > active_from
  )
);

create unique index knowledge_grants_active_scope_idx
on public.knowledge_grants(
  organization_id,
  knowledge_version_id,
  scope_type,
  coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(profile_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(device_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(skill_version_id, '00000000-0000-0000-0000-000000000000'::uuid)
)
where status = 'active';

create index knowledge_grants_resolution_idx
on public.knowledge_grants(
  organization_id,
  status,
  scope_type,
  project_id,
  profile_id,
  device_id,
  skill_version_id,
  active_from,
  expires_at
);

create table public.knowledge_references (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  task_id uuid not null,
  trace_id text not null check (char_length(btrim(trace_id)) between 1 and 200),
  knowledge_version_id uuid not null,
  source_locator text not null check (char_length(btrim(source_locator)) between 1 and 1000),
  excerpt text not null check (char_length(btrim(excerpt)) between 1 and 4000),
  retrieval_score double precision,
  reference_status text not null default 'valid' check (reference_status in ('valid', 'stale', 'withdrawn')),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, trace_id, knowledge_version_id, source_locator),
  constraint knowledge_references_task_fk
    foreign key (organization_id, task_id)
    references public.maintenance_tasks(organization_id, id) on delete restrict,
  constraint knowledge_references_version_fk
    foreign key (organization_id, knowledge_version_id)
    references public.knowledge_versions(organization_id, id) on delete restrict,
  constraint knowledge_references_score_check check (
    retrieval_score is null or (retrieval_score >= 0 and retrieval_score <= 1)
  )
);

create table public.device_content_manifests (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  device_id uuid not null,
  profile_id uuid not null,
  project_id uuid,
  manifest_version bigint generated always as identity,
  etag text not null check (etag ~ '^[0-9a-f]{64}$'),
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  status text not null default 'active' check (status in ('active', 'superseded', 'revoked')),
  generated_at timestamptz not null default now(),
  expires_at timestamptz not null,
  unique (organization_id, id),
  unique (organization_id, device_id, manifest_version),
  unique (organization_id, device_id, etag),
  constraint device_content_manifests_device_fk
    foreign key (organization_id, device_id)
    references public.glasses_devices(organization_id, id) on delete restrict,
  constraint device_content_manifests_profile_fk
    foreign key (organization_id, profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict,
  constraint device_content_manifests_project_fk
    foreign key (organization_id, project_id)
    references public.ops_projects(organization_id, id) on delete restrict,
  constraint device_content_manifests_window_check check (expires_at > generated_at)
);

create index device_content_manifests_latest_idx
on public.device_content_manifests(organization_id, device_id, manifest_version desc);

create table public.skill_knowledge_command_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  actor_profile_id uuid not null,
  command_type text not null check (command_type in (
    'skill.create_draft',
    'skill.transition',
    'skill.assign',
    'skill.revoke_assignment',
    'knowledge.create_draft',
    'knowledge.transition',
    'knowledge.grant',
    'knowledge.revoke_grant'
  )),
  target_type text not null check (target_type in (
    'skill_version',
    'skill_assignment',
    'knowledge_version',
    'knowledge_grant'
  )),
  target_id uuid not null,
  idempotency_key text not null check (char_length(btrim(idempotency_key)) between 1 and 200),
  request_payload jsonb not null check (jsonb_typeof(request_payload) = 'object'),
  result_snapshot jsonb not null check (jsonb_typeof(result_snapshot) = 'object'),
  reason text not null check (char_length(btrim(reason)) between 3 and 1000),
  created_at timestamptz not null default now(),
  unique (organization_id, id),
  unique (organization_id, idempotency_key),
  constraint skill_knowledge_command_events_actor_fk
    foreign key (organization_id, actor_profile_id)
    references public.ops_profiles(organization_id, id) on delete restrict
);

create index skill_knowledge_command_events_target_idx
on public.skill_knowledge_command_events(organization_id, target_type, target_id, created_at desc);

create or replace function private.prevent_published_skill_version_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.status in ('published', 'deprecated', 'archived') then
    if tg_op = 'DELETE' then
      raise exception 'published Skill version is immutable';
    end if;
    if new.organization_id is distinct from old.organization_id
       or new.skill_definition_id is distinct from old.skill_definition_id
       or new.version is distinct from old.version
       or new.rules is distinct from old.rules
       or new.test_cases is distinct from old.test_cases
       or new.test_result is distinct from old.test_result
       or new.content_sha256 is distinct from old.content_sha256
       or new.created_by is distinct from old.created_by
       or new.created_at is distinct from old.created_at
       or new.published_by is distinct from old.published_by
       or new.published_at is distinct from old.published_at then
      raise exception 'published Skill version is immutable';
    end if;
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create trigger skill_versions_published_immutable
before update or delete on public.skill_versions
for each row execute function private.prevent_published_skill_version_mutation();

create or replace function private.prevent_published_knowledge_version_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.status in ('published', 'expired', 'deprecated', 'archived') then
    if tg_op = 'DELETE' then
      raise exception 'published knowledge version is immutable';
    end if;
    if new.organization_id is distinct from old.organization_id
       or new.knowledge_entry_id is distinct from old.knowledge_entry_id
       or new.version is distinct from old.version
       or new.title is distinct from old.title
       or new.summary is distinct from old.summary
       or new.source_type is distinct from old.source_type
       or new.source_reference is distinct from old.source_reference
       or new.language is distinct from old.language
       or new.sensitivity is distinct from old.sensitivity
       or new.license is distinct from old.license
       or new.project_ids is distinct from old.project_ids
       or new.device_models is distinct from old.device_models
       or new.skill_ids is distinct from old.skill_ids
       or new.content is distinct from old.content
       or new.content_sha256 is distinct from old.content_sha256
       or new.author_profile_id is distinct from old.author_profile_id
       or new.created_at is distinct from old.created_at
       or new.published_by is distinct from old.published_by
       or new.published_at is distinct from old.published_at then
      raise exception 'published knowledge version is immutable';
    end if;
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create trigger knowledge_versions_published_immutable
before update or delete on public.knowledge_versions
for each row execute function private.prevent_published_knowledge_version_mutation();

alter table public.skill_definitions enable row level security;
alter table public.skill_versions enable row level security;
alter table public.skill_version_reviews enable row level security;
alter table public.skill_assignments enable row level security;
alter table public.knowledge_entries enable row level security;
alter table public.knowledge_versions enable row level security;
alter table public.knowledge_version_reviews enable row level security;
alter table public.knowledge_grants enable row level security;
alter table public.knowledge_references enable row level security;
alter table public.device_content_manifests enable row level security;
alter table public.skill_knowledge_command_events enable row level security;

revoke all on table public.skill_definitions from public, anon, authenticated;
revoke all on table public.skill_versions from public, anon, authenticated;
revoke all on table public.skill_version_reviews from public, anon, authenticated;
revoke all on table public.skill_assignments from public, anon, authenticated;
revoke all on table public.knowledge_entries from public, anon, authenticated;
revoke all on table public.knowledge_versions from public, anon, authenticated;
revoke all on table public.knowledge_version_reviews from public, anon, authenticated;
revoke all on table public.knowledge_grants from public, anon, authenticated;
revoke all on table public.knowledge_references from public, anon, authenticated;
revoke all on table public.device_content_manifests from public, anon, authenticated;
revoke all on table public.skill_knowledge_command_events from public, anon, authenticated;

revoke all on function private.prevent_published_skill_version_mutation() from public, anon, authenticated;
revoke all on function private.prevent_published_knowledge_version_mutation() from public, anon, authenticated;

create or replace function public.create_skill_draft(
  draft_skill_key text,
  draft_name text,
  draft_description text,
  draft_version text,
  draft_rules jsonb,
  draft_test_cases jsonb,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.skill_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  definition public.skill_definitions;
  previous_event public.skill_knowledge_command_events;
  created_version public.skill_versions;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
begin
  if btrim(draft_skill_key) !~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$'
     or char_length(btrim(draft_name)) not between 1 and 240
     or char_length(btrim(draft_description)) not between 1 and 4000
     or btrim(draft_version) !~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
     or jsonb_typeof(draft_rules) <> 'object'
     or not (draft_rules ?& array[
       'applicableWhen', 'excludedWhen', 'requiredInputs', 'evidenceSchema',
       'steps', 'safety', 'outputConstraints', 'knowledgeScopes'
     ])
     or draft_rules - array[
       'applicableWhen', 'excludedWhen', 'requiredInputs', 'evidenceSchema',
       'steps', 'safety', 'outputConstraints', 'knowledgeScopes'
     ] <> '{}'::jsonb
     or jsonb_typeof(draft_rules -> 'steps') <> 'array'
     or jsonb_array_length(draft_rules -> 'steps') not between 1 and 100
     or jsonb_typeof(draft_test_cases) <> 'array'
     or jsonb_array_length(draft_test_cases) > 100
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Skill draft command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id
    and item.active = true
    and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Skill draft command is not authorized';
  end if;

  perform 1 from public.organizations item
  where item.id = profile.organization_id
  for update;

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'skill.create_draft' then
      raise exception 'Skill command idempotency conflict';
    end if;
    select item.* into created_version
    from public.skill_versions item
    where item.organization_id = profile.organization_id
      and item.id = previous_event.target_id;
    return created_version;
  end if;

  select item.* into definition
  from public.skill_definitions item
  where item.organization_id = profile.organization_id
    and item.skill_key = btrim(draft_skill_key)
  for update;

  if definition.id is null then
    insert into public.skill_definitions(
      organization_id, skill_key, name, description, created_by, updated_by
    ) values (
      profile.organization_id,
      btrim(draft_skill_key),
      btrim(draft_name),
      btrim(draft_description),
      actor_id,
      actor_id
    ) returning * into definition;
  else
    update public.skill_definitions
    set name = btrim(draft_name),
        description = btrim(draft_description),
        updated_by = actor_id,
        updated_at = now()
    where id = definition.id
    returning * into definition;
  end if;

  insert into public.skill_versions(
    organization_id,
    skill_definition_id,
    version,
    status,
    rules,
    test_cases,
    created_by,
    lifecycle_reason
  ) values (
    profile.organization_id,
    definition.id,
    btrim(draft_version),
    'draft',
    draft_rules,
    draft_test_cases,
    actor_id,
    normalized_reason
  ) returning * into created_version;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id,
    actor_id,
    'skill.create_draft',
    'skill_version',
    created_version.id,
    normalized_idempotency_key,
    jsonb_build_object(
      'skillKey', definition.skill_key,
      'version', created_version.version,
      'testCaseCount', jsonb_array_length(draft_test_cases)
    ),
    to_jsonb(created_version),
    normalized_reason
  );

  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id,
    actor_id,
    'skill_version_draft_created',
    'skill_version',
    created_version.id::text,
    jsonb_build_object(
      'skillKey', definition.skill_key,
      'version', created_version.version,
      'reason', normalized_reason
    )
  );
  return created_version;
end;
$$;

create or replace function public.transition_skill_version(
  target_skill_version_id uuid,
  expected_status public.skill_version_status,
  new_status public.skill_version_status,
  published_content_sha256 text,
  published_test_result jsonb,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.skill_versions
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  skill_version public.skill_versions;
  previous_event public.skill_knowledge_command_events;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  review_decision public.content_review_decision;
begin
  if char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200
     or jsonb_typeof(published_test_result) <> 'object' then
    raise exception 'Skill transition command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id
    and item.active = true
    and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Skill transition command is not authorized';
  end if;

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'skill.transition'
       or previous_event.target_id <> target_skill_version_id then
      raise exception 'Skill command idempotency conflict';
    end if;
    select item.* into skill_version
    from public.skill_versions item
    where item.organization_id = profile.organization_id
      and item.id = target_skill_version_id;
    return skill_version;
  end if;

  select item.* into skill_version
  from public.skill_versions item
  where item.organization_id = profile.organization_id
    and item.id = target_skill_version_id
  for update;
  if skill_version.id is null then raise exception 'Skill version not found'; end if;
  if skill_version.status <> expected_status then
    raise exception 'Skill version status conflict' using errcode = '40001';
  end if;

  if expected_status = 'draft' and new_status = 'review_pending' then
    review_decision := 'submitted';
    update public.skill_versions
    set status = new_status,
        submitted_by = actor_id,
        submitted_at = now(),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = skill_version.id
    returning * into skill_version;
  elsif expected_status = 'review_pending' and new_status = 'draft' then
    review_decision := 'rejected';
    update public.skill_versions
    set status = new_status,
        reviewed_by = actor_id,
        reviewed_at = now(),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = skill_version.id
    returning * into skill_version;
  elsif expected_status = 'review_pending' and new_status = 'published' then
    if actor_id = skill_version.created_by
       or btrim(published_content_sha256) !~ '^[0-9a-f]{64}$' then
      raise exception 'Skill publication requires independent review and a content hash';
    end if;
    review_decision := 'approved';
    update public.skill_versions
    set status = new_status,
        content_sha256 = btrim(published_content_sha256),
        test_result = published_test_result,
        reviewed_by = actor_id,
        reviewed_at = now(),
        published_by = actor_id,
        published_at = now(),
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = skill_version.id
    returning * into skill_version;
  elsif expected_status = 'published' and new_status = 'deprecated' then
    update public.skill_versions
    set status = new_status,
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = skill_version.id
    returning * into skill_version;
  elsif expected_status = 'deprecated' and new_status = 'archived' then
    update public.skill_versions
    set status = new_status,
        lifecycle_reason = normalized_reason,
        updated_at = now()
    where id = skill_version.id
    returning * into skill_version;
  else
    raise exception 'Skill version transition is not allowed';
  end if;

  if review_decision is not null then
    insert into public.skill_version_reviews(
      organization_id, skill_version_id, reviewer_profile_id, decision, reason, test_result
    ) values (
      profile.organization_id,
      skill_version.id,
      actor_id,
      review_decision,
      normalized_reason,
      published_test_result
    );
  end if;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id,
    actor_id,
    'skill.transition',
    'skill_version',
    skill_version.id,
    normalized_idempotency_key,
    jsonb_build_object('expectedStatus', expected_status, 'newStatus', new_status),
    to_jsonb(skill_version),
    normalized_reason
  );

  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id,
    actor_id,
    'skill_version_transitioned',
    'skill_version',
    skill_version.id::text,
    jsonb_build_object(
      'fromStatus', expected_status,
      'toStatus', new_status,
      'reason', normalized_reason
    )
  );
  return skill_version;
end;
$$;

create or replace function public.assign_skill_version(
  target_skill_version_id uuid,
  assignment_scope public.content_assignment_scope,
  scope_id uuid,
  assigned_active_from timestamptz,
  assigned_expires_at timestamptz,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.skill_assignments
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  skill_version public.skill_versions;
  previous_event public.skill_knowledge_command_events;
  created_assignment public.skill_assignments;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  effective_from timestamptz := coalesce(assigned_active_from, now());
begin
  if assignment_scope = 'skill_version'
     or (assignment_scope = 'organization' and scope_id is not null)
     or (assignment_scope <> 'organization' and scope_id is null)
     or (assigned_expires_at is not null and assigned_expires_at <= effective_from)
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Skill assignment command is invalid';
  end if;

  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id
    and item.active = true
    and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Skill assignment command is not authorized';
  end if;

  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'skill.assign' then
      raise exception 'Skill command idempotency conflict';
    end if;
    select item.* into created_assignment
    from public.skill_assignments item
    where item.organization_id = profile.organization_id
      and item.id = previous_event.target_id;
    return created_assignment;
  end if;

  select item.* into skill_version
  from public.skill_versions item
  where item.organization_id = profile.organization_id
    and item.id = target_skill_version_id
    and item.status = 'published';
  if skill_version.id is null then raise exception 'Published Skill version not found'; end if;

  if assignment_scope = 'project' and not exists (
    select 1 from public.ops_projects item
    where item.organization_id = profile.organization_id and item.id = scope_id
  ) then raise exception 'Skill assignment project not found'; end if;
  if assignment_scope = 'profile' and not exists (
    select 1 from public.ops_profiles item
    where item.organization_id = profile.organization_id
      and item.id = scope_id and item.active = true and item.status = 'active'
  ) then raise exception 'Skill assignment profile not found'; end if;
  if assignment_scope = 'device' and not exists (
    select 1 from public.glasses_devices item
    where item.organization_id = profile.organization_id
      and item.id = scope_id and item.revoked_at is null
  ) then raise exception 'Skill assignment device not found'; end if;

  update public.skill_assignments assignment
  set status = 'revoked',
      revoked_by = actor_id,
      revoked_at = now(),
      revoke_reason = 'replaced_by_assignment'
  where assignment.organization_id = profile.organization_id
    and assignment.status = 'active'
    and assignment.scope_type = assignment_scope
    and coalesce(assignment.project_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when assignment_scope = 'project' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and coalesce(assignment.profile_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when assignment_scope = 'profile' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and coalesce(assignment.device_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when assignment_scope = 'device' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and exists (
      select 1 from public.skill_versions existing_version
      where existing_version.organization_id = assignment.organization_id
        and existing_version.id = assignment.skill_version_id
        and existing_version.skill_definition_id = skill_version.skill_definition_id
    );

  insert into public.skill_assignments(
    organization_id, skill_version_id, scope_type, project_id, profile_id, device_id,
    active_from, expires_at, assigned_by, assignment_reason
  ) values (
    profile.organization_id,
    skill_version.id,
    assignment_scope,
    case when assignment_scope = 'project' then scope_id end,
    case when assignment_scope = 'profile' then scope_id end,
    case when assignment_scope = 'device' then scope_id end,
    effective_from,
    assigned_expires_at,
    actor_id,
    normalized_reason
  ) returning * into created_assignment;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id,
    actor_id,
    'skill.assign',
    'skill_assignment',
    created_assignment.id,
    normalized_idempotency_key,
    jsonb_build_object(
      'skillVersionId', skill_version.id,
      'scopeType', assignment_scope,
      'scopeId', scope_id
    ),
    to_jsonb(created_assignment),
    normalized_reason
  );

  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id,
    actor_id,
    'skill_version_assigned',
    'skill_assignment',
    created_assignment.id::text,
    jsonb_build_object(
      'skillVersionId', skill_version.id,
      'scopeType', assignment_scope,
      'scopeId', scope_id,
      'reason', normalized_reason
    )
  );
  return created_assignment;
end;
$$;

create or replace function public.revoke_skill_assignment(
  target_assignment_id uuid,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.skill_assignments
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  assignment public.skill_assignments;
  previous_event public.skill_knowledge_command_events;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
begin
  if char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Skill assignment revoke command is invalid';
  end if;
  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Skill assignment revoke command is not authorized';
  end if;
  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'skill.revoke_assignment'
       or previous_event.target_id <> target_assignment_id then
      raise exception 'Skill command idempotency conflict';
    end if;
    select item.* into assignment from public.skill_assignments item
    where item.organization_id = profile.organization_id and item.id = target_assignment_id;
    return assignment;
  end if;
  select item.* into assignment
  from public.skill_assignments item
  where item.organization_id = profile.organization_id and item.id = target_assignment_id
  for update;
  if assignment.id is null then raise exception 'Skill assignment not found'; end if;
  if assignment.status = 'active' then
    update public.skill_assignments
    set status = 'revoked', revoked_by = actor_id, revoked_at = now(), revoke_reason = normalized_reason
    where id = assignment.id returning * into assignment;
  end if;
  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'skill.revoke_assignment', 'skill_assignment',
    assignment.id, normalized_idempotency_key, '{}'::jsonb, to_jsonb(assignment), normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'skill_assignment_revoked', 'skill_assignment',
    assignment.id::text, jsonb_build_object('reason', normalized_reason)
  );
  return assignment;
end;
$$;

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
     or char_length(btrim(draft_content)) not between 1 and 512000
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

  insert into public.knowledge_versions(
    organization_id, knowledge_entry_id, version, status, title, summary,
    source_type, source_reference, language, sensitivity, license,
    project_ids, device_models, skill_ids, knowledge_scopes, content, author_profile_id, lifecycle_reason
  ) values (
    profile.organization_id,
    entry.id,
    draft_version,
    'uploaded',
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
    btrim(draft_content),
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
    to_jsonb(created_version) - 'content',
    normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_version_draft_created', 'knowledge_version',
    created_version.id::text,
    jsonb_build_object(
      'knowledgeKey', entry.knowledge_key,
      'version', created_version.version,
      'reason', normalized_reason
    )
  );
  return created_version;
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
    profile.organization_id, actor_id, 'knowledge_version_transitioned', 'knowledge_version',
    knowledge_version.id::text,
    jsonb_build_object('fromStatus', expected_status, 'toStatus', new_status, 'reason', normalized_reason)
  );
  return knowledge_version;
end;
$$;

create or replace function public.grant_knowledge_version(
  target_knowledge_version_id uuid,
  grant_scope public.content_assignment_scope,
  scope_id uuid,
  granted_active_from timestamptz,
  granted_expires_at timestamptz,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.knowledge_grants
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  knowledge_version public.knowledge_versions;
  previous_event public.skill_knowledge_command_events;
  created_grant public.knowledge_grants;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
  effective_from timestamptz := coalesce(granted_active_from, now());
begin
  if (grant_scope = 'organization' and scope_id is not null)
     or (grant_scope <> 'organization' and scope_id is null)
     or (granted_expires_at is not null and granted_expires_at <= effective_from)
     or char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Knowledge grant command is invalid';
  end if;
  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge grant command is not authorized';
  end if;
  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.grant' then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into created_grant from public.knowledge_grants item
    where item.organization_id = profile.organization_id and item.id = previous_event.target_id;
    return created_grant;
  end if;
  select item.* into knowledge_version
  from public.knowledge_versions item
  where item.organization_id = profile.organization_id
    and item.id = target_knowledge_version_id
    and item.status = 'published'
    and (item.expires_at is null or item.expires_at > now());
  if knowledge_version.id is null then raise exception 'Published knowledge version not found'; end if;

  if grant_scope = 'project' and not exists (
    select 1 from public.ops_projects item
    where item.organization_id = profile.organization_id and item.id = scope_id
  ) then raise exception 'Knowledge grant project not found'; end if;
  if grant_scope = 'profile' and not exists (
    select 1 from public.ops_profiles item
    where item.organization_id = profile.organization_id
      and item.id = scope_id and item.active = true and item.status = 'active'
  ) then raise exception 'Knowledge grant profile not found'; end if;
  if grant_scope = 'device' and not exists (
    select 1 from public.glasses_devices item
    where item.organization_id = profile.organization_id
      and item.id = scope_id and item.revoked_at is null
  ) then raise exception 'Knowledge grant device not found'; end if;
  if grant_scope = 'skill_version' and not exists (
    select 1 from public.skill_versions item
    where item.organization_id = profile.organization_id
      and item.id = scope_id and item.status = 'published'
  ) then raise exception 'Knowledge grant Skill version not found'; end if;

  update public.knowledge_grants grant_item
  set status = 'revoked', revoked_by = actor_id, revoked_at = now(), revoke_reason = 'replaced_by_grant'
  where grant_item.organization_id = profile.organization_id
    and grant_item.status = 'active'
    and grant_item.scope_type = grant_scope
    and coalesce(grant_item.project_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when grant_scope = 'project' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and coalesce(grant_item.profile_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when grant_scope = 'profile' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and coalesce(grant_item.device_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when grant_scope = 'device' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and coalesce(grant_item.skill_version_id, '00000000-0000-0000-0000-000000000000'::uuid)
      = coalesce(case when grant_scope = 'skill_version' then scope_id end, '00000000-0000-0000-0000-000000000000'::uuid)
    and exists (
      select 1 from public.knowledge_versions existing_version
      where existing_version.organization_id = grant_item.organization_id
        and existing_version.id = grant_item.knowledge_version_id
        and existing_version.knowledge_entry_id = knowledge_version.knowledge_entry_id
    );

  insert into public.knowledge_grants(
    organization_id, knowledge_version_id, scope_type, project_id, profile_id, device_id,
    skill_version_id, active_from, expires_at, granted_by, grant_reason
  ) values (
    profile.organization_id,
    knowledge_version.id,
    grant_scope,
    case when grant_scope = 'project' then scope_id end,
    case when grant_scope = 'profile' then scope_id end,
    case when grant_scope = 'device' then scope_id end,
    case when grant_scope = 'skill_version' then scope_id end,
    effective_from,
    granted_expires_at,
    actor_id,
    normalized_reason
  ) returning * into created_grant;

  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.grant', 'knowledge_grant',
    created_grant.id, normalized_idempotency_key,
    jsonb_build_object(
      'knowledgeVersionId', knowledge_version.id,
      'scopeType', grant_scope,
      'scopeId', scope_id
    ),
    to_jsonb(created_grant), normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_version_granted', 'knowledge_grant',
    created_grant.id::text,
    jsonb_build_object(
      'knowledgeVersionId', knowledge_version.id,
      'scopeType', grant_scope,
      'scopeId', scope_id,
      'reason', normalized_reason
    )
  );
  return created_grant;
end;
$$;

create or replace function public.revoke_knowledge_grant(
  target_grant_id uuid,
  actor_id uuid,
  command_reason text,
  idempotency_key text
)
returns public.knowledge_grants
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile public.ops_profiles;
  grant_item public.knowledge_grants;
  previous_event public.skill_knowledge_command_events;
  normalized_reason text := btrim(command_reason);
  normalized_idempotency_key text := btrim(idempotency_key);
begin
  if char_length(normalized_reason) not between 3 and 1000
     or char_length(normalized_idempotency_key) not between 1 and 200 then
    raise exception 'Knowledge grant revoke command is invalid';
  end if;
  select item.* into profile
  from public.ops_profiles item
  where item.id = actor_id and item.active = true and item.status = 'active';
  if profile.id is null or not (profile.role in ('super_admin', 'ops_admin')) then
    raise exception 'Knowledge grant revoke command is not authorized';
  end if;
  select item.* into previous_event
  from public.skill_knowledge_command_events item
  where item.organization_id = profile.organization_id
    and item.idempotency_key = normalized_idempotency_key;
  if previous_event.id is not null then
    if previous_event.command_type <> 'knowledge.revoke_grant'
       or previous_event.target_id <> target_grant_id then
      raise exception 'Knowledge command idempotency conflict';
    end if;
    select item.* into grant_item from public.knowledge_grants item
    where item.organization_id = profile.organization_id and item.id = target_grant_id;
    return grant_item;
  end if;
  select item.* into grant_item
  from public.knowledge_grants item
  where item.organization_id = profile.organization_id and item.id = target_grant_id
  for update;
  if grant_item.id is null then raise exception 'Knowledge grant not found'; end if;
  if grant_item.status = 'active' then
    update public.knowledge_grants
    set status = 'revoked', revoked_by = actor_id, revoked_at = now(), revoke_reason = normalized_reason
    where id = grant_item.id returning * into grant_item;
  end if;
  insert into public.skill_knowledge_command_events(
    organization_id, actor_profile_id, command_type, target_type, target_id,
    idempotency_key, request_payload, result_snapshot, reason
  ) values (
    profile.organization_id, actor_id, 'knowledge.revoke_grant', 'knowledge_grant',
    grant_item.id, normalized_idempotency_key, '{}'::jsonb, to_jsonb(grant_item), normalized_reason
  );
  insert into public.audit_events(
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    profile.organization_id, actor_id, 'knowledge_grant_revoked', 'knowledge_grant',
    grant_item.id::text, jsonb_build_object('reason', normalized_reason)
  );
  return grant_item;
end;
$$;

revoke all on function public.create_skill_draft(
  text, text, text, text, jsonb, jsonb, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.create_skill_draft(
  text, text, text, text, jsonb, jsonb, uuid, text, text
) to service_role;

revoke all on function public.transition_skill_version(
  uuid, public.skill_version_status, public.skill_version_status, text, jsonb, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.transition_skill_version(
  uuid, public.skill_version_status, public.skill_version_status, text, jsonb, uuid, text, text
) to service_role;

revoke all on function public.assign_skill_version(
  uuid, public.content_assignment_scope, uuid, timestamptz, timestamptz, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.assign_skill_version(
  uuid, public.content_assignment_scope, uuid, timestamptz, timestamptz, uuid, text, text
) to service_role;

revoke all on function public.revoke_skill_assignment(uuid, uuid, text, text)
from public, anon, authenticated;
grant execute on function public.revoke_skill_assignment(uuid, uuid, text, text)
to service_role;

revoke all on function public.create_knowledge_draft(
  text, text, text, text, text, text, text, text, uuid[], text[], text[], text[], text,
  integer, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.create_knowledge_draft(
  text, text, text, text, text, text, text, text, uuid[], text[], text[], text[], text,
  integer, uuid, text, text
) to service_role;

revoke all on function public.transition_knowledge_version(
  uuid, public.knowledge_version_status, public.knowledge_version_status, text, text, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.transition_knowledge_version(
  uuid, public.knowledge_version_status, public.knowledge_version_status, text, text, uuid, text, text
) to service_role;

revoke all on function public.grant_knowledge_version(
  uuid, public.content_assignment_scope, uuid, timestamptz, timestamptz, uuid, text, text
) from public, anon, authenticated;
grant execute on function public.grant_knowledge_version(
  uuid, public.content_assignment_scope, uuid, timestamptz, timestamptz, uuid, text, text
) to service_role;

revoke all on function public.revoke_knowledge_grant(uuid, uuid, text, text)
from public, anon, authenticated;
grant execute on function public.revoke_knowledge_grant(uuid, uuid, text, text)
to service_role;
