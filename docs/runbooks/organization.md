# 组织结构与生效日期运行手册（WP02-03）

本手册覆盖法人、部门树、职级/岗位/职位、成本中心、地点、主兼岗、实线/虚线汇报、
限时代管、老板与受托人角色、部门移动影响预览、变更单与历史快照。
所有 14 张组织表都带 `tenant_id`，且 `ENABLE + FORCE ROW LEVEL SECURITY`。

本地开发栈：API `http://127.0.0.1:18080`，数据库在 WSL/Docker 内的 `platform-pg-dev`。

## 0. 边界与禁止事项

| 项 | 现状 | 约束 |
|---|---|---|
| 结构接口 | 只改**当前态** | 需要未来生效的调整一律建变更单，不要提前改结构 |
| 时间模型 | 双轨：结构实体当前态 + 变更单未来态；关系实体自带生效区间 | 生效区间左闭右开，结束日当天已不生效 |
| 到期应用 | `boot-worker` 的 `OrganizationChangeApplier` | 每 30 秒扫描一次非 RLS 系统队列 `platform_organization_due` |
| 移动部门 | 必须先 `POST /org-units/{id}/move-previews` | 票据 10 分钟单次有效；服务端复算 6 项计数，不一致返回 `ORG_IMPACT_PREVIEW_STALE` |
| 老板角色 | 独立业务角色 | 与租户管理员无关；管理员不自动获得任何正文权限 |
| 历史快照 | `organization_change_history` 只允许插入 | 数据库触发器拒绝任何 UPDATE / DELETE |
| 直接改库 | — | **禁止**手工改 `organization_change.status` 或删除到期队列行；会让状态机与审计失真 |

## 1. 模型

```
LegalEntity（法人）
  └── OrgUnit（company → division → department → team，自引用父子）
        ├── Position（职位编制，挂在部门上）
        ├── CostCenter（成本中心，挂法人）
        └── Location（办公地点，带 IANA 时区）

Person → Employment（雇佣关系）
  ├── EmploymentAssignment（primary / secondary，带生效区间）
  ├── ReportingLine（solid / dotted，带生效区间）
  ├── Delegation（限时代管，必须写原因与结束日期）
  └── OwnerRole（owner / trustee，带生效区间）
```

- 一个雇佣关系同时只能有一条未结束的主岗；兼岗可多条但不得重叠。
- 一个员工同时只能有一条未结束的**实线**；虚线可多条。
- 实线与虚线**共同**参与判环：任何一条边只要形成环就整体拒绝。
- 部门树同样禁止环，移动目标不能是自己的子孙。

## 2. 常用调用

```powershell
$h = @{ 'X-Tenant-Id' = '<tenant>'; 'Content-Type' = 'application/json' }

# 法人 → 部门
curl.exe -X POST @h -d '{"code":"LE-001","name":"示例科技有限公司"}' `
  "http://127.0.0.1:18080/api/v1/legal-entities"
curl.exe -X POST @h -d '{"parentId":null,"legalEntityId":"<le>","code":"U-ROOT","name":"总部","unitType":"company"}' `
  "http://127.0.0.1:18080/api/v1/org-units"

# 部门树
curl.exe -H "X-Tenant-Id: <tenant>" "http://127.0.0.1:18080/api/v1/org-units/tree"
```

### 2.1 移动部门（强制预览）

```powershell
# 1) 生成预览票据
curl.exe -X POST @h -d '{"targetParentId":"<newParent>"}' `
  "http://127.0.0.1:18080/api/v1/org-units/<unit>/move-previews"

# 2) 携带 previewId 移动；缺票据返回 409 ORG_IMPACT_PREVIEW_REQUIRED
curl.exe -X POST @h -d '{"targetParentId":"<newParent>","previewId":"<preview>","sortOrder":1}' `
  "http://127.0.0.1:18080/api/v1/org-units/<unit>/move"
```

预览统计：子部门数、在职人数、职位数、汇报线数、代管数、排期变更数。
只有这 6 项当前态计数由本工作包负责；权限、审批、编制的**下游**影响由 WP02-04 与
后续模块补齐，这里不伪造结论。

### 2.2 主兼岗与汇报线

```powershell
curl.exe -X POST @h -d '{"positionId":"<pos>","assignmentType":"primary","effectiveFrom":"2026-11-01"}' `
  "http://127.0.0.1:18080/api/v1/employments/<employment>/assignments"

curl.exe -X POST @h -d '{"subordinateEmploymentId":"<a>","managerEmploymentId":"<b>","lineType":"solid","effectiveFrom":"2026-11-01"}' `
  "http://127.0.0.1:18080/api/v1/reporting-lines"
```

- 新增主岗会自动关闭上一条未结束的主岗（`effective_to = 新起始日`）。
- 自汇报返回 `REPORTING_SELF`，成环返回 `REPORTING_CYCLE`。

### 2.3 代管、老板与就绪

```powershell
curl.exe -X POST @h -d '{"delegatorEmploymentId":"<a>","delegateEmploymentId":"<b>","scopeOrgUnitId":"<unit>","reason":"年假","effectiveFrom":"2026-11-10","effectiveTo":"2026-12-10"}' `
  "http://127.0.0.1:18080/api/v1/delegations"

curl.exe -X POST @h -d '{"employmentId":"<a>","roleType":"owner","effectiveFrom":"2026-11-10"}' `
  "http://127.0.0.1:18080/api/v1/owner-roles"

curl.exe -H "X-Tenant-Id: <tenant>" "http://127.0.0.1:18080/api/v1/organization/readiness"
```

`ready=false` 时 `blockers` 取值：`owner_role_missing`、`trustee_missing`、
`trustee_not_distinct`。业务模块启用前调用 `requireReady()`，未就绪返回
`409 ORGANIZATION_NOT_READY`，不提供「先启用后补」的开关。

### 2.4 变更单

```powershell
# 未来生效：进入排期
curl.exe -X POST @h -d '{"changeType":"unit_update","targetId":"<unit>","effectiveDate":"2026-12-01","payload":{"name":"平台组（改）"},"reason":"组织调整"}' `
  "http://127.0.0.1:18080/api/v1/organization/changes"

# 生效日期已到：立即应用，返回 appliedImmediately=true
# 手动触发一轮到期扫描（演练/补偿用）
curl.exe -X POST @h -d '{"batchSize":50}' `
  "http://127.0.0.1:18080/api/v1/organization/changes/apply-due"

# 历史快照
curl.exe -H "X-Tenant-Id: <tenant>" `
  "http://127.0.0.1:18080/api/v1/organization/history?entityType=org_unit&entityId=<unit>"
```

## 3. Worker 与到期队列

租户表 `FORCE ROW LEVEL SECURITY`，Worker 没有租户上下文时读不到任何行，
因此到期发现走**非 RLS** 的系统队列：

```sql
-- 创建变更单时写入，应用成功后删除
SELECT change_id, tenant_id, effective_date FROM platform_organization_due;
```

`OrganizationChangeApplier` 每 30 秒（`PLATFORM_ORGANIZATION_CHANGE_POLL_INTERVAL`）
调用 `OrganizationChangeService.applyDue(50)`：

1. `FOR UPDATE SKIP LOCKED` 认领到期行（`effective_date <= CURRENT_DATE + 1`）；
2. 逐条进入租户事务，`SELECT ... FOR UPDATE` 重新读取变更单；
3. 若 `UNIT_MOVE`，复算影响计数并与创建时冻结的快照比对，不一致标 `failed`；
4. 应用成功后 `status=applied` 并删除队列行；失败只标 `failed`，**不自动重试**。

排障：

```sql
-- 到期但未应用
SELECT id, tenant_id, change_type, effective_date, status, failure_reason
  FROM organization_change WHERE status IN ('scheduled', 'failed') ORDER BY effective_date;

-- 队列积压
SELECT count(*) FROM platform_organization_due WHERE effective_date <= CURRENT_DATE;
```

`failed` 需要人工判断后重建变更单，不要直接改状态。

## 4. 时区与「今天」

- `OrganizationCalendar.today(tenantId)` 读取 `tenancy_tenant.timezone`（缺省/非法回退 UTC）。
- 所有「是否生效」「是否到期」判断都用租户时区的当天日期，不用服务器时区。
- 生效区间左闭右开：`effective_from <= today < effective_to`。

## 5. 验证

```powershell
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1   # 含 80-organization.sql 与 OrganizationDbTest
```

预期输出包含 `WP02-03 组织结构数据库断言通过` 与
`Tests run: 1, Failures: 0, Errors: 0`（`OrganizationDbTest`）。
