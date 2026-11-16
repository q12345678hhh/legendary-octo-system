# WP04-01 语音/文字工时录入与设备采集移除

本目录记录 WP04-01 的验收状态：语音录制 → 服务端转写 → 员工确认生成工时，
文字录入补齐起止时间与离线补录，同时全量移除设备侧采集链路。

## 结论

- 语音链路在本地假 ASR 下跑通：上传 → 转写 → 确认 → `worklog_entry.source_type = voice`，
  确认后对象存储中的音频文件被立即删除。
- ASR 未配置时转写返回 **503 `ASR_NOT_CONFIGURED`**，且在读取对象存储之前返回，不产生假转写。
- 设备采集（`desktop-agent/`、`worklog/device|policy|update`、`work_assistant_*` 三张表）
  已全量移除；仓库内不再有设备端入口。
- 工程门禁：后端测试 BUILD SUCCESS、前端 23 个用例通过、typecheck 通过、
  契约 8 份通过、`node --test` 32 通过 1 跳过、`scripts/db-verify.ps1` 全部通过。

## 交付内容

- 迁移 `V19__worklog_voice_and_device_removal.sql`
  - 新建 `worklog_voice_capture`（ENABLE + FORCE RLS，单条租户策略；
    `UNIQUE (tenant_id, person_id, client_request_id)` 保证客户端重试幂等）。
  - 删除 `work_assistant_segment / work_assistant_policy / work_assistant_device`，
    并重建 `retention_bin_item / retention_legal_hold` 的 `data_class` CHECK。
- `worklog/voice` 包
  - `AsrProperties`（`platform.asr.*`，默认全空）、`SpeechTranscriber` 接口、
    `OpenAiCompatibleTranscriber`（multipart POST `/audio/transcriptions`）、
    `VoiceCaptureStore / VoiceCaptureService`。
  - MIME 白名单、≤10MB、≤120 秒；确认后删除音频，删除失败不回滚工时并返回 `audioDeleted=false`。
- `ObjectStorageClient.download(objectKey, maxBytes)`：一次性受限下载，硬上限、不写内容日志。
- `WorklogController` 4 个语音端点，全部 `requireSelf("worklog.time.write")`；
  `EntryView` 增加 `sourceType`。
- 前端：`useVoiceRecorder`（仅在点击开始时申请麦克风）、语音卡片、
  文字起止时间、表单草稿与离线待补队列（同一 `clientEntryId` 重放）、来源列。
- 契约：删除 work-assistant 8 条路径、新增 4 条语音路径；
  破坏性变更审批见 `contracts/baseline/approvals/openapi__platform-api.v1.json`。

## 验收项对照

| 验收项 | 实现 | 证据 |
|---|---|---|
| 不录音时麦克风零访问 | `useVoiceRecorder` 只在 `start()` 调 `getUserMedia`；挂载用例断言未调用 | `WorklogView.test.tsx`「挂载时不访问麦克风」 |
| 录音转写可编辑可追溯 | 转写文本、provider、model、confidence、`transcribed_at` 落库；确认采用最终文本 | `voice-flow.txt` |
| 确认后音频立即删除 | 确认成功即删除对象；数据库 `audio_deleted_at` 非空 | `voice-flow.txt`、MinIO 目录无残留 |
| 未配置 ASR 明确失败 | 503 `ASR_NOT_CONFIGURED`，先于对象存储读取 | `asr-unconfigured.txt` |
| 放弃录音 | 删除音频并置 `discarded`，已确认的录音拒绝放弃（409） | `voice-discard.txt` |
| 离线补录 / 重复提交幂等 | 前端同一 `clientEntryId` 重放；服务端 `client_entry_id` 去重 | `WorklogView.test.tsx` |
| 跨日/超长只提示 | 前端提示，最终以后端错误码为准 | 代码审查 |
| 代码库无设备采集 | 目录、包、表、契约、CI job、脚本、runbook 全部移除 | `git status`、`tests/ci/workflow-gates.test.mjs` |

## 门禁结果

| 命令 | 结果 | 证据 |
|---|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl modules/worklog,boot-api -am test` | BUILD SUCCESS | 终端输出 |
| `pnpm --dir frontend run test` | 6 个测试文件、23 个用例通过 | 终端输出 |
| `pnpm --dir frontend run typecheck` | 通过（exit 0） | 终端输出 |
| `node contracts/tools/contract-check.mjs` | 8 份契约通过，3 类覆盖完整 | 终端输出 |
| `node --test "tests/**/*.test.mjs"` | 32 通过、1 跳过、0 失败 | 终端输出 |
| `scripts/db-verify.ps1` | 全部通过（19 个迁移、RLS、跨租户、升级、连接池等） | `db-verify.txt` |
| Flyway 启动日志 | `Successfully applied 1 migration ... now at version v19` | API 运行日志 |

## 手工验证

演示环境：租户 `aaaaaaaa-0000-0000-0000-000000000001`，账号
`demo.manager@example.test`（本地演示口令，仅开发库使用）。

1. 未配置 ASR：登录 → 创建上传会话 → 转写，返回 `503 ASR_NOT_CONFIGURED`（`asr-unconfigured.txt`）。
2. 配置本地假 ASR（`platform.asr.base-url=http://127.0.0.1:18099`）后：
   创建会话 → 预签名 PUT 2048 字节 → 转写得到文本 →
   确认生成 1 分钟工时，`sourceType=voice`，`audioDeleted=true`（`voice-flow.txt`）。
3. 放弃未确认录音：`DELETE` 返回 200，状态 `discarded`，对象存储无音频残留（`voice-discard.txt`）。

## 验收中发现并修复的问题

`WorklogStore.totalMinutes / overlapping` 的 `(? IS NULL OR id <> ?)` 未标注 UUID 类型，
PostgreSQL 在 `excludingId = null` 时无法推断参数类型，导致创建工时（含手工录入）返回 500。
已改为 `CAST(? AS uuid)`，并在真实库上重跑语音确认与日视图验证通过。

## 不在本期范围

流式/实时转写、说话人分离、翻译、AI 周报生成（WP04-02）、单据 OCR 预审（WP04-03）、
音频保留开关与 `voice` 数据类（WP04-05）、主管查看音频、任何设备端替代方案。
