create table public.glasses_device_tokens (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.glasses_devices(id) on delete cascade,
  token_hash text not null unique,
  status text not null default 'active' check (status in ('active', 'revoked', 'expired')),
  expires_at timestamptz not null,
  issued_by uuid not null references public.ops_profiles(id),
  issued_at timestamptz not null default now(),
  revoked_at timestamptz,
  revoked_by uuid references public.ops_profiles(id),
  revoke_reason text not null default ''
);

create unique index glasses_device_tokens_active_device_idx
on public.glasses_device_tokens(device_id)
where status = 'active';

alter table public.task_events
add column occurred_at timestamptz not null default now();

create index task_events_task_occurred_idx on public.task_events(task_id, occurred_at);

alter table public.glasses_device_tokens enable row level security;

create policy "organization administrators can read device tokens"
on public.glasses_device_tokens for select to authenticated
using (
  exists (
    select 1 from public.glasses_devices device
    join public.ops_profiles profile on profile.organization_id = device.organization_id
    where device.id = glasses_device_tokens.device_id
      and profile.id = auth.uid()
      and profile.active = true
      and profile.role in ('super_admin', 'ops_admin')
  )
);

create or replace function public.rotate_glasses_device_token(
  target_device_id uuid,
  new_token_hash text,
  new_expires_at timestamptz,
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
  select organization_id into target_organization_id
  from public.glasses_devices
  where id = target_device_id;

  if target_organization_id is null or not exists (
    select 1 from public.ops_profiles
    where id = actor_id
      and organization_id = target_organization_id
      and active = true
      and role in ('super_admin', 'ops_admin')
  ) then
    raise exception 'device credential rotation forbidden';
  end if;

  update public.glasses_device_tokens
  set status = 'revoked', revoked_at = now(), revoked_by = actor_id, revoke_reason = 'rotated'
  where device_id = target_device_id and status = 'active';

  insert into public.glasses_device_tokens (device_id, token_hash, expires_at, issued_by)
  values (target_device_id, new_token_hash, new_expires_at, actor_id);
end;
$$;

revoke all on function public.rotate_glasses_device_token(uuid, text, timestamptz, uuid) from public;
grant execute on function public.rotate_glasses_device_token(uuid, text, timestamptz, uuid) to service_role;
