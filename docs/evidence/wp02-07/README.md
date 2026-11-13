# WP02-07 证据索引

对应验收项：平台运营账号无法读取任一租户业务正文；六个界面均有对应的后端鉴权用例；
支持访问全过程有工单、审批、时限与审计记录。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn -B -ntp -f backend/pom.xml clean install` | `BUILD SUCCESS`（20 个模块，2:42）；`PlatformOperatorAuthApiTest` 5/5；`PlatformScreenAuthzTest` 6/6；`TenantApiBoundaryTest` 11/11；`TenantHeaderMismatchTest` 5/5；`ModuleStructureTest` 2/2 |
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 空库与升级库 V1–V11 校验和一致；5h 段打印「WP02-07 平台运营数据库断言通过」；`PlatformOpsDbTest` `Tests run: 1, Failures: 0, Errors: 0`；末尾全绿清单含「平台运营端」 |
| `node-tests.txt` | `node --test "tests/**/*.test.mjs"` | 38 个用例：37 通过、1 跳过、0 失败；含 4 条平台运营不变量 |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` 与自测 | 7 份契约全部 PASS，自测 9/9；OpenAPI 1.7.0 新增 19 条路径、26 个 schema（`paths` 130→149，`schemas` 118→144），基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 9 个报告，指令覆盖率 15.02%（covered=7541, missed=42677），基线 0.00%，`PASS` |

## 六界面鉴权用例对照

`PlatformScreenAuthzTest`（后端鉴权唯一入口，前端菜单隐藏不算鉴权）：

| 界面 | 用例 | 断言 |
|---|---|---|
| 全部六个 | `allSixScreensRequireOperatorSession` | 无 `plt_ops_session` 一律 `401 PLATFORM_SESSION_REQUIRED` |
| 运营总览 / 租户 / 目录 / 安全 / 作业 / 设置 | `auditorReadsEveryScreenButCannotWriteCatalog` | auditor 可读六个界面；写目录 `403 PLATFORM_PERMISSION_DENIED` |
| 平台设置 / 安全运营 | `opsCannotManageSettingsOrSecurity` | `platform-ops` 无 `settings.*` 与 `security.manage` |
| 安全运营 / 目录 / 作业 | `securityWriteRequiresMfa` | 有权限但 MFA 未满足时 `403 PLATFORM_MFA_REQUIRED` |
| 平台设置 | `ownerWithMfaCanUpdateSetting` | owner + MFA 可改设置；密钥类只回 `configured` |
| 租户管理 | `tenantDirectoryResponseContainsNoBusinessFields` | 租户目录响应不含人员、文件、工时、消息等业务字段 |

认证与会话补充用例（`PlatformOperatorAuthApiTest`）：登录 Cookie 的 `Path=/platform`、
`HttpOnly`、`SameSite=Strict`；`/auth/me` 返回 12 个权限码；`logout` 清空两个 Cookie；
登录失败统一 `401 PLATFORM_LOGIN_FAILED`；MFA 端点需会话与 CSRF。

域隔离补充用例（`TenantApiBoundaryTest`、`TenantHeaderMismatchTest`）：旧头
`X-Platform-Operator-Id` 一律 `400 PLATFORM_OPERATOR_HEADER_RETIRED`；平台路径带
`X-Tenant-Id` 或租户 Cookie 返回 `403`；租户路径带运营 Cookie 返回 `403`；写操作缺
`X-CSRF-Token` 返回 `403 CSRF_INVALID`。

## 支持访问与平台数据面

- 数据库断言（`tests/db/93-platform-ops.sql`）：13 张平台表存在且不启用 RLS；纯平台表无
  `tenant_id`；`platform_tenant_directory` 由触发器随租户状态同步；`platform_audit_event`
  不可改删；设置键白名单；指标 upsert 幂等；安全事件类别与状态机；运营账号与会话约束。
- 真实库用例（`PlatformOpsDbTest`）：目录投影同步、设置白名单与密钥脱敏、租户数上限
  `limits.max_tenants` 真实生效、支持访问时限上限 `limits.support_access_max_minutes` 生效
  且不超过 60 分钟硬上限、安全事件状态机、总览错误率计算。
- 工单格式、审批人不得等于申请人、过期与撤销语义沿用既有 `TenantLifecycleDbTest`（WP02-01），
  本包只把时限上限改为读平台设置。

## 复现命令

```powershell
# 后端全量构建与测试
mvn -B -ntp -f backend/pom.xml clean install

# 真实 PostgreSQL：迁移、RLS、跨租户与平台运营端
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# Node 不变量、契约与覆盖率门禁
node --test "tests/**/*.test.mjs"
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```
