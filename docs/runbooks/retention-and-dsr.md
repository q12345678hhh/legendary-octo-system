# 删除、保留与数据主体请求运行手册

本文覆盖普通人删除（回收站）、法定留置、保留策略与个人信息删除请求（DSR）的日常操作与故障处理。
租户层面的关闭/冻结/注销仍见 [tenant-lifecycle.md](tenant-lifecycle.md)，两者互不替代。

## 1. 保留策略

三个键都在平台设置里维护，写入时校验合规下限，低于下限直接拒绝（`422 RETENTION_BELOW_MINIMUM`，响应给出 `minimum`）。

| 键 | 默认 | 合规下限 | 上限 | 说明 |
|---|---|---|---|---|
| `retention.audit_days` | 2555（7 年） | 2555 | 36500 | 审计**只读不删**；到期只写安全事件告警 |
| `retention.business_log_days` | 180 | 90 | 3650 | 业务日志保留期 |
| `retention.recycle_bin_days` | 30 | 7 | 90 | 回收站保留期 |

读取：`GET /platform/api/v1/retention/policy`（`platform.settings.read`）。
修改：`PUT /platform/api/v1/retention/policy`，请求体 `{"key":"retention.recycle_bin_days","value":30}`，
需要 `platform.settings.manage` 且完成 MFA。

**不要**为了「省空间」把 `retention.audit_days` 调低：审计表有防改删触发器，调低也不会删除任何记录，只会让告警提前出现。

## 2. 普通删除：判定顺序与错误码

删除按固定顺序判定，顺序不可调换：

1. 对象必须存在且属于当前租户 → 否则 `404 RETENTION_OBJECT_NOT_FOUND`。
2. 对象级留置优先，其次整类留置 → `409 RETENTION_LEGAL_HOLD`，响应正文带留置原因与 `holdId`。
3. 受保护数据类（`audit`、`payroll_result`）→ `409 RETENTION_PROTECTED`。
4. 已有生效回收站条目 → 直接返回原条目（幂等）。
5. 写入回收站，`purge_after = 删除时间 + retention.recycle_bin_days`。

当前接入的数据类：`file`（平台文件）与 `work_assistant_segment`（员工通过语音或文字录入的工时片段，`source_type` 仅 `voice` / `manual`；系统不做设备、屏幕或输入内容采集）。新增数据类时必须实现 `RetentionTarget` 并在此登记，否则删除会返回 `409 RETENTION_CLASS_UNSUPPORTED`。

回收站只存元数据快照（文件名、类型、大小、sha256、时间），不复制业务正文。

### 常见处理

- **用户问「为什么删不掉」**：`GET /api/v1/retention/holds` 查看生效留置；把 `reason` 原样告知。
- **需要临时阻止删除**：新增一条 `retention_legal_hold`（`object_id` 为空表示整类），无需改配置。
- **误删恢复**：`POST /api/v1/retention/bin/{id}/restore`。恢复只写回数据行，**不会重建授权、会话或 API 令牌**。恢复后该条目状态变为 `restored`，已不在回收站内，再次删除会新建一条回收站记录。
- **提前清理**：`DELETE /api/v1/retention/bin/{id}`。先写 `purged` 墓碑，再删对象与元数据，可重复调用。
  只接受 `active` / `anonymized` 条目；对 `restored` 条目直接清空返回 `409 RETENTION_ITEM_NOT_ACTIVE`，避免「数据已删、状态仍写着已恢复」。

## 3. 数据主体删除请求（DSR）

流程：租户侧发起 → 平台核验 → 批准/拒绝 → 执行匿名化 → 完成。

1. 发起：`POST /api/v1/privacy/requests`
   ```json
   { "subjectType": "person", "subjectId": "<uuid>", "reason": "员工离职后申请删除个人信息" }
   ```
   案件表只落主体哈希（`SHA-256(tenantId:subjectId)`），不落姓名与邮箱。
2. 查看：`GET /platform/api/v1/privacy/requests?status=received`（`platform.security.read`）。
3. 批准/拒绝：`POST .../{id}/approve` 或 `.../reject`，需要 `platform.security.manage` + MFA。
   拒绝必须给原因码：`LEGAL_HOLD`、`STATUTORY_RETENTION`、`ACTIVE_EMPLOYMENT`。
4. 执行：`POST .../{id}/execute`。执行前会复核业务关系：
   - 自然人不存在 → 案件标记 `failed / SUBJECT_NOT_FOUND`；
   - 仍在职 → `409 DELETION_BLOCKED_ACTIVE_EMPLOYMENT`，需先解除雇佣关系；
   - 通过 → 匿名化 `organization_person` 与关联 `identity_user_account`，写匿名化墓碑，案件 `completed`。

匿名化结果：显示名替换为 `匿名用户-<hash8>`，邮箱置空或替换为 `anon-<hash8>@invalid`，账号密码置空强制重新激活；
ID、状态、时间戳与组织结构关系保留，统计事实不受影响。

## 4. 备份传播：墓碑与重放

备份文件不可变，**不在备份内做物理擦除**。所有清理与匿名化都会写一条 `platform_deletion_tombstone`，
恢复演练或生产恢复后必须执行一次重放：

```bash
curl -X POST "$PLATFORM_HOST/platform/api/v1/retention/tombstones/replay?limit=1000" \
  -H "Cookie: plt_ops_session=<会话>" -H "X-CSRF-Token: <plt_ops_csrf>"
```

- 返回 `applied / skipped / failed` 三项计数；
- 每条结果写入 `platform_tombstone_replay`，可重复执行；
- `failed>0` 时按 `detail.error` 定位（通常是对象存储不可达），修复后重跑；
- 重放只处理 `purged`（file / work_assistant_segment）与 `anonymized`（person）两类墓碑。

**恢复验收标准**：重放后 `applied + skipped = 墓碑总数`，且 `failed = 0`。

## 5. 后台作业

| 作业 | 频率 | 记录 |
|---|---|---|
| `RetentionPurgeWorker` | 1 小时（首次延迟 5 分钟） | `platform_operation_run(kind='retention_purge')` |
| `RetentionDueScanner` | 24 小时（首次延迟 10 分钟） | `platform_security_event(category='retention_due')` |

清理失败不会丢数据：对象删除失败时元数据行保留，下一轮重试，避免「行没了对象还在」。
本地未配置对象存储凭据时只清元数据，生产必须配置 `platform.storage.*`。

## 6. 排障

| 现象 | 排查 |
|---|---|
| 删除一直 409 | 查 `retention_legal_hold` 是否有未释放的留置；确认数据类不是 `audit` / `payroll_result` |
| 回收站为空 | 确认请求租户上下文正确；RLS 会隐藏其他租户的条目 |
| 恢复报 `RESTORE_BLOCKED_ANONYMIZED` | 该对象已随 DSR 匿名化，属预期行为，不可恢复 |
| 重放 `failed` 增多 | 检查 MinIO 连通性与凭据；修复后重新调用重放 |
| 审计告警 `retention_due` | 属提示性质，按合规要求保留，不需要删除动作 |
