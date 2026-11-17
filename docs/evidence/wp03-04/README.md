# WP03-04 管理洞察证据

本目录记录 Phase 3 管理洞察最小版：租户级阈值、固定维度统计、k-匿名抑制、
二次鉴权下钻与前端洞察页。

## 交付内容

- 后端 `department` 模块新增 `insight` 包：
  - `InsightStore`：只读已提交/已通过口径的人日、分钟数、任务状态、周报状态与按周趋势；
    任务按部门（`collab_task.org_unit_id`/`collab_project.org_unit_id`）或人员范围
    （`assignee_person_id`）统计。
  - `InsightService`：固定维度概览、阈值抑制、下钻、阈值读取与提高。
- HTTP：`/api/v1/insights/{departments,work,work/people,settings}`。
  - 部门范围走部门口径；`DIRECT_REPORTS/REPORTING_CHAIN` 的人员范围走人员口径。
  - 下钻逐人执行 `worklog.time.read`，并写 `insight_drilldown` 访问留痕。
- 前端：`#/insights` 管理洞察页，权限 `insight.work.read`；包含范围/日期选择、
  统计卡片、周趋势、人员明细与阈值设置。
- 测试：`InsightAuthzTest`（6 用例）、`InsightSuppressionTest`（真实 PostgreSQL）。

## 关键不变式

1. `worklog_setting.min_group_size` 默认 5、范围 5–100，只能提高；降低返回
   `INSIGHT_THRESHOLD_DECREASE`。
2. 任一统计口径下成员数或贡献人数低于阈值时整格抑制：不返回数量、均值、排名，
   周维度按周单独抑制。
3. 草稿、退回中与未提交的工时永不参与统计：只读 `worklog_day.state IN
   ('submitted','approved')` 与未删除的 `worklog_entry`。
4. 下钻返回前逐人二次鉴权；二次过滤后可见人数低于阈值时整表抑制（空列表），
   不暴露「被过滤了多少行」。
5. 修改阈值需要 `tenant.admin`，读取与统计需要 `insight.work.read`。

## 验证

| 命令 | 结果 |
|---|---|
| `mvn -f backend/pom.xml install` | BUILD SUCCESS（20 个模块） |
| `mvn -f backend/pom.xml -pl boot-api -am -Dtest=InsightAuthzTest test` | 6 个用例通过（日志见 `insight-authz-test.txt`） |
| `mvn -f backend/pom.xml -pl boot-api -am -Dtest=InsightSuppressionTest -Dplatform.db.url=... test` | 1 个套件通过（真实 PostgreSQL，日志见 `insight-suppression-test.txt`） |
| `mvn -f backend/pom.xml -pl boot-api -am -Dtest=ModuleStructureTest test` | 2 个用例通过，`department.insight` 以命名接口暴露 |
| `pnpm -C frontend/apps/web typecheck` | 通过 |
| `pnpm -C frontend/apps/web test` | 16 个用例通过 |
| `node --test "tests/**/*.test.mjs"` | 42 个用例，41 通过、1 个既有跳过 |
| `node contracts/tools/contract-check.mjs` | 7 份契约通过 |
| `node backend/tools/coverage-gate.mjs` | PASS（非回归门槛） |

`InsightSuppressionTest` 覆盖：4 人小组抑制、6 人但仅 3 人提交的差分查询抑制、
5 人提交 + 1 人草稿（提交人日恰为 5、分钟数不含草稿）、按周小群体抑制、
下钻阈值、阈值只能提高。

数据库测试使用 Docker Desktop 容器 `platform-pg-dev`（宿主端口 55432）：
重建 `platform` 库后按 V1→V16 顺序应用迁移，并以 `platform_app`（NOBYPASSRLS）
连接执行，确保 RLS 与 `worklog_setting` 真实生效。

## 未包含（下一批）

- WP03-06 通用工作流（条件、并行、定时器、SLA、补偿）。
- WP03-07 模块目录与建议状态机。
- 洞察导出（`insight.work.export`）与 OpenAPI/事件目录收口。
