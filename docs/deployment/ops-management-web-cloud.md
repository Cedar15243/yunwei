# 管理平台云端部署

管理平台必须作为独立发布单元运行：独立子域名、独立目录、独立 Docker image、独立容器和 `127.0.0.1:8788` 回环端口。不得复用专家协同的 `8787`、V9 网关的 `8790`，不得把管理页面路由写进 `bb.chinacedar.top:2305` 现有站点块，也不得重启或覆盖专家协同服务。

## 部署前提

1. 为管理平台准备独立生产子域名并完成 DNS 解析与 80/443 入站配置，不能使用 `bb.chinacedar.top` 原站域名。
2. 云服务器已安装 Docker、Docker Compose v2、Python 3、`curl`、`sha256sum`；现有 Caddy 容器名为 `ai-edge-caddy`，配置文件为 `/opt/ai-edge-caddy/Caddyfile`。
3. 生产 Supabase 已应用全部 V9 migrations，并部署 `ops-glasses` Edge Function；真实 Auth、RLS、角色和项目范围已准备。
4. 部署前 `https://bb.chinacedar.top:2305/health` 必须返回 `expert-collab`，`https://bb.chinacedar.top:2305/v9-ops/health` 必须返回 `dingdang-v9-gateway`。

## 生产配置

在服务器创建仅 root 可读的 `/etc/dingdang-ops-management-web.env`：

```sh
install -m 0600 /dev/null /etc/dingdang-ops-management-web.env
editor /etc/dingdang-ops-management-web.env
```

内容使用 `.env.cloud.example` 的字段：

```dotenv
VITE_SUPABASE_URL=https://<project-ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<public-anon-key>
VITE_OPS_API_BASE_URL=https://<project-ref>.supabase.co/functions/v1/ops-glasses
OPS_MANAGEMENT_DOMAIN=<dedicated-management-domain>
OPS_MANAGEMENT_PORT=8788
```

这里只允许浏览器公开配置；禁止写入 Supabase service role、MVS token、供应商密钥、管理员 token 或设备长期凭据。安装器拒绝示例值、非 HTTPS API、原专家域名、`8787` 和 `8790`。

## 不可变发布

将完整 `ops-management-web/` 发布源传到服务器临时目录。推荐先做不改 Caddy、不占公网端口的回环预发布：

```sh
sudo ./deploy/stage.sh 20260812T160000Z-management-web-r1
```

`stage.sh` 执行以下门禁：

- 在 `/srv/dingdang-ops-management-web/releases/<release-id>` 创建只读 release，并生成 `SHA256SUMS`。
- 构建唯一 Docker image，记录并校验 image ID，禁止复用已有 release 或镜像标签。
- 以无主机端口、只读文件系统、最小 capabilities 的临时容器验证 `/health`，随后删除临时容器。
- 只写 `candidate` 指针，不改 `current`、Caddy 或公网路由；确认专家、Caddy 容器指纹不变。

DNS 已解析到当前服务器、Supabase 公共配置已确认后，再原子激活：

```sh
sudo ./deploy/activate.sh 20260812T160000Z-management-web-r1
```

`activate.sh` 会核对 candidate 文件哈希、image ID、生产配置和 DNS，再切换 `current`/`previous`，启动固定 compose project `dingdang-ops-management`，只合并受管 Caddy 站点块并热重载。任何阶段失败都会恢复原 Caddy、原 release 指针和原管理 Web image，不触碰专家目录、专家 compose 或专家容器。

生产条件已经全部满足时，也可用串联入口：

```sh
sudo ./deploy/install.sh 20260812T160000Z-management-web-r1
```

## 发布检查

```sh
OPS_MANAGEMENT_DOMAIN=<dedicated-management-domain> \
  /srv/dingdang-ops-management-web/current/deploy/health-check.sh
docker compose --project-name dingdang-ops-management \
  -f /srv/dingdang-ops-management-web/current/docker-compose.cloud.yml ps
```

浏览器访问必须先登录；匿名用户不可读取任务、项目、媒体、Skill、知识、工作流、声纹或账号信息。网页仅调用授权管理 API，媒体由服务端签发任务范围内的短期 URL。

## 回滚

```sh
sudo /srv/dingdang-ops-management-web/current/deploy/rollback.sh
```

回滚器核对 previous release 的 image ID，恢复上一份 Caddy 快照和 release 指针，再次校验管理站、专家协同与 V9 网关。回滚失败时恢复回滚前状态；禁止手工覆盖 `current` 目录或重新使用旧 image 标签构建新内容。
