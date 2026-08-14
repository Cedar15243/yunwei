delete from public.ops_assets as asset
where asset.asset_tag = 'ASSET-CONSOLE-001'
  and asset.display_name = 'SSH console recovery demo server'
  and asset.host = '192.168.1.50'
  and not exists (
    select 1
    from public.ops_sessions as session
    where session.target_asset_id = asset.id
  );
