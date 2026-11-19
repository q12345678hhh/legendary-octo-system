# WP03-06 通用工作流与审批实施方案

## Context

`phase3-plan.md` 要求 WP03-06 交付「全量通用工作流与审批」：定义版本化、实例、人工任务、
审批、条件、并行、定时器、服务任务、SLA、转交、退回、撤回、补偿、审批人解析、Worker，
并把工时/周报审核接入同一套任务表，最后提供审批箱与实例详情页。

只读调研结论：

| 能力 | 现状 | 差距 |
|---|---|---|
| `workflow` 模块 | 只有 `package-info.java`；`pom.xml` 只依赖 modulith + starter；`allowedDependencies = {tenancy, authorization}` | 无任何表、服务、API；缺 `platform`/`organization` 依赖，直接写库会被 `ModuleStructureTest` 拒绝 |
| 权限码 | `workflow.definition.manage`、`workflow.instance.start`、`workflow.task.act`、`workflow.instance.admin` 已在 [PermissionCatalog.java](file:///D:/Projects/temp_20261109_01/backend/modules/authorization/src/main/java/com/acme/platform/authorization/PermissionCatalog.java#L80-L83) 与 V13 注册 | `workflow.instance.admin` 没有任何内置角色持有；`manager` 已有 `task.act`/`instance.start`，`tenant-owner`/`tenant-admin` 已有 `definition.manage` |
| 工时审核 | [WorklogSubmissionService.review](file:///D:/Projects/temp_20261109_01/backend/modules/worklog/src/main/java/com/acme/platform/worklog/time/WorklogSubmissionService.java#L82-L111) 直连审核；`pending(personIds)` 按授权范围查询 | 没有任务表、没有审批箱、没有转交/退回/撤回/补偿 |
| 周报审核 | [WorklogReportService](file:///D:/Projects/temp_20261109_01/backend/modules/worklog/src/main/java/com/acme/platform/worklog/report/WorklogReportService.java) 直连审核 | 同上 |
| 组织解析 | [OrganizationScopeQueries](file:///D:/Projects/temp_20261109_01/backend/modules/organization/src/main/java/com/acme/platform/organization/OrganizationScopeQueries.java) 已有本人/下属/汇报链/部门子树/代管/老板查询；无「部门负责人」字段 | 需补「直属主管 personId」「按层级的汇报链」「当前老板 personId」三个公开查询 |
| 角色成员 | `authorization_assignment` 有 `role_code`/`member_id`/有效期；无公开「按角色列人」服务 | 角色型审批人需要新增只读公开查询 |
| Worker | [boot-worker](file:///D:/Projects/temp_20261109_01/backend/boot-worker/pom.xml#L45-L78) 已依赖 `workflow`；已有 `@Scheduled` 扫描与 outbox 投递模式 | 缺定时器、服务任务、SLA 扫描三个 Worker |
| 事件 | 仓库已有 `OutboxEventPublisher`，事件目录只有 4 条 | 需新增 `workflow.task.created.v1` 并接站内通知 |
| 迁移 | V13 部门、V14 访问留痕、V15 工时、V16 协作；`db-verify.ps1` 断言 16 个版本 | 工作流迁移顺延为 **V17**，目录断言 16 → 17 |

**范围决策（沿用已确认口径）：** 条件/并行/定时器/SLA/补偿全量实现，但采用
「固定节点枚举 + 显式状态机」，不引入 BPMN 引擎，不提供脚本表达式；定义管理只提供 API，
Phase 3 不做可视化编排器。工时/周报直连审核保留为默认最短路径，二者共用 `workflow_task`。

---

## 一、硬边界

1. **版本钉住**：实例固定 `definition_id + definition_version`；发布新版本不影响运行中实例。
2. **发布不可变**：`status='published'` 后 `schema`/`schema_hash`/`version`/`key` 由触发器禁止修改，只允许转 `retired`。
3. **一个 key 只允许一个 published 版本**：部分唯一索引；发新版本自动把旧版本置 `retired`。
4. **无审批人不自动通过**：解析为空 → 写 `workflow_exception`，实例保持 `running`，任务 `assignee_person_id` 为空且仅 `instance.admin` 可见。
5. **超时不自动批准**：SLA 到期只升级/转人工/写异常，任何 Worker 都不得调用 approve。
6. **重复回调不重复写业务**：任务动作以 `status IN ('pending','claimed')` 条件更新为准；服务任务以 `idempotency_key` 唯一约束去重。
7. **业务写入与任务状态同事务**：审批通过时先执行业务回调，成功后再置任务完成；业务失败则任务保持待办。
8. **只追加**：`workflow_transition_log` 由触发器禁止 UPDATE/DELETE。
9. **不信任客户端主体**：审批人、发起人一律从 `ActorContext`/`identity_tenant_member` 解析。

---

## 二、V17 数据模型

新增 `V17__workflow_approval.sql`，8 张表，全部 `ENABLE + FORCE RLS`，每表恰好一条
`tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid` 策略（照抄
[tenant-table.template.sql](file:///D:/Projects/temp_20261109_01/backend/db/templates/tenant-table.template.sql)）。

### 2.1 `workflow_definition`

| 列 | 说明 |
|---|---|
| `id`、`tenant_id`、`key`、`version`、`name` | `UNIQUE(tenant_id, key, version)`，`version >= 1` |
| `status` | `draft / published / retired` |
| `schema jsonb`、`schema_hash` | `jsonb_typeof(schema)='object'`，哈希 SHA-256 |
| `created_by`、`published_at`、`retired_at`、`created_at/updated_at` | |

- `CREATE UNIQUE INDEX workflow_definition_one_published ON workflow_definition (tenant_id, key) WHERE status = 'published';`
- 触发器 `workflow_definition_published_immutable_trigger`：`OLD.status='published'` 且
  `NEW.schema/NEW.schema_hash/NEW.version/NEW.key` 任一变化 → `RAISE EXCEPTION`。

### 2.2 `workflow_instance`

| 列 | 说明 |
|---|---|
| `id`、`tenant_id`、`definition_id`、`definition_version`、`definition_key` | 运行期只读快照字段 |
| `business_type`、`business_id`、`business_key` | 业务关联；`business_key` 可空 |
| `status` | `running / completed / returned / withdrawn / cancelled / failed` |
| `variables jsonb`、`started_by_person_id`、`started_at`、`ended_at` | |

- 幂等：`CREATE UNIQUE INDEX workflow_instance_business_key ON workflow_instance (tenant_id, business_type, business_key) WHERE business_key IS NOT NULL;`
- 索引：`(tenant_id, status, started_at DESC)`、`(tenant_id, business_type, business_id)`。

### 2.3 `workflow_token`

`id`、`tenant_id`、`instance_id`（FK ON DELETE CASCADE）、`node_key`、
`status(active/waiting/completed/cancelled)`、`created_at`、`completed_at`。

- 索引 `(tenant_id, instance_id, status)`；并行分支的每个令牌一行。

### 2.4 `workflow_task`（工时/周报共用）

| 列 | 说明 |
|---|---|
| `id`、`tenant_id` | |
| `instance_id`、`token_id`、`node_key` | 直连审核任务三者可空 |
| `business_type`、`business_id` | 如 `worklog.time` / `worklog.report` |
| `task_type` | `approval / review / service / revise` |
| `title`、`assignee_person_id`、`assignee_kind`、`assignee_role`、`candidates jsonb` | `assignee_kind` ∈ `person/position/unit_head/reporting_chain/role/business_owner/direct` |
| `status` | `pending / claimed / completed / transferred / returned / cancelled` |
| `action`、`comment`、`due_at`、`sla_state(normal/warning/breached)` | |
| `claimed_at/by`、`completed_at/by`、`created_at/updated_at` | |

- 索引：`(tenant_id, assignee_person_id, status) WHERE status IN ('pending','claimed')`、
  `(tenant_id, business_type, business_id)`、`(tenant_id, due_at) WHERE status IN ('pending','claimed')`。

### 2.5 `workflow_transition_log`

`id`、`tenant_id`、`instance_id`、`task_id`、`node_key`、`from_status`、`to_status`、
`action`、`actor_person_id`、`comment`、`occurred_at`。

- 触发器 `workflow_transition_log_immutable_trigger`：`BEFORE UPDATE OR DELETE` → `RAISE EXCEPTION`。
- 索引 `(tenant_id, instance_id, occurred_at)`。

### 2.6 `workflow_timer`

`id`、`tenant_id`、`instance_id`、`token_id`、`node_key`、`fire_at`、`status(pending/fired/cancelled)`、
`attempts`、`last_error`、`fired_at`。

- `UNIQUE(tenant_id, instance_id, token_id, node_key)` 保证同一令牌只建一个定时器。
- 索引 `(tenant_id, status, fire_at)`。

### 2.7 `workflow_service_task`

`id`、`tenant_id`、`instance_id`、`token_id`、`node_key`、`handler_key`、`idempotency_key`、
`status(pending/running/succeeded/failed/compensated)`、`attempts`、`max_attempts`、
`last_error`、`compensation_of`、`payload jsonb`、`created_at/updated_at`。

- `UNIQUE(tenant_id, idempotency_key)`；`idempotency_key = instanceId + ":" + nodeKey`（重试复用同一键）。
- 补偿任务复用同表：`compensation_of` 指向原服务任务，`handler_key` 为补偿处理器。

### 2.8 `workflow_exception`

`id`、`tenant_id`、`instance_id`、`task_id`、`code`、`message`、`payload jsonb`、
`status(open/resolved)`、`resolved_by/at`、`resolution`、`created_at`。

- `code` ∈ `ASSIGNEE_UNRESOLVED / SLA_BREACHED / SERVICE_FAILED / CONDITION_NO_MATCH / HANDLER_MISSING / BUSINESS_ACTION_FAILED`。
- 索引 `(tenant_id, status, created_at DESC)`。

---

## 三、定义 DSL 与校验

`schema` 固定为：

```json
{
  "nodes": [
    {"key": "start", "type": "start"},
    {"key": "amountGate", "type": "exclusive", "default": "small"},
    {"key": "approve", "type": "approval",
     "assignee": {"kind": "unit_head"},
     "approvalMode": "any",
     "slaMinutes": 1440,
     "onBreach": "escalate",
     "allowTransfer": true,
     "allowReturn": true},
    {"key": "split", "type": "parallel_split"},
    {"key": "wait", "type": "timer", "delayMinutes": 60},
    {"key": "notify", "type": "service", "handler": "workflow.notify", "maxAttempts": 3,
     "compensation": "workflow.notify_compensate"},
    {"key": "join", "type": "parallel_join"},
    {"key": "end", "type": "end"}
  ],
  "edges": [
    {"from": "start", "to": "amountGate"},
    {"from": "amountGate", "to": "approve",
     "condition": {"all": [{"var": "amount", "op": "gte", "value": 10000}]}},
    {"from": "amountGate", "to": "end", "key": "small"},
    {"from": "approve", "to": "split", "when": "approved"},
    {"from": "approve", "to": "end", "when": "returned"}
  ]
}
```

- 节点类型固定 8 种：`start / approval / exclusive / parallel_split / parallel_join / timer / service / end`。
- 条件只支持结构化比较：`all / any / not` + `{var, op, value}`，`op ∈ eq/ne/gt/gte/lt/lte/in/contains`；
  不引入脚本、SpEL 或任意表达式。
- 校验器 `WorkflowSchemaValidator`（纯函数）拒绝：未知节点类型、重复 `key`、悬空边、
  `start` 不唯一、不可达节点、`approval` 缺 `assignee`、`timer` 缺延迟、
  `service` 缺 `handler`、边同时有 `condition` 与 `when`、超过 64 节点/256 边。
- 循环允许（如返工），但运行期 `MAX_TOKEN_HOPS=200` 防止死循环，超限写 `CONDITION_NO_MATCH` 异常并暂停实例。

---

## 四、引擎语义

### 4.1 令牌推进

实例内所有动作在 `TenantTransactions.inTenant` + `SELECT ... FOR UPDATE` 锁定实例行后串行执行：

```
start → 创建 instance + start token
advance(token):
  approval        → 解析审批人；建 task；token=waiting
  exclusive       → 按边顺序求值，命中第一条 → 推进；无命中 → 走 default；
                    仍无 → 异常 CONDITION_NO_MATCH，token=waiting
  parallel_split  → 每个出边建一个 token
  parallel_join   → token=waiting；当 join 节点 waiting token 数 == 入边数，
                    全部置 completed 并只推进一次
  timer           → 建 workflow_timer，token=waiting
  service         → 建 workflow_service_task，token=waiting
  end             → token=completed；实例无 active/waiting token → completed
```

### 4.2 人工任务

- `approvalMode=any`：任一候选人完成即通过，其余任务置 `cancelled`；`all`：全部通过才推进。
- 动作：`approve`、`return`；`return` 必须填意见，任务置 `returned`，实例置 `returned`（终态），
  员工重新提交生成**新的业务 ID 与新实例**（与工时「退回生成新版本」一致，不做节点回环）。
- 认领：`pending → claimed` 条件更新；只有认领人或管理员可完成。
- 转交：当前处理人或 `instance.admin`；写 `transferred` 与新任务，保留原 `due_at`，全程写日志。
- 撤回：发起人可在**没有任何审批通过**前撤回，实例 `withdrawn`，取消所有 pending/claimed 任务与 pending 定时器。
- 取消：`instance.admin`，语义同撤回，并触发补偿。

### 4.3 定时器与服务任务

- `WorkflowTimerWorker`：`@Scheduled(fixedDelay=5s)` 调 `WorkflowTimerService.fireDue()`；
  `SELECT ... FOR UPDATE SKIP LOCKED` 认领 `pending AND fire_at <= now()`，置 `fired` 后推进令牌。
- `WorkflowServiceTaskWorker`：`@Scheduled(fixedDelay=5s)` 调 `WorkflowServiceTaskService.runDue()`；
  处理器注册表按 `handler_key` 查找，未注册 → `HANDLER_MISSING` 异常；失败按指数退避重试，
  超过 `max_attempts` → 异常队列，不阻塞其他任务。
- 幂等：服务任务以 `idempotency_key` 唯一约束 + `status` 条件更新双重保证；重试不再产生第二条记录。

### 4.4 SLA

- 建任务时 `due_at = now() + slaMinutes`（Phase 3 用墙钟，不接组织日历，方案记录该取舍）。
- `WorkflowSlaScanner`（`@Scheduled(fixedDelay=1m)`）：80% 时间置 `warning`；到期置 `breached`，
  给处理人的直属主管生成升级任务（解析不到则只写异常），并写 `SLA_BREACHED` 异常。
- **任何 SLA 路径都不调用 approve**。

### 4.5 补偿

- 取消/撤回时，逆序扫描本实例已 `succeeded` 且配置了 `compensation` 的服务任务，
  插入补偿服务任务（`compensation_of` 指向原任务）。
- 补偿失败 → `SERVICE_FAILED` 异常 + `status='failed'`，由 `instance.admin` 在异常队列重试或关闭。

---

## 五、审批人解析

`AssigneeResolver` 返回 `List<UUID> personIds`，空结果写异常：

| `kind` | 解析规则 |
|---|---|
| `person` | 显式 `personId`；校验 `organization_person.status='active'` 且有当前生效雇佣 |
| `position` | `organization_employment_assignment.position_id` + 生效区间 + `organization_employment.status='active'` 的当前在职人 |
| `unit_head` | **发起人当前实线主管**（`organization_reporting_line` solid，`effective_to IS NULL OR > today`）。Phase 3 没有「部门负责人」主数据字段，本包按此口径实现并写入方案；Phase 4 评估新增显式字段 |
| `reporting_chain` | 从发起人向上第 `depth` 层实线主管；不足 `depth` → 空 |
| `role` | 授权模块新增公开查询 `RoleMembershipQueries.personIdsWithRole(roleCode, today)`：有效 `authorization_assignment` + `identity_tenant_member.status='active'` + `person_id IS NOT NULL` |
| `business_owner` | 当前 `organization_owner_role`（owner + trustee）对应 personId；为空 → 异常，绝不自动通过 |

组织模块新增三个公开只读方法（同一「当前生效」口径，避免第二套 SQL）：
`managerPersonOf(personId, today)`、`managerChainPersonIds(personId, depth, today)`、
`ownerPersonIds(today)`。

多候选人默认 `any`（任一完成即通过）；定义中可配 `approvalMode=all`（会签）。

---

## 六、代码落点与模块边界

### 6.1 `backend/modules/workflow`

`pom.xml` 增加 `platform`、`organization` 依赖；`package-info.java` 改为：

```java
@ApplicationModule(displayName = "workflow",
    allowedDependencies = {"tenancy", "authorization", "organization", "platform"})
```

类清单（均在 `com.acme.platform.workflow` 根包，避免命名接口问题）：

| 类 | 职责 |
|---|---|
| `WorkflowStore` | 全部 JDBC 读写（定义/实例/令牌/任务/日志/定时器/服务任务/异常） |
| `WorkflowDefinitionService` | 草稿、更新草稿、发布（校验 + 退休旧版）、退役 |
| `WorkflowSchemaValidator` | 纯函数校验，事务外可单测 |
| `WorkflowConditionEvaluator` | 纯函数条件求值 |
| `WorkflowInstanceService` | 启动（`business_key` 幂等）、撤回、取消、详情 |
| `WorkflowTaskService` | 认领/完成/转交/退回、`openDirectTask`、`markDecided`、待办查询 |
| `WorkflowEngine` | 令牌推进、并行汇合、节点分发（包内可见） |
| `AssigneeResolver` | 六类审批人解析；空结果写异常 |
| `WorkflowTimerService` / `WorkflowServiceTaskService` / `WorkflowSlaService` | Worker 入口 |
| `WorkflowExceptionService` | 异常队列查询与处置（重试/改派/补偿/关闭） |
| `WorkflowBusinessHandler` | SPI：`businessType()`、`approved(...)`、`returned(...)`，由 boot-api 实现 |
| `WorkflowQueryService` | 审批箱、实例时间线、我的发起 |

`WorkflowTaskService` 通过构造注入 `List<WorkflowBusinessHandler>`；**没有处理器时拒绝完成业务任务**
（`HANDLER_MISSING`），不静默成功。

### 6.2 组织与授权模块的公开扩展

- `OrganizationScopeQueries` 新增 `managerPersonOf`、`managerChainPersonIds`、`ownerPersonIds`。
- `authorization` 新增公开只读 `RoleMembershipQueries`（`personIdsWithRole`）。
- 不改 V1–V12 迁移；不改既有权限码语义。

### 6.3 角色映射修正

`SystemRoleCatalog`：

- `tenant-owner`、`tenant-admin` 增补 `workflow.instance.admin`（当前无角色持有该权限）。
- `security-admin` 增补 `workflow.instance.admin`（异常处置与补偿，不含业务正文）。
- `manager` 保持 `workflow.task.act` + `workflow.instance.start`。
- `employee` 不授予 `workflow.task.act`；工时审核任务由主管处理，员工不进入通用审批箱。

---

## 七、工时/周报接入（共用任务表）

### 7.1 默认最短路径

1. `POST /worklog/days/{date}/submit` 成功后，boot-api 编排调用
   `WorkflowTaskService.openDirectTask(businessType="worklog.time", businessId=submissionId,
   taskType="review", title="工时待审核", assigneeKind="direct", assigneePersonId=直属主管 personId)`。
   - 主管无法解析 → 跳过建任务（不阻断提交），工时仍可在 `GET /worklog/reviews` 审核。
   - 幂等：按 `(tenant, business_type, business_id, task_type)` 已存在则复用。
2. `POST /worklog/submissions/{id}/review` 成功后调用
   `WorkflowTaskService.markDecided("worklog.time", submissionId, reviewer, action, comment)`：
   **只关闭任务、不回调业务**（业务动作已由工时模块完成）。
3. 周报同理：`worklog.report` + `reportId`。

### 7.2 定义驱动路径（可选启用）

- 租户发布 `key='worklog.day.review'`（或 `worklog.report.review`）的定义后，
  submit 改走 `WorkflowInstanceService.start(definitionKey, businessType, businessId, businessKey=submissionId, variables)`。
- 审批节点完成时，`WorkflowTaskService` 调用 boot-api 注册的 `WorklogApprovalHandler`
  （`businessType()` 返回 `worklog.time` / `worklog.report`），在**同一租户事务**内调用
  `WorklogSubmissionService.review` / `WorklogReportService.review`；业务失败则任务保持待办。
- 两条路径共用 `workflow_task`：直连任务 `instance_id` 为空，定义驱动任务挂在实例上；
  审批箱与工时待审列表看到的是同一批待办，任一端完成都会关闭另一端。

### 7.3 失败与恢复

- 提交已落库但建任务失败：重复提交返回同一快照，编排幂等补建任务；`GET /worklog/reviews`
  始终可用，任务缺失不影响审核能力。
- 审批箱完成但业务回调失败：任务保持 `claimed`，异常队列记录 `BUSINESS_ACTION_FAILED`，可重试。

---

## 八、API、权限、事件与契约

### 8.1 端点（OpenAPI 1.9.0 追加，均为向后兼容新增）

| 方法/路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/workflow/definitions` | `workflow.definition.manage` | 列表（含 draft/retired） |
| `POST /api/v1/workflow/definitions` | `workflow.definition.manage` | 建草稿 |
| `PUT /api/v1/workflow/definitions/{id}` | `workflow.definition.manage` | 仅草稿可改 |
| `POST /api/v1/workflow/definitions/{id}/publish` | `workflow.definition.manage` | 校验并发布 |
| `POST /api/v1/workflow/definitions/{id}/retire` | `workflow.definition.manage` | 退役 |
| `POST /api/v1/workflow/instances` | `workflow.instance.start` | 启动，`businessKey` 幂等 |
| `GET /api/v1/workflow/instances/{id}` | 参与者或 `instance.admin` | 实例 + 令牌 + 任务 + 时间线 |
| `POST /api/v1/workflow/instances/{id}/withdraw` | 发起人 | 撤回 |
| `POST /api/v1/workflow/instances/{id}/cancel` | `workflow.instance.admin` | 取消 + 补偿 |
| `GET /api/v1/workflow/tasks?scope=mine\|all` | `workflow.task.act` | 审批箱 |
| `POST /api/v1/workflow/tasks/{id}/claim` | `workflow.task.act` | 认领 |
| `POST /api/v1/workflow/tasks/{id}/complete` | `workflow.task.act` | `approve` / `return` |
| `POST /api/v1/workflow/tasks/{id}/transfer` | `workflow.task.act` | 转交 |
| `GET /api/v1/workflow/exceptions` | `workflow.instance.admin` | 异常队列 |
| `POST /api/v1/workflow/exceptions/{id}/resolve` | `workflow.instance.admin` | `retry/reassign/compensate/close` |

### 8.2 事件与通知

- 新增 `workflow.task.created.v1`（`allOf` 引用 `envelope.v1`），载荷：
  `task_id`、`instance_id`、`business_type`、`business_id`、`title`、`assignee_person_id`、`due_at`。
- 登记 `contracts/events/event-catalog.json`（producer `workflow`，consumer `boot-worker`）。
- `boot-worker` 新增 `WorkflowTaskCreatedHandler`，调用协作模块新增的
  `NotificationService.notifyPerson(...)` 写站内通知；`boot-worker/pom.xml` 增补 `collaboration` 依赖。
- 事件与任务行同一事务写入 `platform_outbox`；消费幂等依靠 `task_id` 去重（通知表按资源去重）。

### 8.3 契约操作

- `contracts/openapi/platform-api.v1.json`：追加上述路径与 schema；`info.version` 收口时统一升 1.9.0。
- 新增事件 schema 与目录条目；`node contracts/tools/contract-check.mjs` 必须通过（新增按兼容处理）。

---

## 九、前端

新增两个路由（[router.ts](file:///D:/Projects/temp_20261109_01/frontend/apps/web/src/router.ts)）：

```text
#/workflow/inbox                 我的审批       permission: workflow.task.act
#/workflow/instances/:instanceId 审批实例详情   permission: workflow.task.act
```

- `WorkflowInboxView.tsx`：待办/已认领/已完成三档；行内「认领 / 通过 / 退回 / 转交」；
  退回必须填写意见；空、错误、无权、部分失败状态复用 `AsyncState`。
- `WorkflowInstanceView.tsx`：实例状态、变量、任务列表、只追加时间线；发起人可撤回，
  管理员可见取消与异常入口。
- `api-client` 新增 `WorkflowTask`、`WorkflowInstance`、`WorkflowDefinition`、`WorkflowException`
  类型与 12 个方法；`App.tsx` 注册视图；`router.test.ts` / `App.test.tsx` 补路由与 fakeClient。
- 导航按 `capabilities.permissions` 隐藏；直接访问仍由后端鉴权。

---

## 十、测试与证据

| 测试 | 覆盖 |
|---|---|
| `WorkflowSchemaValidatorTest`（纯单测） | 未知节点、重复 key、悬空边、缺审批人、超限、条件结构 |
| `WorkflowConditionEvaluatorTest`（纯单测） | all/any/not、类型不匹配、缺失变量、default 边 |
| `WorkflowEngineTest`（纯单测，假 store） | 任何/会签、并行汇合只推进一次、令牌跳数上限、未知处理器 |
| `WorkflowDbTest`（真实 PostgreSQL） | 发布后 schema 不可改；旧版本运行中实例不受新版本影响；`business_key` 重复返回同一实例；重复回调不重复写业务；无审批人 → 异常且不通过；SLA 超时不自动批准；转交/退回/撤回/补偿留痕；补偿失败进异常；跨租户不可见 |
| `WorkflowAuthzTest`（WebMvc） | 员工建定义 403；非处理人完成任务 403；审批箱只返回本人任务；`instance.admin` 才能取消/处置异常；未绑定员工 403 |
| `tests/db/98-workflow.sql` | 8 张表 RLS、唯一 published、日志只追加、发布不可变、跨租户隔离、约束负例 |
| 静态不变量（`tests/security/isolation-invariants.test.mjs`） | `workflow_transition_log` 只追加触发器存在；workflow 模块不直接读 `worklog_*`/`collab_*` |
| 前端 | `WorkflowInboxView.test.tsx`：通过/退回/转交调用、403 与空态 |

同步更新：

- `tests/db/40-integrity.sql`：88 → **96** 张租户表。
- `tests/db/92-cross-tenant.sql`：89 → **97**。
- `scripts/db-verify.ps1`：两处 `-eq 16` → `-eq 17`；新增 5m 段执行 `98-workflow.sql` 并断言
  「WP03-06 工作流数据库断言通过」。
- 证据目录 `docs/evidence/wp03-06/`：测试报告 + db-verify 日志 + 审批箱截图。

---

## 十一、实施顺序

1. **迁移与骨架**：V17 迁移、workflow `pom.xml`/`package-info`、`WorkflowStore`、定义服务与校验器。
2. **引擎核心**：实例/令牌/任务/时间线、条件、并行、`AssigneeResolver`、组织与授权只读查询扩展。
3. **异步能力**：定时器、服务任务、SLA 扫描、异常队列、补偿；boot-worker 三个 Worker。
4. **API 与契约**：`WorkflowController`、权限与角色映射、事件、通知、OpenAPI/事件目录。
5. **工时/周报接入**：直连任务镜像、`markDecided`、可选定义驱动、`WorklogApprovalHandler`。
6. **前端**：审批箱、实例详情、api-client、路由与导航。
7. **验证与留证**：单测/DB/HTTP、前端 typecheck/test、db-verify、契约、覆盖率、证据目录。

依赖关系：1 → 2 → 3/4 可并行 → 5 依赖 2/4 → 6 依赖 4 → 7 最后。

---

## 十二、验证命令

```powershell
mvn -B -ntp -f backend/pom.xml clean install
pnpm -C frontend/apps/web typecheck
pnpm -C frontend/apps/web test
pnpm -C frontend/apps/web build
node --test "tests/**/*.test.mjs"
node contracts/tools/contract-check.mjs
node backend/tools/coverage-gate.mjs
scripts/db-verify.ps1 -OwnerPassword dev_only_change_me -AppPassword dev_only_change_me_app
```

---

## 十三、风险与待确认

1. **范围最大**：本包 8 张表 + 引擎 + Worker + 前端，建议按「引擎 / 异步 / 接入」三次提交，但仍在同一 WP 内交付。
2. **部门负责人无主数据**：按「发起人实线主管」实现；若需要真正的部门负责人字段，需另开组织主数据变更（Phase 4）。
3. **退回语义**：本包采用「退回即终态，重新提交生成新实例」，不做节点回环；如需回环，需扩展 `returnTo`。
4. **默认直连任务镜像**：每次工时/周报提交都会在审批箱生成一条审核任务；若希望审批箱只显示定义驱动任务，可改为仅在存在 published 定义时建任务。
5. **SLA 墙钟**：不接组织日历与节假日；Phase 4 再评估。
6. **模块边界**：workflow 需新增 `platform`/`organization` 依赖；工时回调由 boot-api 的 `WorkflowBusinessHandler` 实现，避免 `workflow → worklog` 双向依赖。
