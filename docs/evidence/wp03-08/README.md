# WP03-08 G3 验收证据（Phase 3 收尾）

本目录记录 Phase 3 收尾：WP03-07「模块目录与建议最小版」、伙伴云风格全站骨架，
以及 G3「手工工时与周报两周运行」的验收状态。

## 结论

- WP03-06 与 WP03-07 的工程门禁全部通过。
- 界面已统一为浅色侧栏 + 蓝色顶栏 + 浅灰底 + 白色圆角卡片的骨架。
- G3 的交互指标（≤2 次点击到本人工作页）、合成部门关系数据与模块建议全流程已补齐；
  **真实连续两周运行数据尚未产生**，本目录提供演示环境与统计口径。

## WP03-07 交付内容

- 迁移 `V18__app_catalog.sql`：`app_module`、`app_module_suggestion`、
  `app_module_suggestion_event` 三张表，全部 ENABLE + FORCE RLS，每表一条租户策略；
  事件表由触发器保证只追加，同时充当审计与站内通知。
- `appcatalog` 模块：
  - 目录：编码/名称/分类/说明/模板/负责人/版本/依赖/适用范围/健康/安装占位；
    支持关键词、分类、健康过滤。
  - 建议：草稿、沟通、待受理、配置、测试、整改、测试失败、待最终批准、驳回、撤回、
    启用、特殊启用待复核、暂停、关闭、弃用共 15 个状态。
  - 受理必须指定配置负责人；驳回/要求补充/测试不通过必须填写意见；
    提交与撤回仅发起人；受理与最终启用必须由租户级复核人执行，
    没有复核人时返回 403 `APP_SUGGESTION_REVIEWER_UNAVAILABLE`，不自动通过。
  - 非法流转返回 409 `APP_SUGGESTION_TRANSITION_INVALID`。
- `boot-api`：`AppCatalogController` 8 个端点（目录 4 个、建议 4 个），
  权限码沿用 `appcatalog.module.read / module.manage / suggestion.create / suggestion.review`。
- 前端：`#/modules` 模块中心（模块目录 + 模块建议，含发起、详情时间线与流转）；
  全站 `AppShell`（浅色侧栏、蓝色顶栏、面包屑、卡片化内容）。
- 契约：`contracts/openapi/platform-api.v1.json` 追加 8 个操作；
  新增端点属于兼容变更，已按仓库流程刷新 `contracts/baseline/contracts-baseline.json`。

## 门禁结果

| 命令 | 结果 | 证据 |
|---|---|---|
| `mvn -B -pl modules/appcatalog test` | BUILD SUCCESS，6 个用例通过 | `backend-appcatalog-tests.txt` |
| `mvn -B -pl boot-api -am test -Dtest=AppCatalogAuthzTest` | BUILD SUCCESS，3 个用例通过 | `backend-authz-tests.txt` |
| `pnpm --filter @platform/api-client test` | 1 个测试文件通过 | `frontend-tests.txt` |
| `pnpm --filter @platform/web test` | 5 个测试文件、20 个用例通过 | `frontend-tests.txt` |
| `pnpm --filter @platform/web typecheck` | 通过（exit 0） | `frontend-tests.txt` |
| `mvn -B -pl modules/authorization test` | BUILD SUCCESS，10 个用例通过、2 个既有跳过 | 终端输出 |
| `mvn -B -pl modules/department test` | BUILD SUCCESS（部门成员查询含兼岗行） | 终端输出 |
| `mvn -B -pl boot-api test -Dtest=ModuleStructureTest` | 2 个用例通过（模块边界含 appcatalog） | 终端输出 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，3 类覆盖完整 | `contract-check.txt` |
| Flyway 启动日志 | `Successfully applied 1 migration ... now at version v18` | API 日志（演示环境） |

## 演示与手工验证

演示账号：
- 主管：`demo.manager@example.test`
- 老板/复核人：`demo.owner@example.test`（与主管同口令，仅演示环境）
- 租户：`aaaaaaaa-0000-0000-0000-000000000001`

| 操作 | 结果 |
|---|---|
| 登录 | 200，下发 HttpOnly 会话 Cookie 与 CSRF Cookie |
| `GET /api/v1/app-catalog/modules` | 200，返回 3 个演示模块 |
| `POST /api/v1/app-catalog/suggestions` | 200，状态 `draft` |
| `POST /suggestions/{id}/actions {"action":"submit"}` | 200，状态 `communicating` |
| `POST /suggestions/{id}/actions {"action":"accept"}`（部门主管） | 403 `APP_SUGGESTION_REVIEWER_UNAVAILABLE`，符合「无老板不自动受理」 |
| `submit-review`（主管）→ `accept` / `start-test` / `test-pass` / `approve`（老板） | 依次 `submitted → configuring → testing → pending_final_approval → enabled`，详情返回 8 条事件 |
| 浏览器 `#/my-work` | 登录后 1 次点击到达本人工作页（侧栏「我的工作」） |
| 浏览器 `#/departments/{产品研发部}` | 5 名成员（刘工兼岗显示为第 6 行），汇报层级 赵总 → 张主管 → 李工 → 陈专员，王工/刘工同级 |
| 浏览器 `#/modules` | 侧栏、顶栏、面包屑、目录表格正常渲染 |

截图（同目录，副本在 `c:\新建文件夹\temp_20261109_01\demo-shots\`）：

- `demo-login.png`：登录页
- `demo-after-login.png` / `demo-home.png`：登录后总览与全站骨架
- `demo-my-work.png`：侧栏「我的工作」直达本人工作页
- `demo-departments.png`：部门列表
- `demo-departments-11111111-0000-4000-8000-000000000003.png`：产品研发部工作区（5 人、汇报层级）
- `demo-workflow.png`：审批箱
- `demo-workflow-*.png`：审批实例详情与认领/通过后的状态（演示数据）
- `demo-modules.png`：模块中心（模块目录表格）
- `demo-modules-suggestions.png`：模块中心（模块建议列表，示例建议已启用）

## G3 合成部门与关系数据

数据脚本：`scripts/demo-g3-seed.sql`（可重复执行，仅作用于演示租户）。

| 关系 | 演示数据 |
|---|---|
| 老板 | 赵总（`demo.owner@example.test`，`organization_owner_role` + `business-owner` 授权） |
| 主管 | 张主管（产品研发部） |
| 三级下属 | 赵总 → 张主管 → 李工 → 陈专员 |
| 同级 | 王工、刘工与李工同级，均汇报张主管 |
| 兼岗 | 刘工主岗研发工程师，兼岗市场部市场专员（`secondary`） |
| 调动 | 刘工由市场部岗位调整至产品研发部岗位，`organization_change` 已生效 |
| 项目成员 | 「客户成功试点」：张主管（owner）、李工、刘工 |

## G3 指标状态

| 指标 | 当前状态 | 说明 |
|---|---|---|
| 到达本人工作页点击数 | **1 次**（登录后点侧栏「我的工作」） | 达标（目标 ≤2） |
| 工时提交率 | 样本不足 | 演示数据只有 1 条建议与既有审批待办，不能代表两周运行 |
| 周报退回率 | 样本不足 | 无真实退回记录 |
| 主管处理时效 | 样本不足 | 需连续两周记录审批箱处理时间 |

结论：交互指标与结构数据已补齐；G3 的提交率、退回率与处理时效必须以真实两周数据重算后，
再决定 Phase 4–11 的投入，本次不据此放宽进入条件。

## 统计口径（供后续两周记录）

- 工时提交率 = 已提交工时天数 / 应提交工作日；按部门与个人维度分别统计。
- 周报退回率 = 被退回周报数 / 已提交周报数。
- 主管处理时效 = 任务完成时间 − 任务创建时间，取中位数与 P90。
- 到达本人工作页点击数 = 登录成功后到「本人工作页」可见的最少点击次数。
