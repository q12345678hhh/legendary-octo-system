# WP04-03 单据上传与 AI 预审

本目录记录 WP04-03 的验收证据：单据上传与 OCR 抽取、确定性规则预审、
自动入账/转人工、人工复核与撤销、租户策略、只追加审计，以及 V21 的六张租户表。

## 结论

- 规则全部通过且租户开启自动入账时，单据直接 `posted`，`post_actor_kind=ai`，
  审计写入 `auto_posted`，金额、币种、科目一并留痕（`golden-sample.txt`）。
- OCR 未配置、超时、上游失败、输出非法、PDF 都会转 `manual_review`，绝不会默认通过；
  手工字段可跳过 OCR，但仍需通过全部规则（`manual-review.txt`）。
- 同一发票号在未释放前只能被一张单据占用；占用冲突由唯一索引兜底并转人工，
  驳回/撤销后释放，允许重新提交（`duplicate-invoice.txt`、DB 断言 5n）。
- 抽取、复核、审计三类记录在数据库层只允许插入，UPDATE/DELETE 均被触发器拒绝；
  策略变更写入前后值审计（`audit-immutability.txt`）。
- 未登录 401、缺少权限 403、提交人复核自己的单据 403 `EXPENSE_SOD_VIOLATION`
  （`auth-negative.txt`）。
- 工程门禁全部通过：后端 98 个测试 0 失败、前端 7 个测试文件全过、
  typecheck 通过、契约 8 份通过、`node --test` 33 个用例（32 通过 1 跳过）、
  `scripts/db-verify.ps1` 21 个迁移全量通过。

## 交付内容

- 迁移 `V21__expense_ocr_review.sql`
  - `expense_document`：单据主体，状态 `manual_review | approved | posted | rejected | voided`。
  - `expense_document_extraction`：OCR 原文与字段快照，只插入不可改。
  - `expense_document_review`：人工结论与推翻理由，只插入不可改。
  - `expense_invoice_claim`：发票号活动占用唯一索引，释放后允许重新提交。
  - `expense_tenant_policy`：自动入账开关/上限、报销窗口、抬头税号、白名单、人工与允许科目、置信度门槛、验真开关。
  - `expense_audit_event`：提交、抽取、预审、复核、入账、撤销、策略变更只追加。
  - 六张表均 `ENABLE + FORCE ROW LEVEL SECURITY`；新增 6 个权限码。
- 后端新模块 `backend/modules/expense`
  - `ocr`：`DocumentExtractor` 端口与 OpenAI 兼容视觉抽取器；PDF 不调用上游，直接转人工。
  - `ExpenseRuleEngine`：13 条确定性规则（R001-R013），规则版本 `expense-rules-2026.11`。
  - `app/ExpenseService`：提交（事务外 OCR、事务内落库与判定）、复核、入账、撤销、策略与审计。
  - `ExpenseStore`：单据、抽取、复核、发票号占用、策略与审计的 JDBC 持久层。
- `boot-api`：`ExpenseController` 8 个端点，权限码 `expense.document.read/submit/review/post/void`、
  `expense.policy.manage`；员工标识从会话解析。
- 前端：路由 `#/expense` 与 `ExpenseView`（上传+提交、单据列表、详情抽屉、
  规则逐条、AI/人工结论、审计时间线、复核/入账/撤销、租户策略），`api-client` 类型与 9 个方法。
- 契约：`/api/v1/expense/**` 7 个路径（8 个操作）与 13 个 schema，版本 1.11.0。

## 验收项对照

| 验收项 | 实现 | 证据 |
|---|---|---|
| 上传发票/账单并抽取关键字段 | 文件预签名直传 + 扫描校验 + OpenAI 兼容视觉抽取 | `manual-review.txt`、OCR 单测 |
| 全部通过自动入账并标记 AI 预审通过 | R001-R013 全 pass → `posted` + `post_actor_kind=ai` | `golden-sample.txt` |
| 任一异常转人工并列规则与置信度 | findings 快照 + 低置信度/金额阈值/验真阻断 | `manual-review.txt` |
| 重复报销拦截 | 发票号活动占用唯一索引 + R006/R007 | `duplicate-invoice.txt` |
| 人工复核可推翻 AI 结论 | 复核只追加、不改快照；驳回释放发票号 | 复核单测、DB 断言 |
| AI 结论、规则版本、OCR 原文、操作者留痕 | extraction 不可变表 + audit 只追加 | `audit-immutability.txt` |
| 自动入账可撤销且留痕 | `voided` + `void_reason` + 审计 + 释放发票号 | 撤销单测、DB 断言 |
| 阈值/白名单/人工科目由租户配置且审计 | `expense_tenant_policy` + `policy.updated` | 策略单测、DB 断言 |
| 预审与入账独立权限码 | review/post/void/policy 四个独立码 | `auth-negative.txt` |
| 契约与类型同步 | `contract-check` + api-client 类型 | `contract-check.txt` |

## 门禁结果

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl boot-api -am test` | BUILD SUCCESS，98 tests / 0 fail / 4 skipped |
| `pnpm --dir frontend run typecheck` | 通过（exit 0） |
| `pnpm --dir frontend run test` | 7 个测试文件、33 个用例全部通过 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，3 类覆盖完整 |
| `node --test "tests/**/*.test.mjs"` | 33 个用例，32 通过、1 跳过、0 失败 |
| `scripts/db-verify.ps1` | 全部通过（21 个迁移、空库/升级、RLS、V21 断言、连接池） |

## 限制与口径

- 无总账与会计科目：`posted` 只记录入账事实（金额/币种/供应商/科目字符串/时间与操作者），
  UI 标注「已入账（待对接账务）」，不生成借贷凭证，口径需财务书面确认。
- 无发票验真服务：`verify_required` 默认 true，R012 将自动入账转人工；
  演示环境可显式关闭，变更写 `policy.updated` 审计。
- PDF 不做自动 OCR：提示上传清晰图片或手工填写字段，手工字段同样跑全部规则。
- 本目录证据来自可重复的 JUnit/DB/契约门禁；未连接真实 OCR 供应商，
  OCR 行为由本地假 HTTP 服务验证（正常/超时/非 2xx/非法 JSON/PDF/未配置）。

## 手工验证

```powershell
mvn -B -ntp -f backend/pom.xml -pl modules/expense,boot-api -am test `
  "-Dtest=ExpenseRuleEngineTest,ExpenseServiceTest,OpenAiCompatibleOcrExtractorTest,ExpenseApiTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-verify.ps1
node contracts/tools/contract-check.mjs
pnpm --dir frontend run test
```
