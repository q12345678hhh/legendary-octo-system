# ADR-0001 仓库目录结构

| 项目 | 内容 |
|---|---|
| 状态 | 已接受（待 G0 随受控基线一并登记） |
| 日期 | 2026-11-09 |
| 决策范围 | 仓库顶层目录、后端/前端分组、Windows 客户端命名、跨端测试位置、Flyway 迁移归属 |
| 不在本决策范围 | 业务模块的职责边界（两文档模块清单已核对一致，结论见 §6.1） |
| R / A | 个人开发者 / 产品负责人（姓名待 WP00-01 落实） |
| 触发来源 | 执行计划 §3.1 与 PLAN Frozen 1.2 §13.3 的目录结构冲突 |

## 1. 背景

`PLAN.md` Frozen 1.2 第 13.3 节给出仓库目录蓝图（以 `backend/`、`frontend/`、`desktop-agent/` 分组），
《IMPLEMENTATION_PLAN.md》第 3.1 节给出另一套平级目录（`web/`、`server/`、`worker/`、`work-assistant/`，并额外包含 `db/migration/`、`tests/`）。

执行计划第 11 行规定：「两份文件冲突时停止实施，先更新并重新确认冻结规格」。因此本结构必须在 G0 之前定稿，否则 WP01-01（仓库和持续集成）没有可执行的输入。

冲突并非纯命名差异，实质分歧有五点：

| # | 分歧 | PLAN §13.3 | 执行计划 §3.1 |
|---|---|---|---|
| 1 | 后端分组 | `backend/` 多模块 Maven 反应堆（`boot-api`、`boot-worker`、`modules`、`test-support`） | `server/` 与 `worker/` 平级 |
| 2 | 前端分组 | `frontend/`（`apps/web` + `packages/*`） | 单目录 `web/` |
| 3 | Windows 客户端命名 | `desktop-agent/` | `work-assistant/` |
| 4 | 跨端测试 | 未设独立目录 | 根级 `tests/` |
| 5 | Flyway 迁移位置 | 未指定 | 根级 `db/migration/` |

## 2. 决策

采用 PLAN 的分组结构为基准，吸收执行计划的 `tests/`，并将 Flyway 迁移收敛为 `backend/db/` 模块。

```text
/
  PLAN.md
  backend/
    pom.xml                       # 父工程，聚合下列模块
    boot-api/                     # API 进程装配
    boot-worker/                  # 异步工作进程装配
    modules/                      # 15 个业务模块，清单见 §6.1
    db/                           # 集中迁移：建表、RLS 策略、种子数据
      src/main/resources/db/migration/
    test-support/                 # Testcontainers、租户与权限测试夹具
  frontend/
    pnpm-workspace.yaml
    apps/web/
    packages/api-client/
    packages/design-system/
    packages/lowcode-runtime/
  desktop-agent/
    windows-app/
    adapters/
    privacy-engine/
  plugin-runner/
  contracts/
  tests/                          # 端到端、性能、多租户攻击、AI 红队
  deploy/
  docs/
```

### 逐条裁定

| # | 裁定 | 依据 |
|---|---|---|
| 1 | 采纳 PLAN | API 与 Worker 共享全部业务模块、同一份迁移和 `contracts/`，必须在同一 Maven 反应堆内编译一次。平级的 `server/` 与 `worker/` 要么各自重复编译模块，要么最终仍需共同父工程，等价于 `backend/` 换名。Worker 是「同一堆模块 + 不同装配入口」，`boot-worker/` 命名即表达此意。 |
| 2 | 采纳 PLAN | 前端除主应用外需复用 `api-client`（OpenAPI 生成）、`design-system`（设计 token）、`lowcode-runtime`（低代码渲染器）；缺 `frontend/packages/` 层会导致包目录散落或被隐式相对路径引用。 |
| 3 | 采纳 PLAN | 产品对外名称是「Windows 工作助手」，目录沿用规格原文 `desktop-agent/`；`work-assistant` 仅作中文口语别名，不进代码路径。 |
| 4 | 采纳执行计划 | Playwright 端到端、性能基线、多租户攻击套件、AI 红队四类测试跨 Web/API/Worker/插件执行器，无法归属单一构建单元，必须有根级 `tests/`。PLAN 的这一项是遗漏。 |
| 5 | 两者均不采纳 | `tenant_id` + RLS 横切全部业务表，PLAN §6.4 要求迁移账号、运行账号、只读分析账号权限分离。模块各自持有迁移会在 15 个模块共用一张 `flyway_schema_history` 时引入版本号排序风险；根级 `db/migration/` 又脱离 `backend/` 构建上下文。`backend/db/` 同时满足单一线性迁移历史、随主构建发布、RLS 策略与建表语句同处受审。 |

## 3. 后果

### 3.1 正面

- 单一 Maven 反应堆，模块编译一次，API 与 Worker 不产生版本漂移。
- 迁移历史线性且集中，RLS 策略可与建表语句一并评审，满足评审重点第 1 条「组织调动后旧权限在目标时限内失效」的可验证性。
- 跨端测试有唯一归属，符合 PLAN §17.1 的测试分层。

### 3.2 代价与约束

- `backend/db/` 成为跨模块共享模块：只允许存放迁移、RLS 策略与种子数据，不允许承载业务逻辑；模块内的表结构变更必须以 SQL 迁移提交到该模块，不得在模块内另建迁移目录。
- 迁移版本号必须全局唯一并按全序编排；建议在 WP01-03 固定编号分区（例如每模块预留号段），具体规则在 WP01-03 产出。
- `frontend/` 必须使用 pnpm workspace；`apps/web` 与 `packages/*` 之间只允许通过包名引用，禁止相对路径跨包穿透。

## 4. 影响面

| 位置 | 需要改动 |
|---|---|
| PLAN §13.3 | 以勘误形式补充 `backend/db/` 与根级 `tests/` 两行，版本记 v1.2.1，属结构性勘误，不改产品语义 |
| 执行计划 §3.1 | 整段替换为第 2 节定稿树 |
| 执行计划 WP01-01 | 构建任务由 6 个调整为 `backend`、`frontend`、`desktop-agent`、`plugin-runner`、`contracts`、`tests` |
| 执行计划 WP01-03 | Flyway 版本规则、校验和门禁的落点改为 `backend/db/` |
| 执行计划 WP01-02 | Docker Compose 与本地拓扑说明中的仓库路径同步 |

### 4.1 勘误文本（可直接替换）

PLAN §13.3 目录蓝图末尾追加两行：

```text
  backend/
    db/                    # 集中 Flyway 迁移、RLS 策略与种子数据
  tests/                   # 端到端、性能、多租户攻击与 AI 红队测试
```

执行计划 §3.1 替换为第 2 节的定稿树，并保留原「模块化单体内部按业务边界拆分……」段落原文（该段落与 PLAN §6.3 一致，无需修订，核对结论见 §6.1）。

## 5. 未采纳的备选方案

| 方案 | 未采纳理由 |
|---|---|
| 完全按 PLAN 原文 | 保留根级无 `tests/`、迁移位置未定的状态，第 4、5 项隐患被推迟到 WP01-01 才暴露 |
| 完全按执行计划平级结构 | 需接受 Maven 反应堆重复编译，或实际上重新发明 `backend/` 父工程 |
| 迁移按模块分散持有 | 15 个模块共用单张迁移历史表时，版本号全序编排与回滚边界不可控；RLS 策略无法集中评审 |

## 6. 核对结论与遗留事项

### 6.1 模块清单核对：不存在冲突

初稿曾判定「PLAN §6.3 只列 12 个模块、缺 `department`/`worklog`/`collaboration`，并与 §6.1 架构图矛盾」。经逐行复核，该判断错误，予以撤销。

事实如下：

- PLAN §6.3 模块边界表（第 569–583 行）共 **15 行**，包含 `tenancy`、`identity`、`organization`、`authorization`、`department`、`appcatalog`、`lowcode`、`workflow`、`hr`、`worklog`、`collaboration`、`integration`、`plugin`、`ai`、`platform`。
- 执行计划第 125 行列同样 15 个模块，集合与 PLAN 完全一致，仅 `hr` 与 `worklog` 的书写顺序不同，无语义差异。
- PLAN §6.1 架构图中的 `IAM`、`DEPT`、`APP`、`CORE` 是粗粒度逻辑分组（`IAM` 覆盖前四个模块，`APP` 覆盖 `appcatalog`/`lowcode`/`workflow`，`CORE` 覆盖 `platform`），与 §6.3 的模块级表不是同一层级，不构成自相矛盾。

结论：模块清单无需勘误，`backend/modules/` 按 PLAN §6.3 的 15 个模块建立目录即可。模块职责边界、禁止事项与事件契约的细化留给 WP01-01 与后续阶段。

### 6.2 遗留事项

1. **迁移版本号分区规则未定**，由 WP01-03 产出并另记 ADR。
2. **`desktop-agent` 的 .NET 解决方案布局**（单 sln 还是多项目）由 WP01-01 细化。
3. **`frontend/packages/lowcode-runtime` 的包边界**（是否与 `apps/web` 共享类型）由 WP05-01 细化。

## 7. 证据与边界

- 本 ADR 仅产出决策记录，未创建任何代码、构建文件、依赖或环境配置。
- 依据文档：`PLAN.md` Frozen 1.2 §13.3（L1173–L1200）、§6.1（L520–L553）、§6.3（L565–L583）、§6.4（L587–L594）、§17.1（L1538–L1548）；《IMPLEMENTATION_PLAN.md》§3.1（L108–L126）、§32（L1304–L1314）。
- G0 确认后，本文件随 PLAN 与执行计划一并登记为受控基线，记录哈希、批准人和日期。

## 8. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-11-09 | 首次定稿，解决 PLAN §13.3 与执行计划 §3.1 的目录结构冲突 |
| 1.1 | 2026-11-09 | 撤销「模块清单 12 vs 15」的错误判定，改为 §6.1 核对结论；目录结构决策不变 |
| 1.2 | 2026-11-09 | 勘误已落地：`PLAN.md` 升级为 Frozen 1.2.1（§13.3 补 `backend/db/`、根级 `tests/`、补全模块注释）；执行计划升级为 v1.1 |
| 1.3 | 2026-11-09 | 新增 WP02-07、WP02-08 后 Phase 2 由 6–7 周调整为 8–9 周；`PLAN.md` 升为 Frozen 1.2.2、总基线 94–116 周，执行计划升为 v1.2 |
