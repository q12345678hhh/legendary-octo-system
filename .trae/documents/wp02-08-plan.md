# WP02-08 删除、保留与数据主体请求实施方案

## Context

PLAN §5.2 要求交付「回收站 + 留置 + 保留策略 + 数据主体请求」，但当前仓库只有租户级生命周期删除：
`closing`（冷静期默认 30 天）→ `deleted`，仅改状态并写 `tenancy_lifecycle_audit`，不清理任何业务数据；
删除后由 [TenantExecutionGate.java](file:///D:/projects/temp_20261109_01/backend/modules/tenancy/src/main/java/com/acme/platform/tenancy/TenantExecutionGate.java) 返回 `410 TENANT_DELETED`。
全仓搜索 `retention` / `legal_hold` / `anonymize` / `data subject`，除 PLAN.md 的概念描述外**没有任何实现**。

**验收口径：** 回收站到期清理、留置阻止删除、匿名化替换、删除请求案件闭环各有正负用例与审计记录。

**已确认范围（用户决策）：**

| 议题 | 决策 |
|---|---|
| 回收站覆盖 | 建**通用机制**，先把 `platform_file` 与 `work_assistant_segment` 接进来，后续模块复用 |
| DSR 受理 | **租户侧发起 + 平台审批执行**，两侧都留痕 |
| 审计保留 | **只读不删**，7 年保留只做配置、合规下限校验与到期告警；业务日志按 180 天清理 |

---

## 一、现状与差距

| 能力 | 现状 | 差距 |
|---|---|---|
| 回收站 | 只有租户状态 `deleted`；业务对象删除即物理删除或不存在删除端点 | 无 `retention_bin_item`、无 30 天到期、无恢复 |
| 法定留置 | 无 | 无 hold 表、无删除前判定、无明确阻止原因 |
| 受保护数据类 | 审计表用触发器禁止 UPDATE/DELETE（V11） | 无「工资结果/审计不允许普通删除」的数据类守卫 |
| 保留策略 | 无 | 无配置键、无合规下限、无到期扫描 |
| DSR | 无 | 无案件表、无状态机、无匿名化、无备份传播 |
| 备份传播 | 备份不可变：`db-backup.ps1` 加密 pg_dump + MinIO 清单；`db-restore-drill.ps1` 隔离恢复 | 恢复会把已删除/已匿名化内容带回；缺墓碑与重放 |
| 审计留痕 | `tenancy_lifecycle_audit`（租户级）、`platform_audit_event`（平台级，不可改删） | 回收站/DSR 动作未接入 |
| 作业记录 | `platform_operation_run.kind` CHECK 只允许 5 种 | 缺 `retention_purge` / `tombstone_replay` |
| 契约 | OpenAPI 1.7.0，149 路径 / 144 schema | 新端点需升 1.8.0 并重冻结基线 |

---

## 二、总体设计

### 2.1 普通删除的判定顺序（固定，不可调换）

1. 对象必须存在且属于当前租户（否则 404，不泄漏存在性）。
2. **对象级留置** → **类级留置**：命中即 `409 RETENTION_LEGAL_HOLD`，响应带 `holdReason`、`holdUntil`、`holdId`。
3. **受保护数据类**：`audit`、`payroll_result` → `409 RETENTION_PROTECTED`，响应带 `dataClass` 与 `retentionReason`。
4. 幂等：同对象已有 `active` 回收站项时直接返回原项，不新建。
5. 写 `retention_bin_item`（`snapshot` 存删除时刻元数据），对象标记为已回收，`purge_after = now() + retention.recycle_bin_days`。
6. 写 `tenancy_lifecycle_audit`（`action` 前缀 `retention.`），审计不可改删。

### 2.2 DSR 案件状态机

```
received → verifying → approved → executing → completed
                    ↘ rejected           ↘ failed(可重试)
received/verifying → cancelled（申请人撤回）
```

判定顺序（`verifying` 阶段固定执行，任一步命中即给出 `decisionCode`）：

| 顺序 | 检查 | 结果 |
|---|---|---|
| 1 | 是否存在生效法定留置 | `rejected` / `LEGAL_HOLD` |
| 2 | 是否属于工资结果等法定保存义务 | `rejected` / `STATUTORY_RETENTION`（可部分匿名化） |
| 3 | 是否存在在职雇佣关系 | `rejected` / `ACTIVE_EMPLOYMENT`（先解除关系，或只匿名化非必要字段） |
| 4 | 是否可匿名化 | `approved` |

`executing` 阶段：

1. 匿名化 `organization_person.display_name` → `匿名用户-<hash8>`，`primary_email` 置空；
2. 匿名化 `identity_user_account.email` → `anon-<hash8>@invalid`，保留 `id`、状态、时间戳与组织结构关系（统计事实保留）；
3. 写 `platform_deletion_tombstone`（`action='anonymized'`）；
4. 关联的在回收站对象标记 `anonymized`，此后**禁止恢复**（`409 RESTORE_BLOCKED_ANONYMIZED`）；
5. 写 `platform_audit_event`，`platform_deletion_request.status='completed'`。

### 2.3 保留策略与合规下限

新增 3 个 `platform_setting` 键，写入时校验下限：

| 键 | 默认 | 合规下限 | 上限 | 语义 |
|---|---|---|---|---|
| `retention.audit_days` | 2555（7 年） | **2555** | 36500 | 审计只读不删，仅到期告警 |
| `retention.business_log_days` | 180 | **90** | 3650 | 业务日志可清理 |
| `retention.recycle_bin_days` | 30 | **7** | 90 | 回收站保留天数 |

低于下限直接 `422 RETENTION_BELOW_MINIMUM`，响应带 `minimum`。审计到期不删除，只由 Worker 写一条 `platform_security_event(category='retention_due')` 提示。

### 2.4 备份传播：墓碑 + 恢复后重放

备份不可变，**不承诺在备份文件内物理擦除**。做法：

1. 任何 purge 与匿名化都写一条 `platform_deletion_tombstone`（`tenant_id`、`data_class`、`object_id`、`subject_hash`、`action`、`request_id`）。
2. 恢复演练/恢复上线后调用 `POST /platform/api/v1/retention/tombstones/replay`，服务扫描墓碑并把复活的对象再次删除或匿名化，返回 `applied` / `skipped` / `failed` 计数与 `platform_operation_run(kind='tombstone_replay')`。
3. 墓碑本身只追加，不允许 UPDATE/DELETE（触发器），保证重放可重复且一致。

### 2.5 恢复与清空不得复活内容或扩大权限

- 恢复只写回**数据行本身**，绝不重建 `authorization_*`、`identity_session`、`identity_api_token`。
- 已匿名化的对象（有 `anonymized` 墓碑）恢复一律 `409`。
- 立即清空（`DELETE /retention/bin/{id}`）与到期清理走同一路径：先写 `purged` 墓碑，再删对象；重复清理幂等。
- 恢复/清空都必须校验操作人对该租户的现有权限，不复用删除时的决策。

---

## 三、数据模型（V12__retention_and_dsr.sql）

| 表 | RLS | 关键列 |
|---|---|---|
| `retention_bin_item` | 是 | `id`、`tenant_id`、`data_class`、`object_id`、`object_ref`、`snapshot jsonb`、`status(active/restored/purged/anonymized)`、`deleted_by`、`deleted_at`、`purge_after`、`restored_at/by`、`purged_at`；部分唯一索引 `(tenant_id,data_class,object_id) WHERE status='active'`；索引 `(tenant_id,purge_after) WHERE status='active'` |
| `retention_legal_hold` | 是 | `id`、`tenant_id`、`data_class`、`object_id`（可空=整类）、`reason`、`created_by`、`created_at`、`released_at/by` |
| `platform_deletion_request` | 否（平台表） | `id`、`tenant_id`、`subject_type`、`subject_id`、`subject_hash`、`reason`、`status`、`decision_code`、`decision_note`、`requested_by`、`requested_at`、`decided_by/at`、`executed_by/at`、`propagated_at` |
| `platform_deletion_tombstone` | 否（平台表） | `id`、`tenant_id`、`data_class`、`object_id`、`subject_hash`、`action(anonymized/purged)`、`request_id`、`created_at`、`replayed_at` |

同时：

- `ALTER platform_file` 状态约束新增 `recycled`；
- `ALTER work_assistant_segment ADD deleted_at timestamptz`；
- `ALTER platform_operation_run` kind 新增 `retention_purge`、`tombstone_replay`；
- 预置 3 个保留策略设置；表中不存任何业务正文（`snapshot` 只存元数据：文件名、类型、大小、sha256、时间）。

---

## 四、代码落点

### 4.1 `backend/modules/tenancy`（新增 `retention` 子包，`@NamedInterface("retention")`）

- `RetentionPolicy`：读 `platform_setting` 三个键，带内存缓存与合规下限常量
- `RetentionStore`：回收站 CRUD、到期扫描（`SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`）
- `LegalHoldStore`：对象级/类级命中查询
- `RetentionService`：§2.1 判定顺序、恢复、清空、到期清理
- `ProtectedDataClass`：`audit` / `payroll_result` 枚举与原因文案

### 4.2 `backend/modules/platform`（新增 `retention` 子包）

- `DeletionRequestStore` / `DeletionRequestService`：案件 CRUD 与状态机校验
- `TombstoneStore`：只追加写入、按租户/对象查询、重放标记
- `RetentionPolicyService`：设置读写 + 下限校验

### 4.3 `identity` / `organization`

- `identity.retention.AccountAnonymizer`：`identity_user_account.email` 替换
- `organization.retention.PersonAnonymizer`：`organization_person.display_name/primary_email` 替换
- 两者只暴露一个方法（`anonymize(tenantId, personId)`），不暴露 SQL

### 4.4 `backend/boot-api`

- `RetentionController`（`/api/v1/retention/**`）：回收站列表、恢复、立即清空、留置只读列表
- `PrivacyRequestController`（`/api/v1/privacy/requests`）：发起、查询
- `FileController` 增加 `DELETE /api/v1/system/files/{fileId}`；`WorkAssistantController` 增加 `DELETE /api/v1/work-assistant/segments/{segmentId}`
- `PlatformRetentionController`（`/platform/api/v1/retention/**`、`/platform/api/v1/privacy/requests/**`）
- `DataSubjectRequestOrchestrator`：跨 `identity` / `organization` / `platform` 的匿名化编排（boot 层编排，避免模块循环依赖）

### 4.5 `backend/boot-worker`

- `RetentionPurgeWorker`：每日扫描到期项 → 删对象存储对象与元数据 → 写 `purged` 墓碑 → `platform_operation_run(kind='retention_purge')`
- `RetentionDueScanner`：审计到期扫描，只写 `platform_security_event(category='retention_due')`

### 4.6 数据库与脚本

- `V12__retention_and_dsr.sql`
- `tests/db/94-retention.sql`（新增）
- `scripts/db-verify.ps1`：版本断言 11→12（两处），新增 5i 段

---

## 五、API 与契约（OpenAPI 1.8.0）

| 域 | 方法 + 路径 | 说明 |
|---|---|---|
| 租户 | `DELETE /api/v1/system/files/{fileId}` | 进回收站；409 时带 `holdReason` / `dataClass` |
| 租户 | `DELETE /api/v1/work-assistant/segments/{segmentId}` | 同上 |
| 租户 | `GET /api/v1/retention/bin` | 只列本租户 |
| 租户 | `POST /api/v1/retention/bin/{id}/restore` | 匿名化后 409 |
| 租户 | `DELETE /api/v1/retention/bin/{id}` | 立即清空 |
| 租户 | `GET /api/v1/retention/holds` | 只读，解释为何不能删 |
| 租户 | `GET/POST /api/v1/privacy/requests` | 发起与查询本租户案件 |
| 租户 | `GET /api/v1/privacy/requests/{id}` | 案件详情与状态 |
| 平台 | `GET/PUT /platform/api/v1/retention/policy` | 三个键，下限校验 |
| 平台 | `GET /platform/api/v1/privacy/requests` | 跨租户案件列表（元数据） |
| 平台 | `POST /platform/api/v1/privacy/requests/{id}/approve` | 权限 `platform.security.manage` + MFA |
| 平台 | `POST /platform/api/v1/privacy/requests/{id}/reject` | 同上，需 `decisionCode` |
| 平台 | `POST /platform/api/v1/privacy/requests/{id}/execute` | 同上，触发匿名化与墓碑 |
| 平台 | `POST /platform/api/v1/retention/tombstones/replay` | 恢复后重放，返回计数 |

平台权限**复用** `platform.security.read/manage`（隐私与安全运营同属一个台账），不新增第 13/14 个权限码，避免再次改角色矩阵。

---

## 六、实施顺序

1. V12 迁移 + 预置设置 + 约束变更；`94-retention.sql`
2. tenancy `retention`（策略、回收站、留置、判定顺序）+ 单测
3. identity/organization 匿名化 + 单测
4. platform `retention`（案件、墓碑、策略服务）+ 单测
5. boot-api 控制器 + `DataSubjectRequestOrchestrator` + 鉴权用例
6. boot-worker 两个 Worker
7. `FileController` / `WorkAssistantController` 接入删除
8. db-verify 5i、静态不变量、契约 1.8.0、文档与证据

---

## 七、验证清单

| 验证 | 命令 / 文件 |
|---|---|
| 回收站正负用例 | `RetentionDbTest`：删除→列表→恢复→再删→到期清理；重复删除幂等 |
| 留置阻止 | 对象级与类级留置各一例，断言 409 + `holdReason` |
| 受保护类 | `audit` / `payroll_result` 删除 409，且不产生回收站项 |
| DSR 闭环 | `received→approved→executing→completed`、`rejected`、`cancelled`、在职拒绝、匿名化后禁止恢复 |
| 备份传播 | 重放接口在「恢复后又出现」的对象上生效，计数正确，墓碑只追加 |
| 不复活不扩权 | 恢复后 `authorization_*` / `identity_session` 行数不变 |
| 合规下限 | 三个键低于下限均 422，响应带 `minimum` |
| 真实库 | `tests/db/94-retention.sql` + `scripts/db-verify.ps1` 5i |
| 静态不变量 | 租户不能读他人回收站/案件；平台不能读业务正文；墓碑无 UPDATE/DELETE |
| 契约 | `node contracts/tools/contract-check.mjs` + `--update` 后 7/7 |
| 覆盖率 | `node backend/tools/coverage-gate.mjs` |
| 全量 | `mvn -B -ntp -f backend/pom.xml clean install` |

---

## 八、风险与遗留

1. **工资结果表尚不存在**（`hr` 模块只有 `package-info.java`）：本包以数据类守卫 + 接口预备覆盖，真实工时/工资表落地时必须调用同一 `RetentionService`，否则出现绕过。列入遗留，在 WP03/WP04 强制接入。
2. **对象存储物理删除**：`platform_file.object_key` 指向 MinIO；purge 时先删对象再删行，对象删除失败则保留行并标记 `pending_object_delete`，由下次扫描重试，避免「行没了对象还在」。
3. **备份内数据**：只保证恢复后重放，不承诺擦除加密备份文件；与 NFR-08 的完整恢复校验分工，写入运行手册。
4. **审计到期告警**：7 年内不会触发，测试用可控时钟验证，不等待真实到期。
5. **跨模块匿名化**：编排放 boot-api，模块只暴露单方法端口；若后续模块增多，再抽独立 `privacy` 模块。
6. **回收站快照范围**：只存元数据，不存文件内容；恢复文件依赖对象存储对象仍在（到期前不删对象）。
