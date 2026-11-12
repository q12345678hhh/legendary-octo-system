# 统一授权决策运行手册（WP02-04）

## 1. 适用范围

人员目录、搜索、CSV 导出、批量读取、文件下载/完成与授权治理接口统一走 `AuthorizationGuard`。组织管理接口在本工作包仍沿用 `IdentityGuard` 管理员守卫（遗留③），后续工作包再切换。

一次判定必须同时满足：认证有效、租户匹配、主体有效、成员在职、权限码存在、数据范围命中、条件通过、无显式拒绝。任何一步缺失都拒绝。

## 2. 角色与权限

- 权限目录：全局表 `authorization_permission`，由迁移播种，租户只读。新增权限必须走新迁移。
- 系统角色：`SystemRoleCatalog` 代码常量，租户不能直接修改，只能复制为自定义角色后收窄。
- 自定义角色：`authorization_role` + `authorization_role_permission`，仅租户内可见。
- 管理员命名空间：`tenant-owner` / `tenant-admin` 只含管理与组织权限，不含业务正文；老板的全租户业务范围只来自 `organization_owner_role`。

| 角色码 | 用途 |
|---|---|
| `tenant-owner` | 租户所有者，由 `identity_tenant_member.is_owner` 派生 |
| `tenant-admin` | 租户管理员，由 `is_admin` 派生 |
| `business-owner` | 企业老板，由 `organization_owner_role` 派生 |
| `security-admin` | 授权治理与解释，显式授予并限时 |
| `hr-admin` | 人员与雇佣管理，显式授予 |
| `manager` | 直接下属与汇报链，由生效实线自动派生 |
| `delegate-manager` | 代管子树，由限时代管自动派生 |
| `employee` | 本人 + 本部门目录，所有在职成员自动派生 |

## 3. 范围

`tenant`、`self`、`self_created`、`direct_reports`、`reporting_chain`、`org_unit`、`org_unit_subtree`、`delegated`、`legal_entity` 已实现；`project_space` 与 `record_shared` 返回 `SCOPE_UNSUPPORTED`，不会降级成租户范围。

范围解析全部下推到 SQL：人员列表用「本人或主岗部门在可见集合内」，禁止先查全量再内存过滤。

## 4. 临时授权与委托

- 临时授权：`validTo` 必填才有到期行为；高权限角色（含 `tenant.admin` 或 `authorization.*.manage`）必须设置到期时间。
- 上限：`platform.authorization.max-temporary-grant-days` 默认 90 天。
- 委托：`delegatedFromId` 指向源授权，必须同角色、范围不得大于源、到期不得晚于源；`platform.authorization.max-delegation-days` 默认 180 天。
- 撤销：`DELETE /api/v1/authorization/assignments/{id}` 立即清本机缓存；其他实例靠版本轮询在 1 秒内失效。

## 5. 字段与条件策略

- 字段策略按角色/资源/字段配置 `can_view/can_edit/can_export/can_use_ai`，`effect=deny` 优先。
- 人员邮箱默认仅本人、`hr-admin`、`business-owner` 可查看；导出还要求 `directory.person.export` 与字段导出位。
- 条件算子：`equals`、`not_equals`、`in`、`not_in`、`gte`、`lte`、`exists`，支持 `all`/`any`/`not`。缺少属性或 JSON 无法解析按不通过处理。

## 6. 缓存与失效

- 版本事实源：`authorization_cache_version`，由数据库触发器在授权、拒绝、字段、条件、成员、雇佣、部门、岗位、汇报线、代管、老板角色变化时递增。
- 本机版本缓存 1 秒；判定结果缓存 60 秒（`platform.authorization.cache-ttl`、`version-ttl`）。
- 高风险撤权、成员停用、老板角色撤销：同事务 bump 版本，应用再清本机缓存。
- 排障：`GET /api/v1/authorization/cache/version` 查看当前版本；版本不增长时先查触发器是否存在。

## 7. 权限解释与模拟

- `POST /api/v1/authorization/decisions/check`：本人可判定自己；判定他人需要 `authorization.decision.check`。
- `POST /api/v1/authorization/decisions/explain`：需要 `authorization.decision.explain`，仅返回因素码（如 `GRANT:employee:EMPLOYEE_UNIT`、`DENY:<id>`、`RESOURCE_OUT_OF_SCOPE`），不返回策略原文。
- `POST /api/v1/authorization/simulations`：假设角色/拒绝，不落库，额外要求 MFA。
- 拒绝原因码：`TENANT_MISSING`、`SUBJECT_MISSING`、`MEMBER_INACTIVE`、`PERMISSION_UNKNOWN`、`NO_ALLOW`、`SCOPE_MISS`、`SCOPE_UNSUPPORTED`、`CONDITION_FAILED`、`EXPLICIT_DENY`，`SOD_CONFLICT` 与 `RISK_BLOCKED` 为 WP02-05/安全模块预留。

## 8. 常见故障

| 现象 | 排查 |
|---|---|
| 登录后人员列表 403 `NO_ALLOW` | 主体无在职雇佣，或角色/权限未授予；用 explain 查看因素 |
| 列表为空但 explain 允许 | 员工未配置主岗职位，`EMPLOYEE_UNIT` 派生了空部门集合 |
| 撤权后仍可访问 | 检查触发器是否存在、`authorization_cache_version` 是否递增、实例版本轮询是否正常 |
| 委托 422 `DELEGATION_SCOPE_BROADER` | 委托范围大于源授权，或到期晚于源授权 |
| 模拟 403 `MFA_REQUIRED` | 先完成 MFA 再调用模拟接口 |

## 9. 验证

```powershell
mvn -B -ntp -f backend/pom.xml clean install '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'
node backend/tools/coverage-gate.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-verify.ps1
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
```
