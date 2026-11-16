# Phase 3 部门工作区、手工工时与模块建议实施方案

## Context

`IMPLEMENTATION_PLAN.md` §8 要求 Phase 3 交付「老板和员工能直接使用的第一批完整业务」：部门空间、个人工作页、手工工时与周报闭环、协作最小原语、权限感知洞察、通用工作流、模块目录与建议，最后以 G3 用户验收收口。

当前仓库的实际情况（只读调研结论）：

| 能力 | 现状 | 差距 |
|---|---|---|
| 部门空间 | `backend/modules/department` 只有 `package-info.java` | 无空间表、无首屏投影、无成员/职位/汇报层级聚合 |
| 个人工作页 | 无 `business.work` 对应的业务读取端点 | 无聚合、无关系权限落地、无访问留痕 |
| 工时/周报 | `worklog` 只有设备登记、策略、片段与升级清单；`work_assistant_segment` 绑定 `device_id` | 无工时条目、无日快照、无周报、无退回版本 |
| 协作 | `collaboration` 只有 `package-info.java` | 频道、帖子、文件、任务、会议、知识页全缺 |
| 洞察 | 无 | 无提交率/逾期/投入统计，无 k-匿名阈值 |
| 工作流 | `workflow` 只有 `package-info.java` | 无定义版本、实例、任务、审批、定时器与补偿 |
| 模块目录 | `appcatalog` 只有 `package-info.java` | 无目录、无建议状态机 |
| 授权 | 决策链完整（[AuthorizationService.java](file:///D:/projects/temp_20261109_01/backend/modules/authorization/src/main/java/com/acme/platform/authorization/AuthorizationService.java)），权限目录 40 条 | 缺工时、协作、工作流、目录、洞察权限码与字段策略 |
| 回收站 | 通用机制已就绪（[RetentionService.java](file:///D:/projects/temp_20261109_01/backend/modules/tenancy/src/main/java/com/acme/platform/tenancy/retention/RetentionService.java)），`data_class` 只允许 `file` / `work_assistant_segment` | 工时草稿无法进回收站，需扩约束并新增 `RetentionTarget` |
| 前端 | 只有登录、总览、作业、文件四个视图（[router.ts](file:///D:/projects/temp_20261109_01/frontend/apps/web/src/router.ts)） | Phase 3 全部页面需新建 |
| 契约 | OpenAPI 1.8.0，163 路径 | Phase 3 新增约 50 路径，结束冻结 1.9.0 |

**用户已确认的决策：**

1. WP03-03 与 WP03-06 **严格按计划全量实现**（频道/文件/任务/会议/知识页；条件、并行、定时器、SLA、补偿等完整工作流能力）。
2. 工时与周报**新建 `worklog_*` 表**；`work_assistant_device` / `work_assistant_segment` 在 Phase 3 保持不动，WP04-01 再改造为语音载体，避免被设备外键阻塞。
3. 新需求边界：系统**不监控员工电脑**；Phase 3 只做手工文本工时，语音/ASR、AI 周报、单据预审均在 Phase 4。

**验收口径：** G3 退出标准——部门、个人工作页、手工工时、周报、协作和模块建议可演示；关系权限负例通过；点击路径 ≤2 次有录屏/截图；用户批准进入 AI 员工助理阶段。

---

## 一、总体设计与硬边界

### 1.1 阶段切片（按依赖排序，逐包交付、逐包留证）

```text
WP03-01 部门空间与成员投影 ─┐
WP03-02 个人工作页与关系权限 ─┼─→ WP03-04 洞察（依赖 01/02/05 的数据）
WP03-05 手工工时与周报闭环 ──┘
WP03-03 协作最小原语（频道/文件/任务/会议/知识页）
WP03-06 通用工作流与审批（为工时/周报提供退回与转交，同时作为通用能力）
WP03-07 模块目录与建议状态机
WP03-08 G3 用户验收与重新估算
```

- 01/02/05 是 G3 主链路，必须先完成；03/06/07 可与 04 并行，但 08 必须最后。
- 每个包独立提交、独立 PR、独立证据目录 `docs/evidence/wp03-0x/`。

### 1.2 关系权限模型（全部复用现有授权决策，不另造判断）

| 主体 | 可见工作域 | 实现方式 |
|---|---|---|
| 员工 | 仅本人 | `ScopeType.SELF` + `business.work.read` |
| 主管 | 本人 + 当前生效直接下属 | `ScopeType.DIRECT_REPORTS`，`OrganizationScopeQueries.scopeOfPerson` |
| 主管（汇报链） | 本人 + 当前生效汇报链 | `ScopeType.REPORTING_CHAIN` |
| 限时代管 | 授权部门子树，按 `organization_delegation` 到期 | `ScopeType.DELEGATED` |
| 老板 | 全租户**已提交**工作域 | `business-owner` 派生角色 + `ScopeType.TENANT` |
| 项目成员 | 仅项目范围投影，**不能进入他人完整工作页** | Phase 3 保持 `ScopeType.PROJECT_SPACE` 为 unsupported（显式拒绝）；项目投影走协作模块自己的成员校验端点 |

硬边界（每条都要有失败用例）：

1. 未确认草稿、语音原文、私人频道、薪酬/银行/员工关系**永不进入**老板工作域；老板令牌请求草稿正文返回 403。
2. 提交人只能是本人：服务端从 `ActorContext`/`identity_tenant_member.person_id` 取人，忽略请求体中的任何 `personId`；主管、管理员、AI、服务账号、批量作业代交全部返回 403 并写安全事件。
3. 提交后快照不可改、不可删；退回只能创建**新草稿版本**，旧快照保留。
4. 无权单元格不返回数量、标题、摘要、文件名或可猜测链接——字段级策略直接**省略字段**，不返回 `null` 占位。
5. 洞察最小群组阈值 5，租户只能提高；低于阈值整格抑制，差分查询不能反推个人。
6. 搜索、通知、洞察、导出、下钻全部复用同一授权结论。

### 1.3 与 Phase 4 的边界

- 不接麦克风、不做 ASR、不生成 AI 周报、不做单据 OCR/预审。
- 不改 `desktop-agent/`、不删设备表；Phase 3 工时 `source_type` 只写 `manual`，枚举预留 `voice` 但服务端拒绝写入。
- 周报在 Phase 3 是**确定性聚合**（按项目/任务汇总已提交工时），不是模型生成。

---

## 二、数据模型（V13–V17）

所有含 `tenant_id` 的表一律 `ENABLE + FORCE RLS`，策略照抄 `backend/db/templates/tenant-table.template.sql`；不允许修改 V1–V12 历史文件。

### V13 `department_workspace.sql`

| 表 | 关键列 | 说明 |
|---|---|---|
| `department_space` | `id`、`tenant_id`、`org_unit_id`（唯一）、`name`、`status(active/archived)`、`created_at`、`archived_at` | 每个有效部门一个空间 |
| `department_space_setting` | `tenant_id`、`org_unit_id`、`day_end_time`、`week_report_due_weekday`、`updated_by` | 部门级提醒时间 |

- 迁移内**回填**所有有效 `organization_unit`；新增 `AFTER INSERT/UPDATE` 触发器：插入即建空间，部门归档即空间归档。
- 成员、职位、汇报层级**不落冗余表**，直接按生效日期从 `organization_employment_assignment`、`organization_position`、`organization_reporting_line` 投影，避免调动后双写漂移；调动历史由 `organization_change_history` 提供。

### V14 `worklog_time_and_reports.sql`

| 表 | 关键列 | 约束/说明 |
|---|---|---|
| `worklog_entry` | `id`、`tenant_id`、`person_id`、`employment_id`、`work_date`、`starts_at`、`ends_at`、`duration_minutes`、`project_id`、`task_id`、`description`、`source_type(manual/voice)`、`client_entry_id`、`draft_version`、`deleted_at`、`created_at/updated_at` | `UNIQUE(tenant_id, person_id, client_entry_id)` 保证离线补录幂等；`duration_minutes BETWEEN 1 AND 1440`；重叠/跨日由服务层检出并返回具体冲突条目，不静默纠正 |
| `worklog_day` | `tenant_id`、`person_id`、`work_date`、`state(draft/submitted/approved/returned)`、`draft_version`、`current_submission_id`、`reminder_sent_at` | `UNIQUE(tenant_id, person_id, work_date)` |
| `worklog_submission` | `id`、`tenant_id`、`person_id`、`work_date`、`version`、`status(submitted/approved/returned)`、`snapshot jsonb`、`snapshot_hash`、`total_minutes`、`submitted_at/by`、`reviewed_at/by`、`review_comment` | `UNIQUE(tenant_id, person_id, work_date, version)`；触发器禁止修改快照/哈希/总时长与删除 |
| `worklog_weekly_report` | `id`、`tenant_id`、`person_id`、`week_start`、`week_end`、`version`、`status(draft/submitted)`、`content jsonb`、`snapshot_hash`、`submitted_at` | 提交后不可变；同一人同一周版本单调 |
| `worklog_report_source` | `report_id`、`submission_id`、`item_key`、`minutes` | 周报每个数字可回溯到日快照 |
| `worklog_access_log` | `id`、`tenant_id`、`viewer_person_id`、`subject_person_id`、`resource_type`、`resource_id`、`action(view/preview/download/export/insight_drilldown)`、`decision_factors jsonb`、`occurred_at` | 只追加（触发器禁 UPDATE/DELETE），供员工自查「谁看过我的已提交数据」 |
| `worklog_setting` | `tenant_id` 主键、`min_group_size ≥ 5`、`max_daily_minutes`、`updated_by` | 洞察阈值只能提高 |

同时：

- `ALTER retention_bin_item` 的 `data_class` 约束新增 `worklog_entry`；
- `ALTER retention_legal_hold` 的 `data_class` 约束新增 `worklog_entry`、`worklog_weekly_report`；
- 新增 `WorklogEntryRetentionTarget`（回收/恢复/清理只改 `deleted_at` 或删草稿行），提交快照无删除端点、不进回收站。

### V15 `collaboration_workspace.sql`

| 表 | 说明 |
|---|---|
| `collab_channel` | `scope(all/members/cross_department)`、`org_unit_id`、`name`、`topic`、`archived_at`；`all` 频道只能由部门空间创建 |
| `collab_channel_member` | 成员、角色、`removed_at`；撤权立即写 `removed_at` 并 bump 授权缓存版本 |
| `collab_post` / `collab_comment` / `collab_mention` / `collab_receipt` | 主题帖、评论、@提及、确认回执、置顶、归档 |
| `collab_project` / `collab_project_member` | 项目空间；项目投影的唯一数据源 |
| `collab_file_link` | 频道/帖子引用 `platform_file.id`，只增引用**不扩 ACL**；`removed_at` 用于撤权失效 |
| `collab_file_version` / `collab_file_tag` | 版本链（`previous_file_id`）与标签 |
| `collab_task` | 看板状态 `todo/doing/done`、负责人、截止日、里程碑 |
| `collab_milestone` / `collab_meeting` / `collab_action_item` / `collab_decision` / `collab_page` | 里程碑、会议纪要、行动项、决定记录、知识页（`parent_id` 树） |
| `collab_notification` | 站内通知，写库前必须已确认收件人对目标有读权限；撤权时清理未读 |
| `collab_share_link` | 直接分享链接；成员移除时按 SLA 失效 |

### V16 `workflow_approval.sql`

| 表 | 说明 |
|---|---|
| `workflow_definition` | `key`、`version`、`status(draft/published/retired)`、`schema jsonb`；`UNIQUE(tenant, key, version)`，发布后 schema 不可改 |
| `workflow_instance` | 固定 `definition_version`、`business_key` 唯一、`status`、`variables jsonb` |
| `workflow_token` / `workflow_task` | 节点令牌与人工任务；任务含 `assignee_person_id`、`assignee_role`、`due_at`、`sla_state`、`status(pending/claimed/completed/transferred/returned)` |
| `workflow_transition_log` | 只追加，记录 from/to、actor、意见 |
| `workflow_timer` | 定时器触发时间与状态，供 Worker 扫描 |
| `workflow_service_task` | `idempotency_key` 唯一、尝试次数、补偿状态、人工入口 |
| `workflow_exception` | 无审批人、供应商失败等异常队列，**永不自动通过** |

### V17 `appcatalog_suggestions.sql`

| 表 | 说明 |
|---|---|
| `catalog_module` | 应用/模块目录：`key`、`name`、`version`、`owner_person_id`、`dependencies jsonb`、`applicable_scope jsonb`、`health`、`install_status(placeholder)` |
| `catalog_suggestion` | 业务问题、目标、范围、数据需求、权限、成本、收益、负责人建议、当前状态 |
| `catalog_suggestion_transition` | 只追加状态迁移与沟通意见，非法迁移由服务层 + CHECK 双重拒绝 |

状态集合固定为：`draft / communicating / pending_acceptance / configuring / testing / rectification / test_failed / pending_final_approval / rejected / withdrawn / enabled / special_enabled_pending_review / paused / closed / deprecated`。Phase 3 只交付状态机与页面骨架，不交付低代码配置和生产特殊启用。

---

## 三、代码落点

### 3.1 模块与依赖修正

`ModuleStructureTest` 仍要求 15 个模块，**不新增模块**。需补 `package-info.java` 的 `allowedDependencies`（当前多个模块缺 `platform`，一写库就违反边界）：

| 模块 | 新增允许依赖 | 用途 |
|---|---|---|
| `department` | `platform` | `TenantTransactions`、请求上下文、Problem Details |
| `collaboration` | `platform`、`organization` | 租户事务、部门频道与人员解析 |
| `workflow` | `platform`、`organization` | 租户事务、审批人按岗位/部门/汇报链解析 |
| `appcatalog` | `platform`、`organization` | 租户事务、负责人校验 |
| `worklog` | 保持现状 + `platform :: retention` 已有 | 工时与回收站 |

**跨模块一律只走公开应用服务或 outbox 事件**：工时提醒、协作通知、工作流任务创建都通过事件解耦（`worklog` 不直接依赖 `collaboration`）。

### 3.2 `backend/modules/department`

- `DepartmentSpaceStore` / `DepartmentSpaceService`：空间查询与归档。
- `DepartmentWorkspaceService`：首屏投影（成员、职位、汇报层级、今日工时状态、周报状态、授权文件数、工作页入口），字段按 `FieldPolicyEvaluator` 过滤。
- `InsightService`：提交率、逾期、任务状态、部门投入；最小群组阈值校验与结果抑制。
- 不读 `worklog_*` 内部表：工时状态通过 `worklog` 的公开只读服务获取；若形成循环依赖，则改由 `boot-api` 编排两个模块（优先选编排，保持模块单向）。

### 3.3 `backend/modules/worklog`（新增 `time`、`report`、`access` 子包）

- `WorklogEntryService`：增删改、合并/拆分、重叠/跨日/超长校验、离线幂等。
- `WorklogDayService`：日汇总、提交、退回后新版本、主管审核；提交人强制取当前主体。
- `WeeklyReportService`：按周聚合已提交/已批准的日快照，生成确定性草稿，员工补问题与下周计划后本人提交。
- `WorklogAccessLogStore`：只追加；`GET /me/work-access-log`。
- `WorklogEntryRetentionTarget`：实现 `platform.retention.RetentionTarget`。
- `WorklogDaySummaryWorker`（boot-worker）：下班时间生成汇总草稿；次日未提交提醒（写 outbox 事件，由协作通知服务落站内通知）。

### 3.4 `backend/modules/collaboration`

- `ChannelService`、`PostService`、`FileLinkService`、`TaskService`、`MeetingService`、`PageService`、`NotificationService`、`SearchService`。
- `RevocationService`：成员移除后扫描 `collab_share_link`、`collab_file_link`、未读通知，按 `collaboration.revocation_sla_minutes`（默认 5 分钟）完成失效并写审计。
- 搜索在 Phase 3 采用**查询时权限过滤**（SQL 联查 `collab_channel_member` / `collab_project_member`），不引入外部索引；外部索引留到 Phase 5，方案中明确记录该取舍。

### 3.5 `backend/modules/workflow`

- `DefinitionService`：草稿、发布、退役；发布后不可变，运行中实例固定旧版本。
- `InstanceService`：启动、变量校验、`business_key` 幂等、撤回、取消。
- `TaskService`：认领、完成（通过/驳回）、转交、退回、补偿人工入口。
- `AssigneeResolver`：人员、岗位、部门负责人、汇报链、角色；解析为空写 `workflow_exception` 并等待人工，**定时器只允许升级/转人工，禁止默认批准**。
- `TimerWorker`、`ServiceTaskWorker`（boot-worker）：幂等键去重，失败进异常队列并可补偿。

### 3.6 `backend/modules/appcatalog`

- `ModuleCatalogService`：目录只读投影与安装占位。
- `SuggestionService`：状态机、非法迁移 409、沟通意见、受理/驳回/要求补充。
- 受理与启用前校验存在有效 `organization_owner_role`，为空返回 `422 OWNER_REQUIRED`。

### 3.7 `backend/boot-api`（新增控制器）

`DepartmentController`、`WorkPageController`、`WorklogController`、`WeeklyReportController`、`CollaborationController`、`NotificationController`、`SearchController`、`InsightController`、`WorkflowController`、`CatalogController`、`CapabilityController`（导航用 `GET /api/v1/me/capabilities`，只返回权限码，不含业务数据）。

### 3.8 `frontend/apps/web`

新增路由与视图（每个视图必须有加载、空、错误、无权、部分失败五种状态，复用 [AsyncState.tsx](file:///D:/projects/temp_20261109_01/frontend/apps/web/src/components/AsyncState.tsx)）：

```text
#/departments            部门列表
#/departments/:id        部门首屏（固定表格）
#/work/:personId         个人工作页（本人/主管/老板）
#/worklog                我的工时（当日条目、提交、历史）
#/worklog/reviews        待我审核
#/reports                我的周报
#/reports/reviews        待审周报
#/collaboration          频道与项目
#/collaboration/:id      频道详情（帖/评/文件/任务）
#/insights               管理洞察
#/workflow/inbox         我的审批
#/catalog                模块目录与建议
```

- 扩展 `api-client`（[client.ts](file:///D:/projects/temp_20261109_01/frontend/packages/api-client/src/client.ts)）新增对应方法；导航按 `capabilities` 隐藏，但直接访问仍由后端鉴权。
- 「部门首页 → 本人工作页」提供固定入口按钮，保证 ≤2 次点击。

### 3.9 权限与角色矩阵

新增权限码（同时写入 `PermissionCatalog` 与 V13 的 `INSERT INTO authorization_permission`，缺一不可）：

```text
department.workspace.read / department.workspace.manage
worklog.time.read / worklog.time.write / worklog.time.review / worklog.time.export
worklog.report.read / worklog.report.submit / worklog.report.review
insight.work.read / insight.work.export
collaboration.channel.read / collaboration.channel.manage / collaboration.post.write
collaboration.task.write / collaboration.page.manage
workflow.definition.manage / workflow.instance.start / workflow.task.act / workflow.instance.admin
appcatalog.module.read / appcatalog.suggestion.create / appcatalog.suggestion.review / appcatalog.module.manage
```

角色映射（`SystemRoleCatalog`）：

- `employee`：`department.workspace.read`、`worklog.time.write`、`worklog.report.submit`、`collaboration.channel.read`、`collaboration.post.write`、`collaboration.task.write`、`appcatalog.suggestion.create`。
- `manager`：在员工基础上加 `worklog.time.read`、`worklog.time.review`、`worklog.report.read`、`worklog.report.review`、`workflow.task.act`、`insight.work.read`。
- `business-owner`：`worklog.time.read`、`worklog.report.read`、`insight.work.read`、`insight.work.export`、`appcatalog.suggestion.review`。
- `tenant-admin` / `security-admin`：`workflow.definition.manage`、`appcatalog.module.manage`、`department.workspace.manage`（不授予业务正文读取）。
- `auditor`：`worklog.time.export` 仅用于合规导出，需叠加 MFA。

字段策略新增：姓名/职位对同部门可见；`todayTimeStatus`、`weekReportStatus`、`authorizedFileCount` 分别独立授权；任一不通过时字段整体省略。

---

## 四、API 与契约（OpenAPI 1.9.0）

| 域 | 代表端点 | 关键权限 |
|---|---|---|
| 部门 | `GET /api/v1/departments`、`GET /api/v1/departments/{orgUnitId}/workspace` | `department.workspace.read` |
| 工作页 | `GET /api/v1/me/work-page`、`GET /api/v1/people/{personId}/work-page` | `business.work.read` + 范围 |
| 访问留痕 | `GET /api/v1/me/work-access-log` | 本人 |
| 工时 | `POST/PATCH/DELETE /api/v1/worklog/entries`、`POST /api/v1/worklog/entries/merge`、`POST /api/v1/worklog/entries/{id}/split`、`GET /api/v1/worklog/days/{date}` | `worklog.time.write` |
| 日提交 | `POST /api/v1/worklog/days/{date}/submit`、`POST /api/v1/worklog/days/{date}/review`、`GET /api/v1/worklog/reviews` | `worklog.time.review` |
| 周报 | `POST /api/v1/worklog/reports/{weekStart}/generate`、`PATCH /api/v1/worklog/reports/{weekStart}`、`POST .../submit`、`POST .../review` | `worklog.report.*` |
| 洞察 | `GET /api/v1/insights/work`、`GET/PUT /api/v1/insights/settings` | `insight.work.read` |
| 协作 | `GET/POST /api/v1/collaboration/channels`、`.../posts`、`.../comments`、`.../receipts`、`.../files`、`.../tasks`、`.../meetings`、`.../pages` | `collaboration.*` |
| 搜索/通知 | `GET /api/v1/search`、`GET /api/v1/notifications` | `search.read` |
| 工作流 | `POST /api/v1/workflow/definitions`、`.../publish`、`POST /api/v1/workflow/instances`、`GET /api/v1/workflow/tasks`、`.../claim|complete|transfer|return`、`.../compensate`、`GET /api/v1/workflow/exceptions` | `workflow.*` |
| 目录 | `GET /api/v1/catalog/modules`、`GET/POST /api/v1/catalog/suggestions`、`POST .../transitions` | `appcatalog.*` |
| 能力 | `GET /api/v1/me/capabilities` | 已认证 |

契约操作：

- 新增域名事件：`worklog.day.submitted.v1`、`worklog.day.returned.v1`、`worklog.report.submitted.v1`、`worklog.day.reminder_due.v1`、`collaboration.member.removed.v1`、`workflow.task.created.v1`、`appcatalog.suggestion.decided.v1`，全部按 `envelope.v1` 用 `allOf` 引用并登记 `contracts/events/event-catalog.json`。
- 新路径属向后兼容新增，不需要破坏性变更审批文件；Phase 3 收口时统一 `node contracts/tools/contract-check.mjs --update` 重冻结基线，OpenAPI `info.version` 升到 1.9.0。
- 已冻结端点与字段**只增不改**；确需变更时按 `docs/api/contract-governance.md` 提交审批文件。

---

## 五、实施顺序（逐包）

### WP03-01 部门空间和成员职位表

1. V13 迁移：空间表、设置表、回填与触发器。
2. `department` 模块空间服务 + 首屏投影 + 字段策略。
3. `DepartmentController` + `CapabilityController`。
4. 前端部门列表与首屏表格。

**证据：** `DepartmentDbTest`（自动建空间、归档、调动后历史归属、跨租户隔离）；`DepartmentAuthzTest`（员工/主管/老板三套旅程，无权字段不出现在响应 JSON）；浏览器网络响应截图。

### WP03-02 个人工作页和关系权限

1. `WorkPageService` 聚合工时、周报、任务、目标、动态、文件、会议行动项、历史提交。
2. 关系权限：本人、直接下属、汇报链、限时代管、老板已提交域；项目空间显式拒绝。
3. `worklog_access_log` 与员工自查端点。
4. 前端个人工作页 + 访问记录面板。

**证据：** `WorkPageAuthzTest` 覆盖下级看上级、同级互看、旧主管、虚线经理、项目成员、旧链接越权全部失败；本人能核对访问历史；查看/预览/下载/导出/下钻各写一条留痕。

### WP03-03 部门协作最小原语（全量）

1. V15 迁移与 RLS。
2. 频道、成员、帖子、评论、@、置顶、回执、归档。
3. 文件版本/标签/引用；权限不因频道而扩大。
4. 通用任务、看板、里程碑、会议纪要、决定记录、行动项、知识页。
5. 撤权扫描 + 通知 + 查询时权限过滤搜索。
6. 前端频道/项目/任务/会议页面。

**证据：** `CollaborationDbTest`（成员移除后旧链接、搜索、通知、文件下载全部失败；敏感频道不出现在无权统计）；`CollaborationAuthzTest`；撤权 SLA 用例。

### WP03-04 管理洞察最小版

1. V14 的 `worklog_setting` 与阈值校验（≥5，只能提高）。
2. `InsightService` 固定维度统计与阈值抑制。
3. 前端洞察页 + 二次鉴权下钻。

**证据：** `InsightSuppressionTest`：小群体、筛选后小群体、差分查询均不能反推个人；草稿不进入排名/绩效分析。

### WP03-05 手工工时与周报闭环

1. V14 迁移、约束与不可变触发器。
2. 工时条目 CRUD、合并/拆分、重叠/跨日/超长校验。
3. 日汇总、本人提交、主管审核/评论/退回（退回生成新草稿版本）。
4. 周报确定性聚合、来源映射、本人提交、主管审核。
5. 下班草稿与次日提醒 Worker；回收站接入。
6. 前端工时/周报/审核页面。

**证据：** `WorklogDbTest`（重复提交幂等、离线重试不重复、退回新版本、快照不可改删）；`WorklogAuthzTest`（主管/管理员/AI/服务账号/批量作业代交成功数为 0）；周报数字与快照逐项一致。

### WP03-06 通用工作流和审批（全量）

1. V16 迁移与定义版本化。
2. 实例、人工任务、审批、条件、并行、定时器、服务任务、SLA、转交、退回、撤回、补偿。
3. 审批人解析（人员/岗位/部门负责人/汇报链/角色）与异常队列。
4. Worker：定时器与服务任务，幂等、重试、补偿。
5. 工时/周报审核接入工作流定义（保留直连审核作为默认最短路径，二者共用同一任务表）。
6. 前端审批箱与实例详情。

**证据：** `WorkflowDbTest`（老版本运行中实例不受新版本影响、重复回调不重复写业务、无审批人不自动通过、高风险超时不默认批准）；`WorkflowAuthzTest`；补偿与转交留痕。

### WP03-07 模块目录和建议最小版

1. V17 迁移与状态机。
2. 目录、建议、沟通意见、受理/驳回/补充、配置负责人指定。
3. 老板为空时阻断；所有决定通知 + 审计。
4. 前端目录与建议页。

**证据：** `AppCatalogDbTest`（非法跳状态被拒、老板为空阻断、状态迁移只追加）；`AppCatalogAuthzTest`。

### WP03-08 G3 用户验收和重新估算

1. 种子合成部门：老板、主管、三级下属、同级、兼岗、调动、项目成员。
2. 连续两周跑手工工时与周报。
3. 记录点击数（目标 ≤2）、提交率、退回率、主管处理时效、权限失败与用户问题；点击路径录屏/截图。
4. 输出 `docs/decisions/scope-change/phase3-reestimate.md`，重估 Phase 4–11。

**证据：** `docs/evidence/wp03-08/` + 浏览器录屏 + 重新估算签字。

---

## 六、验证清单

| 验证 | 命令 / 文件 |
|---|---|
| 全量构建 | `mvn -B -ntp -f backend/pom.xml clean install` |
| 真实库迁移与 RLS | `scripts/db-verify.ps1 -OwnerPassword dev_only_change_me -AppPassword dev_only_change_me_app`；新增 5j–5n 段 |
| 迁移版本断言 | 同步把 `db-verify.ps1` 两处 `-eq 12` 改为 `-eq 17` |
| Node 用例 | `node --test "tests/**/*.test.mjs"` |
| 契约 | `node contracts/tools/contract-check.mjs`，收口时 `--update` 重冻结 1.9.0 |
| 覆盖率 | `node backend/tools/coverage-gate.mjs` |
| 前端单测/构建 | `pnpm -C frontend/apps/web test`、`pnpm -C frontend/apps/web build` |
| 权限负例 | 各 `*AuthzTest` + `tests/db/9x-*.sql` |
| 点击路径 | `browser_use` 走「部门首页 → 我的工作」并截图/录屏 |

新增真实库脚本：`tests/db/95-department.sql`、`96-worklog.sql`、`97-collaboration.sql`、`98-workflow.sql`、`99-appcatalog.sql`。
新增静态不变量（`tests/security/isolation-invariants.test.mjs`）：提交快照无 UPDATE/DELETE；`worklog_access_log`、`catalog_suggestion_transition`、`workflow_transition_log` 只追加；工时代交路径不存在；洞察阈值下限 ≥5；协作搜索必须联查成员表。

---

## 七、风险与不做项

1. **范围风险最大**：WP03-03 与 WP03-06 全量实现会显著挤压 10–12 周基线，G3 可能延后；WP03-08 必须按真实速度重新估算，不得把 P50 当承诺。
2. **模块边界**：`department`/`collaboration`/`workflow`/`appcatalog` 当前 `allowedDependencies` 缺 `platform`，直接写库会被 `ModuleStructureTest` 拒绝；跨模块读数据一律走公开服务或 boot-api 编排。
3. **部门空间自动创建用触发器**：与 V9 缓存版本触发器同例；若后续组织模块改为事件驱动，触发器需同步替换，写入 ADR。
4. **工时快照增长**：每次提交存完整 JSON 快照，需按月分区/归档预案；Phase 3 只建索引与保留策略，不做分区。
5. **工作流复杂度**：全量条件/并行/定时器/补偿容易过度设计；实现以「固定枚举节点 + 显式状态机」为准，不引入 BPMN 引擎。
6. **k-匿名差分攻击**：仅靠阈值不足，必须限制维度与筛选项白名单并记录查询审计；不开放任意 group-by。
7. **搜索无外部索引**：查询时过滤满足 Phase 3 权限要求，但大数据量下性能有限；Phase 5 接索引时必须在索引侧重复权限过滤。
8. **不做项**：语音/ASR、AI 周报、单据 OCR 与预审、设备采集、`desktop-agent` 移除、低代码配置、生产特殊启用、跨租户外部分享、邮件/短信通知、HR/薪酬数据。
