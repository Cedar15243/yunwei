begin;

create unique index if not exists audit_events_voiceprint_revoke_idempotency_idx
  on public.audit_events (organization_id, action, (metadata->>'idempotencyKey'))
  where action = 'voiceprint_revoked'
    and target_type = 'voiceprint_profile'
    and nullif(btrim(metadata->>'idempotencyKey'), '') is not null;

commit;
