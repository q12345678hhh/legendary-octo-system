# WP03-03 部门协作证据（全量）

本目录记录 Phase 3 协作包：频道、成员、帖子/评论/@/回执、项目与文件引用、
通用任务/里程碑、会议纪要/行动项/决定记录、知识页、站内通知、直接分享链接、
撤权扫描与查询时权限过滤搜索。

## 交付内容

- V16：19 张 `collab_*` 表，全部 `ENABLE/FORCE ROW LEVEL SECURITY` 且每表一条隔离策略。
  - 频道：`collab_channel`、`collab_channel_member`。
  - 内容：`collab_post`、`collab_comment`、`collab_mention`、`collab_receipt`。
  - 项目与文件：`collab_project`、`collab_project_member`、`collab_file_link`、
    `collab_file_version`、`collab_file_tag`。
  - 任务与会议：`collab_task`、`collab_milestone`、`collab_meeting`、
    `collab_action_item`、`collab_decision`。
  - 知识页与通知：`collab_page`、`collab_notification`、`collab_share_link`。
- 后端模块 `collaboration`：`CollaborationStore` + 频道/帖子/项目/任务/会议/知识页/
  通知/搜索/分享链接/撤权服务；只依赖 `platform` 提供的租户事务与请求上下文。
- HTTP：`/api/v1/collaboration/**`、`/api/v1/notifications/**`、`/api/v1/search`。
- 前端：`#/collaboration` 与 `#/collaboration/:channelId`，含频道列表、搜索、通知箱、
  帖子/评论/回执/置顶、任务状态、会议、知识页、文件引用与成员管理。
- 测试：`CollaborationAuthzTest`、`tests/db/97-collaboration.sql`；
  `40-integrity.sql` 表数 69→88、`92-cross-tenant.sql` 表数 70→89、
  `scripts/db-verify.ps1` 迁移数 15→16 并新增 5l 段。

## 关键不变式

1. 频道可见性只来自成员表或 `all` 频道的部门范围；`cross_department` 必须显式加人。
2. 移除成员同步执行撤权：写 `removed_at`、撤销该成员在该频道创建的分享链接、
   清理未读通知；接口返回撤销统计与 SLA（默认 5 分钟，实际同步完成）。
3. 通知写库前必须确认收件人仍是频道成员或项目成员；不满足则静默跳过，不产生越权通知。
4. 文件引用只增加引用，不改变 `platform_file` 的下载 ACL；列表按 `file.read`
   逐条过滤，无权文件整行省略，不泄露文件名。
5. 搜索在 SQL 内联查成员/部门范围，只有可见频道、可见部门知识页、可访问任务会命中。
6. 所有写操作的员工身份从会话解析，请求体不接收 personId 作为授权依据。

## 验证

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -f backend/pom.xml -pl modules/collaboration,boot-api -am install -DskipTests -Djacoco.skip=true` | BUILD SUCCESS |
| `mvn -B -ntp -f backend/pom.xml -pl boot-api test -Dtest=CollaborationAuthzTest,DepartmentAuthzTest` | 6 个用例通过（新增 3 个） |
| `mvn -B -ntp -f backend/pom.xml -pl boot-api test -Dtest=ModuleStructureTest` | 2 个用例通过，模块边界无违规 |
| `pnpm -C frontend/apps/web typecheck` | 通过 |
| `pnpm -C frontend/apps/web test -- --run` | 16 个用例通过 |
| `node --test "tests/**/*.test.mjs"` | 42 个用例，41 通过、1 个既有跳过 |
| `node contracts/tools/contract-check.mjs` | 7 份契约通过 |
| `powershell -ExecutionPolicy Bypass -File scripts/db-verify.ps1` | 全部通过，含 5l 协作断言（日志见 `db-verify-run.txt`） |

数据库验证已通过 Docker Desktop 容器 `platform-pg-dev` 实际执行：空库升级到 V16、
V2→V16 升级、RLS/跨租户、并发、锁等待、连接池与 `97-collaboration.sql` 全部通过。
`db-verify.ps1` 增加了 Docker Desktop 模式：优先复用容器内 psql，并自动跟随容器的
宿主端口映射（本机 5432 被其它项目占用，仓库容器映射到 55432）。

## 未包含（下一批）

- WP03-04 权限感知洞察（k-匿名抑制）。
- WP03-06 通用工作流（条件、并行、定时器、SLA、补偿）。
- WP03-07 模块目录与建议状态机。
- OpenAPI 补录与新事件目录项，计划在 Phase 3 收口时统一完成。
