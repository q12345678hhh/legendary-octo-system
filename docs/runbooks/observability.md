# 可观测性手册（WP01-04）

日志、指标、追踪与告警的约定。指标名与日志字段是对外契约，改动必须同步本文件、`deploy/observability/grafana-dashboard.json` 与取证脚本。

## 1. 结构化日志

API 与 Worker 都输出 ECS 单行 JSON（`logging.structured.format.console=ecs`），开发机重定向到文件后同样是 JSON 行：

```json
{"@timestamp":"2026-11-11T05:34:28.845Z","log":{"level":"INFO","logger":"com.acme.platform.worker.WorkerMetricsSummary"},
 "process":{"pid":3616,"thread":{"name":"scheduling-1"}},
 "service":{"name":"platform-worker","version":"0.1.0-SNAPSHOT"},
 "message":"平台指标汇总 platform.http.requests=0 platform.outbox.dispatched=5","ecs":{"version":"8.11"}}
```

| 字段 | 来源 | 用途 |
|---|---|---|
| `trace_id` | API 过滤器按请求生成（或沿用 `X-Correlation-Id` 关联的链路） | 串起页面 → API → 数据库 → Worker |
| `correlation_id` | 请求头 `X-Correlation-Id`，缺省生成 | 前端可直接展示给用户报障 |
| `tenant_id` | `X-Tenant-Id`（Worker 取事件里的租户） | 按租户排障；不得作为日志正文 |
| `log.level` / `log.logger` | 框架 | 过滤与告警 |
| `process.pid` | 框架 | 区分 API 与 Worker 进程 |

**日志卫生规则**：只记录 ID、状态、耗时与错误类型；不记录请求体、文件内容、业务正文与令牌。
幂等指纹用请求体 SHA-256 计算，原文不落库也不落日志。取证脚本会把带标记的作业载荷发进系统，然后断言两个进程的日志里都搜不到该标记。

## 2. 指标

### API（`GET /actuator/prometheus`）

| 指标 | 标签 | 含义 |
|---|---|---|
| `platform_http_requests_total` | `status` | 按状态码计数的请求量，用于错误率与容量 |
| `platform_idempotency_total` | `result`=`stored`/`replayed`/`key_reused` | 幂等命中分布；`key_reused` 突增说明客户端复用键 |
| `platform_jobs_submitted_total` | — | 新建作业数 |
| `platform_jobs_state_total` | `state` | API 侧发生的状态流转（当前只有入队 `queued`） |

### Worker（无 Web 端口，周期汇总日志）

Worker 不暴露 HTTP，指标以 `平台指标汇总 <name>=<count> …` 的 INFO 行每 15 秒输出一次（`platform.metrics.summary-interval` 可调）：

| 指标 | 含义 |
|---|---|
| `platform.outbox.dispatched` / `.failed` / `.replayed` | 投递成功 / 失败（含重试）/ 回放次数 |
| `platform.jobs.state` | Worker 侧状态流转（`succeeded`、`cancelled`） |
| `platform.files.scanned` | 文件扫描次数（按结果求和） |

接 OTLP 采集器（`deploy/observability/otel-collector.yaml`）后改为抓取，汇总日志可下线。

**命名坑（已踩）**：计数器不能叫 `platform.jobs.created`。Micrometer 的 Prometheus 命名会把保留后缀 `created` 剥掉，导出成语义含糊的 `platform_jobs_total`；当前实现用 `platform.jobs.submitted` → `platform_jobs_submitted_total`。

## 3. 告警与处置

| 告警 | 表达式 | 阈值 | 处置 |
|---|---|---|---|
| API 5xx 比例过高 | `sum(rate(platform_http_requests_total{status=~"5.."}[5m])) / clamp_min(sum(rate(platform_http_requests_total[5m])), 0.001) > 0.01` | >1% 持续 5 分钟 | 按 `trace_id` 找首个失败日志；看 Problem Details 的 `code` 分类 |
| outbox 死信出现 | `select count(*) from platform_outbox where status='dead'` | >0 | 走《平台骨架运行手册》第 2 节，确认幂等后带 `reason` 回放 |
| outbox 投递失败增长 | Worker 汇总 `platform.outbox.failed` 增量 | 5 分钟内 +3 | 查下游处理器异常与 `last_error`，别急着重启 Worker |
| 作业长时间未出队 | `select count(*) from platform_job where status='queued' and requested_at < now() - interval '5 minutes'` | >0 | 确认 Worker 存活与 outbox 积压，见运行手册第 5 节 |
| 文件扫描非 clean 比例 | `platform.files.scanned` 与 `platform_file.scan_status<>'clean'` | 5 分钟 >5% | 查 `scan_detail`，判断是规则误杀还是恶意样本 |
| 幂等键复用突增 | `increase(platform_idempotency_total{result="key_reused"}[10m])` | >5 | 客户端 bug，按接口与主体定位调用方 |

## 4. 追踪与检索

一条链路的关联键：页面的 `X-Correlation-Id` → API 生成 `trace_id` → 写入 `platform_job` / `platform_outbox` → Worker 从事件恢复 MDC。

```sql
-- 作业与它的事件
select j.id, j.status, j.progress, o.event_id, o.status, o.attempts, o.last_error
from platform_job j
left join platform_outbox o on o.payload->>'job_id' = j.id::text
where j.trace_id = '<traceId>';

-- 某租户最近的失败事件
select event_name, event_id, attempts, last_error, occurred_at
from platform_outbox
where tenant_id = '<tenantId>' and status in ('dead','pending')
order by occurred_at desc limit 20;
```

`platform_job`、`platform_file`、`platform_idempotency` 都开了 RLS，检索请用 owner 账号（或先 `set_config('app.tenant_id', …, true)`）。
日志侧按 `trace_id` 过滤两个进程的输出即可看到 API 与 Worker 的同一条链。

## 5. 仪表板

`deploy/observability/grafana-dashboard.json`：导入后选择 `DS_PROMETHEUS`（API 抓取）与 `DS_POSTGRES`（异步域状态）两个数据源。

| 面板 | 数据源 | 说明 |
|---|---|---|
| 请求速率（按状态码） | Prometheus | `platform_http_requests_total` |
| 5xx 比例 | Prometheus | 与告警同表达式 |
| 幂等结果分布 | Prometheus | `stored` / `replayed` / `key_reused` |
| 作业状态分布 | PostgreSQL | `platform_job` 按 `status` 分桶 |
| outbox 积压与死信 | PostgreSQL | `platform_outbox` 按 `status` 分桶 |
| 文件扫描状态 | PostgreSQL | `platform_file` 按 `scan_status` 分桶 |
| trace 检索说明 | 文本 | 第 4 节 SQL |
