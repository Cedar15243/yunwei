# 管理平台云端部署

管理平台运行在云服务器的独立容器中，不与现有专家协同服务、`bb.chinacedar.top` 的 Caddy 站点或其静态目录共用进程、端口或发布目录。

## 部署前提

1. 为管理平台准备独立子域名，并将 DNS 指向云服务器。
2. 云服务器已安装 Docker Compose 与 Caddy。
3. Supabase 中已应用 `202607310002_ops_management_foundation.sql`，并已部署更新后的 `ops-glasses` Edge Function。
4. 为网页后台创建经授权的 Supabase Auth 用户与 `ops_profiles` 记录。

## 云服务器操作

将仓库中的 `ops-management-web/` 目录传到独立目录，例如 `/srv/dingdang-ops-management-web`。从 `.env.cloud.example` 创建仅保存在服务器上的 `.env.cloud`，填写 Supabase 项目地址、匿名公钥和 Edge Function 地址。

```sh
cd /srv/dingdang-ops-management-web
cp .env.cloud.example .env.cloud
docker compose --env-file .env.cloud -f docker-compose.cloud.yml up -d --build
docker compose -f docker-compose.cloud.yml ps
```

将 `Caddyfile.ops-management.example` 作为单独站点配置，设置 `OPS_MANAGEMENT_DOMAIN` 为管理后台域名后验证并重载 Caddy：

```sh
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
```

## 发布检查

```sh
curl -I https://<管理后台域名>
docker compose -f docker-compose.cloud.yml logs --tail=100 ops-management-web
```

浏览器访问后必须先登录；匿名用户不可读取任务、项目或媒体。网页只从授权的管理 API 获取数据，媒体地址由服务端按任务权限签发短时 URL。
