create unique index if not exists ops_profiles_organization_normalized_email_unique_idx
on public.ops_profiles(organization_id, lower(btrim(email)))
where btrim(email) <> '';
