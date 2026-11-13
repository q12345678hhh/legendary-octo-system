# 平台运营端运行手册（WP02-07）

本手册覆盖平台运营端的独立认证域、六个运营界面对应的后端接口、权限矩阵、
平台目录、安全事件、作业与审计、平台设置，以及「运营账号默认读不到任何租户业务正文」
这一硬约束如何被验证。

本地开发栈：API `http://127.0.0.1:18080`，数据库在 WSL/Docker 内的 `platform-pg-dev`。

## 0. 边界与禁止事项

| 项 | 现状 | 约束 |
|---|---|---|
| 运营身份 | 独立表 `platform_operator` + 独立 Cookie `plt_ops_session` | 与租户成员表完全分离；不复用 `plt_session`，也不复用租户角色 |
| Cookie 作用域 | `plt_ops_session` / `plt_ops_csrf` 均 `Path=/platform`、`HttpOnly`、`Secure`、`SameSite=Strict` | 浏览器访问 `/api/**` 不会带上运营 Cookie；租户 Cookie 也不会进入 `/platform/**` |
| 旧请求头 | `X-Platform-Operator-Id` 已退役 | 携带即 `400 PLATFORM_OPERATOR_HEADER_RETIRED`；不要在任何脚本里继续使用 |
| 写操作 | 双提交 CSRF | 必须带 `X-CSRF-Token`，值等于 `plt_ops_csrf` Cookie |
| 高风险写 | 权限码以 `.manage` 结尾 | 除权限外还要求本次会话已完成 MFA，否则 `403 PLATFORM_MFA_REQUIRED` |
| 跨租户读 | 只读平台元数据与聚合计数 | 平台端没有任何端点能返回租户业务正文；要看业务数据只能走「支持访问」 |
| 直接改库 | — | **禁止**改 `platform_operator.role_code`、删 `platform_audit_event`、或手工写 `platform_tenant_metric_daily` |

## 1. 登录、会话与 MFA

```powershell
# 1) 登录：邮箱 + 密码（+ 已绑定时必填 TOTP）
curl.exe -c ops-cookies.txt -X POST -H "Content-Type: application/json" `
  -d '{"email":"ops@platform.test","password":"<password>","totpCode":"123456"}' `
  "http://127.0.0.1:18080/platform/api/v1/auth/login"

# 2) 当前账号、角色、MFA 状态与 12 个权限码
curl.exe -b ops-cookies.txt `
  "http://127.0.0.1:18080/platform/api/v1/auth/me"

# 3) 绑定 MFA：先取密钥，再用一次 TOTP 确认
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST `
  "http://127.0.0.1:18080/platform/api/v1/auth/mfa/start"
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST -H "Content-Type: application/json" `
  -d '{"code":"123456"}' `
  "http://127.0.0.1:18080/platform/api/v1/auth/mfa/confirm"

# 4) 登出
curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X POST `
  "http://127.0.0.1:18080/platform/api/v1/auth/logout"
```

登录响应：

- `Set-Cookie: plt_ops_session=...; Path=/platform; HttpOnly; Secure; SameSite=Strict`
- `Set-Cookie: plt_ops_csrf=...; Path=/platform; Secure; SameSite=Strict`
- 响应体含 `operator`（`operatorId`、`email`、`roleCode`、`mfaSatisfied`、`permissions`）与 `sessionExpiresAt`

会话与锁定参数在 `application.yml` 的 `platform.ops`：

| 配置 | 环境变量 | 默认 |
|---|---|---|
| `session-absolute-ttl` | `PLATFORM_OPS_SESSION_TTL` | `8h` |
| `session-idle-ttl` | `PLATFORM_OPS_IDLE_TTL` | `30m` |
| `lock-duration` | `PLATFORM_OPS_LOCK_DURATION` | `15m` |
| `metric-poll-interval` | `PLATFORM_OPS_METRIC_INTERVAL` | `15m` |
| `security-poll-interval` | `PLATFORM_OPS_SECURITY_INTERVAL` | `5m` |
| `abnormal-login-threshold` | `PLATFORM_OPS_ABNORMAL_LOGIN_THRESHOLD` | `10` |

首个运营账号由 `platform.ops.bootstrap-*` 在启动时创建（仅当 `platform_operator` 为空）。
共享环境**必须**用环境变量注入，且创建后立即绑定 MFA、改掉初始密码。

## 2. 权限矩阵（12 权限码 / 4 角色）

| 权限码 | owner | ops | security | auditor |
|---|---|---|---|---|
| `platform.overview.read` | ✓ | ✓ | ✓ | ✓ |
| `platform.tenant.read` | ✓ | ✓ |  | ✓ |
| `platform.tenant.manage` | ✓ | ✓ |  |  |
| `platform.support.manage` | ✓ | ✓ |  |  |
| `platform.catalog.read` | ✓ | ✓ |  | ✓ |
| `platform.catalog.manage` | ✓ |  |  |  |
| `platform.security.read` | ✓ |  | ✓ | ✓ |
| `platform.security.manage` | ✓ |  | ✓ |  |
| `platform.operations.read` | ✓ | ✓ | ✓ | ✓ |
| `platform.operations.manage` | ✓ |  |  |  |
| `platform.settings.read` | ✓ |  |  | ✓ |
| `platform.settings.manage` | ✓ |  |  |  |

`platform-ops` 与 `platform-security` 都没有 `platform.settings.*` 与 `platform.catalog.manage`：
这是刻意的职责分离，不要为了「方便」给它们加权限。

## 3. 六个界面对应的后端接口

| 界面 | 接口 | 权限 |
|---|---|---|
| 运营总览 | `GET /platform/api/v1/overview` | `platform.overview.read` |
| 租户管理 | `GET/POST /platform/api/v1/tenants`、`GET/DELETE /platform/api/v1/tenants/{id}`、`initialize`/`freeze`/`restore`/`closing`/`profile`、`GET .../quotas`、`GET .../audit` | `read` / `manage` 分开 |
| 支持访问 | `GET/POST /platform/api/v1/tenants/{id}/support-access`、`approve`/`deny`/`revoke`/`enter` | `platform.support.manage` |
| 安全运营 | `GET/POST /platform/api/v1/security/events`、`.../{id}/acknowledge`、`.../{id}/resolve`、`POST /security/secret-rotations` | `security.read` / `security.manage` |
| 目录管理 | `GET/POST(PUT) /platform/api/v1/catalog/{apps,connectors,plugin-signatures,model-providers}` | `catalog.read` / `catalog.manage` |
| 作业与审计 | `GET /operations/overview`、`GET/POST /operations/runs`、`GET /operations/audit` | `operations.read` / `operations.manage` |
| 平台设置 | `GET /settings`、`PUT /settings/{key}` | `settings.read` / `settings.manage` |

对应鉴权用例在 `backend/boot-api/src/test/java/com/acme/platform/PlatformScreenAuthzTest.java`：
匿名 401、auditor 只读、ops 不能管设置与安全、security 写需 MFA、owner 可改设置、
租户目录响应不含业务字段。

## 4. 平台目录

- 应用版本：`appCode` + `version` 唯一；状态 `draft` / `published` / `retired`。
- 连接器：`connectorCode` 唯一；`authType` ∈ `none|api-key|oauth2|hmac|basic`；状态 `available|disabled`。
- 插件签名：`pluginCode` + `version` 唯一；`sha256` 必须 64 位小写十六进制；状态 `trusted|revoked`。
- 模型供应商：`providerCode` 唯一；状态 `enabled|disabled`。

写接口同时接受 `POST` 与 `PUT`（幂等 upsert）。每次写入都写 `platform_audit_event`。

## 5. 安全运营

| 类别 | 含义 |
|---|---|
| `abnormal_login` | 短时间内同账号失败登录超阈值（Worker 每 5 分钟扫描，按天去重） |
| `cross_tenant_blocked` | 请求同时带平台身份与租户上下文，或跨域 Cookie |
| `secret_rotation` | 平台秘密轮换登记 |
| `vulnerability` / `incident` | 漏洞与安全事件 |

状态机：`open → acknowledged → resolved`；重复确认或重复闭环保持幂等。
`POST /security/secret-rotations` 只登记秘密名称与说明，**不接收密钥明文**。

## 6. 作业与审计

- `GET /operations/overview`：最近一次迁移、outbox `pending/failed/dead`、24 小时运行记录数。
- `GET /operations/runs?kind=`：`migration|backup|restore_drill|queue_requeue|catalog_seed`。
- 应用启动时 `MigrationAuditRecorder` 会把当前 Flyway 版本写为一条 `migration` 运行记录。
- `GET /operations/audit`：平台审计表只追加，数据库触发器禁止 `UPDATE`/`DELETE`。

## 7. 平台设置

键必须在白名单内（`PlatformSettingCatalog`），未知键返回 `422 SETTING_UNKNOWN`：

| 键 | 类型 | 密钥 |
|---|---|---|
| `email.smtp_host` / `email.smtp_port` / `email.from_address` / `email.username` | text/int/email | 否 |
| `email.password` | text | 是 |
| `storage.endpoint` / `storage.bucket` / `storage.region` | url/text | 否 |
| `storage.access_key` / `storage.secret_key` | text | 是 |
| `model.gateway_url` | url | 否 |
| `model.gateway_key` | text | 是 |
| `limits.max_tenants` | int（1–10000） | 否 |
| `limits.support_access_max_minutes` | int（1–60） | 否 |

密钥类设置读取时恒回 `value: null` 且 `configured: true`；写入只记录「已变更」，不落明文到审计。

```powershell
curl.exe -b ops-cookies.txt `
  "http://127.0.0.1:18080/platform/api/v1/settings"

curl.exe -b ops-cookies.txt -H "X-CSRF-Token: <csrf>" -X PUT -H "Content-Type: application/json" `
  -d '{"value": 500}' `
  "http://127.0.0.1:18080/platform/api/v1/settings/limits.max_tenants"
```

## 8. 数据面隔离如何被验证

| 断言 | 位置 |
|---|---|
| 运营账号无法读取任一租户业务正文 | `PlatformScreenAuthzTest`（租户目录响应字段白名单）、`tests/db/93-platform-ops.sql`（平台表不含业务字段） |
| 平台域与租户域 Cookie / 头互斥 | `TenantApiBoundaryTest`、`TenantHeaderMismatchTest`、`tests/security/isolation-invariants.test.mjs` |
| 旧运营头退役 | `PlatformDomainIsolationFilter`：`400 PLATFORM_OPERATOR_HEADER_RETIRED` |
| 目录投影只含生命周期元数据 | `platform_tenant_directory_sync` 触发器 + `93-platform-ops.sql` |
| 审计不可改删 | `platform_audit_event` 不可变触发器 + `93-platform-ops.sql` |
| 租户数上限与支持访问时限真实生效 | `PlatformOpsDbTest` + `93-platform-ops.sql` |
| 指标聚合幂等 | `platform_tenant_metric_daily` 唯一键 + `PlatformOpsDbTest` |

## 9. 排查

| 现象 | 先查 | 处置 |
|---|---|---|
| `401 PLATFORM_SESSION_REQUIRED` | 是否带 `plt_ops_session`，会话是否过期 | 重新登录；不要用租户 `plt_session` |
| `400 PLATFORM_OPERATOR_HEADER_RETIRED` | 是否仍带 `X-Platform-Operator-Id` | 删除该头，改走登录换 Cookie |
| `403 CSRF_INVALID` | `X-CSRF-Token` 是否等于 `plt_ops_csrf` | 从 Cookie 回填 |
| `403 PLATFORM_PERMISSION_DENIED` | `/auth/me` 返回的 `permissions` | 角色不含该权限码，走权限变更流程，不要改库 |
| `403 PLATFORM_MFA_REQUIRED` | `/auth/me` 的 `mfaSatisfied` | 完成 MFA 绑定或重新验证 |
| `403 PLATFORM_TENANT_CONTEXT_FORBIDDEN` | 是否同时带了 `X-Tenant-Id` 或租户 Cookie | 平台请求只按路径 `tenantId` 进入租户事务 |
| `503 PLATFORM_AUTH_UNAVAILABLE` | 运营认证 Bean 是否装配 | 检查 `identity.operator` 模块与 `platform.ops` 配置 |

## 10. 数据库与迁移

- 迁移：`backend/db/src/main/resources/db/migration/V11__platform_operations.sql`
- 平台级表（**故意不启用 RLS**，与 `platform_outbox` 同例）：
  `platform_tenant_directory`、`platform_tenant_metric_daily`、`platform_security_event`、
  `platform_operator`、`platform_operator_session`、`platform_audit_event`、`platform_ai_cost_daily`、
  `platform_app_version`、`platform_connector`、`platform_plugin_signature`、
  `platform_model_provider`、`platform_setting`、`platform_operation_run`
- 校验：`powershell -File scripts/db-verify.ps1` 的 5h 段执行 `tests/db/93-platform-ops.sql`
  与 `PlatformOpsDbTest`；全量通过后输出「平台运营端」。

## 11. 已知边界

- 本包只做后端与鉴权用例，六个界面的前端实现不在本包。
- 跨租户业务正文读取仍然只有「支持访问」一条通道（工单 + 独立审批 + 时限 + 审计）。
- 平台运营账号的管理界面（创建/停用运营账号、改角色）尚未提供接口，当前由启动引导与环境变量注入。
