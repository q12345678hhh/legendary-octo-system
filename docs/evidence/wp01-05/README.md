# WP01-05 证据：Windows 工作助手技术骨架

生成方式：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\work-assistant-verify.ps1
```

脚本在本机 API + WSL 内 PostgreSQL 上跑完整链路，失败即中断，不会产出「警告后通过」的结论。

## 验收项与证据

| 验收项 | 证据文件 | 关键结论 |
| --- | --- | --- |
| 托盘状态、显式开始/暂停/结束、私人模式、不可隐藏的采集提示 | [indicator.txt](indicator.txt)、[capture-gating.txt](capture-gating.txt) | 未开始时菜单只有「开始记录/退出」；记录中、暂停、私人模式都强制显示置顶提示且不提供退出；菜单不含隐藏、静默入口 |
| 员工未开始或已暂停时采集量为 0 | [capture-gating.txt](capture-gating.txt)、[device-registration.txt](device-registration.txt) | 状态机与 `CaptureGate` 双层拦截；真实链路中暂停后 `RecordActivity` 返回 false 且队列保持 0 |
| 设备注册、设备密钥、策略签名验证、版本报告、撤销、更新回滚 | [device-registration.txt](device-registration.txt)、[policy-signature.txt](policy-signature.txt)、[device-revocation.txt](device-revocation.txt)、[update-rollback.txt](update-rollback.txt) | 注册返回可验签策略；篡改/未知字段/keyId 不符/版本回退全部拒绝；撤销后上传 403 DEVICE_REVOKED；健康检查失败自动回滚 |
| Windows 安全存储保护本地缓存、离线队列、过期删除、崩溃恢复 | [secure-storage.txt](secure-storage.txt)、[offline-queue.txt](offline-queue.txt)、[crash-recovery.txt](crash-recovery.txt) | DPAPI 加密后落盘无明文，换设备熵无法解密；队列 72 小时过期删除；上传成功未删本地条目时重启后重传被 `client_segment_id` 去重 |
| 不提供管理员远程秘密开启接口；策略只能收窄或增加排除 | [policy-signature.txt](policy-signature.txt) | `/capture` 不存在（404）；整体覆盖策略与未知字段一律 422 `POLICY_UNKNOWN_FIELD`；无设备签名的拉取/上传 400 |
| 数据库迁移与 RLS | [db-migration.txt](db-migration.txt)、[db-verify.txt](db-verify.txt) | V5 在真实库应用；三张新表 `rows=1 rls=true force=true`；全量迁移、并发与锁等待检查通过 |
| 构建门禁 | [desktop-build.txt](desktop-build.txt)、[offline-checks.txt](offline-checks.txt) | `dotnet build -c Release` 零警告（警告即错误）；离线自检 53 项全部通过 |

## 真实链路结果

1. **注册**：`POST /api/v1/work-assistant/devices/register` 返回 ECDSA P-256 签名策略，
   客户端按固定公钥验签并把服务端追加项与本地硬编码下限取并集。
2. **补传**：开始记录后产生 2 条离线片段，`FlushAsync` 上传 2 条并清空队列。
3. **暂停零采集**：暂停后 `RecordActivity` 返回 false，队列保持 0，提示继续可见。
4. **崩溃恢复**：上传成功但保留本地条目 → 新进程 `Recover()` 保留 1 条 → 重传被服务端判定重复，
   数据库中该 `client_segment_id` 只有一行。
5. **撤销**：撤销后待传条目上传被 403 拒绝，本地队列清空，采集状态立即回到 Stopped；
   直接上传同样返回 403 `DEVICE_REVOKED`。
6. **数据库终态**：`work_assistant_segment` 3 行（注册 2 + 崩溃 1，重传去重），
   设备 `revoked|true|<操作者>`，租户策略版本 2。

## 过程中发现并修复的问题

- **策略读取 500**：`PolicyStore.tenantPolicy` 使用了 `JdbcTemplate.query(sql, RowMapper)` 二参重载，
  SQL 里的 `?` 没有被绑定，PostgreSQL 报 `syntax error at end of input`。
  改为把租户参数作为最后一个可变参数传入后，注册与策略接口恢复正常。
- **同机多租户密钥互相覆盖**：设备私钥原先固定存放在 `device.key`，
  切换租户会尝试用另一个租户的熵解密。改为按 `sha256(tenantId)` 前 16 位分目录存放。
- **非撤销类设备错误会中断补传**：`FlushAsync` 原先只捕获撤销与服务端异常，
  策略刚被追加导致的 409 会向上抛出。改为保留队列、下个周期重试。

## 边界与遗留

- 真实的窗口与进程采集器未在本工作包接入：`CaptureGate`、排除策略与状态机已可独立验证，
  采集源接入时会复用同一门禁。
- 更新安装器以 `IUpdateInstaller` 接口表示，仓库内只有安装/回滚编排与验签，
  不包含真实安装包解压与 MSI 执行。
- 服务端策略固定 `uploadEnabled=true`；关闭上传属于客户端本地能力，
  用于企业侧临时止损，不依赖服务端下发。
- 托盘壳的图标使用系统默认信息图标，正式发布前需要替换为产品图标。
