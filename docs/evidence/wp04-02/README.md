# WP04-02 AI 周报生成与来源映射冻结

本目录记录 WP04-02 的验收证据：AI 生成周报摘要、事实可回溯校验、AI 不可用降级，
以及 V20 迁移为 `worklog_report_source` 增加的提交后冻结触发器。

## 结论

- AI 正常时生成 3 条摘要要点与 1 条工作产出，全部带非空 `entryIds`；
  `generation.mode=ai`、`droppedClaims=0`，摘要中的 1830 分钟与日报条目逐日核对一致。
- AI 未配置时返回 200，`generation.mode=fallback`、`fallbackReason=AI_NOT_CONFIGURED`，
  按已确认时长生成模板，摘要仍可编辑、可提交。
- AI 返回编造数字与非法 `entryId` 时，服务端丢弃 4 条不可回溯内容，
  最终摘要不含 999/42 等编造数字，工作产出为空，`droppedClaims=4`。
- 授权负例：未登录读周报 401；主管/老板的待审列表只含 `submitted`，看不到本人或他人草稿；
  主管审批自己的周报返回 403。
- 工程门禁：后端测试 BUILD SUCCESS、前端 6 个测试文件全过、typecheck 通过、
  契约 8 份通过、`node --test` 33 个用例（32 通过 1 跳过）、`scripts/db-verify.ps1` 全部通过。

## 交付内容

- 迁移 `V20__worklog_report_source_guard.sql`
  - 提交/审批后的周报拒绝新增、修改、删除 `worklog_report_source`（`P0001`）。
  - 草稿阶段仍允许写入来源映射；启动日志：`Migrating schema "public" to version "20 - worklog report source guard"`。
- `worklog/report/summary` 包
  - `ReportFactsBuilder`：把已确认快照整理为事实包（天数、总分钟、逐日、项目聚合、条目）。
  - `OpenAiCompatibleSummarizer`：OpenAI 兼容 `/chat/completions`，受 `max-input-chars` 截断。
  - `ReportSummaryValidator`：数字白名单（总分钟、天数、逐日/项目/条目分钟、周内日期）
    与 `entryId` 必须属于本周；不通过即整句丢弃并累计 `droppedClaims`。
  - `ReportSummaryGenerator`：`ai / fallback / employee` 三种来源与降级原因。
- `WorklogReportService`：生成、读取、版本列表、保存说明、提交；草稿可重生成，
  提交后只读；恢复版本时保留历史版本链。
- 前端 `WorklogView` 周报页：AI 生成按钮、生成信息条（模型/提示词版本/丢弃条数/截断）、
  降级 Alert、摘要编辑、摘要要点与工作产出（含来源条数）、异常表、项目聚合表、
  条目明细折叠、历史版本表、提交后只读。
- 契约：周报接口与 `WeeklyReportContent` v2 类型进入 `contracts/openapi__platform-api.v1.json`。

## 验收项对照

| 验收项 | 实现 | 证据 |
|---|---|---|
| AI 正常生成、数字可回溯 | 事实包 → 提示词 → 校验器 → 摘要/要点/产出 | `golden-sample.txt` |
| 摘要数字与工时一致 | 周报 `days` 与 `/days/{date}` 条目分钟逐日核对 | `golden-sample.txt` |
| AI 未配置时 200 + 降级 | `AI_NOT_CONFIGURED` 降级为时长模板，摘要可编辑 | `degraded.txt` |
| 编造数字/非法 entryId 被丢弃 | 数字白名单 + entryId 归属校验，`droppedClaims` 记录条数 | `ai-traceability.txt` |
| 他人草稿不可见、自审被拒 | `requireSelf` + 待审列表只返回 `submitted` + 403 | `auth-negative.txt` |
| 提交后来源映射冻结 | V20 触发器拒绝写/改/删 | `db-verify` 5k 段、启动日志 v20 |
| 契约与类型同步 | `contract-check` + `api-client` 类型 | `contract-check.txt` |

## 门禁结果

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl modules/worklog,boot-api -am test` | BUILD SUCCESS，92 tests / 0 fail |
| `pnpm --dir frontend run test` | 6 个测试文件全部通过 |
| `pnpm --dir frontend run typecheck` | 通过（exit 0） |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，3 类覆盖完整 |
| `node --test "tests/**/*.test.mjs"` | 33 个用例，32 通过、1 跳过、0 失败 |
| `scripts/db-verify.ps1` | 全部通过（20 个迁移、V2→最新升级、RLS、V20 冻结、连接池等） |

## 手工验证

演示环境：租户 `aaaaaaaa-0000-0000-0000-000000000001`，账号
`demo.manager@example.test` / `demo.owner@example.test`（本地演示口令，仅开发库使用），
周 `2026-11-09`（4 天已确认、20 条条目、1830 分钟）。

1. 不配置 `PLATFORM_REPORT_AI_*` 启动 API：`POST /api/v1/worklog/reports/2026-11-09/generate`
   返回 200 且 `generation.mode=fallback`（`degraded.txt`）。
2. 启动本地假模型：`node docs/evidence/wp04-02/tools/fake-chat.mjs`，
   API 增加 `PLATFORM_REPORT_AI_BASE_URL=http://127.0.0.1:18099`、
   `PLATFORM_REPORT_AI_MODEL=fake-report-1` 后重新生成，得到 AI 摘要（`golden-sample.txt`）。
3. `curl "http://127.0.0.1:18099/__mode?value=dirty"` 后再次生成，
   编造内容被丢弃、`droppedClaims=4`（`ai-traceability.txt`）。
4. 授权负例：未登录 401、老板同周读不到主管草稿、主管自审 403（`auth-negative.txt`）。

## 验收中发现并修复的问题

`tests/db/96-worklog.sql` 的冻结断言里，PL/pgSQL 变量名 `report_id` 与
`worklog_report_source.report_id` 列同名，`DELETE ... WHERE report_id = report_id`
报 `column reference "report_id" is ambiguous`，导致 `db-verify` 第 5k 步中断。
已将变量改名为 `target_report` 并重跑全量 `db-verify` 通过。

## 不在本期范围

团队/项目周报汇总、周报导出、跨周趋势分析、审批意见富文本、AI 流式生成、
按部门配置提示词模板。
