alter table public.workflow_evidence_uploads
  add column if not exists cancelled_at timestamptz;

alter table public.media_assets
  drop constraint if exists media_assets_upload_status_check;

alter table public.media_assets
  add constraint media_assets_upload_status_check
  check (upload_status in (
    'local_saved', 'queued', 'uploading', 'synced', 'failed', 'deleted', 'cancelled'
  ));

create index if not exists workflow_evidence_uploads_cancelled_idx
  on public.workflow_evidence_uploads(organization_id, cancelled_at desc)
  where cancelled_at is not null;
