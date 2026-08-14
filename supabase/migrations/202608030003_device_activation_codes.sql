create table public.device_activation_codes (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  device_id uuid not null references public.glasses_devices(id) on delete cascade,
  code_hash text not null unique check (code_hash ~ '^[0-9a-f]{64}$'),
  status text not null default 'pending'
    check (status in ('pending', 'used', 'expired', 'revoked')),
  expires_at timestamptz not null,
  issued_by uuid not null references public.ops_profiles(id),
  reason text not null,
  issued_at timestamptz not null default now(),
  consumed_at timestamptz,
  consumed_device_instance_hash text
    check (consumed_device_instance_hash is null or consumed_device_instance_hash ~ '^[0-9a-f]{64}$'),
  consumed_package_name text not null default '',
  consumed_app_version text not null default '',
  consumed_device_model text not null default ''
);

create unique index device_activation_codes_pending_device_idx
on public.device_activation_codes(device_id)
where status = 'pending';

create index device_activation_codes_expiry_idx
on public.device_activation_codes(status, expires_at);

alter table public.device_activation_codes enable row level security;

create policy "super administrators can read device activation audit"
on public.device_activation_codes for select to authenticated
using (
  exists (
    select 1
    from public.ops_profiles actor
    where actor.id = auth.uid()
      and actor.organization_id = device_activation_codes.organization_id
      and actor.role = 'super_admin'
      and actor.active = true
      and actor.status = 'active'
  )
);

create or replace function public.issue_device_activation_code(
  target_device_id uuid,
  activation_code_hash text,
  expires_at timestamptz,
  actor_id uuid,
  reason text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  target_organization_id uuid;
begin
  if activation_code_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid activation code hash';
  end if;
  if nullif(btrim(reason), '') is null then
    raise exception 'device activation reason is required';
  end if;
  if not (
    expires_at >= now() + interval '5 minutes'
    and expires_at <= now() + interval '15 minutes'
  ) then
    raise exception 'device activation expiry must be between five and fifteen minutes';
  end if;

  select device.organization_id into target_organization_id
  from public.glasses_devices device
  where device.id = target_device_id
    and device.status <> 'disabled'
    and device.revoked_at is null;

  if target_organization_id is null or not exists (
    select 1
    from public.ops_profiles actor
    where actor.id = actor_id
      and actor.organization_id = target_organization_id
      and actor.role = 'super_admin'
      and actor.active = true
      and actor.status = 'active'
  ) then
    return false;
  end if;

  if not exists (
    select 1
    from public.device_bindings binding
    join public.ops_profiles profile on profile.id = binding.profile_id
    where binding.device_id = target_device_id
      and binding.organization_id = target_organization_id
      and binding.status = 'active'
      and profile.organization_id = target_organization_id
      and profile.active = true
      and profile.status = 'active'
  ) then
    return false;
  end if;

  update public.device_activation_codes
  set status = 'revoked'
  where device_id = target_device_id and status = 'pending';

  insert into public.device_activation_codes (
    organization_id,
    device_id,
    code_hash,
    expires_at,
    issued_by,
    reason
  ) values (
    target_organization_id,
    target_device_id,
    activation_code_hash,
    expires_at,
    actor_id,
    btrim(reason)
  );

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    target_organization_id,
    actor_id,
    'device_activation_issued',
    'glasses_device',
    target_device_id::text,
    jsonb_build_object('expiresAt', expires_at, 'reason', btrim(reason))
  );

  return true;
end;
$$;

create or replace function public.redeem_device_activation_code(
  candidate_code_hash text,
  claimed_device_instance_hash text,
  claimed_package_name text,
  claimed_app_version text,
  claimed_device_model text,
  new_bootstrap_token_hash text,
  new_bootstrap_expires_at timestamptz
)
returns table (
  activation_status text,
  device_id uuid,
  organization_id uuid,
  bootstrap_expires_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
declare
  activation record;
begin
  if candidate_code_hash !~ '^[0-9a-f]{64}$'
    or claimed_device_instance_hash !~ '^[0-9a-f]{64}$'
    or new_bootstrap_token_hash !~ '^[0-9a-f]{64}$' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if new_bootstrap_expires_at <= now()
    or new_bootstrap_expires_at > now() + interval '90 days' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;

  select
    code.*,
    device.status as device_status,
    device.revoked_at as device_revoked_at,
    device.model as registered_device_model
  into activation
  from public.device_activation_codes code
  join public.glasses_devices device on device.id = code.device_id
  where code.code_hash = candidate_code_hash
  for update of code;

  if not found then
    return query select 'invalid'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if activation.status = 'used' then
    return query select 'used'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if activation.status <> 'pending' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if activation.expires_at <= now() then
    update public.device_activation_codes
    set status = 'expired'
    where id = activation.id;
    return query select 'expired'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if activation.device_status = 'disabled' or activation.device_revoked_at is not null then
    return query select 'revoked'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if claimed_package_name <> 'com.codex.air3nativecamera.dingdangexpert.v9'
    or nullif(btrim(claimed_app_version), '') is null
    or claimed_app_version !~ '^9\.[0-9]+\.[0-9]+'
    or nullif(btrim(claimed_device_model), '') is null
    or (
      nullif(btrim(activation.registered_device_model), '') is not null
      and activation.registered_device_model <> claimed_device_model
    ) then
    return query select 'device_mismatch'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;
  if not exists (
    select 1
    from public.device_bindings binding
    join public.ops_profiles profile on profile.id = binding.profile_id
    where binding.device_id = activation.device_id
      and binding.organization_id = activation.organization_id
      and binding.status = 'active'
      and profile.organization_id = activation.organization_id
      and profile.active = true
      and profile.status = 'active'
  ) then
    return query select 'binding_revoked'::text, null::uuid, null::uuid, null::timestamptz;
    return;
  end if;

  update public.glasses_device_tokens
  set status = 'revoked',
      revoked_at = now(),
      revoked_by = activation.issued_by,
      revoke_reason = 'activation_rotated'
  where public.glasses_device_tokens.device_id = activation.device_id
    and status = 'active';

  insert into public.glasses_device_tokens (
    device_id, token_hash, expires_at, issued_by
  ) values (
    activation.device_id,
    new_bootstrap_token_hash,
    new_bootstrap_expires_at,
    activation.issued_by
  );

  update public.device_activation_codes
  set status = 'used',
      consumed_at = now(),
      consumed_device_instance_hash = claimed_device_instance_hash,
      consumed_package_name = claimed_package_name,
      consumed_app_version = claimed_app_version,
      consumed_device_model = claimed_device_model
  where id = activation.id;

  update public.glasses_devices
  set app_version = claimed_app_version,
      model = case
        when nullif(btrim(model), '') is null then claimed_device_model
        else model
      end,
      last_seen_at = now(),
      updated_at = now()
  where id = activation.device_id;

  insert into public.audit_events (
    organization_id, actor_profile_id, action, target_type, target_id, metadata
  ) values (
    activation.organization_id,
    activation.issued_by,
    'device_activation_redeemed',
    'glasses_device',
    activation.device_id::text,
    jsonb_build_object(
      'activationId', activation.id,
      'deviceInstanceHash', claimed_device_instance_hash,
      'packageName', claimed_package_name,
      'appVersion', claimed_app_version,
      'deviceModel', claimed_device_model,
      'bootstrapExpiresAt', new_bootstrap_expires_at
    )
  );

  return query select
    'activated'::text,
    activation.device_id,
    activation.organization_id,
    new_bootstrap_expires_at;
end;
$$;

revoke all on function public.issue_device_activation_code(
  uuid, text, timestamptz, uuid, text
) from public;
revoke all on function public.redeem_device_activation_code(
  text, text, text, text, text, text, timestamptz
) from public;

grant execute on function public.issue_device_activation_code(
  uuid, text, timestamptz, uuid, text
) to service_role;
grant execute on function public.redeem_device_activation_code(
  text, text, text, text, text, text, timestamptz
) to service_role;
