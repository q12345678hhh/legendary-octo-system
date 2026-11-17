# WP03 核心包证据（部门工作区、个人工作页、手工工时与周报）

本目录记录 Phase 3 第一批可交付能力：WP03-01 / WP03-02 / WP03-05。
WP03-03 协作已交付，见 `docs/evidence/wp03-collaboration/`；WP03-04 洞察、WP03-06 工作流、
WP03-07 模块目录尚未实现，见根目录 `IMPLEMENTATION_PLAN.md` 与
`.trae/documents/phase3-plan.md`。

## 交付内容

- V13：`department_space`、`department_space_setting`、组织单元触发器、24 条 Phase 3 权限码。
- V14：`worklog_access_log`（只追加，员工可核对谁看过自己的已提交数据）。
- V15：`worklog_entry`、`worklog_day`、`worklog_submission`、`worklog_weekly_report`、
  `worklog_report_source`、`worklog_setting`；快照与周报不可变触发器；回收站/留置数据类扩展。
- 授权：员工拆分为 `employee-self`（本人范围）与 `employee`（部门范围），新增 `manager-unit`
  （部门子树范围），避免把工时读取权限错误放大到整个部门。
- 后端：部门列表与首屏、个人工作页与访问留痕、工时增删改/合并拆分/提交/审核、周报生成/提交/审核。
- 前端：部门列表、部门首屏、个人工作页、工时与周报页；导航按权限码隐藏。

## 关键不变式

1. 工时/周报的提交人只能从会话解析，请求体中的 personId 一律忽略。
2. 草稿不进入主管/老板视图；提交后快照不可改删，退回只产生新版本。
3. 无权字段在响应 JSON 中整体省略，不返回 0、占位或可猜测链接。
4. 周报只聚合已提交/已批准的日快照，并通过 `worklog_report_source` 回溯。

## 验证

| 文件 | 内容 |
|---|---|
| backend-tests.txt | `mvn -f backend/pom.xml install`：BUILD SUCCESS，75 个 boot-api 用例、3 个跳过；DepartmentAuthzTest 3/3、ModuleStructureTest 2/2 |
| frontend-typecheck.txt | TypeScript 严格模式通过 |
| frontend-tests.txt | Vitest 3 个文件 16 个用例全部通过 |
| node-tests.txt | 42 个用例，41 通过、1 个既有跳过 |
| contract-check.txt | 7 份契约、3 类覆盖完整 |
| coverage-gate.txt | 指令覆盖率 14.00%，满足非回归门槛 |
| db-verify.txt | V13–V15 迁移成功；95/96 断言通过；完整脚本受 WSL dockerd 回收影响未跑完 |

## 已知缺口（下一批）

- WP03-03 协作：频道、帖子、文件版本、任务、会议、知识页、搜索与撤权。
- WP03-04 洞察：提交率、逾期、部门投入与 k-匿名抑制端点。
- WP03-06 工作流：定义版本、实例、人工任务、定时器、SLA、补偿。
- WP03-07 模块目录与建议状态机。
- OpenAPI 尚未补录本批新路径，计划在 Phase 3 收口时统一冻结到 1.9.0。
