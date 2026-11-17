# WP03-06 通用工作流与审批证据

本目录记录 Phase 3 通用工作流最小完整版：定义版本化与校验、令牌推进引擎、
审批箱、SLA 与异常、服务任务补偿、工时/周报接入与前端页面。

## 交付内容

- 迁移 `V17__workflow_approval.sql`：8 张表（definition、instance、token、task、
  transition_log、timer、service_task、exception），全部 ENABLE + FORCE RLS，
  每表恰好一条租户策略；一 key 一 published、发布后不可改、状态日志只追加
  均由数据库触发器/索引兜底。
- `workflow` 模块：
  - `WorkflowSchemaValidator`：固定节点枚举 + 结构化条件，校验未知类型、重复 key、
    悬空边、不可达、审批人缺失、timer 延迟、service handler、condition/when 互斥、
    64 节点/256 边上限。
  - `WorkflowConditionEvaluator`：`all/any/not` + `eq/ne/gt/gte/lt/lte/in/contains`；
    缺失变量与类型不匹配一律不命中。
  - `WorkflowEngine`：start/approval/exclusive/parallel_split/parallel_join/timer/
    service/end；并行汇合等到齐；跳数上限 200，超限写异常并暂停。
  - `WorkflowDefinitionService` / `WorkflowInstanceService` / `WorkflowTaskService`：
    版本钉住、business_key 幂等、撤回/取消、认领/完成/转交/退回、直连任务去重。
  - `WorkflowTimerService` / `WorkflowServiceTaskService` / `WorkflowSlaService` /
    `WorkflowExceptionService`：定时器、指数退避、SLA 预警/超时升级、异常处置与补偿。
- `boot-worker`：TimerWorker(5s)、ServiceTaskWorker(5s)、SlaWorker(1m)；
  消费 `workflow.task.created.v1` 写站内通知（按资源去重）。
- `boot-api`：`WorkflowController` 15 个端点；定义管理仅 API，无可视化编排器。
- 工时/周报：提交后默认生成直连审核任务（直属主管）；审核后 `markDecided` 只关任务；
  租户发布 `worklog.day.review` / `worklog.report.review` 后改走实例，
  由 `WorkflowBusinessHandler` 在同一租户事务内回调业务。
- 前端：`#/workflow` 审批箱、`#/workflow/:instanceId` 实例详情；
  退回必须填写原因；api-client 增加 12 个方法与完整类型。

## 关键不变式

1. 发布后的定义不可修改 schema/version/key；同一 key 最多一个 published。
2. 实例钉住 definition_id + version，发布新版本不影响运行中实例。
3. 审批人解析为空时写 `ASSIGNEE_UNRESOLVED` 并生成未分配任务，**绝不自动通过**。
4. SLA 超时只标记 `breached`、写 `SLA_BREACHED` 并升级给主管，**永不调用 approve**。
5. 业务回调失败时任务保持待办并写 `BUSINESS_ACTION_FAILED`；缺失处理器写
   `HANDLER_MISSING` 并拒绝完成。
6. 重复回调由 `status IN ('pending','claimed')` 条件更新兜底，不重复写业务。
7. `workflow_transition_log` 只允许追加；UPDATE/DELETE 触发数据库异常。

## 验证

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml clean install` | BUILD SUCCESS（20 个模块） |
| `WorkflowSchemaValidatorTest` | 8 个用例通过 |
| `WorkflowConditionEvaluatorTest` | 4 个用例通过 |
| `WorkflowEngineTest` | 4 个用例通过（无自动通过、无审批人异常、正常收敛、暂停实例不推进） |
| `WorkflowAuthzTest` | 4 个用例通过（定义管理 403、审批箱只本人、非处理人 403、未绑定 403） |
| `WorkflowDbTest`（真实 PostgreSQL） | 1 个套件通过（发布不可变、一 key 一 published、版本钉住、幂等、日志只追加、SLA 不自动批、跨租户） |
| `tests/db/98-workflow.sql` | `WP03-06 工作流数据库断言通过` |
| `pnpm -C frontend/apps/web typecheck` | 通过 |
| `pnpm -C frontend/apps/web test` | 18 个用例通过（4 个文件） |
| `pnpm -C frontend/apps/web build` | 通过 |
| `node --test "tests/**/*.test.mjs"` | 43 个用例，42 通过、1 个既有跳过 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过（含 `workflow.task.created.v1`） |
| `node backend/tools/coverage-gate.mjs` | PASS（指令覆盖率 14.98%，非回归门槛） |
| `scripts/db-verify.ps1` | 全部通过：17 个迁移、RLS、跨租户、`98-workflow.sql`、`WorkflowDbTest` |

数据库验证使用 Docker Desktop 容器 `platform-pg-dev`（宿主端口 55432），
空库与上一版本升级均按 V1→V17 顺序应用，以 `platform_app`（NOBYPASSRLS）执行。
