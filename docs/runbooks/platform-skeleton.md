# 平台骨架运行手册（WP01-04）

本手册覆盖统一请求上下文、幂等、事务 outbox、异步作业、文件预签名上传/扫描/下载再授权这几条骨架链路的故障处置。
所有命令以本地开发栈为例：API `http://127.0.0.1:18080`、数据库与 MinIO 在 WSL 内的容器里。

## 0. 边界与禁止事项

| 项 | 现状 | 约束 |
|---|---|---|
| 认证 | `X-Tenant-Id` / `X-Subject-Id` 请求头即身份（占位） | **禁止在共享或生产环境启用**；WP02-02 接入真实身份后必须删除请求头直信逻辑 |
| 预签名下载地址 | 自写 SigV4，有效期由 `platform.storage.download-ttl` 决定（本地 10s） | URL 是 bearer 凭证：拿到就能下载，无法单次使用；防护靠「签发前再授权 + 短 TTL + 对象键含租户与文件 ID」 |
| outbox 投递 | 至少一次（`FOR UPDATE SKIP LOCKED` 认领） | 处理器必须幂等；重复投递是正常现象，不是故障 |
| 死信回放 | 只重置为 `pending`，不做业务补偿 | 回放前必须确认处理器幂等，操作人写进审计 |

## 1. 幂等冲突（422 `IDEMPOTENCY_KEY_REUSED`）

**症状**：同一个 `Idempotency-Key` 第二次请求换成了不同请求体，返回 `422`，`code=IDEMPOTENCY_KEY_REUSED`。

**判定规则**：
- 键 + 请求体 SHA-256 指纹都相同 → 返回首次响应并带 `Idempotent-Replayed: true`，不产生第二次副作用。
- 键相同、指纹不同 → 422，拒绝服务，避免「一个键两种意图」。

**处置**：
1. 客户端换一个新键重发；若客户端用的是常量键，按缺陷处理。
2. 核对缓存记录：`select status, response_status, created_at, expires_at from platform_idempotency where idempotency_key = '<key>' and tenant_id = '<tenant>'`（记录 24 小时后过期，过期后同键视为新请求）。
3. 已进入 `in_progress` 但进程崩溃的键：等 `expires_at` 过期后重试，或由 DBA 删除该行——删除前确认首次请求的副作用是否已经发生。

## 2. 死信查询与安全回放

**查询**（只看本租户）：

```powershell
curl.exe -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: <subject>" `
  "http://127.0.0.1:18080/api/v1/system/outbox/dead-letters?limit=20"
```

**回放**：

```powershell
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: <subject>" `
  -H "Content-Type: application/json" -H "Idempotency-Key: $(New-Guid)" `
  -d '{"reason":"修复下游 500 后重投，已确认处理器幂等"}' `
  "http://127.0.0.1:18080/api/v1/system/outbox/dead-letters/<eventId>/replay"
```

- `reason` 必填，长度 8–500，否则 `422 REPLAY_REASON_REQUIRED`；成功返回 `202` 与 `status=pending`。
- 审计落在 `platform_outbox_replay`（`event_id`、`reason`、`replayed_by`、`replayed_at`）。
- 跨租户或已不在 `dead` 状态 → `404 DEAD_LETTER_NOT_FOUND`（不泄露其他租户是否有该事件）。

**审批要求**：回放前确认该事件处理器不会二次产生不可逆副作用（扣款、通知、外部写）。本骨架的两个处理器都幂等：作业处理器看到非 `queued` 直接跳过。

## 3. 作业卡在 `queued` 或进度不动

```powershell
curl.exe -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: <subject>" `
  "http://127.0.0.1:18080/api/v1/system/jobs/<jobId>"
```

排查顺序：
1. `select status, progress, last_error, trace_id from platform_job where id = '<jobId>'`。
2. Worker 是否存活：日志里 `platform.outbox.dispatched` 计数是否增长（Worker 每 15s 打一条汇总）。
3. 对应事件是否已进死信：`select status, attempts, last_error from platform_outbox where payload->>'job_id' = '<jobId>'`。
4. 取消：`POST /api/v1/system/jobs/{id}/cancel` 只置 `cancel_requested=true`，由 Worker 在下一步之间检查后自行停止并置 `cancelled`。**不要**直接改库里的 `status`，那会绕过进度写入并留下半写状态。

## 4. 文件上传或扫描卡住

状态机：`platform_file.status`（`pending_upload` → `uploaded` …）、`platform_file.scan_status`（`pending` → `clean` / `infected`）；下载只在 `scan_status=clean` 时签发。

| 现象 | 先查 | 处置 |
|---|---|---|
| `complete` 后一直 `scan_status=pending` | Worker 日志是否有 `platform.file.scanned`；MinIO 对象是否存在 | 修好 Worker 后事件会按 outbox 重试；不要手工改 `scan_status` |
| `scan_status=infected` | `scan_detail` | 隔离对象、删除元数据、通知上传者；规则命中通常来自可执行扩展名或 `eicar` 标记 |
| 下载 `403 FILE_NOT_OWNED` | 请求的 `X-Subject-Id` 与文件 `subject_id` | 文件属于其他主体，签名不发；走共享/授权流程（后续 WP） |
| 下载 `404 FILE_NOT_FOUND` | 请求的 `X-Tenant-Id` | 跨租户被 RLS 过滤，属预期行为 |
| MinIO 返回 `403 SignatureDoesNotMatch` / `AccessDenied` | URL 是否超过 TTL、系统时钟 | 重新申请下载地址；不要延长 TTL 来「修」，那是安全边界 |

## 5. Worker 停机与 outbox 积压

- 症状：作业停在 `queued`，`platform_outbox` 的 `pending` 行数持续增长。
- 影响：API 仍可接收请求（写入与发事件在同一事务），只是副作用延后。
- 恢复：拉起 Worker 即可，无需人工改状态；重复投递由处理器幂等吸收。
- 积压观测：

```sql
select status, count(*) from platform_outbox group by status order by 2 desc;
select count(*) from platform_outbox where status = 'pending' and available_at < now() - interval '5 minutes';
```

- 禁止：把 `dead` 行直接改成 `published`（事件丢失）或 `pending`（绕过审计）。需要重投走第 2 节回放接口。

## 6. 本地复现与取证

```powershell
powershell -ExecutionPolicy Bypass -File scripts\platform-skeleton-verify.ps1
```

脚本会自行启动 API 与 Worker、依次验证幂等/作业/死信/文件/指标/日志卫生/前端，并把结果写入 `docs/evidence/wp01-04/`。
失败时先看 `%TEMP%` 下该次的 `api.out.log` / `worker.out.log`，日志是 ECS JSON，按 `trace_id` 过滤即可还原一条完整链路。
