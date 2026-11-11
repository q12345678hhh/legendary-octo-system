# WP01-04 证据索引

对应验收项：统一请求上下文与 Problem Details、幂等与事务 outbox、异步作业与死信回放、文件预签名与下载再授权、结构化日志/指标/追踪、前端登录壳与状态组件。
总验收口径：**重复请求不产生重复副作用；文件 URL 不能跨主体复用；同一 trace 能串起页面、API、数据库与 Worker。**

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `db-migration.txt` | 真实库上的 Flyway 版本与四张新表 RLS 状态 | 启动前后均为 `1 2 3 4`；`platform_idempotency`、`platform_job`、`platform_file`、`platform_outbox_replay` 各 1 条策略且 `rls=true force=true` |
| `idempotency.txt` | 同一 `Idempotency-Key` 连发两次 + 同键换载荷 | 两次 `201` 且 `jobId=f54c201c…` 相同、`Idempotent-Replayed: true`；`platform_job` 仅 1 行；换载荷 `422 IDEMPOTENCY_KEY_REUSED` |
| `jobs.txt` | 作业成功与取消 | `1a291da2…` 到 `succeeded`、`progress=100`、`traceId=0b6989a6…`；`9a547d56…` 到 `cancelled`、`cancelRequested=true` |
| `dead-letter-replay.txt` | `fail-demo` 重试耗尽进死信 → 无原因回放被拒 → 带原因回放 | `attempts=2`、`lastError=IllegalStateException: 演示失败…`；短原因 `422 REPLAY_REASON_REQUIRED`；有效原因 `202 status=pending`；`platform_outbox_replay` 审计 1 行 |
| `files.txt` | 预签名 PUT → complete → 扫描 → 下载 → 越权尝试 → URL 过期 | PUT `200`、`uploaded`/`clean`/`available`；下载内容与原文件一致；跨主体 `403 FILE_NOT_OWNED`；跨租户 `404 FILE_NOT_FOUND`；10s 后 MinIO 返回 `403 AccessDenied: Request has expired` |
| `metrics.txt` | `/actuator/prometheus` 与 Worker 周期汇总日志 | `platform_jobs_submitted_total 3.0`、`platform_jobs_state_total{state="queued"} 3.0`、`platform_http_requests_total` 6 个状态、`platform_idempotency_total` 3 个结果；Worker 汇总 `outbox.dispatched=5 failed=6 files.scanned=1 jobs.state=4` |
| `trace.txt` | 同一 trace 的跨进程关联 | `trace_id=0b6989a6…` 同时出现在 `platform_job` 行、API 日志（4 次）与 Worker 日志（3 次） |
| `log-hygiene.txt` | 载荷标记不得落日志 | 作业载荷里的 `SECRET_MARKER` 在两个进程日志中出现 0 次 |
| `frontend-verify.txt` | 前端 `pnpm -r typecheck / test / build` | 四个包全部通过：`lowcode-runtime` 3、`design-system` 3、`api-client` 6、`apps/web` 15 个用例；`dist/index.html` 存在 |
| `db-verify.txt` | `scripts/db-verify.ps1` 全量输出（含 V4） | 空库安装 4 个版本（`4|-2118030789`）、RLS 9/9、并发认领不同行、V2→V4 迁移 3.9s、50 万行下探针最慢 33.6ms 且超 1s 的 0 条、索引命中 `platform_outbox_dispatch_idx`、篡改 checksum 后 validate 退出码 1 |
| `ci-run.txt` | 该提交在 GitHub Actions 的结果 | 见文末（提交后补录） |

复现命令（需要 WSL 里的 PostgreSQL 与 MinIO 容器、JDK 21、pnpm）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\platform-skeleton-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1
```

## 验收项映射

| 验收项 | 落点 | 证据 |
|---|---|---|
| 统一请求上下文 / 认证主体占位 / 租户上下文 | `RequestContext`、`RequestContextHolder`、`TenantTransactions`（事务内 `set_config('app.tenant_id', …, true)`）、`HttpContextFilter` | `db-migration.txt` 的 RLS 状态；`files.txt` 跨租户 404 |
| Problem Details 与关联 ID | `ApiException` + `ProblemDetailsSupport`；响应含 `code/detail/instance/correlationId/traceId` | 各证据里 `422/403/404` 的响应体；响应头 `X-Correlation-Id`、`X-Trace-Id` |
| 事务 outbox / 幂等 inbox | `platform_outbox` 同事务写入；`Idempotency-Key` + 请求体 SHA-256 指纹 + 原响应缓存 | `idempotency.txt`（1 行作业、`Idempotent-Replayed`）、`dead-letter-replay.txt` |
| 异步作业 / 死信 / 进度 / 取消 / 安全回放 | `JobService`、`JobRequestedEventHandler`、`DeadLetterService` | `jobs.txt`、`dead-letter-replay.txt` |
| 文件预签名 / 扫描状态 / 下载再授权 / 隔离 | `S3Presigner`（自写 SigV4）、`FileService`、`FileUploadedEventHandler`；对象键 `<tenant>/<fileId>/<name>` | `files.txt`（四种结果） |
| 结构化日志 / 指标 / 追踪 / 告警 | `logging.structured.format.console=ecs`、`PlatformMetrics`、MDC 贯穿 API 与 Worker；告警见 `docs/runbooks/observability.md` | `metrics.txt`、`trace.txt`、`log-hygiene.txt` |
| 前端登录壳 / 路由 / 设计 token / 无障碍 / 状态 | `apps/web` 的 `router.ts`、`Session`、`AsyncState`、`LoginView`、`JobsView`、`FilesView`；`design-system` token 递归导出 CSS 变量 | `frontend-verify.txt`、`apps/web/src/App.test.tsx` 等 15 个用例 |

## 过程记录：失败与修复

**1. 指标名被 Micrometer 改写，取证断言落空**

首次跑取证脚本时 `platform_jobs_created_total` 为 0 行，但 `platform_jobs_state_total` 有数据。单独启动 API 打一条作业后抓取 `/actuator/prometheus` 才发现导出名是 `platform_jobs_total`：

```
platform_http_requests_total{status="201"} 1.0
platform_idempotency_total{result="stored"} 1.0
platform_jobs_total 1.0
platform_jobs_state_total{state="queued"} 1.0
```

原因：Micrometer 的 Prometheus 命名把计数器结尾的保留词 `created` 剥掉再加 `_total`，于是 `platform.jobs.created` 变成了语义含糊的 `platform_jobs_total`。
修复：计数器改名为 `platform.jobs.submitted`（导出 `platform_jobs_submitted_total`），并在 `PlatformMetrics` 里写明原因，避免后人「修回去」。

**2. Worker 侧指标没有 HTTP 出口**

`outbox.dispatched`、`files.scanned` 发生在 Worker，而 Worker 是非 Web 应用，`/actuator/prometheus` 不对外。按设计边界（无 Web 端口时只进内存/日志）改为周期汇总日志：`WorkerMetricsSummary` 每 15 秒打一条 `平台指标汇总 platform.outbox.dispatched=… files.scanned=…`，取证脚本从 Worker 日志里取数。接入 OTLP 采集器后改为抓取，汇总日志可下线。

**3. 前端证据被数组拼接污染**

`Add-Evidence 'frontend-verify.txt' (@(... ($tests -split …) -join "`n" …) -join "`n")` 里先 `-join` 再塞进数组，PowerShell 把它当成嵌套对象，产物末尾出现 `dist/index.html exists=trueSystem.Object[]`。改为先算出 `$testLines` 再与固定行相加；同时把 `pnpm -r build` 加进脚本，避免 `dist` 是上一次的旧产物。

## 口径说明

- 占位认证（`X-Tenant-Id` / `X-Subject-Id`）只用于骨架与负例演示，**禁止在共享环境启用**；真实身份在 WP02-02 替换。
- 预签名 URL 是 bearer 凭证：跨主体防护靠「签发前再授权 + 短 TTL + 对象键隔离」，无法做到单次使用；URL 过期由 MinIO 判定，应用侧不二次校验。
- 死信回放只重投事件，不做业务补偿；`reason` 与操作人写审计表，是否可安全重投由操作人确认（处理器必须幂等）。
- `platform_outbox` 刻意不开 RLS：Worker 需要跨租户认领；API 侧死信查询强制按租户过滤，该取舍写在 V3 迁移注释与运行手册里。
- 本次取证运行在本机进程 + WSL 容器依赖的开发栈上，指标为单进程内存值，不含生产容量结论。
