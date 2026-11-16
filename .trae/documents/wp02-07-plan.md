# WP02-07 平台运营端实施方案

## Context

PLAN §5.2 定义了平台运营端六个界面（运营总览、租户管理、平台目录、安全运营、作业与审计、平台设置），但 WP02-01..06 只交付了租户生命周期、身份会话、组织结构、统一授权、职责分离与跨租户测试，`/platform/**` 至今仍靠 `X-Platform-Operator-Id` 占位头（见 [PlatformDomainIsolationFilter.java](file:///D:/projects/temp_20261109_01/backend/boot-api/src/main/java/com/acme/platform/PlatformDomainIsolationFilter.java)），平台运营账号没有独立身份，六个界面的数据面也不存在。本包补齐该缺口。

**验收口径：** 平台运营账号无法读取任一租户业务正文；六个界面均有对应的后端鉴权用例；支持访问全过程有工单、审批、时限与审计记录。

**已确认范围（用户决策）：**

| 议题 | 决策 |
|---|---|
| 前端 | 本包**只做后端 + 鉴权用例**，六个界面的前端留待后续；与 WP02-01..06 一致（frontend 自 WP01-04 后无提交） |
| 运营账号 | **独立运营账号表 + 独立 Cookie**，与租户 `identity_session` 完全隔离 |
| 目录与设置 | **新建平台级表 + 预置数据**，读写走 `/platform/api/v1`，不含任何租户业务正文 |

---

## 一、现状与差距

| 能力 | 现状 | 差距 |
|---|---|---|
| 平台认证 | `X-Platform-Operator-Id` 请求头即身份（`PlatformDomainIsolationFilter`） | 无凭据、无会话、无 MFA、无权限；任何知道格式的人都能调 |
| 租户管理 | [PlatformTenantController.java](file:///D:/projects/temp_20261109_01/backend/boot-api/src/main/java/com/acme/platform/PlatformTenantController.java) 已有单租户视图/生命周期/配额/审计/支持访问 | 缺租户列表与搜索；`tenancy_tenant` 有 FORCE RLS，平台无法跨租户列出 |
| 运营总览 | `PlatformMetrics` 只有进程内计数器 | 无租户数/容量/AI 成本/集成健康聚合；跨租户读租户表会被 RLS 挡住 |
| 平台目录 | `appcatalog`/`integration`/`plugin`/`ai` 模块只有 `package-info.java` | 无数据模型、无端点 |
| 安全运营 | `identity_auth_event` 是租户 RLS 表；跨租户拦截只体现在 403 响应 | 无平台级安全事件台账、异常登录聚合、秘密轮换记录 |
| 作业与审计 | 作业/死信接口在租户域 `/api/v1/system/**`；迁移/备份/恢复演练只有脚本 | 平台级运行记录、迁移版本、平台审计、队列健康缺失 |
| 平台设置 | 邮件未实现；对象存储与模型网关只读环境变量 | 无在线设置、无脱敏回显、无变更审计 |
| 契约 | `contracts/openapi/platform-api.v1.json` 只覆盖到 1.6.0 的租户与授权端点 | 新端点需扩契约并重冻结基线 |
| 数据/测试 | V1–V10；`tests/db/40-integrity.sql` 断言 57 张租户表；db-verify 5g 到跨租户攻击 | 缺 V11、`93-platform-ops.sql`、真实库平台用例与静态不变量 |

---

## 二、总体设计

### 2.1 两个认证域物理隔离

| | 租户域 | 平台域 |
|---|---|---|
| 路径 | `/api/**` | `/platform/api/v1/**` |
| 身份表 | `identity_session` / `identity_api_token` | `platform_operator` / `platform_operator_session` |
| Cookie | `plt_session`（Path=/api） | `plt_ops_session`（Path=/platform，HttpOnly+Secure+SameSite=Strict） |
| CSRF | `plt_csrf` | `plt_ops_csrf` |
| 过滤器 | `AuthenticationFilter` | 新增 `PlatformOperatorAuthenticationFilter` |
| 互斥 | 拒绝平台 Cookie/旧运营头 | 拒绝 `X-Tenant-Id`、拒绝租户会话 Cookie |

`PlatformDomainIsolationFilter` 保留为「路径/上下文互斥」过滤器，但**不再接受 `X-Platform-Operator-Id` 作为身份**：携带该头一律 400 `PLATFORM_OPERATOR_HEADER_RETIRED`；平台请求带 `X-Tenant-Id` 仍 403。真正的身份由新过滤器建立。

**权限模型（代码内置，租户不可改）：** `PlatformPermissionCatalog` 定义 12 个权限码，`platform_operator.role_code` 映射到权限集合：

| 角色 | 权限 |
|---|---|
| `platform-owner` | 全部 |
| `platform-ops` | `overview.read`、`tenant.read/manage`、`support.manage`、`catalog.read`、`operations.read` |
| `platform-security` | `security.read/manage`、`overview.read`、`operations.read` |
| `platform-auditor` | 全部 `*.read` |

`GET /platform/api/v1/auth/me` 返回权限集合，供后续前端「导航项按权限隐藏」。每个控制器方法前调用 `PlatformAuthorization.require(permission)`，缺权限 403 `PLATFORM_PERMISSION_DENIED`——这是「直接访问路由仍由后端鉴权」的落点。

**MFA：** 运营账号可绑定 TOTP（复用 identity 的 `Totp`/`TotpSecretCipher`）；登录时若已绑定必须校验。`mfa_satisfied=false` 的会话只允许 `*.read`，所有 `*.manage` 与支持访问审批返回 403 `PLATFORM_MFA_REQUIRED`。

### 2.2 跨租户读的边界：只读聚合，不读正文

平台域**不获得 BYPASSRLS**（静态不变量 `运行账号不得绕过 RLS` 保持不变）。跨租户数据通过三张非 RLS 的 `platform_*` 元数据表获得，由数据库触发器或 Worker 在租户上下文内写入：

| 表 | 写入方 | 内容 | 明确不含 |
|---|---|---|---|
| `platform_tenant_directory` | `tenancy_tenant` AFTER INSERT/UPDATE 触发器 | id、slug、name、status、plan_code、created_at、updated_at | 品牌配置、配额明细、审计正文 |
| `platform_tenant_metric_daily` | Worker `TenantMetricCollector`（逐租户 `TenantTransactions`） | 日活用户、请求数、错误数、存储字节、AI tokens/成本、队列积压、集成失败数 | 人员、文件、工时、消息等业务正文 |
| `platform_security_event` | 平台过滤器 + Worker `SecuritySignalScanner` + 运营录入 | 类别、级别、租户 ID、摘要、状态、时间 | 登录邮箱以外的凭据、业务数据 |

三张表都**故意不启用 RLS**，理由与 `platform_outbox` 相同（平台控制面必须跨租户读）；在表注释、`93-platform-ops.sql` 断言和证据 README 三处显式登记，避免被误认为遗漏。

### 2.3 六界面的数据面

| 界面 | 端点 | 数据来源 |
|---|---|---|
| 运营总览 | `GET /platform/api/v1/overview` | `platform_tenant_directory` 按状态计数、`platform_tenant_metric_daily` 近 30 天求和、`platform_outbox` 失败数、`platform_connector` 健康、`MeterRegistry` 请求/错误计数、`platform_ai_cost_daily` 汇总（本包为零，AI 网关接入后写入） |
| 租户管理 | `GET /platform/api/v1/tenants?status=&q=&limit=` + 现有单租户端点 | 列表读 `platform_tenant_directory`；详情/配额/审计/支持访问仍走 `TenantLifecycleService.view`（租户事务 + RLS） |
| 平台目录 | `GET/POST/PUT /platform/api/v1/catalog/{apps\|connectors\|plugin-signatures\|model-providers}` | 4 张平台目录表 + 预置数据 |
| 安全运营 | `GET /platform/api/v1/security/events`、`POST .../{id}/acknowledge\|resolve`、`POST .../secret-rotations`、`POST /platform/api/v1/security/events` | `platform_security_event` |
| 作业与审计 | `GET /platform/api/v1/operations/overview`、`GET .../runs`、`POST .../runs`、`GET .../audit` | `platform_operation_run`、`flyway` 版本、`platform_outbox`、`platform_audit_event` |
| 平台设置 | `GET /platform/api/v1/settings`、`PUT /platform/api/v1/settings/{key}` | `platform_setting`（密钥类只回 `******` 与是否已设置） |

---

## 三、数据模型（V11__platform_operations.sql）

全部为非 RLS 平台表；除 `platform_tenant_directory` / `platform_tenant_metric_daily` / `platform_security_event` 外均不含 `tenant_id`。

| 表 | 关键列 | 约束/说明 |
|---|---|---|
| `platform_operator` | `id`、`email`（唯一，小写）、`display_name`、`role_code`、`password_hash`、`mfa_secret_cipher`、`mfa_confirmed`、`status`、`failed_attempts`、`locked_until`、`last_login_at`、时间戳 | `role_code IN ('platform-owner','platform-ops','platform-security','platform-auditor')`；`status IN ('active','disabled')` |
| `platform_operator_session` | `id`、`operator_id`、`token_hash`（唯一）、`mfa_satisfied`、`ip`、`user_agent`、`created_at`、`last_seen_at`、`expires_at`、`revoked_at` | 只存 SHA-256；空闲/绝对 TTL 由 `PlatformOpsProperties` 控制（默认 30m/8h） |
| `platform_audit_event` | `id`、`operator_id`、`action`、`target_type`、`target_id`、`detail jsonb`、`correlation_id`、`trace_id`、`occurred_at` | BEFORE UPDATE/DELETE 触发器拒绝（与 `authorization_decision_log` 同款） |
| `platform_security_event` | `id`、`tenant_id`（可空）、`category`、`severity`、`status`、`summary`、`detail jsonb`、`occurred_at`、`acknowledged_by/at`、`resolved_by/at` | `category IN ('abnormal_login','cross_tenant_blocked','secret_rotation','vulnerability','incident')`；`severity IN ('low','medium','high','critical')` |
| `platform_tenant_directory` | `id`（=租户 id，PK）、`slug`、`name`、`status`、`plan_code`、`created_at`、`updated_at` | 由 `tenancy_tenant` 触发器同步；V11 回填现有租户 |
| `platform_tenant_metric_daily` | `(tenant_id, day)` PK、`active_users`、`api_requests`、`error_requests`、`storage_bytes`、`ai_tokens`、`ai_cost_cents`、`jobs_queued`、`jobs_failed`、`integration_errors` | Worker 按租户 upsert；保留 400 天，Worker 每日清理 |
| `platform_ai_cost_daily` | `(day, provider_code)` PK、`tokens`、`cost_cents` | 预留给 AI 网关，本包只读 |
| `platform_app_version` | `id`、`app_code`、`app_name`、`version`、`status`、`released_at`、`notes` | 唯一 `(app_code, version)` |
| `platform_connector` | `id`、`connector_code`（唯一）、`name`、`auth_type`、`status`、`updated_at` | 预置 `generic-http`、`webhook` 两条 |
| `platform_plugin_signature` | `id`、`plugin_code`、`version`、`signer`、`key_id`、`sha256`、`status`、`signed_at` | 唯一 `(plugin_code, version)`；状态 `trusted/revoked` |
| `platform_model_provider` | `id`、`provider_code`（唯一）、`name`、`gateway_route`、`status`、`updated_at` | 预置为空，由运营登记 |
| `platform_setting` | `key`（PK）、`category`、`value jsonb`、`is_secret`、`updated_by`、`updated_at` | 键白名单在 `PlatformSettingCatalog`：`email.*`、`storage.*`、`model.*`、`limits.*`；未知键 422 `SETTING_UNKNOWN_KEY`；密钥值只写不读 |
| `platform_operation_run` | `id`、`kind`、`tenant_id`（可空）、`status`、`started_at`、`finished_at`、`summary`、`operator_id`、`correlation_id`、`trace_id` | `kind IN ('migration','backup','restore_drill','queue_requeue','catalog_seed')` |

**真实生效的两条设置**（其余仅存储+校验，运行时消费在对应能力上线时接入，运行手册写明映射）：
1. `limits.max_tenants`：`TenantLifecycleService.create` 先查 `platform_setting`，达到上限 429 `TENANT_LIMIT_REACHED`。
2. `limits.support_access_max_minutes`：`SupportAccessRules` 上限从固定 60 改为取该配置（硬上限仍不得超过 60）。

**迁移审计：** 新增 `MigrationAuditRecorder`（`ApplicationRunner`），启动后用 `Flyway.info()` 把当前版本与状态写入 `platform_operation_run(kind='migration')`，不依赖应用账号读 `flyway_schema_history` 的授权。

---

## 四、代码落点

### 4.1 `backend/modules/platform`（新增子包，复用现有模块避免加第 21 个模块）

| 包 | 文件 | 职责 |
|---|---|---|
| `platform.operator` | `PlatformOperator.java`、`PlatformOperatorStore.java`、`PlatformOperatorService.java`、`PlatformOperatorSessionStore.java`、`PlatformPermissionCatalog.java`、`PlatformAuthorization.java`、`PlatformActorContext.java`、`PlatformActorContextHolder.java`、`PlatformOpsProperties.java` | 登录/登出/会话、权限判定、MFA 门禁、锁定策略（复用 identity `PasswordHasher`、`Totp`、`TotpSecretCipher`） |
| `platform.overview` | `PlatformOverviewStore.java`、`PlatformOverviewService.java` | 总览聚合 SQL（只读 platform_* 表 + MeterRegistry） |
| `platform.catalog` | `PlatformCatalogStore.java`、`PlatformCatalogService.java` | 四类目录 CRUD 与校验 |
| `platform.security` | `PlatformSecurityStore.java`、`PlatformSecurityService.java`、`PlatformSecurityRecorder.java` | 事件录入/确认/解决、跨租户拦截记录（记录失败只记日志，不影响主请求） |
| `platform.operations` | `PlatformOperationStore.java`、`PlatformOperationService.java`、`MigrationAuditRecorder.java` | 运行记录、队列/死信健康、平台审计查询 |
| `platform.settings` | `PlatformSettingCatalog.java`、`PlatformSettingStore.java`、`PlatformSettingService.java` | 白名单校验、脱敏回显、变更审计 |
| `platform` 根包 | `PlatformDirectoryStore.java` | 租户目录与指标查询/写入 |

### 4.2 `backend/boot-api`

- 新增 `PlatformOperatorAuthenticationFilter`（`/platform/api/v1/**`，放行 `auth/login`、`system/health`），写入 `PlatformActorContextHolder`，写 MDC `platform_operator_id`；CSRF 双提交。
- 改 `PlatformDomainIsolationFilter`：禁用 `X-Platform-Operator-Id`（400 退役码）；`/api/**` 拒绝 `plt_ops_session` Cookie 与旧头；跨域拦截调用 `PlatformSecurityRecorder`。
- 改 `PlatformTenantController`：去掉 `@RequestHeader(OPERATOR_HEADER)`，操作人取自 `PlatformActorContextHolder`；新增列表端点；`@PlatformPermission` 注解 + 拦截器统一鉴权（替代每方法手写）。
- 新增控制器：`PlatformAuthController`、`PlatformOverviewController`、`PlatformCatalogController`、`PlatformSecurityController`、`PlatformOperationController`、`PlatformSettingController`。
- `PlatformApiApplication` 增加 `@EnableScheduling` 无需（Worker 负责）；注册新属性类。

### 4.3 `backend/boot-worker`

- `TenantMetricCollector`（`@Scheduled`，默认 15 分钟）：遍历 `platform_tenant_directory`，逐租户 `TenantTransactions` 统计日活（`identity_session` 未过期且 `last_seen_at` 在窗口内）、`platform_file` 存储字节、`platform_job` 队列/失败数、`platform_outbox` 失败数，upsert 当日指标；保留清理。
- `SecuritySignalScanner`（`@Scheduled`，默认 5 分钟）：逐租户统计近 15 分钟 `identity_auth_event` 失败次数，超阈值（默认 10）写 `platform_security_event('abnormal_login')`，同租户同窗口去重。

### 4.4 数据库与脚本

- `backend/db/src/main/resources/db/migration/V11__platform_operations.sql`：建表、目录同步触发器、审计不可变触发器、回填目录、预置目录与默认设置。
- `tests/db/93-platform-ops.sql`：断言 12 张平台表存在且**均未启用 RLS**（显式登记例外）、目录触发器与审计不可变触发器生效、设置键白名单约束、`platform_tenant_directory` 与 `tenancy_tenant` 一致。
- `scripts/db-verify.ps1`：新增 5h 段执行 `93-platform-ops.sql`，末尾汇总加「平台运营端」。
- `tests/db/40-integrity.sql`：57 张租户表计数不变（本包不新增租户表）；补一条断言 `platform_tenant_directory` 无 RLS 且行数 = `tenancy_tenant` 行数。

### 4.5 测试

| 层 | 文件 | 覆盖 |
|---|---|---|
| 切片 | `PlatformOperatorAuthApiTest` | 登录成功/失败/锁定、TOTP、Cookie Path/HttpOnly、登出、`/auth/me` 权限、租户 Cookie 不能访问平台、平台 Cookie 不能访问 `/api` |
| 切片 | `PlatformScreenAuthzTest` | 六个界面各一组：无会话 401、缺权限 403、只读角色可读不可写、MFA 未满足 403；响应体键白名单断言不含业务正文字段 |
| 切片 | `PlatformBoundaryTest`（改 `TenantApiBoundaryTest`） | 旧运营头 400、`X-Tenant-Id` 403、跨域互斥、支持访问仍只读且带 banner |
| 真实库 | `PlatformOpsDbTest` | 目录触发器同步、指标 upsert 幂等、审计不可改删、设置密钥不回显、`limits.max_tenants` 生效、安全事件生命周期、支持访问工单→审批→时限→审计全链路、跨租户隔离 |
| 静态 | `tests/security/isolation-invariants.test.mjs` 增 3 条 | ①控制器不得读取 `X-Platform-Operator-Id`；②平台 Cookie 名与租户 Cookie 名必须不同且 Path 不同；③`platform_operations` 相关 SQL 不得引用租户业务表（`organization_person`、`worklog_*`、`platform_file.file_name` 等） |
| 契约 | `contract-check.mjs` + 自测 | 新增端点均为新增；`X-Platform-Operator-Id` 参数删除属破坏性，按 WP02-02 先例重冻结基线并在 PR 描述登记 |

---

## 五、API 与契约（OpenAPI 1.7.0）

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/platform/api/v1/auth/login` | 公开（限流） |
| POST | `/platform/api/v1/auth/logout` | 会话 |
| GET | `/platform/api/v1/auth/me` | 会话 |
| GET | `/platform/api/v1/overview` | `platform.overview.read` |
| GET | `/platform/api/v1/tenants?status=&q=&limit=` | `platform.tenant.read` |
| GET | `/platform/api/v1/catalog/apps` / `connectors` / `plugin-signatures` / `model-providers` | `platform.catalog.read` |
| POST/PUT | 同上四类 | `platform.catalog.manage` + MFA |
| GET | `/platform/api/v1/security/events?category=&status=&limit=` | `platform.security.read` |
| POST | `/platform/api/v1/security/events`、`.../{id}/acknowledge`、`.../{id}/resolve`、`.../secret-rotations` | `platform.security.manage` + MFA |
| GET | `/platform/api/v1/operations/overview`、`/runs`、`/audit` | `platform.operations.read` |
| POST | `/platform/api/v1/operations/runs` | `platform.operations.manage` + MFA |
| GET | `/platform/api/v1/settings` | `platform.settings.read` |
| PUT | `/platform/api/v1/settings/{key}` | `platform.settings.manage` + MFA |

所有写端点写 `platform_audit_event`；支持访问的申请/批准/拒绝/撤销/进入继续写 `tenancy_lifecycle_audit`（已有），并在 `platform_audit_event` 留平台侧副本，满足「工单、审批、时限与审计记录」。

---

## 六、实施顺序

1. V11 迁移 + 触发器 + 预置数据；`93-platform-ops.sql`、`40-integrity.sql`、db-verify 5h。
2. `platform.operator` 身份域：表存储、服务、会话、权限目录、属性类；`PlatformOperatorAuthenticationFilter` 与 `PlatformDomainIsolationFilter` 改造。
3. 引导脚本/运行器：`PlatformOperatorBootstrapRunner`（环境变量 `PLATFORM_BOOTSTRAP_OPERATOR_EMAIL/PASSWORD/ROLE`，表非空则跳过，幂等）。
4. 租户目录 + 总览聚合 + `PlatformTenantController` 列表与权限注解。
5. 目录四类 CRUD + 安全事件 + 作业与审计 + 设置（含 `max_tenants` 真实生效）。
6. Worker：`TenantMetricCollector`、`SecuritySignalScanner`。
7. 切片测试与真实库测试；静态不变量；旧测试改造。
8. OpenAPI 1.7.0 与基线重冻结。
9. 运行手册 `docs/runbooks/platform-operations.md`、`identity-session.md` 更新；证据 `docs/evidence/wp02-07/`；`IMPLEMENTATION_PLAN.md` 勾选与完成表。
10. 全量验证 → PR → CI 全绿 → squash 合并 → 删远端分支 → 本地 main 快进。

---

## 七、验证清单

| 项 | 标准 |
|---|---|
| 构建 | `mvn -B -ntp -f backend/pom.xml clean install`，20 模块全绿 |
| 真实库 | `scripts/dev-up.ps1` + `scripts/db-verify.ps1` 到 V11，5h 输出「WP02-07 平台运营数据库断言通过」，末尾全绿信息更新 |
| 跨组件 | `node --test "tests/**/*.test.mjs"`，含 3 条新增不变量 |
| 契约 | `node contracts/tools/contract-check.mjs` 通过，基线重冻结后提交 |
| 覆盖率 | `node backend/tools/coverage-gate.mjs` PASS |
| 证据 | `docs/evidence/wp02-07/` 含 db-verify、backend-tests、node-tests、coverage-gate 四份输出与 README（含六界面鉴权用例对照表） |

---

## 八、风险与遗留

1. **破坏性变更**：`X-Platform-Operator-Id` 头退役会让旧的平台脚本失效；PR 描述与运行手册给出迁移方式（改用 `POST /platform/api/v1/auth/login` 换取 Cookie）。
2. **Cookie 同域共存**：必须显式设置 `Path=/platform` 与 `Path=/api`，否则浏览器会把租户会话带到平台域；切片测试断言 `Set-Cookie` 的 Path/HttpOnly/SameSite。
3. **平台设置的真实生效范围**：本包只让 `limits.max_tenants` 与 `limits.support_access_max_minutes` 生效；邮件、对象存储、模型网关的运行时消费在对应能力上线时接入，设置界面先提供存储、校验与审计，运行手册写明映射，避免「改了不生效」的误解。
4. **跨租户元数据表**：三张非 RLS 平台表是设计例外，靠「字段白名单 + 不存业务正文 + SQL 断言 + 静态不变量」四重约束；新增字段必须先证明不是业务正文。
5. **Worker 依赖**：指标与异常登录依赖 Worker 定时任务，停摆时总览显示「数据截止时间」并在状态中标注 stale；提供 `POST /platform/api/v1/operations/runs` 手工补记，不做静默补数。
6. **支持访问审批仍在平台域**：审批人必须是另一个运营账号（`SupportAccessRules` 已有 operator≠approver 规则），本包把「字符串操作人」换成真实运营身份，继续禁止自批。
7. **前端未交付**：六个界面的导航隐藏与页面留待后续工作包；本包以 `/auth/me` 权限集合与逐端点鉴权用例保证后续前端接入时后端已就绪。
