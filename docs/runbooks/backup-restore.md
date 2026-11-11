# 备份与恢复手册

本文件是 WP01-03 的交付物之一：备份脚本规范、加密与密钥、对象存储清单、恢复到隔离环境的步骤与一致性校验，以及 RPO/RTO 的计算方法。**本阶段只完成最小恢复演练**，不含 WAL 归档与生产容量，结论不作为 GA 恢复指标（那部分在 WP11-05 按 NFR-08 验收）。

## 1 备份规范

| 项 | 取值 | 理由 |
|---|---|---|
| 范围 | 整库逻辑备份 | 恢复演练要求「一个文件还原出完整可用实例」；按表备份无法保证外键与序列一致 |
| 格式 | `pg_dump -Fc`（自定义格式） | 支持 `pg_restore` 并行与选择性恢复，且自带压缩 |
| 加密 | AES-256-CBC，PBKDF2-HMAC-SHA256、20 万次迭代、随机盐 | 与 `openssl enc` 默认兼容，便于在任何 Linux 上离线解密 |
| 明文落地 | 不落地 | dump 在容器内生成后立即加密并删除明文，宿主机上不存在未加密副本 |
| 校验 | 密文与明文各记一份 SHA-256 | 密文哈希用于判断文件在传输/存储中是否损坏，明文哈希用于确认解密结果就是当初的 dump |
| 存放 | 本地 `var/backups/`（已 gitignore）+ MinIO 桶 `platform-backups` | 本地留副本便于演练，对象存储是恢复时的权威来源 |
| 清单 | 同目录 `*.manifest.json` | 恢复程序按清单比对，而不是靠人工记忆 |
| 口令 | 生产用 KMS/密钥服务；本地演练读 `var/backups/.passphrase` | 口令不得进仓库、不得写进 compose 文件、不得放进镜像 |

执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\db-backup.ps1
```

脚本依次完成：容器内 `pg_dump -Fc` → 明文 SHA-256 → `openssl` 加密 → 删除明文 → `docker cp` 取回密文 → 计算密文 SHA-256 → 采集恢复基准 → 上传 MinIO 并 `mc stat` 核对对象 → 写 manifest。

## 2 清单字段（`*.manifest.json`）

| 字段 | 含义 |
|---|---|
| `createdAt` | UTC 时间，ISO-8601 |
| `database` / `sourceContainer` | 备份来源 |
| `pgServerVersion` / `flywayVersion` | 源库版本与迁移版本，恢复后必须一致 |
| `backupFile` | 密文文件名 |
| `encryption` | 算法、KDF、迭代次数、盐的来源 |
| `files[].objectKey` | 对象存储位置 `s3://<bucket>/<key>` |
| `files[].sizeBytes` / `cipherSha256` / `plainSha256` | 大小与两个哈希 |
| `restoreBaseline` | 恢复后要比对的行数与聚合摘要（见第 4 节） |
| `objectStorage.verifiedBy` | 上传后 `mc stat --json` 的实际返回，作为对象确实存在的证据 |

## 3 恢复到隔离环境

### 3.1 演练脚本（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\db-restore-drill.ps1
```

脚本刻意先失败后成功，顺序固定：

1. **失败样本**：把正式备份截断一半，用同一口令解密，必须以非零退出（`wrong final block length`）。证明损坏的密文不会被静默接受。
2. **修复核对**：重新计算正式备份的 SHA-256，必须与清单的 `cipherSha256` 一致。
3. **隔离恢复**：拉起 `--network none` 的独立 `postgres:16` 容器（不接任何网络、不复用开发卷），解密 → `createdb` → `pg_restore --no-owner --no-privileges --exit-on-error`。
4. **一致性校验**：比对第 4 节全部指标，任何一项不符即失败。

`-KeepContainer` 可保留现场。演练结束默认删除容器。

### 3.2 手工恢复（脚本不可用时）

```bash
# 1 校验密文
sha256sum backup.dump.enc        # 与 manifest 的 cipherSha256 比对

# 2 解密（口令从 KMS 或密钥文件取，不要写进命令行历史）
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -pass env:BACKUP_PASSPHRASE -in backup.dump.enc -out backup.dump

# 3 还原到隔离实例
createdb -h <isolated-host> -U postgres platform
pg_restore -h <isolated-host> -U postgres -d platform \
  --no-owner --no-privileges --exit-on-error backup.dump

# 4 复核（见第 4 节）
```

`--no-owner --no-privileges` 是必须的：备份里的属主是源环境的 `platform_owner`，在隔离实例上可能不存在，带上它会因角色缺失中断。

### 3.3 切流的判断

恢复到隔离实例**不等于**可以切流。切流前至少要：应用在新库上通过健康检查与冒烟写入；确认 `flyway_schema_history` 的 `success` 全为 `true`；确认没有遗留的长事务；记录恢复时刻与丢失窗口。本阶段没有生产环境，切流步骤只在 WP11-05 的正式演练中执行。

## 4 一致性校验清单

| 指标 | SQL | 为什么需要 |
|---|---|---|
| 迁移版本 | `SELECT max(version) FROM flyway_schema_history WHERE success;` | 结构版本不对，应用可能起不来或行为错误 |
| 迁移全成功 | `SELECT count(*) FROM flyway_schema_history WHERE NOT success;` 应为 0 | 失败记录会让后续迁移拒绝执行 |
| 租户行数 | `SELECT count(*) FROM tenancy_tenant;` | 最基础的数据量核对 |
| 业务表行数 | `SELECT count(*) FROM platform_outbox;` | 异步链路的数据是否齐 |
| 聚合摘要 | `SELECT md5(string_agg(id::text || ':' || slug, ',' ORDER BY id)) FROM tenancy_tenant;` | 行数相同不代表内容相同；排序后聚合能同时发现内容差异与顺序异常 |

`restoreBaseline` 里的 `tenancy_tenant.md5_ordered` 就是第三项，恢复后必须逐字符一致。

## 5 RPO / RTO 计算方法

### 5.1 定义与公式

- **RPO（恢复点目标）**：允许丢失的时间窗。逻辑备份模式下
  `RPO ≈ 备份间隔 + 备份执行耗时/2`（最坏情况是刚做完备份就故障，最好情况是故障前一刻刚备份）。
- **RTO（恢复时间目标）**：从确认故障到服务恢复的时长。
  `RTO = 发现与决策 + 准备隔离实例 + 传输与解密 + pg_restore + 一致性校验 + 切流验证`。

### 5.2 本阶段实测（样本量，不代表生产）

| 环节 | 实测 |
|---|---|
| 备份产物 | 12,864 字节密文（2 个租户、4 条 outbox 的开发库） |
| 上传 MinIO | `mc stat` 返回 size 与 etag，与清单一致 |
| 隔离实例启动 | 90 秒超时内就绪（实测数秒） |
| `pg_restore` | 0.3 s |
| 全流程（损坏样本被拒 → 恢复 → 四项校验通过） | 4 s |

这些数字只证明**流程可执行、校验有效**，不能当作生产 RTO：数据量、索引规模、网络与对象存储带宽都会主导真实耗时。

### 5.3 与目标的差距（必须写明）

`PLAN.md` §2.6 的目标是 RPO ≤ 15 分钟、RTO ≤ 4 小时。当前实现是**定时逻辑备份 + 手工触发**：

- 备份间隔若按每日一次，RPO 是以小时计，**不满足 15 分钟**。要满足必须补齐 WAL 归档与 PITR（连续归档 + 基础备份），这属于 WP11-05。
- RTO 目前只有流程级证据，缺少生产容量下的计时，**不能宣称达标**。
- 本阶段结论：**备份链路、加密、清单与恢复校验闭环成立；RPO/RTO 指标未验收。**

## 6 本阶段限制

1. 只备份数据库，不含对象存储附件；附件与数据库的一致性（预签名上传、孤儿文件清理）随 WP09。
2. 密钥只有一个版本：没有轮换、没有多版本解密回退，密钥生命周期随 WP11-05。
3. 没有审计链校验：备份与恢复操作没有独立的审计留痕。
4. 演练库是空实例恢复，不覆盖「已有数据上的部分恢复」与跨版本恢复。
5. MinIO 附件桶的版本化与对象锁未启用。

## 7 故障处理

| 症状 | 原因 | 处置 |
|---|---|---|
| 解密报 `bad decrypt` / `wrong final block length` | 密文损坏或口令不对 | 先比对 `cipherSha256`；不一致就换副本，一致则查口令来源 |
| `pg_restore` 报 `role "platform_owner" does not exist` | 未加 `--no-owner` | 按 3.2 的命令加参数重跑 |
| `pg_restore` 报 extension 不存在 | 目标实例缺扩展 | 在隔离实例先 `CREATE EXTENSION`，再重跑 |
| 校验行数少 | 备份在执行中被截断，或恢复到错误库 | 用 `pg_restore -l` 检查 TOC，确认恢复到 `platform` 库 |
| 聚合摘要不一致但行数一致 | 内容被改写（例如大小写、编码） | 视为备份不可信，回到源库重新备份并记录事件 |
