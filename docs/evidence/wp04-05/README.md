# WP04-05 隐私、保留与删除

本目录记录 WP04-05 的验收证据：可解释的数据分类与保留期、语音与单据进入
统一回收站、数据主体自查/导出/删除、已入账单据只匿名化，以及租户隐私设置
（驻留地、供应商训练开关、语音/OCR 保留天数）。

## 结论

- 数据分类注册表覆盖 7 类：`file`、`worklog_entry`、`worklog_weekly_report`、
  `worklog_voice_capture`、`expense_document`、`expense_extraction`、`audit`。
  每类都返回用途、保留天数、说明与访问角色；语音与 OCR 的保留天数取租户设置，
  其余取保留策略（回收站 30 天、业务长期 3650 天、审计 2555 天）
  （`catalog.txt`）。
- 租户管理员可查看并修改驻留地（cn/eu/us/sg）、训练开关与语音/OCR 保留天数
  （1..3650）；供应商与模型只读，来自语音转写、单据 OCR、AI 周报三处配置；
  非管理员访问返回 403，非法天数返回 400（`settings.txt`）。
- 员工在「我的语音与单据」中只能看到本人记录：语音导出包含转写/编辑文本，
  单据导出包含结构化字段与 OCR 摘要，均不含文件字节；删除语音与未入账单据
  进入统一回收站，已入账单据只清空 OCR 原文与抽取字段，金额、状态与审计链
  不变，并写墓碑与 `anonymized` 审计（`my-data.txt`）。
- 已入账单据尝试走回收站路径会被拒绝（409 `EXPENSE_POSTED_PROTECTED`），
  数据主体删除流程对同类单据自动降级为匿名化。
- 数据库层：`tenant_privacy_setting` 启用并强制 RLS；语音/单据新增
  `recycled_at`；回收站与法定留置白名单接受两个新数据类；抽取、复核记录
  默认拒绝改删，只有 `app.privacy_anonymize='on'` 的当前语句放行
  （`gate-results.txt` 中的 5o 断言）。

## 交付内容

- 迁移 `V23__privacy_data_classes_and_dsr.sql`
  - `worklog_voice_capture.recycled_at`、`expense_document.recycled_at` 与
    活跃记录部分索引。
  - `retention_bin_item.data_class` 白名单加入语音与单据；
    `retention_legal_hold.data_class` 同步加入。
  - `tenant_privacy_setting`：租户一行，默认 `cn` / 不用于训练 / 语音 365 天 /
    OCR 3650 天，天数 CHECK 1..3650，ENABLE + FORCE RLS 与租户策略。
  - 重建抽取、复核、审计三个只插入触发器：前两者在匿名化开关下允许改删，
    审计只允许在开关下把 `document_id` 置空，保留事件链。
- worklog：`VoiceCaptureRetentionTarget`（快照不含转写原文；purge 删对象存储
  音频后删行）；`VoiceCaptureStore` 增加回收过滤、按人列表、回收/恢复/删除。
- expense：`ExpenseDocumentRetentionTarget`（`posted` → 409；其余回收）；
  `ExpenseStore` 增加回收过滤、`purgeDocument`、`anonymizeExtraction`、
  `findIncludingRecycled`。
- tenancy：`PrivacySetting`、`PrivacySettingsStore`、`PrivacySettingsService`。
- boot-api：`PrivacyService` + `PrivacyController`，6 个端点（分类、设置读写、
  我的数据、导出、删除）。
- 契约：版本 1.13.0，新增 5 个路径（6 个操作）与 `PrivacySettings`、
  `PrivacyMyData`、`PrivacyDataExport` 三个 schema。
- 前端：`#/privacy` 页面，页签「我的语音与单据」「数据分类与供应商」，
  支持导出 JSON、勾选删除与管理员设置保存。

## 验收项对照

| 验收项 | 实现 | 证据 |
|---|---|---|
| 数据分类、保留期与访问角色可解释 | 7 类注册表，语音/OCR 读租户设置 | `catalog.txt` |
| 统一回收站 + 数据主体删除 | 语音与单据接入 `RetentionService`；已入账只匿名化 | `my-data.txt` |
| 供应商驻留与「不用于训练」可见可配 | 设置读写、只读供应商表、训练开关 | `settings.txt` |
| 我的语音与单据可自查、导出、删除 | `/privacy/my-data`、`/export`、`/delete` | `my-data.txt` |

## 门禁结果

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl boot-api -am test` | BUILD SUCCESS；boot-api 106 tests、expense 31 tests，0 失败 / 1 跳过 |
| `pnpm --dir frontend run typecheck` | 通过（exit 0） |
| `pnpm --dir frontend run test` | apps/web 8 个文件 38 用例；api-client 8、design-system 3、lowcode-runtime 3 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，version 1.13.0，baseline 已刷新 |
| `node --test "tests/**/*.test.mjs"` | 33 个用例，32 通过、1 跳过、0 失败 |
| `scripts/db-verify.ps1` | 23 个迁移全量通过；FORCE RLS 106 张；`9a-privacy.sql` 通过 |

## 限制与口径

- 语音与单据的「删除」默认进入回收站（30 天），不是立即物理删除；数据主体
  删除也不绕过法定留置，留置中的对象仍由保留任务处理。
- 已入账单据是会计档案：只匿名化 OCR 文本与抽取字段，不修改金额、状态、
  复核与审计；审计行保留但解除与已删除单据的关联。
- 供应商信息来自运行配置，页面只读；驻留地与训练开关写入租户设置，作为
  对供应商的合同约束提示，不在代码层调用第三方「训练退出」接口。
- 导出同步返回 JSON，最多 200 条语音与 200 张单据；大数据量场景应改为异步
  打包下载（后续工作项）。

## 手工验证

```powershell
mvn -B -ntp -f backend/pom.xml -pl boot-api -am test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-verify.ps1
node contracts/tools/contract-check.mjs
node --test "tests/**/*.test.mjs"
pnpm --dir frontend run typecheck
pnpm --dir frontend run test
```
