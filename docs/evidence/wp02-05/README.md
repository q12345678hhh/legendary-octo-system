# WP02-05 证据索引

对应验收项：职责分离规则代码内置且不可由租户放宽；高权限授予必须双人（仅租户所有者可紧急直授并强制复核）；季度生成访问复核，逾期先升级、宽限期后自动暂停；管理员不能修改自身审计、批准自己的申请或兼任互斥角色；API、批量、后台作业与 AI 共用同一判定链。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`（20 个模块）；`SodRuleCatalogTest` 5/5；`AccessReviewApiTest` 5/5；`AuthorizationApiTest` 4/4 |
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 空库与升级库 V1–V10 校验和一致（V10 `323247044`）；57 张租户表 ENABLE+FORCE RLS；`91-separation.sql` 打印「WP02-05 职责分离数据库断言通过」；`AuthorizationSodDbTest` `Tests run: 1, Failures: 0, Errors: 0`；校验和门禁与连接池隔离 3/3 通过 |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` 与自测 | 7 份契约全部 PASS，自测 9/9；OpenAPI 1.6.0 新增 13 条路径、9 个 schema（`paths` 117→130，`schemas` 109→118），基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 9 个报告，指令覆盖率 15.75%（covered=6929, missed=37077），基线 0.00%，`PASS` |

数据库断言覆盖：申请人与批准人分离、同一待处理申请唯一、批准后回填 `source_request_id`、复核季度唯一、复核人不能是主体、管理员审计不可改删、跨租户引用拒绝与隔离、新租户自动播种复核队列。真实库用例覆盖：高权限无申请被拒、申请→他人批准→授权生效、自己批准被拒、静态角色冲突回滚、季度生成幂等、管理视角确认、逾期升级、宽限期后自动暂停与缓存版本递增。

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install `
  '-Djacoco.destFile=D:/DevCache/jacoco-${project.artifactId}.exec' `
  '-Djacoco.dataFile=D:/DevCache/jacoco-${project.artifactId}.exec'

# 真实 PostgreSQL：迁移、RLS、职责分离与季度复核
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 契约与覆盖率门禁
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```
