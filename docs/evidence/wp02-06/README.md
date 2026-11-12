# WP02-06 证据索引

对应验收项：多租户攻击测试——两个「看起来一样」的租户（同名、同姓名、同邮箱、同工号、同文件名，仅 UUID 不同）互相尝试读取、修改、删除与横向越权，SQL、服务层、HTTP 头三层都必须失败；同时验证连接复用、异常回滚、只读事务与缺失/伪造租户变量不会造成上下文泄漏。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `db-verify.txt` | `scripts/db-verify.ps1` 完整输出 | 58 张受 FORCE RLS 保护的租户表逐表 UUID 猜测矩阵全灭；`92-cross-tenant.sql` 打印「WP02-06 跨租户数据库断言通过」；`CrossTenantAttackDbTest` `Tests run: 1, Failures: 0, Errors: 0`（17 个服务层用例）；连接池隔离 3/3；校验和门禁通过 |
| `backend-tests.txt` | `mvn clean install` 全量构建与测试 | `BUILD SUCCESS`（20 个模块）；`TenantHeaderMismatchTest` 5/5 |
| `node-tests.txt` | `node --test "tests/**/*.test.mjs"` | 35 个用例，34 通过、1 跳过（E2E 未配置入口）、0 失败；含 4 条新增静态不变量 |
| `coverage-gate.txt` | `node backend/tools/coverage-gate.mjs` | 9 个报告，指令覆盖率 15.72%（covered=6917, missed=37089），基线 0.00%，`PASS` |

数据库断言覆盖：身份的 `is_superuser=off` / `row_security=on` / 非 BYPASSRLS 前置检查；从 `pg_policies` + `pg_class.relforcerowsecurity` 自动枚举的 58 张租户表逐表「B 上下文取主键 → A 上下文 SELECT/UPDATE/DELETE 必须 0 行」；同名/同邮箱/同工号的列表、搜索、批量与导出；附件列表、详情、预签名与对象键前缀；作业；授权、决策日志、复核、申请与管理员审计；空串、布尔注入、非法 UUID、不存在的租户变量；连接复用与 `RESET ALL`；异常子事务回滚；`BEGIN READ ONLY`；跨租户写入被 RLS 拒绝。

真实库服务层用例：详情、同名列表、同工号、批量、导出、附件列表/详情/预签名/上传键、缓存版本、作业取消、Outbox 显式过滤、季度复核、访问申请、授权撤销、管理员审计、缓存元数据，共 17 例全部被拒。HTTP 头用例：租户头与会话不一致、缺失头、非法 UUID、伪造 `X-Subject-Id`/`X-Actor-Id`、租户域带平台操作员头，共 5 例。

已知项与设计例外：

- `platform_outbox` 故意不启用 RLS（Worker 必须跨租户投递），隔离责任落在「查询必须显式带 `tenant_id`」；用例改为断言该表无 RLS 且按 `tenant_id` 过滤的结果正确，不用「跳过」掩盖。
- `platform_webhook_delivery` 尚未实现，脚本以 `RAISE NOTICE` 显式登记，Webhook 攻击面当前由 Outbox/作业用例替代。

复现命令：

```powershell
# 后端全量构建与测试
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
mvn -B -ntp -f backend/pom.xml clean install

# 真实 PostgreSQL：迁移、RLS、跨租户攻击套件
powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 跨组件测试与覆盖率门禁
node --test "tests/**/*.test.mjs"
node backend/tools/coverage-gate.mjs
```
