# 数据库迁移手册

本文件是 WP01-03 的交付物之一：写明版本规则、校验和门禁、回滚决策与生产锁评估。执行细节以脚本为准（`scripts/db-verify.ps1`、`backend/db/pom.xml`），本文件解释规则和判断口径。

## 1 单一线性历史

| 规则 | 落地方式 | 违反后果 |
|---|---|---|
| 迁移只有一个来源 | `backend/db/src/main/resources/db/migration` | 模块自建 `db/migration` 会让两套历史互相踩踏，代码评审直接拒绝 |
| 命令行与应用读同一份目录 | 插件用 `filesystem:${project.basedir}/src/main/resources/db/migration`，应用用 `classpath:db/migration` | 用 `filesystem` 是为了不先打包也能跑迁移与演练 |
| 版本号全局唯一且全序 | `V<整数>__<snake_case>.sql`，`outOfOrder=false` | 出现 4 和 5 之后再补 3 会直接被拒；确需补只能新开版本号 |
| 只追加，不改旧文件 | 已进入任何共享环境（CI、测试、预发、生产）的迁移文件视为只读 | 改动会改变 checksum，`validate` 失败，见第 2 节 |
| 一个迁移只做一件事 | 建表、加策略、加索引分开写 | 合并后无法单独定位失败点，回滚粒度也不够 |

新增迁移的检查清单：

1. 对象名带模块前缀（`tenancy_`、`platform_`），避免不同模块抢同一张表名。
2. DDL 用可重入写法（`IF NOT EXISTS`），但只在确有必要时使用——它能掩盖「上一个版本没按预期执行」。
3. 大表回填单独成迁移，按主键分批（每批 1 万行左右），不要在单个事务里扫全表。
4. 新增租户表必须套用第 5 节的 RLS 模板。
5. 本地先跑 `scripts/db-verify.ps1`，它同时覆盖空库和「上一版本 → 候选版本」两条路径。

## 2 校验和门禁

Flyway 对每个已执行迁移记录文件校验和。`validateOnMigrate=true` 表示：只要历史文件的字节变了，本次 `migrate` 和单独的 `validate` 都会失败，不会带着不一致的历史继续升级。

演练方式（`scripts/db-verify.ps1` 第 6 步，每次跑都会执行）：

```sql
UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '2';
```

随后 `flyway:validate` 必须以非零退出；把 checksum 改回去后 `validate` 必须重新通过。两条断言都成立才算门禁有效。

真实拦截时的处置顺序：

1. 先看 `flyway_schema_history` 里该版本的 `checksum` 与 `installed_on`、`installed_by`，确认是谁在什么时候执行过。
2. 用 `git log -- backend/db/src/main/resources/db/migration/<文件>` 找出改动提交，还原文件。
3. 如果无法还原（例如文件被误删后重建），**不要**手改 `flyway_schema_history`；按第 3 节的恢复路径处理，并在变更记录里写明原因。
4. 确认要调整的对象时，新增一个更高版本号的补偿迁移——历史版本只追加，不改写。

## 3 回滚决策

Flyway 社区版没有 `undo`，本项目也不引入反向脚本（反向脚本在生产上几乎总是没被验证过）。回滚一律是「前向修复 + 必要时恢复备份」：

| 现象 | 判断 | 处置 |
|---|---|---|
| 迁移中途报错，事务未提交 | 结构未变 | 修复脚本后重跑；先在升级演练库复现 |
| 迁移成功，但应用行为错误 | 结构已变，数据模型不兼容 | 优先追加补偿迁移；不可逆的破坏性变更（删列、改类型）按第 4 节规则本就不允许直接做 |
| 迁移成功且数据被错误改写 | 数据受损 | 从最近一次加密备份恢复到隔离实例，核对后按恢复手册切换；不能用 SQL 手工补 |
| 迁移执行时间超出窗口，业务被阻塞 | 锁问题 | 立即取消该语句（`pg_cancel_backend`），回退到「先加可空列/加 NOT VALID 约束、后补数据、再 VALIDATE」的分步迁移 |

硬性禁止：手工删除 `flyway_schema_history` 记录、修改 checksum、用 `flyway:repair` 掩盖失败。`repair` 只允许用于清理失败的残留记录，且必须在变更记录中写明。

## 4 生产锁评估

### 4.1 必须关闭事务级咨询锁

V3 的 `CREATE INDEX CONCURRENTLY` 不能在事务内执行。Flyway 默认把咨询锁放在一个未提交事务里，并发建索引会一直等这个事务，本地实测**永久挂起**（`pg_stat_activity` 里表现为 `idle in transaction` 与 `CREATE INDEX CONCURRENTLY` 互相等待 Lock/virtualxid）。

因此所有 Flyway 调用都带：

```
-Dflyway.postgresql.transactional.lock=false      # 命令行
spring.flyway.postgresql.transactional-lock=false # 应用内（application.yml）
```

对应迁移文件 `V3__outbox_retry_tracking.sql` 旁有 `.conf`，内容 `executeInTransaction=false`。

### 4.2 本阶段实测结论

样本：`platform_outbox` 50 万行（本地开发容器，PostgreSQL 16，WSL2），V3 执行期间另起会话持续做短事务写入（`tests/db/50-lock-probe-writer.sql`，1500 次插入）。

| 指标 | 实测 |
|---|---|
| 迁移（V2 → V3）耗时 | 3.4–3.7 s |
| 写探针语句数 | 1501 条 |
| 最慢单条语句 | 12–30 ms（多次运行波动） |
| 超过 1 s 的语句 | 0 条 |
| 写入错误 | 0 |
| 投递扫描执行计划 | `Index Scan using platform_outbox_dispatch_idx`，未退化为全表扫描 |

结论：`ADD COLUMN`（可空、无默认重写）+ `CREATE INDEX CONCURRENTLY` 的组合在该数据量下不阻塞写入。**这个结论只对本阶段样本成立**：GA 前按 NFR-23 在生产同构容量与真实并发下重跑同一脚本，脚本本身不需要改，只改数据量与目标库。

### 4.3 写法约束（避免长锁）

- 加列一律可空、无 `DEFAULT` 表达式；需要默认值就分两步（加列 → 回填 → 设默认）。
- 加索引一律 `CONCURRENTLY`，且配 `.conf` 的 `executeInTransaction=false`。
- 加约束用 `NOT VALID` 再 `VALIDATE CONSTRAINT`，避免长时间 `ACCESS EXCLUSIVE`。
- 改类型、重写表、`VACUUM FULL` 不在迁移里做，走独立的运维窗口。
- 大批量回填按主键分批，每批独立事务，避免长事务挡住 autovacuum。

## 5 租户表与 RLS

新建租户表必须照 `backend/db/templates/tenant-table.template.sql` 写，三件套缺一不可：

```sql
ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <table> FORCE ROW LEVEL SECURITY;   -- 表属主也受策略约束
CREATE POLICY <table>_isolation ON <table> FOR ALL
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- `USING` 与 `WITH CHECK` 必须同时写，否则只能挡读、挡不住写。
- `nullif(..., '')` 处理「变量已定义但为空串」的情况，否则会 `invalid input syntax for type uuid`。
- 运行期账号 `platform_app` 是 `NOBYPASSRLS`；`platform_owner` 只用于迁移，禁止给应用配置。
- 上下文必须用事务级 `SET LOCAL`。会话级 `SET` 会随连接池复用泄漏到下一个请求——`ConnectionPoolTenantContextTest` 把这条风险固定成了用例。

`platform_outbox` 刻意**没有**开 RLS：跨租户投递需要一个受治理的 dispatcher 角色，随 WP01-04 落地。在那之前，任何直接暴露 outbox 的接口都必须自己限定 `tenant_id`。

## 6 本地与 CI 执行

```powershell
# 完整验证：空库升级 → RLS → 并发 → 上一版本升级 → 索引 → 校验和门禁 → 连接池
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1

# 单独迁移某个库
mvn -B -ntp -f backend\db\pom.xml flyway:migrate `
  -Dflyway.url=jdbc:postgresql://<host>:5432/platform `
  -Dflyway.user=platform_owner -Dflyway.password=<口令> `
  -Dflyway.postgresql.transactional.lock=false
```

注意事项：

- 本机 Docker 跑在 WSL 发行版 `pglab` 里且是 NAT 模式，宿主机 `localhost` 不转发到发行版。脚本会自动取 `wsl -d pglab -u root -- hostname -I` 的 IP 作为 JDBC host；手工执行时也必须用这个 IP。
- 脚本强制 `WSL_UTF8=1` 并把控制台输出编码设为 UTF-8（管道编码必须无 BOM，否则 psql 会把 BOM 当成 SQL 第一个字符）。
- 演练库用完即删；`-KeepDatabases` 可保留现场排查。
- CI 的 `db` 作业用 `postgres:16` 服务容器重跑同一脚本，不复用开发机状态。

## 7 故障排查

| 症状 | 原因 | 处置 |
|---|---|---|
| `flyway:validate` 报 checksum mismatch | 历史文件被改 | 还原文件；确需变更就新增版本 |
| `migrate` 卡在 `CREATE INDEX CONCURRENTLY` | 事务级咨询锁未关闭 | 加 `transactional-lock=false`，见 4.1 |
| `permission denied for table ...` | 运行期账号缺授权 | 新建库后补 `GRANT ... ON ALL TABLES IN SCHEMA public`，脚本已内置 |
| RLS 用例全返回 0 行 | 未设 `app.tenant_id`，或账号是 `BYPASSRLS` | 检查上下文与账号属性 |
| `invalid input syntax for type uuid: ""` | 上下文设成了空串且策略未用 `nullif` | 按第 5 节模板改策略 |
