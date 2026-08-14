-- Keep the RLS membership helper available only to trusted database roles.
revoke all on function public.is_ops_organization_member(uuid) from public, anon;
grant execute on function public.is_ops_organization_member(uuid) to authenticated, service_role;
