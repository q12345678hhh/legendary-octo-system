# 本地运行手册

本文件是 WP01-02 的交付物之一：把「新机器怎么起、两种运行方式怎么选、单组件坏了怎么定位」写成可照做的步骤。产品范围与验收口径以 `PLAN.md` 为准。

## 1 前置条件

按 WP00-02 的版本锁定表准备，缺一不可：

| 组件 | 版本 | 检查命令 |
|---|---|---|
| JDK | 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`） | `java -version` |
| Maven | 3.9.x | `mvn -v` |
| Node / pnpm | 与 `frontend/package.json` 的 engines 一致 | `node -v`、`pnpm -v` |
| Docker | Docker Desktop，或 WSL2 发行版内的 Docker Engine | `docker info` |

> 本机默认 `JAVA_HOME` 可能仍指向 1.8。`.vscode/settings.json` 已把 VS Code 的 Java 运行时钉在 JDK 21；命令行构建请显式设置 `JAVA_HOME`。

Maven 本地仓库固定为 `D:\DevCache\maven-repo`，命令里用 `-Dmaven.repo.local` 指定，参数在 PowerShell 中必须带引号。

## 2 首次启动

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
```

脚本按顺序做五件事：缺 `deploy/compose/.env` 时从 `.env.example` 生成 → 逐端口探测占用 → 找可用的 Docker（本机 Desktop 优先，失败回退 WSL 内的 Engine）→ `up -d` → 轮询容器状态直到全部 running / healthy。

`-Reset` 会先 `down --volumes` 删掉 `pg_data`、`redis_data`、`minio_data` 再重建，数据不可恢复；`-Down` 只停容器、保留卷。

## 3 运行方式

### 3.1 方式一：VS Code 调试（日常开发）

1. 先起基础设施：任务 `topology: up`（等同第 2 节命令）。
2. 按 F5，选择：
   - `API（调试）`：`com.acme.platform.PlatformApiApplication`，监听 8080，`PLATFORM_SMOKE_ENABLED=true`。
   - `Worker（调试）`：`com.acme.platform.PlatformWorkerApplication`，无 Web 端口。
   - `API + Worker`：组合配置，一次请求里同时看两边的断点与日志。
   - `Web（Chrome）`：先跑 `web: dev`（Vite 5173），再启动 Chrome 调试，`/api` 由 Vite 代理到 8080。
   - `API（附加，端口 5005）`：进程已由命令行或容器带 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` 启动时使用。

3. 常用任务：`backend: package`、`web: dev`、`topology: up/down/reset`、`topology: up (含应用容器)`。

首次在 VS Code 打开仓库需要等 Java 语言服务器导入 Maven 工程（右下角进度条结束）再按 F5。

### 3.2 方式二：容器化验收（验证镜像与跨容器连通）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1 -App
```

与方式一的差别只有三处：

- 镜像只搬运构建产物，不在容器里跑 Maven / pnpm。缺 `boot-api`、`boot-worker` 的 jar 或 `frontend/apps/web/dist` 时脚本会先补构建。
- compose 带 `--profile app`，额外起 `api`、`worker`、`web` 三个容器，连接串用服务名（容器里的 `localhost` 是它自己）。
- 容器起来后再从宿主机验一次 `/actuator/health` 与 Web 首页，`running` 不等于能服务请求。

容器档的入口：API `http://localhost:8080`，Web `http://localhost:3000`（nginx 把 `/api/` 反代到 `api:8080`）。

只停应用容器、留基础设施：

```powershell
docker compose -f deploy/compose/compose.dev.yaml --profile app stop api worker web
```

改完代码要重新验收时，重跑 `-App` 即可：`up --build` 会重建镜像，但前提是目标 jar / dist 已经是最新构建。

### 3.3 方式三：工作助手原生 Windows 验收

工作助手必须跑在真实 Windows 桌面上，不能用容器或 CI 代替：强制排除要看真实的进程与窗口。

```powershell
dotnet publish desktop-agent\privacy-engine\WorkAssistant.PrivacyEngine.Checks\WorkAssistant.PrivacyEngine.Checks.csproj `
  -c Release -r win-x64 --self-contained false -o $env:TEMP\wa-native-publish
& "$env:TEMP\wa-native-publish\WorkAssistant.PrivacyEngine.Checks.exe" --live
```

`--live` 只读进程名与窗口标题，不读窗口正文、控件树、剪贴板或屏幕像素。判定要求：正在运行的微信 / QQ 全部排除；无法识别进程按失败即拒绝；停止采集后采集量为 0。

## 4 端口、账号与入口

默认值来自 `deploy/compose/.env.example`，改 `.env` 即可覆盖，脚本会读同一个文件。

| 变量 | 默认 | 用途 |
|---|---|---|
| `POSTGRES_PORT` | 5432 | PostgreSQL，库 `platform` |
| `REDIS_PORT` | 6379 | Redis |
| `MINIO_API_PORT` / `MINIO_CONSOLE_PORT` | 9000 / 9001 | 对象存储 API / 控制台 |
| `SMTP_PORT` / `MAILPIT_UI_PORT` | 1025 / 8025 | 邮件捕获 SMTP / 浏览器查看 |
| `OTLP_GRPC_PORT` / `OTLP_HTTP_PORT` | 4317 / 4318 | OTLP 收集端 |
| `API_PORT` / `WEB_PORT` | 8080 / 3000 | 应用容器端口（仅 `-App`） |

数据库固定两个角色，这是本拓扑的关键约束：

- `platform_owner`：建库、迁移、建表，Flyway 用。
- `platform_app`：运行期读写，`NOSUPERUSER` + `NOBYPASSRLS`。

用 owner 跑应用会让 RLS 被表所有者绕过，跨租户负例全部假绿，因此不要为图省事改运行期账号。

## 5 单组件失败怎么定位

先看整体状态，再看单个服务：

```powershell
docker compose -f deploy/compose/compose.dev.yaml ps
docker compose -f deploy/compose/compose.dev.yaml logs --tail 50 <服务名>
```

| 现象 | 定位命令 |
|---|---|
| PostgreSQL 连不上 / 迁移失败 | `docker exec -it platform-pg-dev psql -U platform_owner -d platform` |
| Redis 不健康 | `docker exec -it platform-redis-dev redis-cli ping` |
| 桶没建出来 | `docker logs platform-minio-init-dev`（一次性任务，失败后需重跑 up） |
| 邮件收不到 | 打开 `http://localhost:8025`，Mailpit 列出所有投递 |
| API 起了但请求失败 | `curl http://localhost:8080/actuator/health`，再看 API 日志里的 `trace=` 与 `corr=` |
| Worker 不消费 | 查 `platform_outbox` 的 `status` 是否为 `pending`、`available_at` 是否在未来 |

端口被占时脚本会在起栈前报出占用进程；容器运行时自身占用（`docker`、`com.docker`、`wslrelay` 等）会被放行。

## 6 开发数据重建

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1 -Reset
```

重建后是空库，Flyway 在 API 或 Worker 启动时自动迁移（`ddl-auto=validate` 只做校验，不会替你建表）。重建不需要手工改库、不需要删镜像；这与「重建不依赖手工改库」的验收口径对应。

## 7 端到端冒烟（请求 → outbox → Worker）

前提：API 带 `PLATFORM_SMOKE_ENABLED=true`（容器档默认打开）。

```powershell
# 1. 发一条冒烟事件，带租户、关联 ID 与 trace
curl.exe -s -X POST http://localhost:8080/api/v1/system/smoke/ping-events `
  -H "Content-Type: application/json" `
  -H "X-Tenant-Id: 11111111-1111-1111-1111-111111111111" `
  -H "X-Correlation-Id: corr-local-1" `
  -H "X-Trace-Id: 0123456789abcdef0123456789abcdef" `
  --data-binary '{\"message\":\"手动冒烟\"}'

# 2. 查 outbox：应看到 status=published、attempts=1，且 correlation_id / trace_id 与请求一致
docker exec platform-pg-dev psql -U platform_owner -d platform -c `
  "select event_name, status, attempts, correlation_id, trace_id from platform_outbox order by occurred_at desc limit 3"
```

3. 在 Worker 日志里找到同一条 `eventId`，`correlationId` 和 `traceId` 必须与请求头相同；日志的级别行已带 `[trace=… corr=… tenant=…]`。

PowerShell 直接内联 JSON 容易把引号吃掉导致 400。稳妥做法是先写入文件再 `--data-binary "@文件"`。

## 8 常见问题

| 现象 | 原因与处理 |
|---|---|
| `failed to connect to docker API at npipe:////./pipe/dockerDesktopLinuxEngine` | Docker Desktop 未运行。脚本会自动回退 WSL 内的 Engine；想用 Desktop 就先把它起来 |
| `wsl: 检测到 localhost 代理配置，但未镜像到 WSL` | 仅提示。WSL 走 NAT，访问宿主代理要用默认网关 IP，`deploy/wsl/dockup.sh` 已处理 |
| 起栈后过一段时间 Docker 又连不上 | WSL 发行版空闲会关停 Engine，重跑脚本即可 |
| `缺少 boot-api 的可执行 jar` | 首次或换机。`-App` 会自动 `mvn package`，也可先跑任务 `backend: package` |
| 前端 404 / 白屏 | 确认 `frontend/apps/web/dist/index.html` 存在；容器内由 nginx 回落到 `index.html`，开发模式由 Vite 处理 |
| `pnpm` 不是内部或外部命令 | 装 Node 后执行 `corepack enable pnpm` |
