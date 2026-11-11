# WP02-01 证据索引

对应验收项：租户创建/初始化/启用/冻结/恢复/注销/冷静期/最终删除状态机；域名、品牌、语言、时区、功能开关、套餐、配额与使用量；平台运营默认不可读业务正文（支持访问必须工单 + 独立审批 + 时限 + 醒目标识）；冻结后阻断业务写入、插件与 AI 新执行，只保留受控导出与申诉。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`；`TenantExecutionGateTest` 4、`SupportAccessRulesTest` 3、`TenantStatusTest` 3、`TenantApiBoundaryTest` 8 全部通过；`TenantLifecycleDbTest` 在无数据库的本地默认构建中按设计跳过（需 `-Dplatform.db.url`） |
| `db-verify.txt` | `scripts/db-verify.ps1` 八个步骤的完整输出 | 空库与升级库 V1–V6 校验和一致（V6 `-1364102595`）；RLS 9/9；结构中 15 张租户表全部 ENABLE+FORCE；`60-tenant-lifecycle.sql` 打印「WP02-01 租户生命周期数据库断言通过」；`TenantLifecycleDbTest` `Tests run: 1, Failures: 0, Errors: 0`；连接池隔离 3/3 |
| `two-tenant-numbers.txt` | db-verify 第 5b 步原文 | 两个租户（`aaaaaaaa-…0001`、`bbbbbbbb-…0002`）各自取到 `WO-000001` 并存；同租户同域重复编号被唯一约束拒绝；无租户上下文时 `tenancy_tenant` / `tenancy_business_number` 返回 0 行 |
| `frozen-block.txt` | 状态机与冻结门禁用例清单 | `freeze` 后业务写/插件/AI 三类执行分别返回 `423 TENANT_FROZEN`/`TENANT_PLUGIN_BLOCKED`/`TENANT_AI_BLOCKED`；`closing` 仍 423；`deleted` 410；冷静期未到期删除 409 `COOLING_OFF_ACTIVE` |
| `support-access.txt` | 支持访问规则与真实链路 | 非法工单/理由/时限 400；审批人等于申请人 403 `SUPPORT_SEPARATION_REQUIRED`；`enter` 返回 `readOnly=true` 与 `banner`；到期 403 `SUPPORT_ACCESS_EXPIRED`；删除后 410 |
| `platform-isolation.txt` | 平台域/租户域互斥与边界 | 平台缺运营头 401；平台带租户头 403；租户带平台头 403；支持访问会话非 GET 403；平台接口无业务正文端点 |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` | 7 份契约全部 PASS，新增 28 条路径与 20 个 schema 均向后兼容，基线已按治理流程重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 6 个模块报告，指令覆盖率 16.31%（covered=2022, missed=10374），基线 0.00%，`PASS` |

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install

# 真实 PostgreSQL：迁移、RLS、同号、状态机、连接池
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 契约与覆盖率门禁
node contracts/tools/contract-check.mjs
mvn -B -ntp -f backend/pom.xml verify `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'
node backend/tools/coverage-gate.mjs
```

> 本机工作区路径含中文（`新建文件夹`），JaCoCo agent 经 `cmd.exe` 分叉时会把 `destfile` 的 UTF-8 路径写坏，表现为 `Skipping JaCoCo execution due to missing execution data file`。用上面 `-Djacoco.destFile` 指向 ASCII 路径即可；CI 的 Linux 工作区不需要这个参数。

## 过程记录：失败与修复

**1. db-verify 第 5 步发现 `tenancy_profile` 缺少 RLS（真实缺陷）**

首次运行 `db-verify.ps1`，前面步骤全部通过，到第 5 步结构断言失败：

```
ERROR:  tenancy_profile 必须同时 ENABLE 与 FORCE RLS，实际 enable=f, force=f
```

原因：`V6__tenant_lifecycle.sql` 建了 `tenancy_profile`，但漏写 `ENABLE/FORCE ROW LEVEL SECURITY` 与隔离策略；其余 7 张新表都有。
修复：在 `tenancy_profile` 的 `COMMENT` 之后补上 ENABLE、FORCE 与 `tenancy_profile_isolation` 策略。
复测：重跑 `db-verify.ps1`，第 5 步通过，V6 校验和由 `-437376284` 变为 `-1364102595`（迁移内容变更的预期结果）。

**2. 切片测试枚举序列化为大写**

`TenantApiBoundaryTest` 首次运行断言 `$.tenant.status` 期望 `provisioning`，实际 `PROVISIONING`。
修复：`TenantStatus` 与 `SupportAccessStatus` 的 `wire()` 加 `@JsonValue`，对外统一小写线格式。
复测：8 个用例全部通过。

**3. 切片测试缺少统一错误体**

`tenantWriteRequiresSubject` 断言 `$.code` 为空，因为 `ProblemDetailsSupport` 被 mock 成返回 `null`。
修复：测试改为 `@Import(ProblemDetailsSupport.class)` 使用真实实现。
复测：`ACTOR_REQUIRED` 正常返回，8 个用例全部通过。

**4. `TenantLifecycleDbTest` 编译期泛型歧义**

`assertThat(tenantTransactions.inTenant(...))` 在结果类型未显式声明时同时匹配 `assertThat(Predicate)` 与 `assertThat(IntPredicate)`。
修复：先赋给 `List<TenantQuota>`、`List<String>`、`List<TenantDomain>` 等显式类型局部变量再断言。

## 边界

- 平台跨租户目录与平台控制台界面属 WP02-07；本包只提供平台域 API 与租户生命周期。
- 真实身份、权限与职责分离属 WP02-02/02-04/02-05；当前 `X-Platform-Operator-Id`、`X-Subject-Id` 为骨架占位。
- 最终删除只做状态、审计与域名/支持访问释放，业务数据物理清空属 WP02-08。
