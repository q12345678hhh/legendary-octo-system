# 尖峰 A 报告：PostgreSQL RLS 默认拒绝

| 项目 | 内容 |
|---|---|
| 任务 | T00-03-1 |
| 日期 | 2026-11-09 |
| 结论 | **通过（有条件）**：默认拒绝成立，但有 4 条必须固化的约束，未固化即视为不通过 |
| 证据 | `docs/evidence/spike-a/verify-output.txt`（首次）、`verify-output-ubuntu.txt`（持久环境复跑，结果一致） |
| 脚本 | `tests/security/spike-a/01-schema.sql`、`02-verify.sql` |
| 决策 | `docs/adr/ADR-0002-rls-tenant-context.md` |

## 1. 环境与方法

- PostgreSQL **16.15**（Alpine / musl），端口 5433，套接字 `/tmp`。
- 三个角色：`platform_owner`（超级用户，仅用于 DDL）、`spike_owner`（**非超级用户**表所有者）、`spike_app`（运行角色）。
- 表 `spike_a.employee`：3 行，A 租户 2 行、B 租户 1 行。
- 策略：`USING / WITH CHECK` 均为 `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`。
- 同时开启 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`。
- 每个用例都包含正向验证与绕过尝试。

**环境偏差（需记录）**：原计划的 Docker 容器路径不可用——Docker Hub 鉴权端点返回 EOF、五个镜像源均不可达、Docker Desktop 引擎随命令会话退出；`wsl --install` 亦因微软 CDN 不可达失败。改为导入 Ubuntu 24.04.5 最小根文件系统（清华镜像）建立本地发行版 `pglab`，apt 安装 PostgreSQL 16.15，数据目录 `/var/lib/postgresql/spike` 位于 WSL vhdx，**可持久**，启动命令 `wsl -d pglab -u root /usr/local/bin/pgup`。该环境已用于复跑，11 个用例结果与首次一致。

这不改变 PLAN §13.2 的 Docker Compose 开发基线；Docker 可用性仍是 G1 阻断项，但数据库类尖峰与迁移验证不再被阻塞。

## 2. 结果

| 用例 | 场景 | 预期 | 实测 | 结论 |
|---|---|---|---|---|
| CONTROL 1 | 超级用户 + FORCE，无租户上下文 | 3 | 3 | 超级用户绕过 RLS，**运行角色绝不能是超级用户** |
| CONTROL 2 | 非超级用户所有者 + FORCE，无上下文 | 0 | 0 | 默认拒绝成立 |
| CONTROL 3 | 非超级用户所有者 + 取消 FORCE | 3 | 3 | **FORCE 是强制的**，缺它就等于没有隔离 |
| CASE 1 | 运行角色无上下文查询 | 0 | 0 | 默认拒绝成立 |
| CASE 1b | 运行角色无上下文插入 | 报错 | `new row violates row-level security policy` | `WITH CHECK` 生效 |
| CASE 2 | 上下文=A，伪造插入 B 租户 | 报错 | 同上 | 写路径无法越权 |
| CASE 3 | 上下文=A，可见总数 | 2 | 2 | 仅见本租户 |
| CASE 3b | 上下文=A，显式过滤 B | 0 | 0 | 显式条件也无法穿透策略 |
| CASE 4 | 会话级 GUC 跨事务保留 | 2 / 1 / 2 | 2 / 1 / 2 | `SET LOCAL` 在提交后回退到会话值，**会话值本身会残留** |
| CASE 5 | `RESET` 后查询（硬化策略） | 0 | 0 | 使用 `nullif` 后不再抛类型错误 |
| CASE 6 | 事务内 `SET` 后提交 | 2 | 2 | **连接池复用会串租户**，这是真实泄漏路径 |

`verify_exit=0`，无未预期错误。

## 3. 四条必须固化的约束

1. **运行角色不得是超级用户**；表所有者与运行角色分离，并对所有租户表 `FORCE ROW LEVEL SECURITY`。CONTROL 1 与 CONTROL 3 分别证明了缺少任一条的后果。
2. **策略必须写成 `nullif(current_setting('app.tenant_id', true), '')::uuid`**。裸写 `''::uuid` 会在 `RESET` 后抛类型错误——虽然仍是拒绝，但表现为 500 而非空结果，且会把内部错误暴露到接口层。
3. **租户上下文只能用 `SET LOCAL` 在事务内设置**，禁止会话级 `SET`；连接归还池前必须复位。CASE 6 证明 `SET` 会跨请求残留，连接池下等同串租户。
4. **无上下文即拒绝是正确行为，不是缺陷**。任何后台任务、迁移、运维脚本必须显式获得租户上下文，否则读不到数据——这一点必须在 WP01-03 的迁移规范里写明，避免运维误判为故障。

## 4. 对后续阶段的影响

| 影响项 | 具体要求 | 归属 |
|---|---|---|
| 数据库角色 | 建三个角色：迁移用超级用户、表所有者、运行角色；应用连接串只允许运行角色 | WP01-03 |
| 连接池 | 明确 `SET LOCAL` + 事务边界，或采用事务级池化并验证上下文不跨事务 | WP02-01 |
| 测试基线 | 本尖峰的两个 SQL 文件转为回归用例，纳入 `tests/security/` | WP01-02 |
| 所有租户表 | 建表模板必须同时包含 `ENABLE` + `FORCE` + `nullif` 策略 | WP01-03 |
| 运维脚本 | 需提供带上下文的运维入口，不允许手工裸连改数据 | WP11-06 |

## 5. 未覆盖项

- 连接池实测（PgBouncer / HikariCP 行为差异）留到 WP02-01。
- `UPDATE` 的 `USING` 与 `WITH CHECK` 双向约束未单独测，但机制相同。
- 分区表与继承表上的 RLS 行为未测，若后续引入分区需补测。
- 性能：策略对索引选择的影响未测，留到 WP11-03 容量基线。
