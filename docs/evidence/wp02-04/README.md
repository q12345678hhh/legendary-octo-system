# WP02-04 证据索引

对应验收项：角色、权限、数据范围、字段权限、条件策略、显式拒绝、临时授权与委托；同一授权结果覆盖 API、搜索、导出、批量、文件与 AI 入口；权限解释器与模拟器受安全权限保护；缓存版本与 60 秒失效，高风险撤权立即生效；缺少租户、主体、权限或范围时默认拒绝。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`（20 个模块）；boot-api 38/38；authorization 单元 3/3；新增 `PeopleAccessApiTest` 3、`AuthorizationApiTest` 3、`FileAccessApiTest` 2 |
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 空库与升级库 V1–V9 校验和一致（V9 `-1911349163`）；RLS 9/9、53 张租户表 ENABLE+FORCE；`90-authorization.sql` 打印「WP02-04 统一授权数据库断言通过」；`AuthorizationDbTest` `Tests run: 1, Failures: 0, Errors: 0`；校验和门禁与连接池隔离 3/3 通过 |
| `authz-scenarios.txt` | `AuthorizationDbTest` 与 SQL 断言的 40 个场景 | 默认拒绝、员工/主管/代管/老板范围、显式拒绝优先、临时授权到期、委托子树、缓存版本递增、解释与模拟、搜索/导出/批量/文件不能绕过、跨租户隔离 |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` 与自测 | 7 份契约全部 PASS，自测 9/9；OpenAPI 1.5.0 新增 15 条路径、22 个 schema（`paths` 102→117，`schemas` 87→109），基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 9 个报告，指令覆盖率 14.23%（covered=5559, missed=33503），基线 0.00%，`PASS` |

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'

# 真实 PostgreSQL：迁移、RLS、统一授权与缓存版本
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 契约与覆盖率门禁
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```

## 过程记录：失败与修复

**1. 显式拒绝写入后查不到记录**

`addDeny` 先生成 UUID，但 `insertDeny` 没把 id 传入，数据库默认生成另一个 id，随后按旧 id 查询为空。
修复：`insertDeny` 显式接收 id 并在 INSERT 中写入。

**2. 代管人看不到子部门的人**

委托派生的是 `ORG_UNIT_SUBTREE`，范围里只有部门集合；人员资源请求只带 `personId`，没有 `orgUnitId`，`resourceInScope` 直接判不命中。
修复：新增 `OrganizationScopeQueries.primaryUnitOfPerson`，资源是人时回查其当前主岗部门再与可见部门求交；正式决策与模拟共用该口径。

**3. `identity_actor` 缺少复合唯一约束**

授权记录要用 `(tenant_id, actor_id)` 指向统一主体，但 V7 只给主键建了唯一约束。
修复：V9 先补 `identity_actor_tenant_id_unique UNIQUE (tenant_id, id)`，再做复合外键。

**4. `@WebMvcTest` 切片缺过滤器依赖**

`AuthenticationFilter`、`HttpMetricsFilter`、`IdempotencyFilter` 都会被切片加载，首次运行因缺 `SessionService` 启动失败。
修复：补 `@MockitoBean SessionService/IdentityProperties/PlatformMetrics/IdempotencyService`，并给请求带 Bearer 与 `X-Tenant-Id`。

**5. 契约门禁把新 schema 的 required 当作破坏性变更**

首次 `contract-check` 报大量「新增必填字段」；这些字段都在本包新增的 schema 内，不影响既有调用方。
修复：确认无既有 schema 字段被删除或改必填后，按既有流程 `--update` 重冻结基线，再跑门禁与自测。

**6. 模块依赖不能成环**

计划里把 `IdentityGuard` 改为委托授权守卫，但 authorization 已依赖 identity，反向依赖会形成 Modulith 环。
修复：保持 `IdentityGuard` 与组织管理接口现状，人员、文件、搜索/导出/批量与授权治理统一走 `AuthorizationGuard`；该边界写入 IMPLEMENTATION_PLAN 遗留③。

## 边界

- 职责分离与季度访问复核由 WP02-05 实现；本包只固定 `SeparationOfDutyChecker` 调用点并默认放行。
- `project_space` 与 `record_shared` 尚无实体，一律 `SCOPE_UNSUPPORTED`。
- 组织管理接口仍使用既有管理员守卫；AI 工具执行链接入时必须复用 `AuthorizationGuard`。
- 系统角色模板升级不回溯改写租户已复制的自定义角色。
- 决策审计只记录拒绝与解释请求，避免写放大；允许结果的审计按需另行开启。
