# 租户生命周期运行手册（WP02-01）

本手册覆盖租户从创建到最终删除的状态机、冻结与恢复、冷静期注销、受控导出与申诉，
以及平台运营在「默认不可读业务正文」前提下如何通过工单获得限时只读支持访问。

所有命令以本地开发栈为例：API `http://127.0.0.1:18080`，数据库在 WSL/Docker 内。

## 0. 边界与禁止事项

| 项 | 现状 | 约束 |
|---|---|---|
| 平台运营身份 | `plt_ops_session` 运营会话 Cookie（WP02-07） | 平台域请求**禁止**携带 `X-Tenant-Id`；租户域请求**禁止**携带运营 Cookie。`X-Platform-Operator-Id` 已退役，携带即 `400 PLATFORM_OPERATOR_HEADER_RETIRED` |
| 业务正文 | 平台接口只返回租户元数据、配置、配额、审计与申诉 | 不提供跨租户业务正文读取端点；需要看业务数据只能走「支持访问」 |
| 支持访问 | 工单 + 独立审批 + ≤60 分钟 + 只读 | 审批人不得等于申请人；进入后前端必须常驻展示 banner，不允许折叠或仅放通知中心 |
| 最终删除 | 只改状态、释放域名、撤销支持访问、写审计 | 业务数据物理清理由 WP02-08 负责；本阶段不做物理删除 |
| 直接改库 | — | **禁止**直接改 `tenancy_tenant.status`：状态迁移必须走服务接口，否则审计链断裂、域名不释放 |

## 1. 状态机

```
provisioning → active | deleted
active       → frozen | closing
frozen       → active（恢复） | closing
closing      → active（冷静期内撤回） | deleted
deleted      → 终态
```

| 状态 | 可写业务 | 插件 / AI 新执行 | 受控导出 | 申诉 |
|---|---|---|---|---|
| `provisioning` | 否 | 否 | 是 | 否 |
| `active` | 是 | 是 | 是 | 否 |
| `frozen` | 否 | 否 | 是 | 是 |
| `closing` | 否 | 否 | 是 | 是 |
| `deleted` | 否（410） | 否（410） | 否（410） | 否 |

非法迁移返回 `409 TENANT_STATE_INVALID`；并发下条件更新失败返回 `409 TENANT_STATE_CONFLICT`。
两者都是「请刷新后重试」，不要重试同一个过期状态。

## 2. 创建与启用

```powershell
# 0) 平台运营登录，换取 plt_ops_session + plt_ops_csrf（Path=/platform）
curl.exe -c ops-cookies.txt -X POST -H "Content-Type: application/json" `
  -d '{"email":"ops@platform.test","password":"<password>","totpCode":"123456"}' `
  "http://127.0.0.1:18080/platform/api/v1/auth/login"

# 1) 创建：状态 provisioning，套餐缺省 trial；超过 limits.max_tenants 返回 429
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" `
  -d '{"slug":"demo","name":"演示租户","planCode":"standard","primaryDomain":"demo.example.test","domains":["demo-alias.example.test"]}' `
  "http://127.0.0.1:18080/platform/api/v1/tenants"

# 2) 校验域名、品牌与配额后启用
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/initialize"

# 3) 查看配置、配额与最近审计
curl.exe -b ops-cookies.txt `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>"

# 4) 租户目录（只含生命周期元数据，不含业务字段）
curl.exe -b ops-cookies.txt `
  "http://127.0.0.1:18080/platform/api/v1/tenants?status=active&limit=20"
```

- 平台身份只来自运营会话 Cookie；`plt_ops_session` 的作用域是 `Path=/platform`，租户域 `/api/**` 不会带上它。
- 所有写操作必须带 `X-CSRF-Token`，其值等于 `plt_ops_csrf` Cookie。
- 短标识与域名冲突返回 `409 TENANT_CONFLICT`；套餐不存在返回 `422 PLAN_UNKNOWN`；租户数达到上限返回 `429 TENANT_LIMIT_REACHED`（上限见 `platform_setting` 的 `limits.max_tenants`）。
- 创建时按套餐写入默认配额（`users`、`storage_mb`、`ai_tokens`、`plugin_installations`）。
- 每个状态变更都会写 `tenancy_lifecycle_audit`，`correlationId`/`traceId` 与请求一致。

## 3. 冻结与恢复

```powershell
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST -H "Content-Type: application/json" `
  -d '{"reason":"账单逾期，按合同暂停服务"}' `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/freeze"

curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST -H "Content-Type: application/json" `
  -d '{"reason":"账单已结清，恢复服务"}' `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/restore"
```

冻结后：

- 所有非 GET 的 `/api/**` 请求被 `TenantWriteBlockFilter` 拦截，返回 `423 TENANT_FROZEN`；
- 插件执行返回 `423 TENANT_PLUGIN_BLOCKED`，AI 新执行返回 `423 TENANT_AI_BLOCKED`；
- 白名单只放行 `POST /api/v1/tenant/appeals` 与 `POST /api/v1/tenant/closing/cancel`；
- `GET /api/v1/tenant/export` 仍可用，响应带 `X-Controlled-Export: tenant-lifecycle` 与 `Cache-Control: no-store`。

**不要**用停库、摘流量或改防火墙来「冻结租户」：那会同时中断受控导出与申诉通道，违反冻结语义。

## 4. 注销、冷静期与最终删除

```powershell
# 申请注销：进入 closing，写 cooling_off_until（默认 30 天）
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: admin-1" -H "Content-Type: application/json" `
  -d '{"reason":"业务调整，申请注销"}' "http://127.0.0.1:18080/api/v1/tenant/closing"

# 冷静期内撤回
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: admin-1" -H "Content-Type: application/json" `
  -d '{"reason":"业务继续，撤回注销"}' "http://127.0.0.1:18080/api/v1/tenant/closing/cancel"

# 冷静期结束后最终删除（平台）
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X DELETE -H "Content-Type: application/json" `
  -d '{"reason":"冷静期已结束，执行最终删除"}' `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>"
```

- 冷静期未结束即删除返回 `409 COOLING_OFF_ACTIVE`，错误里带最早可删除时间，**不要**改库绕过。
- 冷静期长度由 `platform.tenancy.cooling-off-days` 控制（默认 30），只能在启动配置调整。
- 最终删除后：域名行删除、支持访问全部撤销、状态置 `deleted`；后续业务请求返回 `410 TENANT_DELETED`。
- 删除前建议先导出并留档：`GET /api/v1/tenant/export`。

## 5. 平台支持访问（工单 → 审批 → 限时只读）

```powershell
# 1) 申请（需要 platform.support.manage）
curl.exe -b ops-a-cookies.txt -H "X-CSRF-Token: <csrf-a>" -X POST -H "Content-Type: application/json" `
  -d '{"ticketId":"TCK-1001","reason":"核对受控导出结果与审计链","minutes":30}' `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/support-access"

# 2) 独立审批：必须换另一个运营账号的会话（ops-b ≠ ops-a）
curl.exe -b ops-b-cookies.txt -H "X-CSRF-Token: <csrf-b>" -X POST `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/support-access/<accessId>/approve"

# 3) 进入只读会话
curl.exe -b ops-a-cookies.txt -H "X-CSRF-Token: <csrf-a>" -X POST `
  "http://127.0.0.1:18080/platform/api/v1/tenants/<tenantId>/support-access/<accessId>/enter"
```

时限上限来自 `platform_setting` 的 `limits.support_access_max_minutes`（预置 60），服务端另有 60 分钟硬上限，配置更大也不会突破。

规则与错误码：

| 场景 | 结果 |
|---|---|
| 工单格式不符（须 `^[A-Z][A-Z0-9-]{3,31}$`）、理由不足 10 字、时限不在 1–60 | `400` |
| 申请人自己审批 | `403 SUPPORT_SEPARATION_REQUIRED` |
| 已过期再进入 | `403 SUPPORT_ACCESS_EXPIRED`，同时把记录置 `expired` |
| 未审批、已拒绝或已撤销再进入 | `403 SUPPORT_ACCESS_INACTIVE` |
| 租户已删除 | `410 TENANT_DELETED` |

进入成功后，前端必须：

1. 常驻展示 `banner.message`（含工单号、运营主体、到期时间）；
2. 只做读取，不发起写请求；
3. 到期或撤销后立即退出。

租户侧使用支持访问时，请求带 `X-Support-Access-Id`，服务端回写：

- `X-Support-Access-Notice: ... read-only ...`
- `X-Support-Access-Expires-At`
- `X-Support-Access-Ticket`

支持访问携带该头时只允许 GET；任何非 GET 一律 `403 SUPPORT_ACCESS_READ_ONLY`。
所有申请、审批、拒绝、撤销、进入都会写生命周期审计。

## 6. 配额、编号与业务域

```powershell
# 扣减配额：原子条件更新，超限 429 QUOTA_EXCEEDED；冻结 423
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: admin-1" -H "Content-Type: application/json" `
  -d '{"amount":10}' "http://127.0.0.1:18080/api/v1/tenant/quotas/ai_tokens/consume"

# 取业务编号：每租户独立递增
curl.exe -X POST -H "X-Tenant-Id: <tenant>" -H "X-Subject-Id: admin-1" `
  "http://127.0.0.1:18080/api/v1/tenant/business-numbers/WO"
```

- 编号形如 `WO-000001`，唯一键是 `(tenant_id, scope, business_number)`；
  两个租户可以同时存在 `WO-000001`，互不冲突。
- 同一租户同一业务域内序号唯一；`(tenant_id, scope, sequence)` 也有唯一约束兜底。
- 取号是写操作：冻结/注销状态下返回 `423 TENANT_FROZEN`。

## 7. 排查

| 现象 | 先查 | 处置 |
|---|---|---|
| 平台请求 `401 PLATFORM_SESSION_REQUIRED` | 是否带 `plt_ops_session`，或会话是否过期 | 重新 `POST /platform/api/v1/auth/login`；不要把租户 `plt_session` 拿来用 |
| 平台请求 `400 PLATFORM_OPERATOR_HEADER_RETIRED` | 是否还带 `X-Platform-Operator-Id` | 删除该头；身份只认运营会话 Cookie |
| 平台写请求 `403 CSRF_INVALID` | 是否带 `X-CSRF-Token` 且与 `plt_ops_csrf` 一致 | 从 Cookie 读取并回填请求头 |
| 平台请求 `403 PLATFORM_TENANT_CONTEXT_FORBIDDEN` | 是否同时带了 `X-Tenant-Id` | 去掉租户头，平台按路径 tenantId 进入租户事务 |
| 业务写 `423 TENANT_FROZEN` | `select status, frozen_reason from tenancy_tenant where id = '<tenant>'` | 按流程恢复，或走申诉/导出 |
| 审计缺失某次变更 | `select action, from_status, to_status, actor, reason, occurred_at from tenancy_lifecycle_audit where tenant_id = '<tenant>' order by occurred_at desc` | 若确认绕过接口改库，按事故处理并补审计说明 |
| 删除返回 `409 COOLING_OFF_ACTIVE` | `select cooling_off_until from tenancy_tenant where id = '<tenant>'` | 等冷静期结束；提前删除属违规 |
| 支持访问一直 `403` | `select status, approved_by, expires_at, revoked_at from tenancy_support_access where id = '<accessId>'` | 未审批 / 已过期 / 已撤销，对应重新走流程 |

## 8. 数据库与迁移

- 迁移：`backend/db/src/main/resources/db/migration/V6__tenant_lifecycle.sql`
- 平台级表：`tenancy_plan`（无 RLS，套餐与默认配额）
- 租户级表（全部 `ENABLE + FORCE ROW LEVEL SECURITY`，策略 `*_isolation`）：
  `tenancy_profile`、`tenancy_domain`、`tenancy_quota`、`tenancy_business_number_sequence`、
  `tenancy_business_number`、`tenancy_lifecycle_audit`、`tenancy_support_access`、`tenancy_appeal`
- 2026-11 之前的 `closed` 状态在迁移中统一改写为 `deleted` 并补 `deleted_at`。
- 校验：`powershell -File scripts/db-verify.ps1` 会检查 15 张租户表的 RLS、策略数量、
  两租户同编号、非法状态与审批人自批约束，并跑 `TenantLifecycleDbTest`。

## 9. 已知边界

- 平台跨租户目录（列出所有租户、搜索）已在 WP02-07 落地：`GET /platform/api/v1/tenants`，只含生命周期元数据。
- 平台身份与权限已由 WP02-07 的独立运营账号与 12 个权限码接管；旧的 `X-Platform-Operator-Id` 占位头已退役。
- 最终删除后的物理清理与备份留存见 WP02-08。
