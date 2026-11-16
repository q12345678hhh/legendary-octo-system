# WP02-05 职责分离和访问复核实施方案

## 一、背景与目标

WP02-04 已把「租户 → 主体 → 成员 → 权限 → 显式拒绝 → 允许集合 → 范围 → 条件」做成统一决策链，并留了 `SeparationOfDutyChecker` 调用点（默认放行）。本包补齐 PLAN §9.4、§9.5 的产品要求：

- 定义并强制执行四类职责分离：权限申请/批准、薪酬制单/复核/批准/付款导出、插件上传/安装、AI 工具注册/评级/启用；另有模块正常启用的配置/批准分离、审计员只读。
- 高权限默认限时并记录依据；季度生成高权限访问复核，逾期按「先升级、7 天后暂停」处理。
- 禁止管理员修改自身审计、批准自己的高权限、兼任被禁止的角色组合。
- 同一规则覆盖 API、批量、后台作业与 AI 入口（复用 `AuthorizationService` 决策点，不新增旁路）。

**验收证据：** 每条规则有正/负用例；批量接口、后台作业和 AI 也受同一规则约束。

### 已确认口径（用户决策）

| 议题 | 决策 |
|---|---|
| 高权限授予路径 | **强制双人**：高权限必须引用一条已由他人批准的申请；仅租户所有者可紧急直授，直授标记待复核并留痕 |
| 季度复核确认人 | **直接主管**（实线汇报），缺失回退租户所有者；所有者本人的高权限由安全管理员复核 |
| 复核逾期 | **先升级，7 天宽限后自动暂停**未确认授权 |
| SoD 规则维护 | **代码内置固定规则**，租户只读查看，不能自行放宽 |

## 二、现状与复用点

| 现状 | 复用方式 |
|---|---|
| `AuthorizationService.decide` 固定顺序，SoD 在范围/条件之后、字段之前 | 在此接入真实规则；所有入口（API、批量、文件、AI 工具）自动受约束 |
| `SeparationOfDutyChecker` 只有 `conflicts(subject, permission)`，`subject` 仅含匹配到的角色 | 扩展为携带全部角色、匹配角色与请求属性；属性缺失按不通过处理 |
| `SystemRoleCatalog.highRisk` 以角色权限集合判断高权限 | 保留方法，改为委托新的 `SodRuleCatalog`（按权限码判定） |
| `authorization_assignment` 已有 `reason`、`valid_to`、`delegated_from_id`；高权限强制到期在 `AuthorizationAdminService.grant` | 复用；新增 `source_request_id`、`review_required`，加双人校验 |
| `authorization_decision_log` 有 BEFORE UPDATE/DELETE 触发器 | 复用；另加管理员操作审计 `authorization_admin_action`，同样只插入 |
| `identity_tenant_member` 有 `is_owner`；`organization_owner_role` 派生老板；`ReportingLineStore.managersOf` 可取直接主管 | 用于复核人解析 |
| Worker 已有 `@Scheduled` 与跨租户队列模式（`platform_organization_due`，非 RLS） | 季度复核队列照此模式建 `platform_access_review_due`，避免扫描 RLS 表 |
| 支持访问已校验审批人 ≠ 申请人（`SupportAccessRules`） | 高权限申请审批沿用同一分离口径 |
| 薪酬/插件/AI/模块治理域尚无实体 | 本包只落权限码、SoD 规则、申请/复核基础设施；不实现业务域状态机（见风险与边界） |

## 三、数据模型（V10__separation_of_duty.sql）

### 3.1 新增权限码（全局目录，17 个）

在 `authorization_permission` 追加，并同步 `PermissionCatalog`：

| 分类 | 权限码 |
|---|---|
| 权限申请/复核 | `authorization.access.request`、`authorization.access.approve`、`authorization.review.read`、`authorization.review.decide`、`authorization.review.manage`、`authorization.admin_action.read` |
| 薪酬 | `payroll.run.prepare`、`payroll.run.review`、`payroll.run.approve`、`payroll.export` |
| 插件 | `plugin.upload`、`plugin.install` |
| AI 工具 | `ai.tool.register`、`ai.tool.rate`、`ai.tool.enable` |
| 模块治理 | `module.activation.configure`、`module.activation.approve` |

同步 `SystemRoleCatalog` 新增 12 个模板角色：`payroll-preparer/reviewer/approver/exporter`、`plugin-publisher/installer`、`ai-tool-developer/reviewer/enabler`、`module-configurer/approver`、`auditor`；并给 `employee` 加 `authorization.access.request`，给 `manager` 加 `authorization.review.read/decide`，给 `security-admin`、`tenant-owner` 加 `authorization.access.approve/review.manage/admin_action.read`。

### 3.2 授权申请表 `authorization_access_request`（租户表）

| 列 | 约束 |
|---|---|
| `id` | PK |
| `tenant_id` | FK `tenancy_tenant` |
| `requester_actor_id` / `requested_for_actor_id` | 复合 FK `(tenant_id, id) → identity_actor` |
| `role_code`、`scope_type`、`scope_id`、`valid_to` | 与 `authorization_assignment` 同口径 CHECK |
| `reason` | 非空 |
| `state` | `pending/approved/rejected/cancelled` |
| `decided_by_actor_id`、`decided_at`、`decision_note` | 非 pending 时必填 |
| `assignment_id` | 批准后回填，复合 FK 指向授权 |

约束：`requester_actor_id <> decided_by_actor_id`、`requested_for_actor_id <> decided_by_actor_id`（数据库兜底，服务层先给 4xx）；部分唯一索引 `(tenant_id, requested_for_actor_id, role_code, scope_type, coalesce(scope_id, 零值)) WHERE state='pending'` 防重复申请。

### 3.3 授权表增列 `authorization_assignment`

- `source_request_id uuid`：高权限双人证据；复合 FK 指向申请表。
- `review_required boolean NOT NULL DEFAULT false`：紧急直授标记，季度复核强制纳入。
- 新增 `CHECK`：`source_request_id IS NOT NULL OR review_required` 不强制（低风险直授两者皆空），由服务层按高权限判定。

### 3.4 季度复核表

`authorization_access_review`：`id`、`tenant_id`、`period_start/period_end date`、`state open/overdue/closed`、`due_at`、`created_at/closed_at/updated_at`；唯一 `(tenant_id, period_start)` 保证每季度一份；CHECK `period_end > period_start`。

`authorization_access_review_item`：`id`、`tenant_id`、`review_id`、`assignment_id`、`subject_actor_id`、`reviewer_actor_id`（可空：无人可解析）、`state pending/confirmed/revoked`、`decided_at`、`note`；唯一 `(tenant_id, review_id, assignment_id)`；复合 FK 指向复核与授权（复核删除级联，授权不级联）；CHECK 非 pending 必须有 `decided_at`。

### 3.5 管理员操作审计 `authorization_admin_action`（租户表）

`id`、`tenant_id`、`actor_id`（可空，系统作业）、`action`、`target_type`、`target_id`、`detail jsonb`、`occurred_at`。BEFORE UPDATE/DELETE 触发器拒绝（与决策日志同款），保证「管理员不能修改自身审计」。

### 3.6 季度复核队列 `platform_access_review_due`（非 RLS 系统表）

`tenant_id PRIMARY KEY`、`next_run_at timestamptz`、`updated_at`。V10 回填现有租户；`tenancy_tenant AFTER INSERT` 触发器为新租户播种下一季度。Worker 只读此表发现到期租户，业务写入仍在租户事务内完成。

### 3.7 RLS、缓存与集成

- 新增 4 张租户表全部 `ENABLE + FORCE RLS`，策略沿用 `app.tenant_id`。
- `authorization_access_request/review/item/admin_action` 挂 `authorization_bump_version()`（申请批准影响授权、复核暂停影响授权）；授权表变更仍由既有触发器覆盖。
- `tests/db/40-integrity.sql` 租户表 53 → 57；`scripts/db-verify.ps1` 迁移版本 9 → 10，新增 `91-separation.sql` 与 `AuthorizationSodDbTest` 段。

## 四、职责分离规则（代码内置）

### 4.1 静态互斥角色对（`SodRuleCatalog`）

| 组 | 互斥角色 |
|---|---|
| 薪酬（四人四职） | `payroll-preparer` × `payroll-reviewer`、`payroll-preparer` × `payroll-approver`、`payroll-preparer` × `payroll-exporter`、`payroll-reviewer` × `payroll-approver`、`payroll-reviewer` × `payroll-exporter`、`payroll-approver` × `payroll-exporter` |
| 插件 | `plugin-publisher` × `plugin-installer` |
| AI 工具 | `ai-tool-developer` × `ai-tool-reviewer`、`ai-tool-developer` × `ai-tool-enabler` |
| 模块治理 | `module-configurer` × `module-approver` |
| 审计只读 | `auditor` 与任何含管理类权限的角色互斥（`tenant.admin`、`authorization.*.manage`、`organization.structure.manage`、`directory.person.manage`、`payroll.*`、`plugin.*`、`ai.tool.*`、`module.activation.configure`） |

执行点：
1. **授予预检**：新增角色与主体现有生效角色形成任一对 → 422 `SOD_ROLE_CONFLICT`（含冲突对与依据）。
2. **决策兜底**：请求权限属于冲突对任一侧的权限集合时 → 403 `SOD_CONFLICT`；存量违规数据不会因为「历史遗留」放行。
3. 申请批准同样先做冲突预检（批准时以 `requested_for_actor_id` 的角色集合计算）。
4. 提供 `GET /authorization/separation-conflicts`（需 `authorization.review.manage`）列出当前冲突主体，供复核整改。

### 4.2 动态「制单—复核」规则（按权限 + 业务属性）

| 权限 | 禁止与操作人相同的属性 |
|---|---|
| `authorization.access.approve` | `requestedBy` |
| `authorization.assignment.manage` | `targetActorId`（仅当 `highRisk=true`） |
| `payroll.run.review` | `preparedBy` |
| `payroll.run.approve` | `preparedBy`、`reviewedBy` |
| `payroll.export` | `preparedBy`、`reviewedBy`、`approvedBy` |
| `plugin.install` | `uploadedBy` |
| `ai.tool.rate` | `registeredBy` |
| `ai.tool.enable` | `registeredBy`、`ratedBy` |
| `module.activation.approve` | `configuredBy` |
| `authorization.review.decide` | `subjectActorId`（复核人不能复核自己） |

规则语义：属性必须存在；**缺失即拒绝** `SOD_ATTRIBUTE_MISSING`；属性值统一按 UUID 比较，非法 UUID 同样拒绝。属性只能由服务端从业务记录派生，禁止直接回显客户端入参（写入运行手册）。

### 4.3 高权限判定

`SodRuleCatalog.highPrivilegePermissions()`：`tenant.admin`、`authorization.role.manage`、`authorization.assignment.manage`、`authorization.deny.manage`、`authorization.policy.manage`、`authorization.access.approve`、`payroll.run.approve`、`payroll.export`、`plugin.install`、`ai.tool.enable`、`module.activation.approve`。角色权限集合与其相交即为高权限角色；`SystemRoleCatalog.highRisk` 改为委托该方法。

### 4.4 自授与审计保护

- `AuthorizationAdminService.grant`：高权限角色且 `actorId == 当前操作人` → 403 `SOD_SELF_APPROVAL`。
- 高权限授予必须携带 `requestId`，且该申请已批准、`requested_for_actor_id` 匹配、`requester_actor_id <> 当前操作人`；否则 422 `ACCESS_REQUEST_REQUIRED`。
- 唯一例外：当前操作人经 `organization_owner_role` 判定为租户所有者时允许紧急直授，写入 `review_required=true`，并记录 `detail.emergency=true`。
- 所有管理写操作（角色、授权、拒绝、字段/条件策略、复核决定、申请决定）在同一事务写 `authorization_admin_action`；决策日志与管理员审计均不可更新/删除。

## 五、季度访问复核

### 5.1 生成

`boot-worker` 新增 `AccessReviewScheduler`（`@Scheduled(fixedDelayString="${platform.authorization.review.poll-interval:1h}")`），调用 `AccessReviewService.generateDue(20)`：

1. `platform_access_review_due` 中 `next_run_at <= now()` 的行按批取出，并把 `next_run_at` 推到下一季度（先推进再处理，避免失败重放造成重复）。
2. 每个租户建 `RequestContext(tenantId, null, "access-review", traceId)`，在 `tenantTransactions.inTenant` 内：
   - 计算本季度周期 `period_start = 当前季度首日`、`period_end = 下一季度首日`；`due_at = 生成时间 + review-window-days`（默认 14 天）。
   - 幂等插入复核（`ON CONFLICT (tenant_id, period_start) DO NOTHING`）。
   - 取所有生效授权（`revoked_at IS NULL` 且 `valid_to IS NULL OR valid_to > now()`）中「高权限角色」或 `review_required=true` 的条目，逐条解析复核人并插入 item。
   - 无条目时复核状态直接 `closed`，保留「本季度已执行」证据。
3. 解析复核人：主体 actor → user → member → person → 当前实线主管 → 主管成员对应的 actor；无主管回退租户所有者；主体本人是所有者时回退任一非本人的 `security-admin` 授权主体；仍无法解析则 `reviewer_actor_id` 为空，交由 `review.manage` 处理，但**逾期自动暂停照常执行**（fail-closed）。

### 5.2 决策与逾期

- `confirm`：item → `confirmed`，授权不变，写管理员审计。
- `revoke`：item → `revoked`，同时撤销授权（`revoked_at/revoked_by='access-review:<id>'`），触发器 bump 缓存版本；要求 MFA。
- 复核人校验：`reviewer_actor_id == 当前 actor` 或持有 `authorization.review.manage`；且不能是 `subject_actor_id`。
- 列表默认只看「我是复核人」的条目；`review.manage` 可看全租户。
- 到期：`state open → overdue`，写 `REVIEW_ESCALATED` 审计；宽限 7 天后（`grace-days`）仍未决的 pending item 自动 `revoke` 并记录原因「逾期未确认自动暂停」；全部条目决完后复核自动 `closed`。
- 手动补偿入口：`POST /authorization/access-reviews/generate`（`review.manage`），供 Worker 停摆时补跑。

### 5.3 配置

`AuthorizationProperties` 增加：`reviewWindowDays=14`、`reviewGraceDays=7`、`reviewPollInterval` 占位符由 `@Scheduled` 使用；`maxTemporaryGrantDays`（90）、`maxDelegationDays`（180）保持不动。

## 六、API 与契约（OpenAPI 1.6.0）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v1/authorization/separation-rules` | `authorization.decision.check` |
| GET | `/api/v1/authorization/separation-conflicts` | `authorization.review.manage` |
| POST | `/api/v1/authorization/access-requests` | `authorization.access.request` |
| GET | `/api/v1/authorization/access-requests?state=&limit=` | `authorization.access.request`（仅本人）或 `authorization.access.approve` |
| POST | `/api/v1/authorization/access-requests/{id}/approve` | `authorization.access.approve` + MFA |
| POST | `/api/v1/authorization/access-requests/{id}/reject` | `authorization.access.approve` + MFA |
| POST | `/api/v1/authorization/access-requests/{id}/cancel` | 申请人本人 |
| GET | `/api/v1/authorization/access-reviews?state=&limit=` | `authorization.review.read` |
| GET | `/api/v1/authorization/access-reviews/{id}` | `authorization.review.read`（按复核人过滤） |
| POST | `/api/v1/authorization/access-reviews/{id}/items/{itemId}/confirm` | `authorization.review.decide` |
| POST | `/api/v1/authorization/access-reviews/{id}/items/{itemId}/revoke` | `authorization.review.decide` + MFA |
| POST | `/api/v1/authorization/access-reviews/{id}/close` | `authorization.review.manage` |
| POST | `/api/v1/authorization/access-reviews/generate` | `authorization.review.manage` |
| GET | `/api/v1/authorization/admin-actions?limit=` | `authorization.admin_action.read` |

- 错误码沿用 `ProblemDetails`：`SOD_ROLE_CONFLICT`、`SOD_SELF_APPROVAL`、`SOD_ATTRIBUTE_MISSING`、`ACCESS_REQUEST_REQUIRED`、`REVIEW_ITEM_NOT_FOUND`、`REVIEW_NOT_REVIEWER`、`REVIEW_ALREADY_DECIDED`。
- `contracts/openapi/platform-api.v1.json` 升 1.6.0，新增上述路径与 schema；契约基线重新冻结。
- 授权治理写接口继续要求 MFA；申请批准、复核撤销、季度生成同样要求 MFA。

## 七、代码落点

### 新增 `backend/modules/authorization`

| 文件 | 职责 |
|---|---|
| `SodRuleCatalog.java` | 静态互斥对、动态属性规则、高权限权限码；`conflicts()` 纯函数 |
| `SodChecker.java` | `SeparationOfDutyChecker` 实现，注入 `SodRuleCatalog`；替换 WP02-04 默认放行 Bean |
| `AccessRequestStore.java` / `AccessRequestService.java` | 申请、审批、撤销、驳回；批准时复用 `grant` 内部路径 |
| `AccessReviewStore.java` / `AccessReviewService.java` | 生成、复核、逾期升级、自动暂停、关闭 |
| `AdminActionStore.java` | 管理员操作审计写入与查询 |
| `AuthorizationAdminService.java`（改） | 双人校验、自授禁止、冲突预检、审计写入 |
| `AuthorizationService.java`（改） | SoD 调用传全部角色 + 属性；属性缺失拒绝 |
| `AuthorizationProperties.java`（改） | 复核窗口/宽限/轮询配置 |
| `PermissionCatalog.java`、`SystemRoleCatalog.java`（改） | 新权限码与角色模板 |

### 修改 `organization`

- `OrganizationScopeQueries` 增加 `managerActorOf(personId, today)`、`ownerActorIds(today)`，供复核人解析；SQL 仍留在 organization 模块内。

### 修改 `boot-api` / `boot-worker` / 数据库

- `AuthorizationController`：规则与冲突查询、管理员审计查询。
- `AuthorizationAdminController`：申请与复核端点。
- `boot-worker`：`AccessReviewScheduler`；`pom.xml` 增加 `authorization` 依赖。
- `V10__separation_of_duty.sql`；`tests/db/91-separation.sql`；`scripts/db-verify.ps1`；`tests/db/40-integrity.sql`。

## 八、实施顺序

1. V10 迁移：新增权限码、4 张租户表、授权增列、复核队列、回填与触发器；更新 40-integrity、db-verify 版本与 91 断言。
2. `SodRuleCatalog` + `SodChecker` + `SeparationOfDutyChecker` 接口扩展 + `AuthorizationService` 接线；单元测试覆盖每条规则正负用例。
3. 管理员审计 `AdminActionStore`、`AuthorizationAdminService` 双人/自授/冲突预检。
4. 申请单存储与服务、API；切片测试。
5. 复核存储、服务、Worker 调度、API；真实库测试覆盖生成、确认、撤销、逾期升级与 7 天自动暂停。
6. OpenAPI 1.6.0、契约基线重冻结。
7. 运行手册（`docs/runbooks/authorization.md` 增补复核与 SoD 章节）、证据目录 `docs/evidence/wp02-05/`、`IMPLEMENTATION_PLAN.md` 勾选与完成表。
8. 全量验证 → PR → CI → squash 合并 → 删除分支 → 快进 main。

## 九、验证清单

| 项 | 标准 |
|---|---|
| 单元 | `SodRuleCatalogTest`：静态互斥对逐对正负、动态规则逐权限正负、属性缺失拒绝、高权限判定；`AuthorizationCacheTest` 回归 |
| 切片 | `SeparationOfDutyApiTest`：自授 403、冲突 422、申请审批分离、无申请的高权限直授 422（非 owner）；`AccessReviewApiTest`：复核权限、非复核人 403、已决 409、MFA |
| 真实库 | `AuthorizationSodDbTest`：申请→批准→授权生效；静态冲突落库被拒；季度生成幂等；逾期升级；7 天后自动暂停且缓存版本递增；管理员审计不可改删；跨租户隔离 |
| SQL 断言 | `91-separation.sql`：4 张表 RLS、唯一索引、CHECK、不可变触发器、队列回填、租户级隔离，输出「WP02-05 职责分离数据库断言通过」 |
| 全量 | `mvn clean install`（20 模块）、`db-verify.ps1`（V1–V10）、`contract-check.mjs` + 自测、`coverage-gate.mjs` |
| 绕过检查 | 批量读、CSV 导出、Worker 生成的复核与文件下载均走 `AuthorizationGuard`/`AuthorizationService`，用同一 SoD 用例证明无法绕过 |

## 十、风险与兼容

1. **存量冲突角色**：决策层对受影响权限直接拒绝，可能让历史用户突然失去某个管理动作；上线前用 `separation-conflicts` 清单先整改，运行手册给出处置步骤。
2. **属性可信度**：动态规则依赖调用方传入 `preparedBy` 等属性。约定只能由服务层从持久化记录派生；缺失即拒绝。AI 工具执行链必须由工具适配层填充，不接受模型生成值。
3. **复核积压**：Worker 停摆会导致复核不生成；提供手动生成入口，并把「本季度复核存在」纳入运维检查。自动暂停是 fail-closed 选择，可能误伤休假主管，故保留 7 天宽限与升级提醒。
4. **紧急直授**：仅所有者可用且强制进入复核；审计标记 `emergency`，不能借此绕过双人规则给他人高权限。
5. **业务域未实现**：薪酬、插件、AI 工具、模块治理本包只落权限码与规则，真实状态机在后续工作包接入时必须传入上述属性；未实现前这些权限只用于规则测试与预置角色。
6. **平台支持访问审批人**：租户所有者与平台支持访问审批者分属两个认证域，本条由 WP02-07 的平台运营端保证；本包只在运行手册记录该边界。
7. **季度边界**：周期按 `Clock` 的时区计算，跨时区租户统一按服务端时区划季；如需租户本地时区，后续在 `AuthorizationProperties` 增加租户级覆盖，不改变数据模型。
