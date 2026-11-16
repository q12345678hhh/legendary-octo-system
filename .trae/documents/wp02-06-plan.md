# WP02-06 多租户攻击测试 实施方案

> 目标：把 Phase 2 退出标准「跨租户成功读写为 0」从人工约定变成可执行、可阻断的门禁。本包只新增测试、夹具与门禁接线，不改业务语义。

## 1. 验收映射

| 计划条目 | 本方案落点 | 判定 |
|---|---|---|
| 双相似租户测 UUID 猜测、列表、批量、搜索、导出、附件、缓存、作业、Webhook、RLS | 夹具 `92-cross-tenant.sql` + 服务层套件 + 既有 `10-rls-cases.sql` | 每个攻击面至少 1 正 1 负用例 |
| 连接池复用、缺失 RLS 变量、异常事务、只读分析角色 | `92-cross-tenant.sql` 第 4–7 节 | 全部断言不可见/被拒 |
| 对象存储路径、预签名 URL、搜索索引、日志关联隔离 | 服务层套件 + 静态不变量测试 | key 前缀、跨租户 404、日志无正文 |
| 自动化套件在双租户同业务编号、同姓名数据上 100% | `CrossTenantAttackDbTest` 汇总断言 `passed == total` | 任一失败即 CI 红 |

## 2. 调研结论（现状与缺口）

已具备：
- 租户表 FORCE RLS + `NOBYPASSRLS` 运行账号（`application.yml:7-24`、`tenant-table.template.sql`）。
- 请求头只决定租户上下文（`RequestContextBindingFilter.java:50-87`），身份只能来自会话/令牌（`AuthenticationFilter.java:62-87`）；缺失租户头 401 `TENANT_REQUIRED`。
- 文件对象 key 为 `tenantId/fileId/fileName`，下载走 `authorizeDownload`（`FileService.java:19-138`）。
- 授权缓存 key 含 tenantId（`AuthorizationCache.java:52-67`）。
- 平台域/租户域互斥与支持访问只读已覆盖（`TenantApiBoundaryTest`）。
- 真实库断言已有 `10-rls-cases.sql`、`90-authorization.sql`、`91-separation.sql`。

缺口：
1. 没有「双相似租户」夹具：现有两租户数据量小，未覆盖同业务编号、同姓名、同文件名。
2. 没有逐表 UUID 猜测矩阵：新增租户表后可能漏测。
3. 没有服务层跨租户套件：`PeopleService`、`FileService`、`AuthorizationService`、复核/申请等只被测过单租户。
4. 没有连接池复用与「上一请求残留 `app.tenant_id`」断言。
5. 没有跨租户「列表/批量/搜索/导出」聚合断言（现有 SQL 只做单条 SELECT）。
6. Webhook 尚未实现（仓库无 webhook 代码）：本包以 outbox/死信/作业队列替代，并在套件中显式登记「Webhook 投递不存在」为已知项，后续实现时必须复用同一套件。

## 3. 攻击夹具：双相似租户

新增 `tests/db/92-cross-tenant.sql`，以迁移账号种入：

- 租户 A `a1a1a1a1-...`、租户 B `b2b2b2b2-...`，名称、套餐、域名后段完全相同，仅 UUID 不同。
- 每个租户各 3 名人员：同名「张三」「李四」「王五」，`primary_email` 分别为 `zhangsan@same.example`（**完全相同**）、`employee_no` 同为 `E-0001/E-0002/E-0003`。
- 每个租户各 1 份文件：同名 `合同.pdf`，对象 key 仅租户前缀不同。
- 每个租户各 2 条 outbox 事件、1 条 work 作业、1 条授权 assignment、1 条授权决策日志、1 条访问复核。
- 固定 UUID 段：A 用 `a1000000-...`，B 用 `b2000000-...`，便于用例直接引用。

## 4. 分层套件

### 4.1 数据库层 `tests/db/92-cross-tenant.sql`

以 `platform_app` 执行，全部包装在事务内，最后统一 `ROLLBACK`（除必要的持久夹具）。

1. **逐表 UUID 猜测**：从 `pg_policies` 自动枚举所有租户表（不硬编码表名，新表自动纳入）。对每张表以 A 上下文按 B 的主键执行 `SELECT count(*)`、`UPDATE ... WHERE id = :b_id`、`DELETE ... WHERE id = :b_id`，断言 0 行；仅含 `id` 列的表做 SELECT。
2. **列表/批量/搜索/导出**：A 上下文下 `SELECT * FROM directory_person WHERE primary_email = 'zhangsan@same.example'` 只能看到 A 的张三；`IN (:b_ids)` 批量返回 0；`ILIKE '%张三%'` 只返回 A 的行；导出等价的 `COPY (SELECT ...) TO STDOUT` 行数等于 A 的行数。
3. **附件**：A 上下文按 B 的 file id 查询 0 行；尝试把 A 的租户上下文写入 B 的文件行被 `WITH CHECK` 拒绝。
4. **缓存/作业/Webhook**：`authorization_cache_version` 两租户版本独立递增；`platform_outbox` A 上下文只看到 A 的事件；`platform_job` 按 B 的 job id 查询为 0；无 webhook 表时断言 `to_regclass('platform_webhook_delivery') IS NULL`，登记为已知未实现。
5. **缺失/伪造 RLS 变量**：不设 `app.tenant_id`、设空串、设非法 UUID、设 `true`（布尔注入）四种情况下所有租户表返回 0 行或报错，绝不返回数据。
6. **连接池复用**：同一连接先 `set_config(..., true)` 查 A（有行），提交后不重设直接查 B 表 → 0 行；再 `SET LOCAL`/事务结束后残留检查；`RESET ALL` 后仍为 0。
7. **异常事务**：事务内先设 A，触发约束错误回滚，随后同一连接查询必须 0 行（上下文随事务回滚消失）。
8. **只读分析角色**：以 `BEGIN READ ONLY` + A 上下文验证读隔离；写入尝试必须失败（`25006`）。另断言 `platform_app` 非 superuser 且 `rolbypassrls = false`。
9. **跨租户写**：A 上下文插入 `tenant_id = B` 的行被策略拒绝；`ON CONFLICT` 更新 B 的行影响 0 行。

输出 `\echo 'WP02-06 跨租户数据库断言通过'`，失败即抛异常。

### 4.2 服务层真实库套件 `CrossTenantAttackDbTest`（Java）

放在 `backend/boot-api/src/test/java/com/acme/platform/security/`，沿用 `AuthorizationSodDbTest` 的手动装配（JdbcTemplate + TenantTransactions + 真实服务），避免引入全量 Spring 上下文与 Redis/MinIO 依赖。

用例（每例登记 name/tenant/期望，最后汇总）：

| # | 攻击面 | 用例 | 期望 |
|---|---|---|---|
| 1 | 详情 | A 主体读 B 的 personId | `PERSON_NOT_FOUND` / 404 |
| 2 | 同名 | A 按 `q=张三` 列表 | 只返回 A 的张三（1 条） |
| 3 | 同业务编号 | A 按 `employee_no=E-0001` 查询 | 只命中 A |
| 4 | 批量 | A 的 `batchRead([B1,B2])` | 空列表 |
| 5 | 导出 | A 导出 `q=张三` | CSV 行数 = 1，不含 B 的 email |
| 6 | 附件列表 | A 列文件 | 只含 A 的 `合同.pdf` |
| 7 | 附件详情 | A 用 B 的 fileId `find` | 空/404 |
| 8 | 预签名 | A 对 B 的 fileId `authorizeDownload` | 404，不返回 URL |
| 9 | 对象路径 | 上传后断言 key 以 `A/` 开头且不含 `B/` | 通过 |
| 10 | 缓存 | 同 actorId 在两租户各授权一次 | 两个 key，互不命中 |
| 11 | 作业 | A 取消 B 的 jobId | 404 且 B 作业状态不变 |
| 12 | Outbox | A 上下文列事件 | 只有 A |
| 13 | 复核 | A 读 B 的 reviewId/itemId | 404 |
| 14 | 申请 | A 批准 B 的 requestId | 404 |
| 15 | 授权治理 | A 撤销 B 的 assignmentId | 404 且 B 仍生效 |
| 16 | 决策日志 | A 导出决策审计 | 不含 B 的 actor |
| 17 | 元数据 | A 的 `currentVersion()` 与 B 独立 | 版本互不影响 |

汇总断言：`passed == total`（当前 17 例），失败输出用例名与期望/实际。

### 4.3 HTTP 头层 `TenantHeaderMismatchTest`（@WebMvcTest）

补 `TenantApiBoundaryTest` 未覆盖的头部组合：

- `X-Tenant-Id = B` + A 的 Bearer → 401/403，且业务服务未被调用；
- 缺失 `X-Tenant-Id` → 401 `TENANT_REQUIRED`；
- 非法 UUID → 401 `TENANT_REQUIRED`；
- 伪造 `X-Subject-Id` + 合法会话 → 以会话主体为准；
- `X-Tenant-Id = A` + 平台操作员头 → 403 `PLATFORM_OPERATOR_NOT_ALLOWED`。

### 4.4 静态不变量（扩展 `tests/security/isolation-invariants.test.mjs`）

- 所有迁移中 `CREATE TABLE` 含 `tenant_id` 的表必须同时 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`（扫描 `backend/db/src/main/resources/db/migration/*.sql`，允许模板文件）。
- 控制器不得直接读取 `X-Subject-Id`；`RequestContextBindingFilter` 不得接受客户端主体。
- `FileService` 的对象 key 拼接必须包含 `tenantId`。
- `AuthorizationCache.key(...)` 必须包含 `tenantId`。
- `application.yml` 运行账号默认 `NOBYPASSRLS` 语义（迁移与运行账号分离）。

## 5. 代码落点

新增：
- `tests/db/92-cross-tenant.sql`
- `backend/boot-api/src/test/java/com/acme/platform/security/CrossTenantAttackDbTest.java`
- `backend/boot-api/src/test/java/com/acme/platform/TenantHeaderMismatchTest.java`
- `docs/evidence/wp02-06/`（db-verify、backend-tests、node-tests、README）

修改：
- `scripts/db-verify.ps1`：新增 5g 步骤（执行 `92-cross-tenant.sql` + 以 `-Dtest=CrossTenantAttackDbTest` 跑真实库套件），最终通过信息追加「跨租户攻击套件」。
- `tests/security/isolation-invariants.test.mjs`：新增 5 条静态不变量。
- `IMPLEMENTATION_PLAN.md`：勾选 WP02-06 四条目并写「遗留与说明」。

不改业务代码；若套件发现真实漏洞，单独提交修复并补回归用例。

## 6. 实施顺序

1. 夹具与 SQL 断言（先红后绿：在现有实现上应直接通过；若失败即为真实缺陷）。
2. `CrossTenantAttackDbTest` 骨架 + 17 例，逐例跑通。
3. `TenantHeaderMismatchTest` 5 例。
4. 静态不变量 5 条。
5. 接入 `db-verify.ps1`，本地全量跑通。
6. 证据归档、计划勾选。

## 7. 验证清单

- `powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1` → `scripts\db-verify.ps1`：新增 5g 全部通过，最终输出含「跨租户攻击套件」。
- `mvn -B -ntp -f backend/pom.xml clean install`：BUILD SUCCESS，新增测试全绿。
- `node --test "tests/**/*.test.mjs"`：静态不变量通过。
- `node backend/tools/coverage-gate.mjs`：非回归。
- CI：`db`、`backend`、`tests` 三个 job 全绿后合并。

## 8. 风险与取舍

- **全链路 HTTP 攻击**：需要 Redis/MinIO 与完整 Spring 上下文，本包不引入；HTTP 头部与会话域用切片覆盖，数据越界用真实库服务层覆盖，两者合起来满足验收。E2E job 在提供 `PLATFORM_E2E_BASE_URL` 时可追加同一用例表。
- **表清单自动发现**：依赖 `pg_policies`，若某表漏建策略会被 `40-integrity.sql` 先拦下，本套件不重复兜底。
- **只读分析角色**：为避免在 CI 增建角色，采用「只读事务 + 权限属性断言」等价验证；若后续引入真实分析角色，追加连接级用例。
- **Webhook 未实现**：显式登记为已知项，不用「跳过」掩盖；实现投递后必须补 `platform_webhook_delivery` 的跨租户用例。
- **测试数据隔离**：夹具使用独立 UUID 段与 `cross-tenant.` 域名后缀，结束后只回滚本事务，不污染既有断言数据。
