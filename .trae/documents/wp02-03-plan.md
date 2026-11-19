# WP02-03 组织和生效日期 实施方案

## Context

WP02-02 已合并（`8dd2385`）。当前 `organization` 模块只有 `Person` / `Employment` 两张表与 `PeopleService`，组织侧完全是空白：没有法人、部门树、岗位、职位、职级、成本中心、地点，也没有主/兼岗、汇报线、管理委派和老板角色。`PeopleController` 的读写只要求登录 + `is_admin`，没有任何组织生效日期概念。

本工作包按 `IMPLEMENTATION_PLAN.md` WP02-03 补齐：

- 法人、部门、岗位、职位、职级、成本中心、地点和主/兼岗；
- 实线/虚线汇报、管理委派、未来变更、生效/失效和历史快照；
- 组织树和汇报图禁止环；移动部门前必须生成影响预览；
- 老板/最高负责人是独立业务角色，至少一名有效受托人；租户管理员不自动获得正文权限。

验收证据：三级汇报链、兼岗、代管、虚线和调动历史正确；空老板角色会阻断正常模块启用。

## 0. 已确认决策

1. **双轨时间模型**：结构实体保存当前态 + 变更单 + 不可变历史快照；关系实体（主兼岗、汇报线、委派、老板角色）直接带 `effective_from/effective_to`。
2. **boot-worker 定时应用**：到期变更由 worker 每 30 秒逐租户应用，`FOR UPDATE SKIP LOCKED` 防重复。
3. **预览票据强制**：移动部门必须先拿 `previewId`，服务端复算影响并比对，票据 10 分钟过期、单次消费。
4. **岗位/职位拆两张表**：`organization_job` 表示岗位定义，`organization_position` 表示挂在部门下的具体职位。

## 1. 数据模型（V8 迁移）

新增 `backend/db/src/main/resources/db/migration/V8__organization_structure.sql`，全部租户表照抄 `backend/db/templates/tenant-table.template.sql`：`tenant_id` 外键、`ENABLE + FORCE RLS`、同名 `*_isolation` 策略。

为支持复合外键、防止跨租户 UUID 引用，先给既有两表补唯一约束（幂等、不影响数据）：

```sql
ALTER TABLE organization_person     ADD CONSTRAINT organization_person_tenant_id_unique     UNIQUE (tenant_id, id);
ALTER TABLE organization_employment ADD CONSTRAINT organization_employment_tenant_id_unique UNIQUE (tenant_id, id);
```

### 结构实体（当前态）

| 表 | 关键字段与约束 |
| --- | --- |
| `organization_legal_entity` | `code`、`name`、`registration_no` 可空、`status`（active/inactive）；`(tenant_id, code)` 唯一 |
| `organization_unit` | `parent_id` 可空、`legal_entity_id`、`code`、`name`、`unit_type`（company/division/department/team）、`status`（active/archived）、`sort_order`；`(tenant_id, code)` 唯一，`parent_id <> id`，父级用复合外键 `(tenant_id, parent_id)` 保证同租户 |
| `organization_job_level` | `code`、`name`、`rank >= 1`、`status`；`(tenant_id, code)` 唯一 |
| `organization_job` | `code`、`name`、`job_family` 可空、`default_level_id` 可空、`status`；`(tenant_id, code)` 唯一 |
| `organization_position` | `org_unit_id`、`job_id`、`code`、`name` 可空、`headcount_limit` 可空且 `>= 0`、`status`；`(tenant_id, code)` 唯一 |
| `organization_cost_center` | `code`、`name`、`legal_entity_id`、`status`；`(tenant_id, code)` 唯一 |
| `organization_location` | `code`、`name`、`address` 可空、`timezone` 默认 `Asia/Shanghai`、`status`；`(tenant_id, code)` 唯一 |

### 关系实体（生效区间）

| 表 | 关键字段与约束 |
| --- | --- |
| `organization_employment_assignment` | `employment_id`、`position_id`、`assignment_type`（primary/secondary）、`effective_from`、`effective_to` 可空；`effective_to > effective_from`；主岗部分唯一索引 `(tenant_id, employment_id) WHERE assignment_type='primary' AND effective_to IS NULL` |
| `organization_reporting_line` | `subordinate_employment_id`、`manager_employment_id`、`line_type`（solid/dotted）、`effective_from`、`effective_to` 可空、`created_by`；`subordinate <> manager`；实线部分唯一 `(tenant_id, subordinate_employment_id) WHERE line_type='solid' AND effective_to IS NULL` |
| `organization_delegation` | `delegator_employment_id`、`delegate_employment_id`、`scope_org_unit_id`、`reason` 非空、`effective_from`、`effective_to` **非空**（限时）；`delegator <> delegate`，`effective_to > effective_from` |
| `organization_owner_role` | `employment_id`、`role_type`（owner/trustee）、`effective_from`、`effective_to` 可空、`appointed_by`；同人同角色活动区间唯一 |

### 变更、历史与预览

| 表 | 关键字段与约束 |
| --- | --- |
| `organization_change` | `change_type`、`target_id` 可空、`effective_date`、`payload jsonb`、`status`（scheduled/applied/cancelled/failed）、`requested_by`、`reason`、`applied_at`、`failure_reason` |
| `organization_change_history` | `change_id` 可空、`entity_type`、`entity_id`、`action`、`effective_date`、`before_state jsonb`、`after_state jsonb`、`actor`、`occurred_at`：不可变，只插入 |
| `organization_impact_preview` | `unit_id`、`target_parent_id`、`descendant_count`、`employment_count`、`position_count`、`reporting_line_count`、`delegation_count`、`scheduled_change_count`、`created_by`、`expires_at`、`consumed_at` |

`change_type` 固定集合：`unit_create`、`unit_update`、`unit_move`、`unit_archive`、`legal_entity_upsert`、`job_upsert`、`job_level_upsert`、`position_upsert`、`cost_center_upsert`、`location_upsert`、`owner_assign`、`owner_revoke`。

租户表总数从 31 增至 **45**。

## 2. 领域规则

### 2.1 生效日期与变更单

- 「当日」按租户档案时区换算：`organization` 新增对 `tenancy` 的依赖，读 `TenantStore.profile(tenantId).timezone()`，缺失回退 UTC。
- `effective_date <= today`：同一事务内立即应用，并写一条 history。
- `effective_date > today`：只写 `organization_change(status=scheduled)`，不提前改当前态。
- 应用变更：单事务内 `SELECT ... FOR UPDATE SKIP LOCKED` 认领 → 复算前置条件（如目标部门仍存在、未归档）→ 更新当前态 → 写 history → 置 `applied`；失败置 `failed` 并记录 `failure_reason`，不留下半成品。
- 幂等：只有 `scheduled` 可应用；重复调用返回 0 条。
- 关系实体不经过变更单，直接写入未来生效行；「当前生效」= `effective_from <= today AND (effective_to IS NULL OR effective_to > today)`。

### 2.2 组织树禁止环

- 建/改/移动部门时，用递归 CTE 从目标父级向上走到根；若路径包含自身，返回 422 `ORG_UNIT_CYCLE`。
- 归档部门要求无 active 子部门、无 active 职位，否则 409 `ORG_UNIT_HAS_CHILDREN` / `ORG_UNIT_HAS_ACTIVE_POSITIONS`。
- 跨租户父级由复合外键直接拒绝，不依赖应用层判断。

### 2.3 汇报图禁止环

- 建实线/虚线前检查：`manager == subordinate` → 422 `REPORTING_SELF`；沿当前生效汇报链（实线+虚线）向上走，遇到 subordinate → 422 `REPORTING_CYCLE`。
- 虚线允许多个经理，但不允许形成环。
- 同一员工同一时刻最多一条生效实线，由部分唯一索引兜底。

### 2.4 主/兼岗与代管

- 每段 Employment 最多一条生效主岗；兼岗可多条。
- 新分配与已有区间重叠 → 409 `ASSIGNMENT_OVERLAP`。
- 职位所属部门必须 active，否则 422 `POSITION_UNIT_ARCHIVED`。
- 委派必须给结束日期，最长 180 天（配置 `platform.organization.max-delegation-days`），超限 422 `DELEGATION_PERIOD_INVALID`。

### 2.5 影响预览票据

`POST /org-units/{id}/move-previews` 返回：

- 影响数量：子孙部门、子树内在职 Employment、active 职位、跨子树汇报线、子树内委派、待生效变更；
- 四个维度结论：`permission`（WP02-04 前固定 `pending_authorization_module`）、`approval`（跨法人或影响人数超过阈值 `platform.organization.move-approval-threshold` 默认 50 时为 `required`）、`headcount`（职位数与编制上限合计）、`reporting`（受影响汇报线数量）；
- `previewId`、`expiresAt`（10 分钟）。

`POST /org-units/{id}/move` 必须带 `previewId`：

- 缺失/已消费/过期 → 409 `ORG_IMPACT_PREVIEW_REQUIRED` / `ORG_IMPACT_PREVIEW_EXPIRED`；
- 部门或目标父级与票据不一致、或复算数量变化 → 409 `ORG_IMPACT_PREVIEW_STALE`；
- 通过后消费票据、执行移动、写 history。

### 2.6 老板/最高负责人

- 老板与受托人都写在 `organization_owner_role`，与 `identity_tenant_member.is_owner/is_admin` **完全无关**；租户管理员不会因此获得业务正文权限。
- `GET /organization/readiness` 返回 `{ready, blockers[]}`，blocker 编码：
  - `OWNER_ROLE_MISSING`：无当前生效 owner；
  - `TRUSTEE_MISSING`：无当前生效 trustee；
  - `TRUSTEE_NOT_DISTINCT`：所有生效 trustee 同时都是 owner。
- `OrganizationReadiness.requireReady()` 供 Phase 5 模块启用前调用；本工作包先以接口 + 测试固定口径，不伪造尚不存在的模块启用链路。

## 3. 模块与代码结构

### `backend/modules/organization`

按现有 `Store` 只写 SQL、事务由 `TenantTransactions.inTenant(...)` 控制的风格分包：

- `legalentity/`、`unit/`、`level/`、`job/`、`position/`、`costcenter/`、`location/`
- `assignment/`：主兼岗
- `reporting/`：汇报线 + 环检测
- `delegation/`：管理委派
- `owner/`：老板角色 + `OrganizationReadiness`
- `change/`：`OrganizationChange`、`OrganizationChangeStore`、`OrganizationChangeService`、`OrganizationChangeHistoryStore`
- `preview/`：`ImpactPreviewService`
- `OrganizationConfiguration`、`OrganizationProperties`

`pom.xml` 新增 `tenancy` 依赖（读租户时区）。不依赖 identity。

### `backend/boot-worker`

新增 `organization/OrganizationChangeApplier`：

```java
@Scheduled(fixedDelayString = "${platform.organization.change-poll-interval:30s}")
public void poll() { ... }
```

- 先读 `tenancy_tenant`（平台表，无 RLS）拿租户列表；
- 逐租户 `RequestContextHolder.set(...)` 后调用 `OrganizationChangeService.applyDueForTenant(today)`；
- 失败只记日志与 `failed`，不阻塞其他租户；
- `pom.xml` 新增 `organization` 依赖。

### `backend/boot-api`

新增四个控制器，避免单类过大：

- `OrganizationStructureController`：法人、部门、岗位、职级、职位、成本中心、地点、组织树、移动预览/移动。
- `OrganizationAssignmentController`：主兼岗、汇报线、汇报树、委派。
- `OrganizationOwnerController`：老板角色、readiness。
- `OrganizationChangeController`：变更单列表/创建/取消/手动应用、历史查询。

授权沿用最小模型：读 `guard.requireUser()`；写 `guard.requireAdmin(false)`；老板角色与撤销 `guard.requireAdmin(true)`（强制 MFA）。

## 4. API 与契约

契约版本升到 **1.4.0**，新增约 36 条路径与 12 个 schema：

- 法人：`GET/POST /api/v1/legal-entities`、`PATCH /legal-entities/{id}`
- 部门：`GET/POST /org-units`、`GET/PATCH /org-units/{id}`、`GET /org-units/tree`、`POST /org-units/{id}/move-previews`、`POST /org-units/{id}/move`、`POST /org-units/{id}/archive`
- 职级/岗位/职位：`GET/POST /job-levels`、`PATCH /job-levels/{id}`；`GET/POST /jobs`、`PATCH /jobs/{id}`；`GET/POST /positions`、`PATCH /positions/{id}`
- 成本中心/地点：`GET/POST /cost-centers`、`PATCH /cost-centers/{id}`；`GET/POST /locations`、`PATCH /locations/{id}`
- 主兼岗：`GET/POST /employments/{employmentId}/assignments`、`PATCH /assignments/{id}/end`
- 汇报线：`GET/POST /reporting-lines`、`PATCH /reporting-lines/{id}/end`、`GET /reporting-lines/tree?employmentId=`
- 委派：`GET/POST /delegations`、`PATCH /delegations/{id}/end`
- 老板角色：`GET/POST /owner-roles`、`PATCH /owner-roles/{id}/end`、`GET /organization/readiness`
- 变更与历史：`GET/POST /organization/changes`、`POST /organization/changes/{id}/cancel`、`POST /organization/changes/apply-due`、`GET /organization/history`

新增错误码：`ORG_*`、`LEGAL_ENTITY_*`、`JOB_*`、`POSITION_*`、`ASSIGNMENT_*`、`REPORTING_*`、`DELEGATION_*`、`OWNER_ROLE_*`、`ORG_IMPACT_PREVIEW_*`、`CHANGE_*`。

## 5. 需要同步修改的既有文件

- `backend/db/src/main/resources/db/migration/V8__organization_structure.sql`（新增）
- `tests/db/40-integrity.sql`：注册 14 张新表，期望租户表 31 → **45**
- `tests/db/80-organization.sql`（新增）：RLS、无上下文 0 行、跨租户父级拒绝、主岗唯一、实线唯一、历史只插入
- `scripts/db-verify.ps1`：版本数 7 → **8**（两处断言），新增步骤 5d（`80-organization.sql` + `OrganizationDbTest`，沿用 5c 的聚合 POM reactor 写法）
- `backend/modules/organization/pom.xml`：新增 `tenancy`
- `backend/boot-worker/pom.xml`：新增 `organization`
- `backend/boot-api/src/main/resources/application.yml`：`platform.organization.*`（移动审批阈值、委派上限、变更轮询间隔）
- `contracts/openapi/platform-api.v1.json`、`contracts/baseline/contracts-baseline.json`：1.4.0 并重冻结
- `IMPLEMENTATION_PLAN.md`：WP02-03 勾选 + 完成情况表 + 遗留说明

## 6. 测试与证据

### 单元/切片

- `OrganizationTreeTest`：三级链、深链、自环、跨级成环、归档前置条件。
- `ReportingGraphTest`：自汇报、直接环、传递环、虚线成环、虚实混合成环。
- `EffectiveDatingTest`：未生效、当前生效、已失效、未来行写入。
- `ImpactPreviewServiceTest`：计数正确、过期、重复消费、复算不一致。
- `OwnerReadinessTest`：缺 owner、缺 trustee、trustee 全是 owner、就绪；`is_admin`/`is_owner` 不产生老板身份。
- `OrganizationChangeServiceTest`：立即应用 vs 排期、幂等、取消、失败置 `failed`。
- `OrganizationApiTest`（`@WebMvcTest`）：未认证 401、非管理员 403、老板角色改配缺 MFA 403、移动缺票据 409。

### 真实 PostgreSQL

`OrganizationDbTest`（沿用 `IdentityDbTest` 的 `@EnabledIfSystemProperty("platform.db.url")` 方式）覆盖：

1. 三级汇报链正确；
2. 兼岗（主岗 + 兼岗）；
3. 代管（限时委派）；
4. 虚线经理；
5. 部门调动前后历史快照；
6. 未来变更到日由 `applyDueForTenant` 原子切换；
7. 组织环与汇报环被拒绝；
8. 跨租户父级被复合外键拒绝；
9. 裸连接与错误租户上下文 0 行。

### 证据与文档

- `docs/runbooks/organization.md`：实体与生效语义、变更单、环规则、预览票据、老板角色与 readiness、错误码、迁移与回滚。
- `docs/evidence/wp02-03/`：`README.md`、`backend-tests.txt`、`db-verify.txt`、`org-scenarios.txt`、`contract-check.txt`、`coverage-gate.txt`。

## 7. 实施顺序

1. V8 迁移 + `40-integrity.sql` / `80-organization.sql` / `db-verify.ps1`。
2. organization 记录、Store（全部经租户事务）。
3. 结构实体服务、环检测、生效日期与变更单。
4. 预览票据、老板角色与 readiness。
5. worker 定时应用器 + 配置。
6. 控制器 + OpenAPI 1.4.0 + 基线重冻结。
7. 全量测试、证据、运行手册、计划更新；提交 PR 等 CI 全绿后 squash 合并。

## 8. 验证命令

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'
powershell -File scripts/db-verify.ps1
node contracts/tools/contract-check.mjs
node --test tests/
```

数据库验证沿用 WSL `pglab` 内 `platform-pg-dev` 容器，不新增容器。

## 9. 已知边界

- 本工作包不做前端界面；组织和汇报关系只提供 API 与契约。
- 「权限、审批、编制、报表」影响预览中，权限属 WP02-04、审批属 WP03-06、编制与报表属 Phase 7；本包只计算可确定的组织侧数量，并在响应里显式标注 `pending_*`，不伪造结论。
- `OrganizationReadiness.requireReady()` 的真实调用点在 Phase 5 模块启用；本包只固定接口与测试口径。
- 历史快照按「变更/动作」记录 before/after，不提供任意日期全量组织快照（as-of 查询留到 Phase 7 组织快照需求时评估）。
- 老板角色允许多名 owner；受托人必须至少一名且与所有 owner 不同人。
- 委派目前只支持「某部门子树」范围；跨部门项目与跨法人委派随 Phase 3 协作空间再扩。
