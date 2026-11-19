# WP01-04 通用平台技术骨架 实施计划

## Context

WP01-01~03 已完成并推送（最新提交 `1e5d999`）：仓库与 CI、Windows 工作助手、数据库迁移/RLS/备份恢复基线。WP01-04 要在同一套真实依赖（PostgreSQL + MinIO + Redis）上补齐产品壳的五条能力，并留下可复核证据。

当前缺口（已核实）：
- `RequestContext` 只有 `tenantId/correlationId/traceId`，没有认证主体占位；应用侧从不设置 `app.tenant_id`。
- 没有幂等表/过滤器，重复 POST 会重复写 outbox。
- 没有作业表、文件表、死信查询与回放入口。
- 没有 Problem Details 统一 advice；异常响应结构不统一。
- 没有 S3 预签名能力（无 minio SDK），没有文件下载再授权。
- 日志只有 pattern，没有结构化输出；没有业务指标；Worker 不绑定事件里的 trace/tenant 上下文。
- 前端只有单页健康检查，无登录壳、路由、空/错/加载统一组件。

目标验收：重复请求不产生重复副作用；文件 URL 不能跨主体复用；同一 trace 能串起页面、API、数据库与 Worker。

已定设计（沿用上轮结论）：
- 新表走 V4，按 `backend/db/templates/tenant-table.template.sql` 开 RLS+FORCE。
- `platform_outbox` 保持不开 RLS：Worker 必须跨租户抢占投递；API 侧死信查询强制 `tenant_id` 过滤。该取舍写入迁移注释与运行手册。
- 预签名用自写 SigV4（不引 minio SDK，避免供应链风险），用 curl 真实 PUT/GET 取证。
- 前端不引路由库，用 hash 路由 + 状态机视图 + 共享 `AsyncState`。

## 一、数据库（V4）

新增 `backend/db/src/main/resources/db/migration/V4__platform_skeleton.sql`：

| 表 | 关键列 | 说明 |
|---|---|---|
| `platform_idempotency` | `id, tenant_id, subject_id, endpoint, idempotency_key, request_hash, status(in_progress/completed), response_status, response_body jsonb, response_headers jsonb, created_at, completed_at, expires_at` | 唯一键 `(tenant_id, subject_id, endpoint, idempotency_key)`；RLS+FORCE |
| `platform_job` | `id, tenant_id, subject_id, job_type, status(queued/running/succeeded/failed/cancelled), progress 0..100, cancel_requested, payload jsonb, result jsonb, last_error, trace_id, correlation_id, requested_at/started_at/finished_at` | RLS+FORCE；`(tenant_id, requested_at desc)` 索引 |
| `platform_file` | `id, tenant_id, subject_id, file_name, content_type, size_bytes, sha256, object_key unique, status(pending_upload/uploaded/available/rejected), scan_status(pending/scanning/clean/infected/failed), scan_detail, uploaded_at/scanned_at` | RLS+FORCE；`(tenant_id, created_at desc)` 索引 |
| `platform_outbox_replay` | `id, event_id, tenant_id, reason not null, replayed_by, replayed_at` | 安全回放审计；RLS+FORCE |

同时：为 `platform_outbox` 补充注释说明「为何无 RLS、跨租户投递的治理边界」；新表沿用默认权限（`ALTER DEFAULT PRIVILEGES` 已覆盖）。

需要同步修改的既有断言：
- `scripts/db-verify.ps1` 中「3 个版本」改为 4；
- `tests/db/40-integrity.sql`、`tests/db/10-rls-cases.sql` 若有表数量/RLS 断言，补 V4 新表。

## 二、后端① 上下文、认证主体、租户事务、Problem Details

`backend/modules/platform`：
- `RequestContext` 增加 `subjectId`（可为空字符串→null 归一），保留四参便捷构造器以少改调用点。
- 新增 `TenantTransactions`：`TransactionTemplate` 内执行 `select set_config('app.tenant_id', ?, true)`、`app.subject_id`、`app.trace_id`，把 RLS 上下文与业务写库绑在同一事务；无租户时直接拒绝。
- 新增 `ApiException`（code/status/detail）与 `ProblemDetailsFactory`（补 `code/correlationId/traceId/errors/instance`）。

`backend/boot-api`：
- `RequestContextBindingFilter` 解析 `X-Subject-Id`（占位认证，写入 MDC `subject_id`）；租户/主体都只在头里声明，后续由 WP02-02 换成真实会话。
- 新增 `ProblemDetailsAdvice`（`@RestControllerAdvice` 继承 `ResponseEntityExceptionHandler`），覆盖参数校验、`ApiException`、`ResponseStatusException` 与兜底异常；响应头透传 trace。
- `pom.xml` 增加 `spring-boot-starter-validation`。

## 三、后端② 幂等、作业、死信与安全回放

- `IdempotencyService`（platform 模块，JDBC）：`claim` 先插入 `in_progress`；唯一冲突时读已有记录：`completed` 直接重放缓存响应，`in_progress` 返回 409。
- `IdempotencyFilter`（boot-api 根包，Order 在上下文过滤器之后）：仅拦截 `POST/PUT/PATCH/DELETE` 且带 `Idempotency-Key` 的 `/api/v1/**`；对「租户+主体+方法+路径+请求体 SHA-256」做指纹，冲突指纹返回 422；成功/失败响应都缓存（排除 5xx）。
- `JobService/JobStore/JobController`：
  - `POST /api/v1/system/jobs`：同一事务内插 `platform_job` 并 publish `platform.job.requested.v1`（事件带 jobId/tenantId/trace）。
  - `GET /api/v1/system/jobs`、`GET /api/v1/system/jobs/{id}`、`POST /api/v1/system/jobs/{id}/cancel`（置 `cancel_requested=true`）。
- `DeadLetterController`：
  - `GET /api/v1/system/outbox/dead-letters`：`WHERE tenant_id = current tenant AND status='dead'`。
  - `POST /api/v1/system/outbox/dead-letters/{eventId}/replay`：`reason` 必填（否则 400/422），同租户校验，重置 `attempts=0,status='pending',available_at=now()`，写 `platform_outbox_replay` 审计。

Worker（`backend/boot-worker`）：
- `OutboxDispatcher` 在投递前后设置/清理 `RequestContextHolder` 与 MDC（tenant/correlation/trace/subject），并记录 `platform.outbox.dispatched/failed` 指标。
- 新增 `JobRequestedEventHandler`：绑定事件租户上下文 → `running` → 分步推进 progress（每步查 `cancel_requested`）→ `succeeded/cancelled/failed`；每步用 `TenantTransactions`。
- `boot-worker/pom.xml` 增加 `platform` 模块依赖。

## 四、后端③ 文件、预签名、扫描状态、下载再授权

`backend/modules/platform`：
- `ObjectStorageProperties`（endpoint/bucket/region/accessKey/secretKey/uploadTtl/downloadTtl）与 `S3Presigner`（SigV4 query 签名，path-style，URI 编码按 AWS 规则）。
- `FileStore/FileService`：创建上传会话（`pending_upload`，返回预签名 PUT）、完成上传（HEAD 对象，置 `uploaded` 并 publish `platform.file.uploaded.v1`）、下载再授权（校验同租户且 `subject_id == 当前主体`，`scan_status='clean'` 才发短 TTL 预签名 GET；否则 403/409）。

`backend/boot-api`：
- `FileController`：`POST /api/v1/system/files/uploads`、`POST /api/v1/system/files/{id}/complete`、`GET /api/v1/system/files`、`GET /api/v1/system/files/{id}/download`。
- `application.yml` 增加 `platform.storage.*` 配置（密钥只从环境变量读，默认值仅本地）。

Worker：`FileUploadedEventHandler` 做扫描占位（content-type 白名单 + 文件名/大小规则，命中 `eicar`/可执行扩展名→`infected`），更新 `scan_status` 与 `status`，publish `platform.file.scanned.v1`；指标 `platform.files.scanned{result}`。

## 五、后端④ 日志、指标、追踪、告警

- `application.yml`（api/worker）：`logging.structured.format.console: ecs`（Boot 4 内置，JSON 行含 MDC），保留本地可读性由 profile 覆盖；API 暴露 `metrics`（`health,info,metrics`）；worker 记录指标并写汇总日志。
- `PlatformMetrics`：`platform.http.requests{status}`、`platform.idempotency{result}`、`platform.jobs{state}`、`platform.files.scanned{result}`、`platform.outbox.{dispatched,failed,replayed}`。
- `HttpMetricsFilter`（boot-api）统计状态码与耗时（不记录路径参数与正文）。
- 日志默认不记录正文：过滤器/服务只记录 ID、状态、耗时；请求体仅计算哈希。
- `deploy/observability/grafana-dashboard.json`：五个指标面板 + trace 检索说明。
- 告警规则写入 `docs/runbooks/observability.md`（比率/阈值 + 处置动作）。

## 六、后端⑤ 契约

- `contracts/openapi/platform-api.v1.json`：新增 jobs/files/dead-letters 端点与 schema；`Problem` 补 `traceId/errors`。
- `contracts/events/`：新增 `platform.job.requested.v1.json`、`platform.file.uploaded.v1.json`、`platform.file.scanned.v1.json`，登记 `event-catalog.json`。
- 执行 `node contracts/tools/contract-check.mjs --update` 重新冻结基线（新增必填 schema 字段会被判破坏性变更，按治理流程重冻结并在提交说明中记录）。

## 七、前端登录壳与页面状态

`frontend/packages/api-client`：
- `ApiClientOptions` 增加 `tenantId/subjectId`（或 `credentialsProvider`）；请求自动带 `X-Tenant-Id`、`X-Subject-Id`、`Idempotency-Key`（变更类请求默认生成）。
- 新增方法：`createJob/listJobs/getJob/cancelJob/createUploadSession/completeUpload/listFiles/getDownloadUrl/listDeadLetters/replayDeadLetter`。

`frontend/apps/web/src`：
- `router.ts`：`#/login`、`#/home`、`#/jobs`、`#/files` 的解析/跳转/`popstate` 订阅。
- `session.ts`：sessionStorage 保存租户/主体；退出清空。
- `components/AsyncState.tsx`：统一 loading/empty/error/partial 四态，`aria-live`、`role="alert"`、重试按钮。
- `views/LoginView.tsx`（占位认证，校验 UUID）、`HomeView.tsx`（健康 + 最近 trace）、`JobsView.tsx`（创建/进度轮询/取消）、`FilesView.tsx`（上传→完成→下载，展示扫描状态）。
- `App.tsx`：登录壳、导航、跳过链接、当前路由焦点管理、错误边界。
- `design-system`：补 `focusRing`、`color.warning/info`、控件高度 token，更新 `css-variables.test.ts`。
- 测试：`router.test.ts`、`AsyncState.test.tsx`、重写 `App.test.tsx`，api-client 头与幂等键测试。

## 八、验证与证据（真实链路）

新增 `scripts/platform-skeleton-verify.ps1`（UTF-8 BOM，PowerShell 5.1）：
1. 解析 `deploy/compose/.env`；WSL 模式下用 `wsl hostname -I` 的 IP 作为 DB/MinIO 主机。
2. 以 owner 建/重置两个租户与测试主体；构建并后台启动 API/Worker（`-Djacoco.skip=true`，worker `max-attempts=1 retry-delay=0` 便于造死信）。
3. **幂等**：同一 `Idempotency-Key` 连发两次创建作业 → 两次响应 `jobId` 相同，`SELECT count(*) FROM platform_job` 只 +1、`platform_outbox` 只 +1；换主体重复 → 不命中缓存（不同作业）。
4. **作业**：轮询到 `succeeded`，trace 拼接；再创建一个作业立即取消 → `cancelled`。
5. **死信回放**：创建 `job_type=fail-demo` → outbox 进 `dead`；无 `reason` 回放 → 422；带 `reason` 回放 → `pending` 且 `platform_outbox_replay` 有审计行。
6. **文件**：预签名 PUT 上传 → complete → 等 `scan_status=clean` → GET 下载 URL 取回内容一致；另一主体请求下载 → 403；跨租户（伪造 `X-Tenant-Id`）→ 403/404；TTL 到期后 URL → 403（MinIO 响应）。
7. **观测**：`GET /actuator/metrics/platform.jobs.created` 等；API/Worker 日志按 trace 过滤出同一条链；日志中 `SECRET_MARKER`（请求体标记）出现次数必须为 0。
8. 输出逐节 `==` 断言与结论，退出码非零即失败。

产出证据目录 `docs/evidence/wp01-04/`：`db-migration.txt`、`idempotency.txt`、`jobs.txt`、`dead-letter-replay.txt`、`files.txt`、`trace.txt`、`metrics.txt`、`log-hygiene.txt`、`frontend-verify.txt`、`README.md`。

文档：
- `docs/runbooks/platform-skeleton.md`（幂等冲突、死信回放审批、文件卡扫描、Worker 停机、跨主体下载拒绝的处置）。
- `docs/runbooks/observability.md`（结构化日志字段、指标含义、告警阈值、trace 检索 SQL）。
- 更新 `IMPLEMENTATION_PLAN.md` WP01-04 五条勾选 + 完成记录表。
- 更新 `scripts/db-verify.ps1` 版本数断言并重跑归档。

## 九、执行顺序与提交

1. V4 迁移 + db-verify 断言修正 → 本地跑 `scripts/db-verify.ps1`。
2. platform 模块（上下文/事务/幂等/作业/文件/S3）。
3. boot-api（过滤器、advice、controller、指标、配置）。
4. boot-worker（上下文绑定、两个 handler）。
5. 契约 + baseline 重冻结。
6. 前端 api-client → web 壳与页面 → 测试。
7. 全量后端构建（`mvn -B verify`，中文路径加 `-Djacoco.skip=true`）、前端 `pnpm verify`。
8. `scripts/platform-skeleton-verify.ps1` 取证，归档 docs/evidence/wp01-04。
9. 回填计划，`git commit -F var/commit-msg.txt`（`feat(wp01-04): 通用平台技术骨架`），push，盯 CI 至全绿并归档 `docs/evidence/wp01-04/ci-run.txt`。

## 风险与边界

- 占位认证（`X-Subject-Id`）只用于骨架与负例演示，不是身份系统；WP02-02 必须替换，运行手册标注「禁止在共享环境启用」。
- 预签名 URL 是 bearer 凭证，跨主体防护靠「签发前再授权 + 短 TTL + 对象键含租户/文件 ID」，无法做到单次使用；文档明确该边界。
- 死信回放仅重投事件，不做业务补偿；`reason` 与操作人落审计表。
- Worker 指标在无 Web 端口时只进内存/日志，GA 阶段接 OTLP（`deploy/observability` 已有 collector）。
- 结构化日志切换可能影响既有 CI 日志断言；若 Boot 4 的 `ecs` 属性不可用，回退为 logback + 自定义 JSON encoder（不引第三方 encoder）。
