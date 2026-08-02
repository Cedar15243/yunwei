alter table public.media_assets
  add column if not exists duration_seconds integer not null default 0
  check (duration_seconds >= 0 and duration_seconds <= 86400);
