# WP02-08 证据索引

对应验收项：回收站到期清理、留置阻止删除、匿名化替换、删除请求案件闭环各有正负用例与审计记录。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `backend-tests.txt` | `mvn -B -ntp -f backend/pom.xml clean install` | `BUILD SUCCESS`（20 个模块，1:36）；boot-api 72 个用例；`RetentionAuthzTest` 6/6 |
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 空库与升级库 V1–V12 校验和一致；5i 段打印「WP02-08 删除、保留与数据主体请求数据库断言通过」；`RetentionDbTest` 3/3；`DeletionAnonymizeDbTest` 1/1；末尾全绿清单含「删除与保留」 |
| `node-tests.txt` | `node --test "tests/**/*.test.mjs"` | 42 个用例：41 通过、1 跳过、0 失败；含 4 条删除与保留不变量 |
| `contract-check.txt` | `node contracts/tools/contract-check.mjs` 与自测 | 7 份契约全部 PASS，自测 9/9；OpenAPI 1.8.0 新增 14 条路径、17 个操作（`paths` 149→163），基线已重冻结 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 8 个报告，指令覆盖率 14.45%（covered=7739, missed=45808），基线 0.00%，`PASS` |

## 四类验收对照

| 验收项 | 正例 | 负例 | 审计留痕 |
|---|---|---|---|
| 回收站到期清理 | `RetentionDbTest.expiredItemsArePurgedInBatch`：插入过期条目 → `purgeDue` 批量清理 → 状态 `purged`；`RetentionPurgeWorker` 每小时按租户上下文扫描 | `94-retention.sql`：`purge_after > deleted_at`、类级白名单、活跃条目部分唯一索引 | `retention_event`（`purged` / `expired=true`）+ `platform_deletion_tombstone` |
| 留置阻止删除 | 无留置时可入回收站（`recycleHoldRestoreAndPurge`） | 类级留置命中 → `409 RETENTION_LEGAL_HOLD` 且带原因；`94-retention.sql` 断言留置表 RLS 与约束 | `retention_event` 记录 `recycled`；留置本身只读展示于 `GET /retention/holds` |
| 匿名化替换 | `DeletionAnonymizeDbTest`：显示名替换为 `匿名用户-<hash8>`、邮箱置空、登录邮箱转 `anon-<hash8>@invalid`、密码置空、状态与统计事实保留、写 `anonymized` 墓碑 | 仍在职 → `409 DELETION_BLOCKED_ACTIVE_EMPLOYMENT`；主体不存在 → `failed / SUBJECT_NOT_FOUND` | `platform_deletion_tombstone`（`action='anonymized'`）+ `platform_audit_event`（`privacy.request.execute`） |
| 删除请求案件闭环 | `RetentionDbTest`：`received→verifying→approved→executing→completed`；撤回 `cancelled` | 已完成案件再判定、撤回后执行、拒绝缺原因码、跨租户撤回均为 `409/400/404` | 案件表结论字段 + 平台审计；主体只存 `SHA-256(tenantId:subjectId)` |

## 数据库与静态不变量

- `tests/db/94-retention.sql`：三张租户表强制 RLS、三张平台表不启用 RLS；跨租户不可见；活跃唯一索引、到期时间、受保护类拒绝、留痕与墓碑改删触发 `P0001`；作业类型与设置键白名单；保留策略预置值与合规下限。
- `tests/db/40-integrity.sql`：受 FORCE RLS 保护的租户表 57→60。
- `tests/db/92-cross-tenant.sql`：逐表 UUID 猜测矩阵 58→61。
- `tests/security/isolation-invariants.test.mjs` 新增 4 条：回收站/案件只用服务端租户上下文；平台删除判定与执行必须 `manage` 权限；保留事件与墓碑只允许追加；恢复不得重建授权或身份。

## 复现命令

```powershell
# 后端全量构建与测试
mvn -B -ntp -f backend/pom.xml clean install

# 真实 PostgreSQL：迁移、RLS、跨租户、平台运营端、删除与保留
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# Node 不变量、契约与覆盖率门禁
node --test "tests/**/*.test.mjs"
node contracts/tools/contract-check.mjs
node --test contracts/tools/contract-check.test.mjs
node backend/tools/coverage-gate.mjs
```
