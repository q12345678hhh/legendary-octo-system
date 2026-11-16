# WP02-01 租户生命周期 实施方案

## Context

`IMPLEMENTATION_PLAN.md` 中 Phase 1 只到 WP01-05，且已全部合并（PR #3/#4，`e02fb73`），仓库不存在 WP01-06。经确认，本轮执行 Phase 2 第一项 **WP02-01 租户生命周期**，不改编号。

目标：建成所有业务模块依赖的租户底座——生命周期状态机、租户配置（域名/品牌/语言/时区/功能开关/套餐/配额/使用量）、平台运营边界（默认不可读业务正文，支持访问需工单+审批+时限+醒目标识）、冻结写阻断（业务写入/插件/AI，保留受控导出与申诉），并产出可复核证据。

现状要点（已核实）：

- `backend/modules/tenancy` 目前只有 `package-info.java`，模块边界已由 `ModuleStructureTest` 固定为 15 个业务模块。
- `tenancy_tenant` 在 `V1__baseline.sql` 只有 `id/slug/name/status/created_at/updated_at`，status 仅 `active/frozen/closed`；`V2__tenant_rls.sql` 已 ENABLE+FORCE RLS。
- 所有租户写库必须经 `platform.TenantTransactions.inTenant(...)`（事务级 `set_config('app.tenant_id', …, true)`），否则撞 RLS。
- `RequestContextBindingFilter` 从 `X-Tenant-Id` 取租户、`X-Subject-Id` 取主体占位。
- `scripts/db-verify.ps1` 断言迁移版本数为 5；`tests/db/40-integrity.sql` 断言 7 张 V4/V5 租户表；CI `db` 作业真实跑 PostgreSQL。
- `contracts/tools/contract-check.mjs` 只把删除/改类型/新增必填等视为破坏性变更，**新增 OpenAPI 路径是兼容变更**。
- CI 的 `backend` 作业不启动数据库；需要数据库的用例一律用 `-Dplatform.db.url` 存在才执行（参考 `ConnectionPoolTenantContextTest`）。

## 设计决策

1. **状态机**：`provisioning → active → frozen / closing`；`frozen → active / closing`；`closing → active（冷静期内撤回）/ deleted`；`deleted` 终态。`active` 才允许业务写入、插件与 AI 新执行。冷静期默认 30 天，可从 `platform.tenancy.cooling-off` 配置。
2. **平台运营边界**：平台接口统一在 `/platform/api/v1/**`，要求 `X-Platform-Operator-Id` 且**拒绝** `X-Tenant-Id`；租户接口 `/api/v1/**` **拒绝** `X-Platform-Operator-Id`。平台对租户元数据的操作仍通过 `TenantTransactions.inTenant(targetTenantId)` 进入 RLS，因此平台没有跨租户读业务表的通道。租户列表/平台总览属于 WP02-07，本包不实现跨租户目录查询。
3. **支持访问**：工单号 + 申请理由 + 审批人（必须与申请人不同）+ 最长 60 分钟 + 只读 + 过期即拒绝；进入时返回 `banner`（含工单、到期时间、只读与留痕提示），租户 GET 请求携带 `X-Support-Access-Id` 时由过滤器校验并回写醒目标识响应头。
4. **冻结阻断**：非 GET 的 `/api/v1/**` 在 `frozen/closing` 下返回 423 `TENANT_FROZEN`；仅放行注销撤回与申诉；导出为 GET，保留受控导出。插件/AI 通过 `TenantExecutionGate` 的 `assertPluginExecution` / `assertAiExecution` 在各自入口调用。
5. **两租户同业务编号**：编号按 `(tenant_id, scope)` 独立序列发放，业务表主键包含 `tenant_id`，同一 `number` 在两个租户下天然不冲突。
6. **最终删除**：本包只做状态与审计（`deleted`、`deleted_at`、释放域名、撤销支持访问）；物理清空/保留策略由 WP02-08 承接，避免越界。
7. **单号/配额/审计/支持访问均为租户表**：全部照抄 `backend/db/templates/tenant-table.template.sql` 的 ENABLE+FORCE RLS。

## 变更清单

### 1. 数据库迁移（新增，不改历史文件）

`backend/db/src/main/resources/db/migration/V6__tenant_lifecycle.sql`

- `tenancy_plan`（平台级目录，无 RLS，只有套餐名与默认配额/开关）：`code` 主键，种子 `trial/standard/enterprise`。
- 扩展 `tenancy_tenant`：`plan_code`、`frozen_at`、`frozen_reason`、`closing_requested_at`、`cooling_off_until`、`deleted_at`、`version`；status 约束改为 `provisioning/active/frozen/closing/deleted`；补 `closing`/`deleted` 的一致性检查。
- 新增租户表（全部 RLS ENABLE+FORCE，策略同模板）：
  - `tenancy_profile`（品牌名/品牌色/语言/时区/功能开关 jsonb）
  - `tenancy_domain`（域名全局唯一、主域名每租户唯一）
  - `tenancy_quota`（配额上限/已用量/周期）
  - `tenancy_business_number_sequence`（按租户+业务域的原子序列）
  - `tenancy_business_number`（已发放编号，`(tenant_id, scope, number)` 主键）
  - `tenancy_lifecycle_audit`（动作、前后状态、操作人、原因、工单、关联 ID）
  - `tenancy_support_access`（工单、申请人、审批人、时限、状态、使用时间）
  - `tenancy_appeal`（冻结/注销中的申诉）

### 2. tenancy 模块（主要实现）

`backend/modules/tenancy`：

- `pom.xml`：补 `platform`、`spring-jdbc`、`spring-tx`、`jackson-databind`、测试依赖（JUnit、postgresql）。
- `TenantStatus`（枚举 + 合法迁移 + `writable/executable`）
- `TenantRecord` / `TenantProfile` / `TenantQuota` / `TenantDomain` / `TenantLifecycleAudit` / `TenantSupportAccess` / `TenantAppeal` / `TenantPlan` 记录类型
- `TenantStore`：租户、配置、域名、配额、编号、审计、申诉的 JDBC 读写；所有调用都在 `TenantTransactions` 内
- `SupportAccessStore`：支持访问读写
- `TenantStatusQueries`：按请求上下文读取当前租户状态（供过滤器与 Gate 复用）
- `TenantExecutionGate`：`assertBusinessWrite` / `assertPluginExecution` / `assertAiExecution`
- `TenantLifecycleService`：create/initialize/freeze/restore/requestClosing/cancelClosing/delete/profile/quota/编号/导出/申诉；每次状态变更同事务写审计
- `TenantSupportAccessService`：申请/审批/拒绝/撤销/进入；`SupportAccessRules` 纯规则（工单格式、理由长度、1–60 分钟、审批人不得等于申请人、过期判定）
- `TenancyConfiguration`：`Clock` Bean（便于冷静期测试）

### 3. boot-api 接口与边界

`backend/boot-api/src/main/java/com/acme/platform/`：

- `TenantController`（`/api/v1/tenant`）：概览、更新配置、注销申请/撤回、受控导出（GET）、申诉、审计、配额消耗演示、业务编号发放。
- `PlatformTenantController`（`/platform/api/v1/tenants`）：创建、初始化、冻结、恢复、注销申请/撤回、最终删除、配置、配额、支持访问申请/审批/拒绝/撤销/进入。
- `PlatformDomainIsolationFilter`：平台域与租户域请求头互斥（401/403，RFC 9457 JSON）。
- `TenantWriteBlockFilter`：冻结/注销中拦截非 GET 业务写，放行注销撤回与申诉。
- `SupportAccessBannerFilter`：校验 `X-Support-Access-Id`，只读；回写 `X-Support-Access-Notice` 与到期时间头。

### 4. 契约与数据库用例

- `contracts/openapi/platform-api.v1.json`：追加上述路径与最小 schema（新增路径为兼容变更，无需改基线）。
- `tests/db/60-tenant-lifecycle.sql`：以 `platform_app` 验证新表 RLS、同编号跨租户并存、审计可写、无上下文读不到业务表。
- `tests/db/40-integrity.sql`：RLS 表清单从 7 张扩到 15 张（新增 8 张）。
- `scripts/db-verify.ps1`：迁移版本 5→6；新增“租户生命周期”步骤（执行 `60-tenant-lifecycle.sql`，再跑 tenancy 模块的 `TenantLifecycleDbTest`）。

### 5. 测试

- `TenantStatusTest`：合法/非法迁移与 `writable`。
- `SupportAccessRulesTest`：工单格式、理由长度、时限、审批人分离、过期。
- `TenantExecutionGateTest`：active 放行；frozen/closing/deleted 分别返回 423/410；插件与 AI 的动作码。
- `TenantLifecycleDbTest`（`-Dplatform.db.url` 才执行）：创建两租户→初始化→同业务编号 `WO-000001` 并存→冻结后 Gate 拒绝→恢复→注销→冷静期前删除 409→改冷却期后删除→审计链完整；无上下文时 `work_assistant_segment` 与 `tenancy_tenant` 均为 0 行。
- `TenantControllerTest` / `PlatformTenantControllerTest`（`@WebMvcTest` + `@MockitoBean`）：关键状态码、平台/租户头互斥、冻结写阻断、支持访问只读与过期。
- `TenantWriteBlockFilterTest`：放行 GET/白名单，拦截 POST。
- 更新 `ModuleStructureTest` 无需改动（仍 15 个模块）。

### 6. 文档与证据

- `docs/runbooks/tenant-lifecycle.md`：状态图、操作步骤、冻结影响面、支持访问与导出/申诉、回退说明。
- `docs/evidence/wp02-01/`：`backend-tests.txt`、`db-verify.txt`、`contract-check.txt`、`two-tenant-numbers.txt`、`frozen-block.txt`、`support-access.txt`、`platform-isolation.txt`、`README.md`（日期/版本/命令/期望/实际/结论）。
- `IMPLEMENTATION_PLAN.md`：勾选 WP02-01 四项，补完成记录表。

## 验证步骤

1. `mvn -B -ntp -f backend/pom.xml -pl modules/tenancy -am test`
2. `mvn -B -ntp -f backend/pom.xml clean install`（含模块边界、单测、覆盖率 JVM 参数）
3. `node backend/tools/coverage-gate.mjs`
4. `node contracts/tools/contract-check.mjs`
5. `powershell -File scripts/db-verify.ps1`（真实 PostgreSQL：迁移 6 个版本、RLS、生命周期、编号、审计、幂等与连接池）
6. `pwsh` 不可用时用 Windows PowerShell；CI 使用 `pwsh -NoProfile -File scripts/db-verify.ps1`
7. 全部通过后按固定流程：分支 → Conventional Commit → push → REST 建 PR → 11 项 CI 全绿 → squash 合并 → 删远端分支 → 本地 main 快进。

## 范围外

- 平台运营端六个界面与跨租户目录/总览（WP02-07）。
- 真实身份、会话、MFA 与权限决策（WP02-02/04）；本包沿用 `X-Subject-Id` 占位并继续标注该限制。
- 数据主体请求、回收站与物理清空（WP02-08）。
- 前端页面：平台控制台随 WP02-07；本包只交付 API、过滤器与返回给前端的 `banner` 数据。
