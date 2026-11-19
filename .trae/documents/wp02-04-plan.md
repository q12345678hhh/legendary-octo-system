# WP02-04 统一授权决策实施方案

## 一、背景与目标

WP02-02 遗留④：`/api/v1/people/**` 读接口只要求登录；WP02-03 遗留④：细粒度权限解释由 WP02-04 收口。当前仓库只有 `IdentityGuard.requireAdmin`（owner/admin 布尔位）可用，`authorization` 模块只有一个空包声明，没有角色、权限、范围、字段、条件、显式拒绝、临时授权、委托或缓存失效能力。

本工作包落地 PLAN §9 的统一决策：一次判定同时满足「认证有效 ∧ 租户匹配 ∧ 主体有效 ∧ 权限存在 ∧ 数据范围命中 ∧ 条件通过 ∧ 无显式拒绝」，默认拒绝、显式拒绝优先，并提供受安全权限保护的权限解释器与模拟器。本包同时把人员目录、搜索、导出、批量读取收口到同一决策，文件下载接入同一决策，为后续 AI 工具与报表留下同一入口。

**边界（不在本包）**：职责分离规则（WP02-05，仅留 hook）、平台运营认证域与页面（WP02-07）、项目/协作空间实体（本包只保留 `project_space` 范围类型并显式拒绝）、AI 工具注册与执行链（本包提供守卫与测试替身）、前端菜单。

## 二、现状与复用点

| 能力 | 现有落点 | 复用方式 |
| --- | --- | --- |
| 主体与会话 | `ActorContext`（tenantId/actorId/userId/memberId/actorType/mfa） | 决策入参直接使用，不在新模块重建认证 |
| 租户成员 | `identity_tenant_member`（`is_owner`/`is_admin`/`status`/`person_id`），`MemberStore` | 作为派生授权的身份基线，决策前重新校验成员状态 |
| 租户事务与 RLS | `TenantTransactions.inTenant(...)`、`RequestContext` | 所有授权表读写与决策查询都在租户事务内执行 |
| 组织数据 | `organization_*` 表、`OrganizationStructureStore`、`ReportingLineStore`、`DelegationStore`、`OwnerRoleStore`、`AssignmentStore` | 在 organization 模块新增只读 `OrganizationScopeQueries`，authorization 只调用它的公开方法 |
| 老板角色 | `organization_owner_role`（owner/trustee） | 派生 `business-owner` 授权，租户管理员不自动获得业务正文 |
| 管理员守卫 | `IdentityGuard.requireAdmin(mfa)` | 改为委托 `AuthorizationGuard` 的 `tenant.admin` 权限，行为保持 |
| 人员接口 | `PeopleController` + `PeopleService` + `PersonStore` | 读接口加权限与范围过滤，新增搜索/导出/批量 |
| 文件 | `FileService`（下载需重新授权，仅所有者） | 增加 `file.read` 统一决策，保留所有者兜底 |
| 模块边界 | `ModuleStructureTest`、各模块 `package-info.java` | authorization 的 `allowedDependencies` 增加 `platform` |
| 契约与门禁 | OpenAPI 1.4.0、`tools/contract-check.mjs`、基线文件 | 升 1.5.0 并按既有流程重冻结基线 |
| 数据库验证 | `scripts/db-verify.ps1`、`tests/db/40-integrity.sql` | 新增 `90-authorization.sql` 与租户表计数调整 |

## 三、数据模型（V9__authorization.sql）

所有租户表照抄 `backend/db/templates/tenant-table.template.sql`：`ENABLE + FORCE RLS`，策略统一 `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`。

### 3.1 全局权限目录（非租户表）

`authorization_permission`：`code` PK、`resource_type`、`action`、`description`、`sensitive`、`created_at`。产品目录由迁移播种，租户不可改。初始目录约 30 条，至少覆盖：

- 人员目录：`directory.person.read`、`directory.employment.read`、`directory.person.manage`、`directory.person.export`
- 组织：`organization.structure.read`、`organization.structure.manage`
- 文件：`file.read`、`file.manage`
- 通用：`search.read`、`report.read`、`report.export`、`ai.context.read`
- 授权治理：`authorization.role.manage`、`authorization.assignment.manage`、`authorization.deny.manage`、`authorization.policy.manage`、`authorization.decision.check`、`authorization.decision.explain`、`authorization.audit.read`、`authorization.cache.read`
- 管理：`tenant.admin`、`identity.member.manage`、`business.work.read`

### 3.2 租户表（8 张）

1. `authorization_role`：自定义角色；`id`、`tenant_id`、`code`、`name`、`description`、`status`、`created_at/updated_at`；`UNIQUE (tenant_id, code)`、`UNIQUE (tenant_id, id)`。系统角色不入库（见 3.5）。
2. `authorization_role_permission`：`role_id`、`permission_code`、`UNIQUE (tenant_id, role_id, permission_code)`；复合外键 `(tenant_id, role_id)`，`permission_code` 引用全局目录。
3. `authorization_assignment`：`actor_id`、`member_id`、`role_code`、`scope_type`、`scope_id`、`valid_from`、`valid_to`、`reason NOT NULL`、`granted_by`、`delegated_from_id`（自引用）、`revoked_at`、`revoked_by`。约束：`valid_to > valid_from`；`scope_type` 枚举校验；`scope_id` 在 `tenant/self/self_created/direct_reports/reporting_chain` 下必须为空，其余必须非空；`role_code` 允许系统角色码或本租户自定义角色码（服务层校验）。索引：`(tenant_id, actor_id, revoked_at)`、`(tenant_id, role_code)`。
4. `authorization_deny`：`actor_id` 或 `role_id` 至少一个；`permission_code` 必填；可选 `resource_type`、`resource_id`、`scope_type`、`scope_id`；`reason`、`expires_at`、`created_by`。部分唯一索引防重复。
5. `authorization_field_policy`：`role_id`、`resource_type`、`field_name`、`can_view`、`can_edit`、`can_export`、`can_use_ai`、`effect ('allow','deny')`；`UNIQUE (tenant_id, role_id, resource_type, field_name)`；字段拒绝优先于记录级允许。
6. `authorization_condition_policy`：`permission_code`、`name`、`condition jsonb`、`effect ('allow','deny')`、`priority`、`status`；`CHECK (jsonb_typeof(condition) = 'object')`。
7. `authorization_cache_version`：`tenant_id` PK、`version bigint NOT NULL DEFAULT 1`、`updated_at`。
8. `authorization_decision_log`：`actor_id`、`permission_code`、`resource_type`、`resource_id`、`scope_type`、`scope_id`、`allowed`、`reason_code`、`factors jsonb`、`occurred_at`；只插入触发器拒绝 UPDATE/DELETE；索引 `(tenant_id, occurred_at DESC)`、`(tenant_id, actor_id, occurred_at DESC)`。

### 3.3 版本触发（缓存失效的数据库事实源）

新增函数 `authorization_bump_version()`：`INSERT INTO authorization_cache_version(tenant_id) VALUES (NEW.tenant_id) ON CONFLICT (tenant_id) DO UPDATE SET version = version + 1, updated_at = now()`。

在以下表挂 `AFTER INSERT/UPDATE/DELETE` 行触发器（DELETE 用 `OLD.tenant_id`）：

- 授权表：`authorization_role_permission`、`authorization_assignment`、`authorization_deny`、`authorization_field_policy`、`authorization_condition_policy`
- 组织表：`organization_employment_assignment`、`organization_reporting_line`、`organization_delegation`、`organization_owner_role`
- 身份表：`identity_tenant_member`（状态、`person_id`、owner/admin 位变化都会重算派生授权）

这样无论从应用服务、批量脚本还是后续模块写入，事务提交即 bump 版本，不依赖各模块记得调用失效方法；高风险撤权接口再额外清本机缓存。

### 3.4 系统角色模板（代码，不可被租户修改）

`SystemRoleCatalog`（代码常量）：

| 角色码 | 权限 | 默认范围 | 说明 |
| --- | --- | --- | --- |
| `tenant-owner` | `tenant.admin`、`identity.member.manage`、`organization.structure.*`、`authorization.*` | tenant | 由 `is_owner` 派生 |
| `tenant-admin` | `tenant.admin`、`identity.member.manage`、`organization.structure.*` | tenant | 由 `is_admin` 派生；不含业务正文 |
| `business-owner` | `business.work.read`、`directory.person.read`、`directory.employment.read` | tenant | 由 `organization_owner_role=owner` 派生 |
| `security-admin` | `authorization.role/assignment/deny/policy.manage`、`authorization.audit.read`、`authorization.decision.explain` | tenant | 显式授权，不自动给 owner/admin |
| `hr-admin` | `directory.person.*`、`directory.employment.*` | tenant | 显式授权 |
| `manager` | `directory.person.read`、`business.work.read` | direct_reports + reporting_chain | 由生效实线汇报派生 |
| `delegate-manager` | 同 `manager` | org_unit_subtree（委派子树） | 由生效 `organization_delegation` 派生 |
| `employee` | `directory.person.read`、`file.read` | self + 本部门目录 | 所有在职成员基线 |

自定义角色只存权限集合，范围由授权记录决定；复制系统角色时把权限集合写入自定义角色，后续系统模板升级不回溯改写。

### 3.5 派生授权与显式授权合并

`DerivedGrantResolver` 在每次决策时基于当前组织快照计算（不落存储，天然随调动/离职/委派生效自动重算）：

- `identity_tenant_member.is_owner` → `tenant-owner`，`is_admin` → `tenant-admin`（管理员命名空间，不含业务正文）。
- 生效 `organization_owner_role`（owner/trustee）→ `business-owner`。
- 生效 `organization_delegation` 且被委托人是当前主体 → `delegate-manager`，范围取委派子树。
- 生效实线汇报中作为经理 → `manager`（直接下属 + 汇报链）。
- 所有在职成员 → `employee`（本人 + 主岗所在部门目录）。

显式 `authorization_assignment` 在此之上叠加；显式 `authorization_deny` 优先于两者。

## 四、决策引擎

### 4.1 入参与出参

`AccessRequest(tenantId, actorId, permission, resourceType, resourceId, scopeType, scopeId, attributes)`；`AccessDecision(allowed, reasonCode, factors, scopes, fieldRules, cacheVersion, evaluatedAt)`。

`DecisionReason`（fail-closed，缺一即拒绝）：

`TENANT_MISSING`、`SUBJECT_MISSING`、`MEMBER_INACTIVE`、`PERMISSION_UNKNOWN`、`NO_ALLOW`、`SCOPE_MISS`、`SCOPE_UNSUPPORTED`、`CONDITION_FAILED`、`EXPLICIT_DENY`、`SOD_CONFLICT`（WP02-05 预留）、`RISK_BLOCKED`（预留）、`ALLOWED`、`CACHE_HIT`。

### 4.2 判定顺序（`AuthorizationService.decide`）

1. 租户存在且与请求上下文一致；主体存在且不是匿名。
2. 成员存在且 `status=active`；`actor_type` 受支持。
3. 权限码存在于全局目录，否则 `PERMISSION_UNKNOWN`。
4. 显式拒绝：actor/role 命中的 deny（未过期、资源/范围匹配）→ `EXPLICIT_DENY`，直接拒绝。
5. 允许集合：显式授权 + 派生授权，按 `valid_from <= now < valid_to`、未撤销、角色有效过滤；必须包含目标权限。
6. 范围：`ScopeResolver` 解析每个候选范围；全部未命中 → `SCOPE_MISS`；`project_space`/`record_shared` 未实现 → `SCOPE_UNSUPPORTED`（不静默降级）。
7. 条件：命中权限的 allow 策略全部通过、deny 策略任一命中即拒绝；缺少属性按失败处理。
8. 职责分离 hook（`SeparationOfDutyChecker`，本包默认放行，WP02-05 注入）与风险 hook 预留。
9. 计算字段规则并返回 `ALLOWED`。

`AuthorizationGuard.require(...)` 在拒绝时抛 `ApiException.forbidden(reasonCode, 中文说明)`，沿用 `ProblemDetailsAdvice` 的 403 结构。

### 4.3 范围解析（`ScopeResolver` + `OrganizationScopeQueries`）

在 organization 模块新增只读公开组件 `OrganizationScopeQueries`，返回 `EmploymentScope(personId, employmentId, primaryOrgUnitId, legalEntityId, owner, directReportEmploymentIds, chainEmploymentIds, delegatedUnitIds)`，SQL 留在 organization 模块内。

- `SELF`：`personId`/`memberId` 匹配。
- `SELF_CREATED` / `RECORD_SHARED`：比较资源 `ownerId`/共享表（共享表尚未存在，先按 `SCOPE_UNSUPPORTED`）。
- `DIRECT_REPORTS`：生效实线下属雇佣对应的 person。
- `REPORTING_CHAIN`：生效实线递归 CTE，深度上限 50 防御异常数据。
- `ORG_UNIT` / `ORG_UNIT_SUBTREE`：部门本身或递归子树。
- `DELEGATED`：生效 `organization_delegation.scope_org_unit_id` 子树。
- `LEGAL_ENTITY`：主岗职位所属部门的 `legal_entity_id`。
- `TENANT`：租户内全部。
- `PROJECT_SPACE`：本包无实体，显式 `SCOPE_UNSUPPORTED`。

列表类查询统一使用 `AccessScope.visibleOrgUnitIds()` / `visiblePersonIds()`，由 `PeopleService` 传给 `PersonStore`，禁止先查全量再内存过滤。

### 4.4 字段权限

`FieldPolicyEvaluator.allowedFields(resourceType, purpose, roles)`，`purpose ∈ VIEW/EDIT/EXPORT/AI`；deny 规则优先。人员目录字段：

- 公开目录字段：`displayName`、`status`、部门/职位（后续接入）。
- `primaryEmail` 默认仅本人、`hr-admin`、`business-owner` 可 VIEW；EXPORT/AI 需 `directory.person.export` 且字段允许。
- 返回体附带 `fieldRules`，前端据此隐藏；后端仍以过滤后的 DTO 输出，不依赖前端。

### 4.5 临时授权与委托

- 临时授权 = `authorization_assignment.valid_to` 必填；高权限角色默认上限 `platform.authorization.max-temporary-grant-days=90`，安全管理员可配置更短。
- 委托 = `delegated_from_id` 指向源授权；同租户、同角色、范围不得大于源、`valid_to` 不得晚于源；默认上限 `platform.authorization.max-delegation-days=180`（与组织代管默认一致）；必须填 `reason`。
- 到期后无需清理任务：决策按时间过滤；每日可选的清理只做归档，不影响判定。

## 五、缓存与失效

- `CacheVersionStore`：读取 `authorization_cache_version`；本机缓存版本号 1 秒，保证跨实例传播 ≤1 秒。
- `AuthorizationCache`：`ConcurrentHashMap`，key 含租户、版本、actor、权限、资源、范围、属性摘要；TTL 60 秒，命中时仍校验当前版本，版本变化立即整体失效。
- 高风险撤权（`DELETE /assignments/{id}`、`POST /denies`、成员停用、老板角色撤销）在事务提交后调用 `invalidateNow(tenantId, actorId)`：清本机缓存并立即重读版本；其他实例靠 1 秒版本轮询。
- 决策日志只记录拒绝与解释请求（避免写放大），保留 `factors` 供审计。

## 六、API 与契约（OpenAPI 1.5.0）

### 6.1 授权治理与决策

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/authorization/permissions` | `authorization.decision.check` | 全局权限目录 |
| `GET /api/v1/authorization/roles` | `authorization.role.manage` | 系统模板 + 自定义角色 |
| `POST /api/v1/authorization/roles` | `authorization.role.manage` + MFA | 建自定义角色 |
| `PUT /api/v1/authorization/roles/{roleId}/permissions` | `authorization.role.manage` + MFA | 设置权限集合 |
| `GET /api/v1/authorization/assignments` | `authorization.assignment.manage` | 按主体/角色查询 |
| `POST /api/v1/authorization/assignments` | `authorization.assignment.manage` + MFA | 含临时授权与委托 |
| `DELETE /api/v1/authorization/assignments/{id}` | `authorization.assignment.manage` + MFA | 撤销并立即失效 |
| `GET/POST/DELETE /api/v1/authorization/denies[/{id}]` | `authorization.deny.manage` + MFA | 显式拒绝 |
| `GET/PUT /api/v1/authorization/field-policies` | `authorization.policy.manage` + MFA | 字段策略 |
| `PUT /api/v1/authorization/condition-policies` | `authorization.policy.manage` + MFA | 条件策略 |
| `POST /api/v1/authorization/decisions/check` | 本人可查自身；查他人需 `authorization.decision.check` | 单次判定 |
| `POST /api/v1/authorization/decisions/explain` | `authorization.decision.explain` | 解释因素，不返回策略原文 |
| `POST /api/v1/authorization/simulations` | `authorization.decision.explain` | 假设角色/拒绝，不落库 |
| `GET /api/v1/authorization/cache/version` | `authorization.cache.read` | 版本与更新时间 |
| `GET /api/v1/authorization/audit/decisions` | `authorization.audit.read` | 拒绝与解释审计 |

### 6.2 人员接口（收口既有遗留）

- `GET /api/v1/people`：新增 `q` 搜索；按 `directory.person.read` 与 `AccessScope` 过滤；默认字段按字段策略输出。
- `GET /api/v1/people?format=csv`：新增导出；需 `directory.person.export`，按 EXPORT 字段策略列级过滤，`Content-Disposition` 文件名固定。
- `POST /api/v1/people/batch-read`：新增批量读取；只返回命中范围的 person，未命中的 ID 不出现在响应中且不泄露存在性。
- `GET /api/v1/people/{id}`、`/people/{id}/employments`：命中范围才返回；否则 `PERSON_NOT_FOUND`（避免存在性泄露）。
- 写接口继续要求 `tenant.admin`（兼容现有管理员行为）。

### 6.3 文件

`FileController` 的完成/下载增加 `file.read` 决策（资源类型 `file`、`resourceId=fileId`、owner 为上传主体），保留 `FILE_NOT_OWNED` 兜底；未授权统一 `FILE_ACCESS_DENIED`。

### 6.4 契约

`contracts/openapi/platform-api.v1.json` 升级 1.5.0：新增授权 18 条路径、人员 2 条路径与约 20 个 schema；人员 schema 的 `primaryEmail` 保持可空。重冻结 `contracts/baseline/contracts-baseline.json`，`node tools/contract-check.mjs` 与 `node --test tools/contract-check.test.mjs` 必须通过。

## 七、代码落点

### 新增 `backend/modules/authorization`

- `PermissionCatalog`、`SystemRoleCatalog`、`ScopeType`、`AccessScope`、`AccessRequest`、`AccessDecision`、`DecisionReason`、`FieldRule`
- `AuthorizationStore`（角色/授权/拒绝/字段/条件/决策日志/版本，全部 JDBC + 租户事务）
- `CacheVersionStore`、`AuthorizationCache`
- `DerivedGrantResolver`、`ScopeResolver`、`ConditionEvaluator`、`FieldPolicyEvaluator`
- `AuthorizationService`（决策/解释/模拟）、`AuthorizationGuard`（对外守卫）
- `RoleAdminService`、`AssignmentService`、`DenyAdminService`、`PolicyAdminService`
- `SeparationOfDutyChecker`（默认放行接口）、`RiskGate`（预留）
- `AuthorizationProperties`（`platform.authorization.*`：缓存 TTL、临时授权上限、委托上限、解释开关）
- 修改 `package-info.java` 的 `allowedDependencies` 增加 `platform`；`pom.xml` 参照 `organization/pom.xml` 补 `platform`、`identity`、`organization`、JDBC 依赖

### 新增 organization 只读能力

- `OrganizationScopeQueries`、`EmploymentScope`（公开只读）
- `PersonStore` 增加按 `visiblePersonIds`/`visibleOrgUnitIds` 的查询与搜索（`ILIKE`，参数绑定）
- `PeopleService.list(query)`、`exportCsv`、`batchRead`，所有入口先决策再查询

### 修改 boot-api

- 新增 `AuthorizationController`、`AuthorizationAdminController`、`RequiresPermission` 注解、`AuthorizationInterceptor`、`AuthorizationWebConfiguration`
- 修改 `PeopleController`（读权限、q、CSV、批量）、`FileController`（`file.read`）
- 修改 `IdentityGuard.requireAdmin`：保留 MFA 检查，权限判定委托 `AuthorizationGuard.requirePermission("tenant.admin")`

### 数据库与测试

- `backend/db/src/main/resources/db/migration/V9__authorization.sql`
- `tests/db/90-authorization.sql`（RLS、触发器 bump、显式拒绝、临时授权过期、委托上限、只插不改）
- `tests/db/40-integrity.sql` 更新租户表计数；`scripts/db-verify.ps1` 增加 90 用例
- 单测：`DecisionEngineTest`、`ScopeResolverTest`、`ConditionEvaluatorTest`、`FieldPolicyEvaluatorTest`、`DerivedGrantTest`、`AuthorizationCacheTest`
- 切片/API：`AuthorizationApiTest`（缺租户/主体/权限/范围默认拒绝、解释需权限、模拟不落库、撤销立即生效）
- 真实库：`AuthorizationDbTest`（RLS 隔离、触发器版本、临时授权到期、委托范围不得放大）
- 人员越权：`PeopleAccessApiTest`（部门目录过滤、邮箱字段、CSV/Batch 不泄露、未命中 404）
- 文件：`FileAccessApiTest`（非所有者 403、跨租户 404）
- 模块边界：`ModuleStructureTest` 保持 15 个模块不变

## 八、实施顺序

1. V9 迁移、权限目录、触发器与 DB 断言；先跑 `db-verify.ps1`。
2. authorization 域模型、目录、存储、版本与缓存。
3. 决策引擎、派生授权、范围解析、条件/字段策略。
4. 治理服务与 `AuthorizationGuard`。
5. organization 只读范围查询；人员读/搜索/导出/批量收口。
6. boot-api 控制器、拦截器、`IdentityGuard` 委托、文件接入。
7. OpenAPI 1.5.0 与基线重冻结。
8. 测试补齐、证据目录 `docs/evidence/wp02-04/`、`docs/runbooks/authorization.md`、`IMPLEMENTATION_PLAN.md` 勾选与完成表。
9. 全量验证后提 PR，CI 11 项全绿再 squash 合并、删远端分支、本地 main 快进。

## 九、验证清单

```powershell
mvn -B -ntp -f backend/pom.xml clean install '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'
node backend/tools/coverage-gate.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-verify.ps1
node tools/contract-check.mjs
node --test tools/contract-check.test.mjs
```

证据需覆盖：

- 缺租户、缺主体、未知权限、无范围、条件失败、显式拒绝各有 403 用例，默认拒绝。
- 同一决策结果覆盖 API、搜索、CSV 导出、批量、文件；未授权 ID 不泄露存在性。
- 解释与模拟需安全权限；无权限 403；模拟不产生授权记录。
- 改角色/撤授权/停成员/调岗/撤销老板角色后，版本递增；60 秒内缓存失效；高风险撤权本机立即生效。
- RLS 全表通过；`authorization_decision_log` 不可改删；跨租户 UUID 写入失败。

## 十、风险与兼容

1. `/people` 读行为变化会打破“登录即可读全租户”的既有假设，需同步更新 `AuthApiTest`/`OrganizationApiTest` 的夹具与断言。
2. 触发器 bump 版本会在组织调动、成员停用时各多一次单行更新，可接受；迁移需验证 `platform_app` 在租户上下文内可写 `authorization_cache_version`。
3. 递归 CTE 必须有深度上限，防止脏数据导致查询失控。
4. 系统角色在代码中，租户不能直接编辑；只能复制为自定义角色后收窄，需在 API 与 runbook 写明。
5. `PROJECT_SPACE`/`RECORD_SHARED` 尚未有实体，一律 `SCOPE_UNSUPPORTED`，不得静默当作租户范围。
6. 管理员兼容：`tenant-owner`/`tenant-admin` 不含业务正文；老板权限只来自 `organization_owner_role`，与 WP02-03 遗留④一致。
7. 契约 1.5.0 为增量版本，人员响应字段保持兼容（邮箱可空），基线重冻结后门禁通过。
