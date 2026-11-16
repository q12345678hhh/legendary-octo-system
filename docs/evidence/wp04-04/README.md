# WP04-04 权限、职责分离与异常处置

本目录记录 WP04-04 的验收证据：入账职责分离、越权 403 与安全事件、
批量异常（同供应商 / 同上传人 / 金额集中）告警与批次暂停，以及 V22 迁移。

## 结论

- 提交人、复核人、入账执行人三方分离：复核人不能是提交人（WP04-03 已有），
  入账人不能是提交人，也不能是最后复核人；AI 全规则自动通过不豁免职责分离
  （AI 不是自然人执行人，人工入账一律要求第三方）（`sod.txt`）。
- 预审与入账仍使用独立权限码 `expense.document.review` / `expense.document.post`，
  默认按角色授予（管理员 review+post+void+manage、主管 review、员工 submit）；
  越权 403 同时写入平台安全事件 `incident`，且记录失败不会覆盖原始 403
  （`authorization-denied.txt`）。
- 批量异常固定阈值（24 小时窗口）：同供应商 ≥5 张、同上传人 ≥10 张、
  金额相近（±5%）≥5 张；命中即写 `expense_batch_hold` 并审计
  `batch_anomaly.detected`，同一事务内当前单据也被 R014 阻断自动入账。
  active 暂停未解除前，该范围后续单据持续转人工（`batch-anomaly.txt`）。
- 供应商失败 / OCR 超时 / 输出非法 / 规则版本不一致一律转人工（WP04-03 的
  R000 与 R014 并存，回归通过）。
- 工程门禁全部通过：后端 BUILD SUCCESS（boot-api 100 tests、expense 31 tests，
  0 失败、4 跳过）；前端 7 个测试文件 34 用例全过；typecheck 通过；
  契约 8 份、版本 1.12.0；`node --test` 33 用例（32 通过 1 跳过）；
  `scripts/db-verify.ps1` 22 个迁移全量通过（含 V22 与 99-expense 断言）。

## 交付内容

- 迁移 `V22__expense_sod_and_batch_hold.sql`
  - `expense_batch_hold`：`scope_type`（vendor/uploader/amount）、`scope_key`、
    `reason`、`window_hours`（默认 24）、`document_count`、`amount_total`、
    `status`（active/released）、`created_by/at`、`released_by/at/note`。
  - active 部分唯一索引 `(tenant_id, scope_type, scope_key)`；released 必须带
    释放人与备注；`ENABLE + FORCE ROW LEVEL SECURITY`；禁止 DELETE 触发器。
- 规则引擎：新增 `BatchSignals` 与 R014；active 暂停或任一命中 → `blocked`。
- `ExpenseStore`：`batchCounts`、`insertHoldIfAbsent`、`activeHoldReason`、
  `findHold`、`listHolds`、`releaseHold`、`lastReviewer`。
- `ExpenseService`：提交时计算批量信号并落暂停/审计；`post` 增加 SOD 403；
  `holds` 与 `releaseHold`（解除写 `batch_hold.released` 审计）。
- `ExpenseController`：越权与服务层 403 记录安全事件；新增
  `GET /api/v1/expense/holds`、`POST /api/v1/expense/holds/{holdId}/release`。
- 前端：`#/expense` 新增「异常告警」页签（review 权限可见），列出 active
  暂停、展示范围/对象/原因/数量/时间，post 权限可填写说明后解除。
- 契约：版本 1.12.0，新增 2 个路径与 `ExpenseBatchHold`、
  `ExpenseBatchHoldReleaseRequest` 两个 schema。

## 验收项对照

| 验收项 | 实现 | 证据 |
|---|---|---|
| 提交人/复核人/入账执行人分离，AI 自动通过不豁免 | review 与 post 双向 SOD 校验；AI 自动入账不产生自然人入账人 | `sod.txt` |
| 预审与入账独立权限码、默认按角色授予 | V21 权限码 + SystemRoleCatalog 角色模板 | `authorization-denied.txt` |
| 越权 403 并记录安全事件 | `requireActor`/服务 403 捕获后 best-effort `security.record(incident)` | `authorization-denied.txt` |
| 批量异常触发告警并暂停该批次自动入账 | R014 + `expense_batch_hold` + 事务内阻断 | `batch-anomaly.txt` |
| 供应商失败/OCR 超时/规则版本不一致转人工 | R000 保留，R014 叠加不改变失败转人工语义 | 后端回归、`gate-results.txt` |

## 门禁结果

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl boot-api -am test` | BUILD SUCCESS；boot-api 100 tests、expense 31 tests，0 失败 / 4 跳过 |
| `pnpm --dir frontend run typecheck` | 通过（exit 0） |
| `pnpm --dir frontend run test` | 7 个测试文件、34 个用例全部通过 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，3 类覆盖完整（version 1.12.0） |
| `node --test "tests/**/*.test.mjs"` | 33 个用例，32 通过、1 跳过、0 失败 |
| `scripts/db-verify.ps1` | 全部通过（22 个迁移、空库/升级、RLS 105 张、99-expense 断言、连接池） |

## 限制与口径

- 批量阈值为代码内固定常量，不进入租户策略与契约，避免运营阈值被租户
  自行放宽；后续如需可配置，应新增策略字段并走版本化变更。
- 暂停粒度是「范围」而非「单据」：命中后该供应商/上传人/金额带的后续单据
  都转人工，解除由持 `expense.document.post` 的人员填写说明后执行。
- 安全事件为 best-effort：平台安全中心不可用时 403 仍正常返回，不阻断主流程。

## 手工验证

```powershell
mvn -B -ntp -f backend/pom.xml -pl modules/expense,boot-api -am test `
  "-Dtest=ExpenseRuleEngineTest,ExpenseServiceTest,ExpenseApiTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-verify.ps1
node contracts/tools/contract-check.mjs
pnpm --dir frontend run test
```
