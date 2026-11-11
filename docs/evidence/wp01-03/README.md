# WP01-03 证据索引

对应验收项：迁移规则与校验和门禁、上一版本升级与锁等待评估、真实 PostgreSQL 上的迁移/RLS/并发/索引/连接池、加密备份与隔离恢复、RPO/RTO 方法与最小恢复演练。

## 文件

| 文件 | 内容 | 关键结果 |
|---|---|---|
| `db-verify.txt` | `scripts/db-verify.ps1` 的完整输出（8 个步骤） | 空库 3 个版本校验和 `1|-421171951`、`2|-1218073849`、`3|-1157903501`；RLS 9/9；并发认领两行不同；50 万行灌入 3s；V2→V3 迁移 3.5s、探针 1501 条语句最慢 16.7ms、超 1s 的 0 条；索引命中 `platform_outbox_dispatch_idx`；篡改 checksum 后 `validate` 退出码 1、还原后通过；连接池用例 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` |
| `backup.txt` | `scripts/db-backup.ps1` 的输出 | 密文 12,864 字节、SHA-256 `4ded3818…`、明文 SHA-256 `bf00eabc…`；上传 MinIO 后 `mc stat` 返回 size 12864 与 etag；恢复基准 tenants=2 outbox=4 md5 `30ee01d6…` flyway=3 |
| `manifest.sample.json` | 那次备份的清单原文 | 加密参数、两个哈希、对象键、恢复基准、对象存储核对结果 |
| `restore-drill.txt` | `scripts/db-restore-drill.ps1` 的输出 | 截断密文解密 `exit=1`（`wrong final block length`）；正式密文哈希与清单一致；`--network none` 隔离实例 `pg_restore` 0.3s；四项一致性校验全部通过；全流程 4s |

复现命令：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\db-verify.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-backup.ps1
powershell -ExecutionPolicy Bypass -File scripts\db-restore-drill.ps1
```

## 过程记录：失败与修复

验收要求「一次失败恢复演练有修复和复测证据」。本轮共出现三次真实失败，全部保留处置过程：

**1. 恢复演练首次执行失败（脚本缺陷，非备份问题）**

第一次运行 `db-restore-drill.ps1`，第 1 步「损坏密文必须被拒绝」判定正确通过后，打印错误摘要的那一行崩溃：

```
== 1 失败样本：损坏密文必须被拒绝
  解密按预期失败（exit=1）
db-restore-drill.ps1 : A parameter cannot be found that matches parameter name 'join'
    + FullyQualifiedErrorId : NamedParameterNotFound,db-restore-drill.ps1
Command failed with exit code 1
```

原因：`Select-Object -First 2 -join ' '` 把 `-join` 当成了 `Select-Object` 的参数。修复为先取前两行再聚合（`(… | Select-Object -First 2) -join ' '`），重跑后完整通过，即 `restore-drill.txt`。

**2. 证据文本中文变成问号**

`db-verify.txt` 首版里 `RLS ???????9/9?`、`????????????` 这类行是乱码：Windows PowerShell 5.1 按系统代码页解码原生命令输出。已把 `[Console]::OutputEncoding` 设为 UTF-8 后重跑，日志恢复可读。

**3. 修编码时引入管道 BOM**

把 `$OutputEncoding` 直接设成 `[System.Text.Encoding]::UTF8` 会带 BOM，SQL 经管道进入 psql 后报 `syntax error at or near "﻿SELECT"`。改为 `New-Object System.Text.UTF8Encoding($false)`（无 BOM）后通过。

**4. 备份基准一开始没有意义**

首次备份时开发库只有 4 条 outbox、0 个租户，`restoreBaseline` 的 `tenants=0`，恢复校验等于没校验。补入两个种子租户（`tests/db/01-seed-bulk.sql` 用的同一组固定 UUID）后重新备份，基准变为 tenants=2、md5 `30ee01d6…`，`manifest.sample.json` 就是这次的结果。

## 口径说明

- 锁等待评估基于 50 万行 outbox 的开发容器，只证明写法在该量级不阻塞写入；GA 前按 NFR-23 在生产同构容量重跑，结论随 WP11-05 给出。
- 恢复演练是「空实例 + 样本数据」，不含 WAL 归档、对象存储附件、密钥轮换与审计链，不宣称满足 NFR-08 的 RPO ≤ 15 分钟 / RTO ≤ 4 小时，见 `docs/runbooks/backup-restore.md` 第 5.3 节。
- `platform_outbox` 暂未启用 RLS，权限由 WP01-04 的 dispatcher 角色治理。
