# WP02-03 证据索引

对应验收项：法人、部门、岗位、职位、职级、成本中心、地点与主/兼岗；实线/虚线汇报、管理委派、未来变更、生效/失效与历史快照；组织与汇报图禁止环，移动部门前生成影响预览；老板/最高负责人是独立业务角色且至少一名有效受托人；租户管理员不自动获得正文权限。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`（20 个模块）；`EffectiveDatingTest` 3、`OrganizationTreeTest` 5、`ReportingGraphTest` 4、`OwnerReadinessTest` 4 共 16 个单元测试通过；`OrganizationApiTest` 6/6；boot-api 合计 30/30，boot-worker 4/4，测试支撑 3/3 |
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 空库与升级库 V1–V8 校验和一致（V8 `-525129633`），50 万行升级耗时 4.7s、超 1s 语句 0 条；RLS 9/9、45 张租户表 ENABLE+FORCE；`80-organization.sql` 打印「WP02-03 组织结构数据库断言通过」；`OrganizationDbTest` `Tests run: 1, Failures: 0, Errors: 0`；校验和门禁与连接池隔离 3/3 通过 |
| `org-scenarios.txt` | `OrganizationDbTest` 16 个场景与单元/切片测试清单 | 三级汇报链、兼岗、限时代管、虚线、调动历史、未来变更切换、预览票据单次消费、历史不可篡改、空老板阻断、无上下文 14 表 0 行 |
| `contract-check.txt` | `node tools/contract-check.mjs`（`contracts/` 目录） | 7 份契约全部 PASS，自测 9/9；OpenAPI 1.4.0 新增 36 条路径、27 个 schema（`paths` 66→102，`schemas` 60→87），基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 8 个报告，指令覆盖率 14.14%（covered=4376, missed=26574），基线 0.00%，`PASS` |

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'

# 真实 PostgreSQL：迁移、RLS、组织结构、变更应用、连接池
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 契约与覆盖率门禁（注意在 contracts/ 目录执行）
node tools/contract-check.mjs
node --test tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```

> 本机工作区路径含中文（`新建文件夹`），JaCoCo agent 经 `cmd.exe` 分叉时会把 `destfile` 的 UTF-8 路径写坏。用 `-Djacoco.destFile` 指向 ASCII 路径即可；CI 的 Linux 工作区不需要这个参数。

## 过程记录：失败与修复

**1. 根部门预览触发 `NullPointerException`**

`OrganizationStructureStore.findParentId` 用 `stream().findFirst()` 返回父部门，根部门 `parent_id` 为 `NULL` 时流中只有一个 `null` 元素，`findFirst()` 内部 `Optional.of(null)` 抛 NPE。
修复：改为取列表首元素并 `Optional.ofNullable(...)`；`当前父部门为空` 与「部门不存在」都表示walk结束，语义一致。

**2. 影响预览插入参数个数不匹配**

`ImpactPreviewStore.insert` 的 `VALUES` 有 12 个 `?`，实际只绑定 11 个参数，PostgreSQL 报「未设定参数值 12 的内容」。
修复：删掉多余的占位符，列数与参数数对齐。

**3. 立即生效变更返回旧状态（真实缺陷）**

`OrganizationChangeService.create` 立即生效分支先 `lock` 得到 `SCHEDULED` 快照，应用后直接返回同一对象，调用方看到的状态仍是 `SCHEDULED`，而库里已是 `APPLIED`。
修复：`applyLocked` 之后重新 `find` 再返回。

**4. 兼岗被误判为与主岗重叠**

`AssignmentStore.hasOverlap` 不区分主/兼岗，给已有主岗的员工加兼岗时返回 `ASSIGNMENT_OVERLAP`。
修复：改为 `hasOverlappingPrimary`（`assignment_type = 'primary'`），`assign` 只在主岗分支校验；兼岗允许与主岗并存。

**5. `organization` 模块缺少 JDBC 驱动**

真实库测试报 `Could not load JDBC driver class [org.postgresql.Driver]`。
修复：模块 `pom.xml` 增加 `org.postgresql:postgresql`（`test` 作用域），与 `identity` 模块一致。

**6. `80-organization.sql` 在 `COMMIT` 后丢失租户上下文**

第 4、5 段 DO 块在上一段提交后运行且未重设 `app.tenant_id`，RLS 返回 0 行，误报「乙主管没有实线经理」。
修复：两段各自 `BEGIN; SELECT set_config('app.tenant_id', :org_e, true); ... COMMIT;`，并用 `COALESCE(chain_depth, 0)` 防 NULL 静默通过。

**7. `@WebMvcTest` 切片缺过滤器依赖**

`AuthenticationFilter`、`HttpMetricsFilter`、`IdempotencyFilter` 都是 `@Component`，切片会加载。
修复：补 `@MockitoBean SessionService/IdentityProperties/PlatformMetrics/IdempotencyService`，`@BeforeEach` stub Cookie 名与 `authenticateToken`，请求统一带 Bearer 与 `X-Tenant-Id`。

**8. OpenAPI 岗位 schema 覆盖了既有异步作业 `Job`**

第一版把组织岗位命名为 `Job`，覆盖已有 `Job` schema，`contract-check` 报 `schema:Job.properties.createdAt 新增必填字段` 等破坏性变更。
修复：回滚契约文件，改名 `OrganizationJob` / `OrganizationJobRequest`（描述注明与异步作业是不同概念），重新注入并重冻结基线。

**9. 编译期两处问题**

`OrganizationChangeStore` 的字段初始化器引用未赋值的 `objectMapper`（改为 `mapper()` 方法）；`OrganizationRelationService.requireReady()` 调用了 `ReadinessView` 上不存在的方法（改为读取 `ready()` 后抛 409 `ORGANIZATION_NOT_READY`）。

## 边界

- 影响预览当前只统计组织侧可确定的六项：子部门、在职人数、职位、汇报线、代管、排期变更；权限、审批、编制与报表影响等 WP02-04 与后续模块接入后补齐，不伪造结论。
- 排期移动在创建变更单时消费预览票据并冻结计数快照；到期应用前重新计算，数据变化即标记 `failed`，不自动重放。
- `platform_organization_due` 为非 RLS 系统队列，仅 boot-worker 跨租户扫描；单条失败只标记该条，不影响同批其他租户。
- 管理委派默认上限 180 天（`PLATFORM_ORGANIZATION_MAX_DELEGATION_DAYS`），预览票据 TTL 默认 10 分钟（`PLATFORM_ORGANIZATION_PREVIEW_TTL`）。
- 老板/受托人以雇佣（Employment）为锚点；租户管理员不因管理员身份自动获得组织正文权限，细粒度权限解释由 WP02-04 收口。
- OIDC 与邮件通知不在本工作包范围。
