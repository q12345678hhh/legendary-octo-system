# AI 原生企业应用管理平台从零到正式落地执行计划

> **For agentic workers:** 后续只有在用户再次确认进入实施后，才可执行本计划。实施时必须逐工作包推进，使用复选框记录状态；任何阶段不得跳过租户隔离、权限负例、员工隐私、数据迁移、审计和恢复验证。

**Goal:** 从空白工作区开始，逐步建设、试点并正式运营 Frozen 1.2 定义的 AI 原生多租户企业应用管理 SaaS，首批落地部门工作区、工时周报与模块治理，最终完成完整 HRM、低代码、集成插件和执行型 AI。

**Architecture:** 采用 Windows 工作助手加 React Web、Java 21 Spring Boot 模块化单体、独立 Worker、PostgreSQL、Redis、对象存储、隔离插件执行器和模型网关。先交付稳定的组织权限与业务 API，再开放低代码、插件和 AI 写操作；任何特殊启用只能跳过老板事前业务批准，不能跳过技术或安全门禁。

**Tech Stack:** Windows 11、VS Code、Git、Java 21、Spring Boot、Spring Modulith、Maven、React、TypeScript、Vite、PostgreSQL、Flyway、Redis、S3 兼容对象存储、Docker Compose、.NET 8 Windows 客户端、OpenAPI、Testcontainers、Playwright、OpenTelemetry。

**Spec:** `PLAN.md` Frozen 1.2.2。本文件只负责实施顺序、工作包、证据和阶段门禁；产品范围、权限规则、风险边界与验收口径以 `PLAN.md` 为准。两份文件冲突时停止实施，先更新并重新确认冻结规格。

**版本：** v1.2（2026-11-09），对应规格 Frozen 1.2.2。相对 v1.0 的变更：按 ADR-0001 统一仓库目录结构；新增 WP02-07（平台运营端）、WP02-08（删除、保留与数据主体请求）；WP01-01 增加领域事件目录与三类契约治理；补齐量化 NFR 验收阈值（依据 `docs/nfr-acceptance-register.md`）；明确五域分支覆盖门槛与三项验收口径。相对 v1.1 的变更：Phase 2 由 6–7 周调整为 8–9 周，总基线由 92–114 周上调为 94–116 周。产品范围与验收口径未变。

## 全局约束

- 当前轮次只编制计划，不创建项目、不安装依赖、不修改开发环境、不编写应用代码。
- 所有业务表强制包含 `tenant_id`；应用层授权和 PostgreSQL RLS 同时生效。
- 微信、QQ 及平台强制排除社交软件不得采集、上传、派生摘要或用于管理分析。
- AI 只生成工时草稿；只有草稿所属员工本人可以确认并手动提交。
- 普通员工只能进入本人工作页；主管只能进入本人和当前生效管理范围内下属工作页；老板可查看全公司已提交工作域，但不能查看未确认草稿、原始采集和独立高敏 HR 域。
- 特殊启用只允许已经技术发布、测试与安全门禁通过的 M0/M1 模块，范围锁定在主管本部门。
- 每一项写操作都必须有授权、幂等、审计和失败恢复设计；允许执行的高风险动作需要人工审批，R4 动作永远拒绝。
- Flyway 迁移只能前向追加；禁止修改已进入共享环境的历史迁移文件。
- 每个工作包必须产生可复核证据。没有证据的“完成”视为未完成。
- 个人开发者基线为 94–116 个有效开发周；27–37 个月是 P50 规划基线，37–49 个月是 P80 管理预期。

## 评审重点

1. 组织调动、兼岗、代管、汇报线变更后，旧主管、缓存、搜索索引和旧文件链接必须在目标时限内失效。
2. 主管特殊启用不能绕过制品发布、安全扫描、依赖、迁移、权限差异、测试和回滚点，也不能扩大到其他部门。
3. Windows 适配器失败、伪装进程、恶意窗口标题、断网和跨日恢复不能导致禁止内容上传或重复工时。
4. 低代码、插件和 AI 均不得形成绕过后端权限、审批、RLS、审计或数据分类的第二通道。
5. 薪酬、员工关系、敏感导出、外部系统写入和 R3 AI 必须保持职责分离与老板事前批准；R4 AI 不得通过审批转为可执行。

---

## 1 使用方法

### 1.1 状态规则

每个复选框只能使用以下状态：

- `[ ]` 未开始。
- `[~]` 进行中，必须在同一行注明负责人和预计完成日。
- `[x]` 已完成且证据已归档。
- `[!]` 被阻断，必须链接阻断记录、负责人和下一次复核时间。
- `[-]` 经批准取消，必须链接范围变更记录。

### 1.2 工作包完成定义

每个工作包只有同时满足以下条件才可标记为完成：

- 前置条件全部满足，需求与边界没有未决冲突。
- 设计、接口、数据迁移、权限和威胁分析已经评审。
- 正向测试、失败测试、跨租户和越权负例全部通过。
- 日志、指标、追踪、审计、告警和运行手册已经接入。
- 从上一受支持版本升级成功，失败可回退或前向修复。
- 页面包含加载、空、错误、无权和部分失败状态。
- 演示场景通过，截图、日志、测试报告和审批记录归档。
- 没有阻断缺陷和高危安全缺陷；中危缺陷有负责人和日期。

### 1.3 证据目录约定

计划实施时创建以下目录；本轮不创建：

```text
docs/
  adr/                         # 不可逆或跨模块技术决定
  product/                     # 冻结规格、变更请求、需求追踪
  architecture/                # 组件图、数据流、威胁模型
  api/                         # OpenAPI、事件、Webhook、插件和 AI 工具契约
  security/                    # 数据分类、DPIA、红队、渗透和事件记录
  runbooks/                    # 发布、回退、恢复、停机和事故处理
  training/                    # 管理员、员工、主管、老板和搭建者材料
  evidence/
    phase-00/ ... phase-11/    # 每个阶段的门禁证据
  decisions/
    scope-change/              # 范围变化和重新估算
```

每份证据至少记录：日期、环境、版本或提交、租户、执行人、命令或操作、期望、实际结果、失败截图/日志、结论和复核人。

### 1.4 会议和节奏

- 每日：开发者更新工作包、阻断、测试和风险，不用长日报。
- 每周：演示一个可运行纵向切片；核对范围、缺陷、隐私和安全事件。
- 每两周：发布内部候选版，运行完整冒烟、迁移、租户和权限测试。
- 每阶段结束：举行正式门禁评审，只能“通过、带条件通过、退回整改”。
- Phase 0、Phase 3、Phase 5 结束：根据真实完成速度、缺陷和返工重新估算日期与资源。
- 每季度：恢复演练、高权限复核、依赖升级、AI 红队和路线图复盘。

## 2 参与角色与最低责任

| 角色 | 最低责任 | 不能替代的角色 |
|---|---|---|
| 产品负责人 | 冻结范围、排序、接受业务演示、决定范围变化 | 不能代替安全/隐私专业判断 |
| 个人开发者 | 设计、实现、测试、文档、发布准备和缺陷修复 | 不能独自批准薪酬、权限和高风险 AI 上线 |
| 企业老板受托人 | 模块正常启用、重大范围和试点批准 | 不能删除审计或绕过技术门禁 |
| 部门主管代表 | 部门流程验收、下属范围确认、特殊启用业务说明 | 不能批准跨部门和 M2/M3 模块 |
| HR/薪酬业务专家 | HR 口径、黄金算例、职责分离和业务签字 | 不能兼任全部薪酬制单/复核/批准角色 |
| 安全与隐私复核人 | 威胁模型、DPIA、渗透、事件和安全暂停 | 不能借安全角色读取无关业务正文 |
| 试点员工 | 验证工作助手、工时、周报和隐私体验 | 不能代表所有岗位和软件场景 |
| 运维受托人 | 生产部署、备份恢复、告警和事故响应 | 不能修改业务审批结果 |

个人开发阶段允许一人承担多种“执行工作”，但职责分离要求的批准和复核必须由另一名真实人员完成。缺少相应业务或安全专家时，相关高风险功能不得进入生产。

默认 RACI：个人开发者对实现与证据负责（R），产品负责人对普通业务工作包验收负责（A）；涉及隐私/安全时安全与隐私复核人共同签字（C/A），涉及 HR/薪酬时 HR/薪酬专家签字（C/A），涉及生产运行时运维受托人签字（C/A），试点员工和部门主管为实际使用验收人（C）。模块启用由企业老板受托人负责最终业务批准（A）。每个工作包启动时必须把默认 RACI 落成具体姓名；一人不得同时承担受职责分离限制的 R 与 A。

## 3 计划中的仓库和环境

### 3.1 仓库边界

```text
PLAN.md                 # Frozen 1.2.2 规格
backend/                # Maven 父工程，聚合下列模块
  boot-api/             # API 进程装配
  boot-worker/          # 异步作业、通知、同步、报表和 AI 作业装配
  modules/              # 15 个业务模块
  db/                   # 集中迁移：建表、RLS 策略、种子数据
    src/main/resources/db/migration/
  test-support/         # Testcontainers、租户与安全测试夹具
frontend/               # pnpm workspace
  apps/web/             # React 管理端和员工端
  packages/api-client/  # OpenAPI 生成的客户端
  packages/design-system/
  packages/lowcode-runtime/
desktop-agent/          # .NET 8 Windows 工作助手
  windows-app/
  adapters/
  privacy-engine/
plugin-runner/          # 隔离插件执行器
contracts/              # OpenAPI、事件、Webhook、插件和 AI 工具模式
tests/                  # 契约、端到端、性能、安全与红队测试
deploy/                 # Docker Compose、反向代理、观测和环境模板
docs/                   # 规格、ADR、运行手册、培训和证据
```

模块化单体内部按业务边界拆分 `tenancy`、`identity`、`organization`、`authorization`、`department`、`appcatalog`、`lowcode`、`workflow`、`worklog`、`hr`、`collaboration`、`integration`、`plugin`、`ai` 和 `platform`。模块只能调用公开应用服务或事件，禁止跨模块读取内部表。

### 3.2 环境顺序

| 环境 | 用途 | 数据 | 发布权限 |
|---|---|---|---|
| Local | 开发者日常开发和快速测试 | 合成数据 | 开发者本地 |
| CI | 自动构建、测试、扫描和迁移 | 每次重建 | CI 服务账号 |
| Dev | 集成调试和阶段演示 | 合成租户 | 开发者，受审计 |
| Test | 端到端、权限、迁移、性能和用户验收 | 匿名化/合成 | 受控流水线 |
| Staging | 生产同构预发布和恢复演练 | 最小匿名样本 | 发布审批后 |
| Production | 正式租户 | 真实数据 | 双人批准、流水线执行 |

生产密钥、模型密钥、对象存储凭据和数据库账号不得进入源码、镜像、聊天记录或普通日志。Dev/Test/Staging 与 Production 使用不同账号、存储桶、网络边界和密钥。

## 4 总体门禁和里程碑

| 门禁 | 预计阶段 | 必须证明的结果 | 通过后允许 |
|---|---|---|---|
| G0 计划确认 | Phase 0 前 | Frozen 1.2、执行计划、角色和范围确认 | 创建工程和安装依赖 |
| G1 技术风险通过 | Phase 0 | RLS、动态数据、工作流幂等、插件隔离、AI 工具、工作助手隐私尖峰 | 建设正式工程底座 |
| G2 安全底座通过 | Phase 2 | 多租户、身份、组织、权限和撤权负例通过 | 开发真实业务数据 |
| G3 首批业务通过 | Phase 3 | 部门工作区、个人工作页、手工工时、周报和模块建议可用 | 接入自动工作内容理解 |
| G4 工作助手试点通过 | Phase 4 | 禁止应用上传为 0、本人提交、语音、离线和删除通过 | 扩大自愿试点 |
| G5 模块平台通过 | Phase 5 | 正常/特殊启用、风险、沙箱、发布、回滚和审计闭环 | 开发可安装模块包 |
| G6 参考模块通过 | Phase 6 | 三个模块可安装、升级、停用、恢复 | 扩展 HRM 和客户模块 |
| G7 HRM 通过 | Phase 8 | 完整 HRM 和薪酬黄金算例通过 | HR 试点和迁移 |
| G8 扩展边界通过 | Phase 9 | 集成和插件无法绕权、可对账回放 | 开放外部系统连接 |
| G9 执行型 AI 通过 | Phase 10 | R3 未审批执行为 0、R4 执行为 0、动作有回执和停机 | 小范围开放 AI 写操作 |
| G10 GA 批准 | Phase 11 | 功能、安全、性能、恢复、培训和试点签字 | 正式商业运营 |

---

## 5 Phase 0 确认 基线与最高风险验证

**周期：** 2 周。  
**目标：** 在投入长期开发前，验证最可能推翻架构或隐私边界的六类风险。  
**退出门禁：** G1。

### WP00-01 范围与责任确认

**前置条件：** 用户确认进入实施；Frozen 1.2 不再有未决产品问题。

- [ ] 将 `PLAN.md` 和本文件登记为受控基线，分配版本、负责人和批准人。
- [ ] 建立需求追踪表，把每个 AC、风险和范围外条目映射到阶段与证据。
- [ ] 明确老板受托人、部门主管代表、HR/薪酬专家、安全隐私复核人和试点员工。
- [ ] 记录 27–37 个月 P50 与 37–49 个月 P80，不把 P50 当固定合同承诺。
- [ ] 建立范围变更表；任何新增固定模块必须替换范围、增加人员或延长日期。

**交付物：** 批准记录、角色表、追踪矩阵、首版风险登记册、变更模板。  
**验收证据：** 所有 P0 需求有阶段归属；所有职责分离角色有真实人员；无“以后再定”的关键边界。

### WP00-02 开发机和工具基线

**前置条件：** G0 通过。

- [ ] 盘点 Windows 版本、虚拟化、CPU、内存、磁盘、证书和代理限制。
- [ ] 锁定 Java 21、Maven、Node、pnpm、.NET 8、Docker Desktop、PostgreSQL、Git 和 VS Code 的补丁版本。
- [ ] 设计一键环境检查；输出缺失、版本不符、端口占用和磁盘不足，不自动修改机器。
- [ ] 明确 Windows 路径与 WSL 使用规则，统一换行、编码、时区和文件权限。
- [ ] 定义依赖升级频率、LTS 策略和已知漏洞处置时限。

**交付物：** 环境清单、版本锁定表、安装说明、环境检查规范。  
**验收证据：** 一台干净 Windows 测试机可按文档准备；版本差异可被检查发现。

### WP00-03 六类技术尖峰

- [ ] 尖峰 A：验证 PostgreSQL RLS 在缺失/伪造租户上下文、批量、连接池复用时默认拒绝。
- [ ] 尖峰 B：验证低代码类型化动态字段、关系、索引和 10 万记录代表性查询。
- [ ] 尖峰 C：验证工作流重复回调、超时重试、并行任务和补偿不会重复产生副作用。
- [ ] 尖峰 D：验证插件执行器无数据库直连、文件系统和未授权网络访问。
- [ ] 尖峰 E：验证 AI 工具注册、权限再检查、审批绑定、幂等和回执最小链路。
- [ ] 尖峰 F：验证 Windows 工作助手在内容读取前识别微信/QQ，完成本地过滤、Edge/Office/VS Code 适配、设备身份和主动语音转写。

**交付物：** 六份尖峰报告、失败记录、候选 ADR 和是否继续的结论。  
**验收证据：** 每个尖峰含正向与绕过尝试；任何无法证明的风险必须在 G1 退回，不得以“开发中再解决”通过。

### WP00-04 初始威胁模型和隐私评估

- [ ] 绘制浏览器、API、Worker、数据库、对象存储、工作助手、插件和模型供应商之间的数据流。
- [ ] 标记租户、认证、文件、HR、薪酬、工时原始内容、语音、插件和 AI 信任边界。
- [ ] 对工作内容采集编制个人信息影响评估草案，写明目的、最小化、排除、保留、员工控制、申诉和停用。
- [ ] 定义 C0–C3 数据分类和默认保留期；C3 不得进入普通日志、搜索、模型上下文或特殊启用。
- [ ] 建立安全事件分级、证据保全、通知和安全暂停流程。

**交付物：** 威胁模型、DPIA 草案、数据分类、保留矩阵、事件流程。  
**验收证据：** 每个高风险数据流有控制人、预防、检测和恢复措施。

### WP00-05 G1 评审和重新估算

- [ ] 汇总尖峰结果、风险、实际工时、未知项和需外部专家确认的事项。
- [ ] 决定每个 ADR：接受、修改、替代或停止。
- [ ] 重新估算 Phase 1–11，记录 P50、P80 和资源缺口。
- [ ] G1 评审只允许通过、带条件通过或退回整改。

**退出标准：** 六类风险有可重复证据；工作助手证明强制排除先于读取；RLS 和插件边界可验证；用户批准进入 Phase 1。

## 6 Phase 1 工程与运行底座

**周期：** 4–5 周。  
**目标：** 建立可重复构建、部署、迁移、观测和恢复的产品壳。

### WP01-01 仓库和持续集成

- [x] 创建约定仓库结构、分支保护、代码所有者、格式化和提交规范。
- [x] 按 ADR-0001 建立六个构建任务：`backend`（含 `boot-api`、`boot-worker`、`modules`、`db`、`test-support`）、`frontend`、`desktop-agent`、`plugin-runner`、`contracts`、`tests`。
- [x] CI 运行静态检查、单元测试、模块边界、依赖漏洞、SBOM、容器扫描和秘密扫描。
- [x] 建立领域事件目录与版本兼容规则：事件名使用过去时与版本号，信封字段固定，首批事件与 `contracts/` 保持同步。
- [x] OpenAPI、领域事件、插件协议三类契约全部纳入 CI 破坏性变更检测，缺任一类视为未覆盖。
- [x] 接入 JaCoCo 覆盖率检查基础设施与 `backend/coverage-scope.yaml` 模板，此时只设非回归门槛（不低于主分支基线）。
- [x] 缓存只能提升速度，清空缓存后构建结果必须一致。
- [x] 每个候选版生成不可变版本、制品哈希和依赖清单。

**交付物：** 仓库、CI 流水线、分支策略、制品清单。
**验收证据：** 干净检出可一次构建；故意引入秘密、高危依赖或三类契约的未批准破坏性变更都会阻断流水线。

**完成记录（2026-11-09）：**

| 项 | 落点 | 证据 |
|---|---|---|
| 仓库结构与概念 | `backend/`（20 个 reactor 模块）、`frontend/`、`desktop-agent/`、`plugin-runner/`、`contracts/`、`tests/` | `docs/evidence/wp01-01/backend-build-1.txt`、`frontend-verify.txt`、`desktop-agent-verify.txt`、`plugin-runner-smoke.txt` |
| 分支保护与评审 | `docs/runbooks/ci-pipeline.md` §1、`.github/CODEOWNERS` | 已启用并回读验证：`docs/evidence/wp01-01/branch-protection.txt`（10 项必需检查、`enforce_admins=true`、禁强推/禁删除、直推 main 被 `GH006` 拒绝、PR #1 全绿后 squash 合并）；CI 断言见 `tests/ci/workflow-gates.test.mjs` |
| 格式化与提交规范 | `.editorconfig`、`.gitattributes`、`commitlint.config.mjs` | `commit-lint` 任务 |
| 六任务流水线 | `.github/workflows/ci.yml` | `tests/ci/workflow-gates.test.mjs` 断言六任务齐备且门禁不得降级；GitHub Actions 实跑记录见 `ci-runs-first-green.txt`（运行 #7 全绿） |
| 模块边界 | `@ApplicationModule` 声明 + `ApplicationModules.verify()` | `backend/boot-api/src/test/.../ModuleStructureTest.java`（15 个模块） |
| 依赖漏洞 | pnpm audit（high）、OWASP dependency-check（CVSS ≥ 7）、`dotnet list package --vulnerable` | `frontend-verify.txt`（升级 Vitest 4.1.12 后归零）；`dependency-check-before.txt` / `dependency-check-after.txt`（升级 Spring Boot 后 24 个高危 CVE 归零） |
| SBOM | CycloneDX Maven 2.9.3、`pnpm sbom`、Trivy、`dotnet CycloneDX` | `release-manifest.json` 的 `dependencyManifests` |
| 容器扫描 | Trivy image（CRITICAL/HIGH 阻断） | `plugin-runner` 任务；`ci-runs-first-green.txt`（修复 zlib1g CVE-2026-76844 / CVE-2026-85091 后转绿） |
| 秘密扫描 | gitleaks 全历史 | `secret-scan` 任务（`continue-on-error` 被断言禁止） |
| 事件目录与版本规则 | `contracts/events/`（信封 7 字段 + `employee.hired.v1`） | `tests/contracts/three-category-coverage.test.mjs` |
| 三类契约破坏性变更 | `contracts/tools/contract-check.mjs` + 基线 + 审批 | `contracts-gate.txt`；`tests/contracts/breaking-change-blocks.test.mjs`（四例均阻断） |
| 覆盖率非回归 | `backend/coverage-scope.yaml` + `backend/tools/coverage-gate.mjs` | `coverage-gate.txt`（指令覆盖率 86.21%，基线 0） |
| 缓存无关一致性 | `project.build.outputTimestamp` 固定 | `reproducible-build.txt`（两次 clean verify 的 jar 与 SBOM 哈希一致） |
| 不可变版本与制品哈希 | `tools/artifact-manifest.mjs` | `release-manifest.json`（7 个制品 + 2 份依赖清单） |

**遗留与说明：** ①容器扫描当前仅覆盖 `plugin-runner`，`boot-api` 镜像随 WP01-02 接入；②本机工作区路径含中文，JaCoCo agent 无法落盘，覆盖率证据取自 ASCII 路径干净副本（CI 无此问题）；③分支保护已于 2026-11-11 在仓库设置中启用（`docs/evidence/wp01-01/branch-protection.txt`），当前批准数为 0，加入第二位协作者后必须改为 1 并开启 code owner 审核。

### WP01-02 本地和测试拓扑

- [x] 用 Docker Compose 编排 PostgreSQL、Redis、对象存储、邮件捕获、观测组件和应用依赖。
- [x] Web、API 和 Worker 支持 VS Code 调试与容器化验收两种运行方式。
- [x] 工作助手在真实 Windows 测试机运行，不能用容器模拟替代原生验收。
- [x] 配置开发数据重建、端口检查、健康检查和环境变量模板。
- [x] 证明一条受保护请求写库、产生 outbox、被 Worker 消费并贯穿 trace_id。

**交付物：** 本地运行说明、Compose 拓扑、健康检查和端到端冒烟。  
**验收证据：** 新机器在文档时间内启动；单组件失败可定位；重建不依赖手工改库。

**进展记录（2026-11-11）：**

| 项 | 落点 | 证据 |
|---|---|---|
| Compose 拓扑 | `deploy/compose/compose.dev.yaml`（5 个基础设施 + `--profile app` 的 api / worker / web）、`deploy/compose/.env.example` | `docs/evidence/wp01-02/container-acceptance.txt` |
| 一键起栈与健康等待 | `scripts/dev-up.ps1`（端口检查、Docker 回退 WSL、`-Reset` / `-Down` / `-App`） | 冷启动 7s（仅基础设施）、45–46s（含应用容器） |
| 两种运行方式 | `.vscode/launch.json`（API / Worker / 组合 / 附加 5005 / Web）、`.vscode/tasks.json`、`backend/boot-api/Dockerfile`、`backend/boot-worker/Dockerfile`、`frontend/apps/web/Dockerfile` + `nginx.conf` | 容器档实测见证据文件；本机进程调试路径沿用 WP01-01 起已通过的 `mvn` 构建 |
| 本地运行说明 | `docs/runbooks/local-dev.md` | 端口与账号表、单组件定位表、重建与冒烟步骤 |
| 端到端 trace | `POST /api/v1/system/smoke/ping-events` → `platform_outbox` → Worker | 201 + 响应回写关联头；outbox `published` / `attempts=1`；Worker 日志 `correlationId` / `traceId` 与请求一致 |
| 工作助手原生验收 | `desktop-agent/privacy-engine/.../Checks` 的 `--live` 采样，win-x64 发布后在本机运行 | `docs/evidence/wp01-02/desktop-agent-native-windows.txt`（Windows 11 26200 桌面；真实 QQ 12 个、Weixin 5 个进程全部排除） |

**遗留与说明：** ①`bitnamilegacy` 的 MinIO 归档镜像仅限本地；②容器扫描仍只覆盖 `plugin-runner`，应用镜像的扫描随发布流水线接入；③工作助手原生验收只覆盖隐私边界与可运行性，托盘、状态提示与语音属 Phase 4 范围。

### WP01-03 数据库迁移和恢复基线

- [x] 建立 Flyway 版本规则、校验和门禁、回滚决策和生产锁评估；迁移集中在 `backend/db/` 单一线性历史，模块不得自建迁移目录，版本号全局唯一并按全序编排。
- [x] 从上一个受支持版本升级到候选版成功且校验和一致，并在生产级数据量下给出锁等待评估结论。
- [x] 使用真实 PostgreSQL 运行迁移、RLS、并发、索引和连接池测试。
- [x] 建立加密备份、对象存储清单、恢复到隔离环境和一致性校验。
- [x] 记录 RPO/RTO 计算方法；本阶段完成最小恢复演练，不冒充 GA 恢复结果。

**交付物：** 迁移规范、备份脚本规范、恢复运行手册草案。  
**验收证据：** 从空库与上一版本均可升级；一次失败恢复演练有修复和复测证据。

**进展记录（2026-11-11）：**

| 项 | 落点 | 证据 |
|---|---|---|
| 单一线性历史与版本规则 | `backend/db/src/main/resources/db/migration`（V1 基线、V2 租户 RLS、V3 outbox 重试追踪）、`flyway-maven-plugin`（`outOfOrder=false`、`validateOnMigrate=true`、`cleanDisabled=true`） | `docs/runbooks/db-migration.md` 第 1 节 |
| 校验和门禁 | `scripts/db-verify.ps1` 第 6 步：篡改 `flyway_schema_history.checksum` 后 `validate` 必须失败，还原后必须通过 | `db-verify.txt`（`flyway:validate 按预期失败（exit=1）`） |
| 上一版本升级 | 先 `-Dflyway.target=2` 迁移，再升到最新；断言 V1/V2 校验和与空库逐条一致 | `db-verify.txt`（三次运行 checksum 均为 `1|-421171951`、`2|-1218073849`、`3|-1157903501`） |
| 锁等待评估 | `transactional-lock=false` 解掉 `CREATE INDEX CONCURRENTLY` 的永久挂起；V3 执行期间 `50-lock-probe-writer.sql` 持续短事务写入 | 50 万行 outbox：迁移 3.5s、探针 1501 条语句最慢 16.7ms、超 1s 的 0 条（见 `db-verify.txt`） |
| RLS 与并发 | `tests/db/10-rls-cases.sql` 9 条正负例（无上下文、伪造租户、跨租户读写、空串上下文、提交后不残留、`SET ROLE` 被拒）；两个独立会话 `FOR UPDATE SKIP LOCKED` 必须拿到不同行 | `db-verify.txt`（`RLS 用例全部通过（9/9）`；会话 1/2 取得不同事件 ID） |
| 索引 | `V3__outbox_retry_tracking.sql` 的 `platform_outbox_tenant_status_idx`；`30-index-plan.sql` 断言投递扫描不得退化为全表扫描 | `db-verify.txt`（`Index Scan using platform_outbox_dispatch_idx`） |
| 连接池 | `backend/db/src/test/java/.../ConnectionPoolTenantContextTest.java`：单连接池证明 `SET LOCAL` 提交后不残留、会话级 `SET` 会泄漏、两条连接并发各看各租户 | `db-verify.txt` 第 7 步（`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`） |
| 加密备份与对象存储清单 | `scripts/db-backup.ps1`：容器内 `pg_dump -Fc` → AES-256-CBC（PBKDF2 20 万次）→ 删除明文 → 上传 MinIO → `mc stat` 核对 → 写 manifest | `docs/evidence/wp01-03/backup.txt`、`manifest.sample.json`（密文 12,864 字节，两个 SHA-256，恢复基准 tenants=2 outbox=4） |
| 隔离恢复与一致性 | `scripts/db-restore-drill.ps1`：损坏密文必须解密失败 → 校验正式备份哈希 → `--network none` 独立实例 `pg_restore` → 比对版本/行数/聚合摘要 | `docs/evidence/wp01-03/restore-drill.txt`（`wrong final block length`、四项校验全通过、全流程 4s） |
| 规范与手册 | `docs/runbooks/db-migration.md`（版本规则、门禁、回滚决策、锁评估、RLS 模板）、`docs/runbooks/backup-restore.md`（备份规范、清单字段、恢复步骤、RPO/RTO 计算） | 两份手册 + `docs/evidence/wp01-03/README.md` 的失败与修复记录 |
| CI 门禁 | `.github/workflows/ci.yml` 新增 `db` 作业：`postgres:16` 服务容器 + 创建 `platform_app`（NOBYPASSRLS）+ 重跑同一脚本 | 与本地同一套断言，不依赖开发机状态 |

**遗留与说明：** ①锁等待结论只基于 50 万行样本，GA 前按 NFR-23 在生产同构容量重跑；②备份为定时逻辑备份，没有 WAL 归档，RPO 达不到 15 分钟，PITR 与密钥轮换属 WP11-05；③恢复演练不含对象存储附件与审计链；④`platform_outbox` 暂未启用 RLS，跨租户投递需要受治理的 dispatcher 角色，随 WP01-04 落地。

### WP01-04 通用平台技术骨架

- [x] 实现统一请求上下文、认证主体占位、租户上下文、Problem Details 和关联 ID。
- [x] 实现事务 outbox、幂等 inbox、异步作业、死信、进度、取消和安全回放骨架。
- [x] 建立文件预签名上传、病毒扫描状态、下载再授权和对象存储隔离骨架。
- [x] 接入结构化日志、指标、追踪和告警；日志默认不记录业务正文。
- [x] 建立前端登录壳、路由、设计 token、无障碍、错误、空和加载状态。

**交付物：** 最小产品壳、公开契约、观测仪表板和故障运行手册。  
**验收证据：** 重复请求不产生重复副作用；文件 URL 不能跨主体复用；trace 可关联页面、API、数据库和 Worker。

**进展记录（2026-11-11）：**

| 项 | 落点 | 证据 |
|---|---|---|
| 请求上下文与 Problem Details | `platform` 模块的 `RequestContext`/`RequestContextHolder`/`TenantTransactions`（事务内 `set_config('app.tenant_id', …, true)`）、`ApiException` + `ProblemDetailsSupport`、boot-api 的 `HttpContextFilter`；错误响应统一含 `code/detail/instance/correlationId/traceId` | `docs/evidence/wp01-04/files.txt` 的 422/403/404 响应体与 `X-Correlation-Id`/`X-Trace-Id` 响应头 |
| 幂等 inbox | `IdempotencyService`：键 + 请求体 SHA-256 指纹；命中回放原响应并带 `Idempotent-Replayed: true`，键 24 小时过期，同键换载荷 422 | `idempotency.txt`（两次 201 同一 jobId、`platform_job` 仅 1 行、换载荷 `IDEMPOTENCY_KEY_REUSED`） |
| 事务 outbox 与死信回放 | `platform_outbox`（V1/V3）+ `JdbcOutboxStore`（`FOR UPDATE SKIP LOCKED`）+ `DeadLetterService`（原因 8–500 字、同租户、写 `platform_outbox_replay` 审计） | `dead-letter-replay.txt`（attempts=2 → 422 → 202 pending → 审计 1 行） |
| 异步作业、进度与取消 | `JobService` + `JobRequestedEventHandler`：分步写进度、每步查 `cancel_requested`、非 `queued` 直接跳过（幂等） | `jobs.txt`（`succeeded`/100%、`cancelled`/`cancelRequested=true`） |
| 文件链路 | 自写 SigV4 预签名（不引 SDK）、`complete` 后扫描（`clean`/`infected`）、下载前再授权、对象键 `<tenant>/<fileId>/<name>` | `files.txt`（PUT 200、跨主体 403 `FILE_NOT_OWNED`、跨租户 404、10s 后 403 `Request has expired`） |
| 可观测性 | `logging.structured.format.console=ecs`、`PlatformMetrics`、MDC 贯穿 API 与 Worker；API 走 `/actuator/prometheus`，Worker 走周期汇总日志 | `metrics.txt`、`trace.txt`、`log-hygiene.txt`；手册 `docs/runbooks/observability.md`、仪表板 `deploy/observability/grafana-dashboard.json` |
| 前端骨架 | hash 路由 + 会话、登录壳、`AsyncState`（loading/ready/empty/error/partial）、设计 token 递归导出 CSS 变量、跳转链接与路由焦点管理 | `frontend-verify.txt`（typecheck/test/build 全绿，`apps/web` 15 个用例） |
| 运行手册 | `docs/runbooks/platform-skeleton.md`（幂等冲突、死信回放审批、扫描卡住、Worker 停机、下载拒绝）、`docs/runbooks/observability.md`（字段/指标/告警/trace 检索） | 两份手册 + `docs/evidence/wp01-04/README.md` 的失败修复记录 |
| CI | `mvn -B install` + 覆盖率门禁 + 依赖漏洞、前端 typecheck/test/build/audit、db 作业跑 `db-verify.ps1`、密钥扫描 | `ci-run.txt`：Run 42973191622（`a309190`）`completed successfully`，10 个作业成功、`commit-lint` 在 push 上跳过；前两次失败运行（`9edafe5`、`fb37305`）一并留档 |

**遗留与说明：** ①占位认证（请求头直信）仅用于骨架，WP02-02 必须替换，当前禁止在共享环境启用；②预签名 URL 是 bearer 凭证、无法单次使用，GA 前评估代理下载或一次性令牌；③Worker 指标当前只进汇总日志，接入 OTLP 采集器后改为抓取；④`platform_outbox` 仍不开 RLS（跨租户投递需要），API 侧死信查询强制租户过滤，独立 dispatcher 角色留待后续 WP。

### WP01-05 Windows 工作助手技术骨架

- [x] 建立托盘状态、显式开始/暂停/结束、私人模式和不可隐藏的采集提示。
- [x] 建立设备注册、设备密钥、策略签名验证、版本报告、撤销和更新回滚。
- [x] 使用 Windows 安全存储保护本地缓存；定义离线队列、过期删除和崩溃恢复。
- [x] 不提供管理员远程秘密开启接口；任何策略只能收窄或增加排除。

**交付物：** 工作助手最小壳、设备协议和升级运行手册。  
**验收证据：** 撤销设备后无法上传；员工未开始或已暂停时采集量为 0；状态始终可见。

**完成情况（WP01-05）：**

| 项目 | 落点 | 证据 |
| --- | --- | --- |
| 托盘壳与状态机 | `desktop-agent/windows-app/WorkAssistant.WindowsApp`（WinForms `NotifyIcon` + 置顶提示窗 + `TrayIconFactory` 状态图标）、`WorkAssistant.Core/TrayStatePresenter`、`PrivacyEngine` 状态机 | `docs/evidence/wp01-05/indicator.txt`（6 项）、`capture-gating.txt`（采集门禁与提示，含真实链路暂停零采集） |
| 采集门禁 | `WorkAssistant.Core/CaptureGate`、`PrivacyEngine/ForbiddenApplicationPolicy`、`adapters/ForegroundActivityCapture`（运行时接入） | `offline-checks.txt` 中未开始/暂停/私人模式/上传关闭四类零采集断言；`docs/evidence/wp01-05-followup/`（假探针与真实桌面采样） |
| 设备协议 | 7 个 `/api/v1/work-assistant/...` 端点（`contracts/openapi/platform-api.v1.json`）、`worklog/device`+`policy`+`update`、`DeviceIdentity`/`DeviceApiClient`/`AgentRuntime` | `device-registration.txt`（注册、策略验签、版本心跳与最低版本门禁）、`policy-signature.txt`（未知字段/整体覆盖 422、无签名 400、无远程开始端点 404） |
| 撤销 | `POST /devices/{id}/revoke`、客户端 `HandleRevoked` | `device-revocation.txt`（上传 403 `DEVICE_REVOKED`、队列清空、采集停止） |
| 安全存储与离线 | `Storage/DpapiVault`（CurrentUser + 设备熵、原子写）、`OfflineQueue`（72h TTL、隔离区）、`LocalStateStore` | `secure-storage.txt`、`offline-queue.txt`、`crash-recovery.txt`（重传由 `client_segment_id` 去重） |
| 升级与回滚 | `UpdateService`（清单验签、包 SHA-256、拒绝降级、健康检查失败回滚）、`adapters/ZipPackageInstaller`（ZIP 载荷覆盖、整目录备份、回滚删除新增文件、拒绝目录穿越） | `update-rollback.txt`；`docs/evidence/wp01-05-followup/offline-checks.txt`；运行手册 `docs/runbooks/work-assistant-upgrade.md` |
| 数据库 | `V5__work_assistant_device.sql`（三表 + RLS FORCE）、`tests/db/40-integrity.sql`、`db-verify.ps1` 版本数 5 | `db-migration.txt`（三表 `rows=1 rls=true force=true`）、`db-verify.txt` |
| 构建与自检 | `desktop-agent.sln`（core/storage/device/adapters/checks/windows-app）、CI 增加 `Device.Checks` 与托盘壳 `--self-check` | `desktop-build.txt`（零警告）、`offline-checks.txt`（53 项，收口后 63 项） |

**遗留与说明（WP01-05）：** ①真实窗口/进程采集器已接入运行时（`adapters/WindowsWindowProbe` + `ForegroundActivityCapture`），离线假探针与真实桌面采样证据见 `docs/evidence/wp01-05-followup/`；②更新安装器已有生产实现（ZIP 载荷覆盖、整目录备份、回滚、目录穿越拒绝），更新包的下载与员工确认入口仍随发布流水线接入；③托盘图标按状态生成（未记录灰、记录中绿、暂停琥珀、私人模式紫）；④服务端策略固定 `uploadEnabled=true`，关闭上传为客户端本地止损能力；⑤同机多租户设备密钥按租户分目录存放，切换租户需重新注册。

**Phase 1 退出标准：** 环境、构建、迁移、备份、追踪、异步和设备最小链路均有自动证据；阻断缺陷为 0。

## 7 Phase 2 多租户 身份 组织与权限

**周期：** 8–9 周（v1.2 起由 6–7 周上调 2 周，用于承接 WP02-07 平台运营端与 WP02-08 删除、保留及数据主体请求）。  
**目标：** 建成所有业务模块共同依赖的安全企业底座。  
**退出门禁：** G2。

### WP02-01 租户生命周期

- [x] 完成租户创建、初始化、启用、冻结、恢复、注销申请、冷静期和最终删除状态机。
- [x] 完成域名、品牌、语言、时区、功能开关、套餐、配额和使用量。
- [x] 平台运营角色默认不可读租户业务正文；支持访问必须有工单、审批、时限和醒目标识。
- [x] 冻结租户后阻止业务写入、插件和 AI 新执行，只保留受控导出与申诉。

**验收证据：** 两租户同业务编号无冲突；冻结、恢复和删除流程有审计；平台管理员无法直接读取业务数据。

**完成情况（WP02-01）：**

| 项目 | 落点 | 证据 |
| --- | --- | --- |
| 生命周期状态机 | `tenancy/TenantStatus`、`TenantLifecycleService`（create/initialize/freeze/restore/requestClosing/cancelClosing/delete）、`V6__tenant_lifecycle.sql` 状态 CHECK | `docs/evidence/wp02-01/frozen-block.txt`（合法/非法迁移、423/410）、`db-verify.txt`（`TenantLifecycleDbTest` 1/1） |
| 域名、品牌、语言、时区、功能开关 | `tenancy_profile`、`tenancy_domain`（全局唯一 + 每租户唯一 primary）、`UpdateProfileCommand` | `backend-tests.txt`、`db-verify.txt`（15 张租户表 RLS）、契约 `TenantProfile`/`TenantDomain` |
| 套餐、配额与使用量 | `tenancy_plan`（trial/standard/enterprise 与默认配额）、`tenancy_quota` 原子扣减、`QuotaExceeded` 429 | `backend-tests.txt`、`tests/db/60-tenant-lifecycle.sql`；契约 `TenantQuota` |
| 两租户同业务编号 | `tenancy_business_number_sequence` + `tenancy_business_number`（`(tenant_id, scope, business_number)` 唯一） | `two-tenant-numbers.txt`：两租户各自 `WO-000001` 并存，无租户上下文 0 行 |
| 平台不可读业务正文 | `PlatformDomainIsolationFilter`（平台/租户请求头互斥）、平台接口只暴露元数据/配额/审计/支持访问 | `platform-isolation.txt`（401/403 互斥用例 8/8） |
| 支持访问工单与醒目提示 | `SupportAccessRules`、`SupportAccessStore`、`SupportAccessBannerFilter`（banner + 三个 ASCII 响应头 + 只读限制） | `support-access.txt`（独立审批、过期、banner、非 GET 403） |
| 冻结后阻断与保留通道 | `TenantExecutionGate`（业务写/插件/AI 三种 423）、`TenantWriteBlockFilter` 白名单（导出、申诉、撤回注销） | `frozen-block.txt`；运行手册 §3–§5 |
| 注销冷静期与最终删除 | `closing_requested_at`/`cooling_off_until`、`COOLING_OFF_ACTIVE` 409、删除释放域名与支持访问、`deleted` 410 | `db-verify.txt`、`frozen-block.txt`；审计动作链 `tenancy_lifecycle_audit` |
| API 与契约 | `/api/v1/tenant/**`、`/platform/api/v1/tenants/**`；OpenAPI 1.2.0 新增 28 条路径、20 个 schema | `contract-check.txt`（7 份契约 PASS，基线已重冻结） |
| 运行手册与证据 | `docs/runbooks/tenant-lifecycle.md`、`docs/evidence/wp02-01/` | `db-verify.txt`、`backend-tests.txt`、`backend-verify.txt`、`coverage-gate.txt` |

**遗留与说明（WP02-01）：** ①平台运营身份仍是 `X-Platform-Operator-Id` 占位头，真实认证与权限由 WP02-02/02-04 接入，共享环境必须关闭该头信任；②平台跨租户目录与控制台界面属 WP02-07，本包只提供平台域 API；③最终删除仅做状态、审计、域名与支持访问释放，业务数据物理清空属 WP02-08；④冷静期长度只能通过启动配置 `platform.tenancy.cooling-off-days` 调整；⑤补偿：`tenancy_profile` 最初漏写 RLS，由 db-verify 第 5 步拦截并修复，过程见证据 README。

### WP02-02 身份与会话

- [x] 建立 User、TenantMember、Actor、Person 和 Employment 分离模型。
- [x] 完成邀请、激活、密码策略、MFA、会话、设备、API 令牌、服务账号和撤销。
- [x] 高权限强制 MFA；主体禁用或 auth_version 变化后会话及时失效。
- [x] 预留 OIDC/SAML/SCIM 模型，但不让 P1 连接器阻塞 P0。

**验收证据：** 无账号员工、再雇佣、一人多雇佣和账号更换场景通过；过期、撤销和跨租户令牌全部拒绝。

**完成情况（WP02-02）：**

| 项目 | 落点 | 证据 |
| --- | --- | --- |
| 分离模型 | `organization_person`/`organization_employment`；`identity_user_account`/`identity_tenant_member`/`identity_actor`；`identity_member_account_history` | `docs/evidence/wp02-02/identity-scenarios.txt`（无账号员工、再雇佣、一人多雇佣、账号更换） |
| 邀请与激活 | `InvitationService`、`identity_invitation`（只存哈希、一次性）、首成员自动 owner 并强制 MFA | `identity-scenarios.txt`；运行手册 §5 |
| 密码策略 | `PasswordPolicy`（12–128、四类中三类、弱口令、邮箱本地部分、最近 5 次） | `backend-tests.txt`（`PasswordPolicyTest` 3/3） |
| MFA | RFC 6238 TOTP + AES-256-GCM 密钥、10 个一次性恢复码、挑战 5 分钟/10 次；高权限强制 | `identity-scenarios.txt`（注册/确认/挑战/禁止关闭） |
| 会话与撤销 | 32 字节令牌 + SHA-256、绝对 8h/空闲 30m、`plt_session` HttpOnly、CSRF 双提交、`auth_version` 代际失效 | `auth-negative.txt`（401/403/CSRF）、`identity-scenarios.txt`（撤销/过期/代换/跨租户） |
| 服务账号与令牌 | `ServiceAccountService`、Bearer 令牌（≤90 天、只存哈希、停用即撤销） | `identity-scenarios.txt`（跨租户 `TOKEN_INVALID`、撤销 `TOKEN_REVOKED`） |
| 身份提供商预留 | `identity_identity_provider`、`IdentityProviderService`（oidc/saml/scim，active → 409） | `backend-tests.txt`（`IdentityProviderServiceTest`） |
| 数据库与 RLS | `V7__identity_and_session.sql`（16 张租户表，全部 FORCE RLS） | `db-verify.txt`（RLS 31/31、`70-identity.sql`、`IdentityDbTest`） |
| API 与契约 | `/api/v1/auth/**`、`/members/**`、`/service-accounts/**`、`/identity-providers/**`、`/people/**`；OpenAPI 1.3.0 新增 34 条路径、14 个 schema，删除 `X-Subject-Id` | `contract-check.txt`（7 份契约 PASS，基线已重冻结） |
| 运行手册与证据 | `docs/runbooks/identity-session.md`、`docs/evidence/wp02-02/` | `backend-tests.txt`、`db-verify.txt`、`coverage-gate.txt` |

**遗留与说明（WP02-02）：** ①P0 无邮件服务，邀请/重置令牌由接口一次性返回，P1 接邮件后改为只投递；②OIDC/SAML/SCIM 只落模型与配置，`active` 返回 409，不建立外部连接；③平台域 `/platform/**` 仍用运营占位头，独立认证域属 WP02-07；④`/api/v1/people/**` 读接口只要求登录，细粒度数据范围与字段权限由 WP02-04 收口；⑤`X-Subject-Id` 已删除（破坏性变更），基线重冻结后门禁通过。

### WP02-03 组织和生效日期

- [x] 完成法人、部门、岗位、职位、职级、成本中心、地点和主/兼岗。
- [x] 完成实线/虚线汇报、管理委派、未来变更、生效/失效和历史快照。
- [x] 组织和汇报图禁止环；移动部门前生成权限、审批、编制和报表影响预览。
- [x] 老板/最高负责人是独立业务角色，至少一名有效受托人；租户管理员不自动获得正文权限。

**验收证据：** 三级汇报链、兼岗、代管、虚线和调动历史正确；空老板角色会阻断正常模块启用。

**完成情况（WP02-03）：**

| 项目 | 落点 | 证据 |
| --- | --- | --- |
| 组织结构 | `organization_legal_entity`/`organization_unit`/`organization_job_level`/`organization_job`/`organization_position`/`organization_cost_center`/`organization_location`；`OrganizationStructureService` | `org-scenarios.txt`（法人、三级部门、职级/岗位/职位）；`db-verify.txt`（`80-organization.sql`） |
| 主/兼岗与调动 | `organization_employment_assignment`；主岗区间唯一、兼岗可与主岗并存；调动自动把前任主岗截止到新主岗生效日前一天 | `org-scenarios.txt`（主岗+兼岗、调动历史） |
| 汇报线 | `organization_reporting_line`（solid/dotted）；三级实线链、虚线参与判环；自汇报与成环返回 `REPORTING_CYCLE` | `org-scenarios.txt`；`backend-tests.txt`（`ReportingGraphTest`） |
| 管理委派 | `organization_delegation`，区间必填、默认上限 180 天，写入前拒绝超期与自我委派 | `org-scenarios.txt`（限时代管）；`backend-tests.txt`（`EffectiveDatingTest`） |
| 生效/失效与未来变更 | `organization_change` + 非 RLS 系统队列 `platform_organization_due`；`OrganizationChangeApplier` 在 boot-worker 轮询应用到期变更 | `org-scenarios.txt`（排期、撤销、apply-due、立即生效） |
| 历史快照 | `organization_change_history` 只插入，数据库触发器拒绝 UPDATE/DELETE | `org-scenarios.txt`（历史可读、篡改抛 `DataAccessException`） |
| 移动影响预览票据 | `organization_impact_preview`：10 分钟 TTL、单次消费、服务端复算六项计数；缺票据返回 `ORG_IMPACT_PREVIEW_REQUIRED`，重放返回 `ORG_IMPACT_PREVIEW_CONSUMED` | `org-scenarios.txt`；`OrganizationApiTest`（移动缺票据 409） |
| 老板与受托人 | `organization_owner_role`（owner/trustee），`readiness` 在 owner 或 trustee 缺失时阻断并要求独立受托人；租户管理员不自动获得正文权限 | `org-scenarios.txt`（空老板 `ORGANIZATION_NOT_READY`）；`backend-tests.txt`（`OwnerReadinessTest`） |
| 数据库与 RLS | `V8__organization_structure.sql`：14 张租户表全部 ENABLE+FORCE RLS，跨表引用统一 `(tenant_id, id)` | `db-verify.txt`（V8 校验和 `-525129633`、RLS 45/45） |
| API 与契约 | `/api/v1/legal-entities`、`/org-units`、`/job-levels`、`/jobs`、`/positions`、`/cost-centers`、`/locations`、`/employments/{id}/assignments`、`/reporting-lines`、`/delegations`、`/owner-roles`、`/organization/changes`、`/organization/history`、`/organization/readiness`；OpenAPI 1.4.0 新增 36 条路径、27 个 schema | `contract-check.txt`（7 份契约 PASS，基线已重冻结） |
| 运行手册与证据 | `docs/runbooks/organization.md`、`docs/evidence/wp02-03/` | `backend-tests.txt`、`db-verify.txt`、`org-scenarios.txt`、`coverage-gate.txt` |

**遗留与说明（WP02-03）：** ①影响预览当前只统计组织侧可确定的六项（子部门、在职人数、职位、汇报线、代管、排期变更）；权限、审批、编制与报表影响需等 WP02-04 与后续模块接入后补齐，不伪造结论。②排期移动在创建变更单时消费预览票据并冻结计数快照，到期应用前重新计算，数据变化即标记失败，不自动重放。③`platform_organization_due` 是非 RLS 系统队列，仅 boot-worker 跨租户扫描；单条失败只标记 `failed`，需人工重新确认。④老板/受托人以雇佣（Employment）为锚点，租户管理员不因管理员身份自动获得组织正文权限，细粒度权限解释由 WP02-04 收口。⑤OIDC 与邮件不在本工作包范围。

### WP02-04 统一授权决策

- [x] 完成角色、权限、数据范围、字段权限、条件策略、显式拒绝、临时授权和委托。
- [x] 支持本人、直接下属、汇报链下属、指定部门/子树、管理委派、项目空间和全租户范围。
- [x] 同一授权结果覆盖页面、API、搜索、导出、报表、文件和 AI 上下文。
- [x] 实现权限解释器和模拟器；解释本身受安全权限保护。
- [x] 建立权限缓存版本和 60 秒内失效目标，高风险撤权立即生效。

**验收证据：** 缺少租户、主体、权限或范围时默认拒绝；批量、搜索、导出、文件和 AI 工具不能绕过。

**完成情况（WP02-04）：**

| 项目 | 落点 | 证据 |
| --- | --- | --- |
| 权限与角色 | 全局 `authorization_permission` 白名单；系统角色 `SystemRoleCatalog` 写在代码中，租户只能复制为自定义角色后收窄 | `authz-scenarios.txt`（角色/权限目录）；`db-verify.txt` |
| 数据范围 | `SELF`、`DIRECT_REPORTS`、`REPORTING_CHAIN`、`ORG_UNIT`、`ORG_UNIT_SUBTREE`、`DELEGATED`、`LEGAL_ENTITY`、`TENANT`；`PROJECT_SPACE`/`RECORD_SHARED` 显式 `SCOPE_UNSUPPORTED` | `authz-scenarios.txt`（员工、主管、代管人、部门外拒绝） |
| 决策顺序 | 租户与主体 → 成员状态 → 权限存在 → 显式拒绝 → 允许集合 → 范围 → 条件 → 职责分离/风险 hook；`AuthorizationService` | `backend-tests.txt`（`ConditionEvaluatorTest`、`AuthorizationDbTest`） |
| 字段权限 | `authorization_field_policy` + `FieldPolicyEvaluator`；邮箱默认仅本人/HR/老板，字段拒绝覆盖记录级允许 | `authz-scenarios.txt`；`PeopleAccessApiTest`（邮箱可空） |
| 条件策略 | `authorization_condition_policy`，白名单算子 equals/not_equals/in/not_in/gte/lte/exists；缺属性按失败处理 | `backend-tests.txt`（`ConditionEvaluatorTest`） |
| 临时授权与委托 | `valid_to` 到期即失效；高权限必须限时（默认 90 天）；委托用 `delegated_from_id`，范围不得放大、到期不得晚于源（默认 180 天） | `authz-scenarios.txt`（到期、委托子树）；`db-verify.txt`（约束） |
| 缓存与失效 | `authorization_cache_version` + 触发器（授权/拒绝/字段/条件/成员/雇佣/部门/岗位/汇报/委派/老板角色）；本机版本缓存 1 秒、决策缓存 60 秒；高风险撤权清本机缓存 | `authz-scenarios.txt`（版本递增、缓存命中）；`db-verify.txt` |
| 解释器与模拟器 | `POST /authorization/decisions/explain`、`/simulations`；需 `authorization.decision.explain`，模拟额外要求 MFA，不落库 | `AuthorizationApiTest`（解释权限、MFA 403） |
| 人员收口 | `GET /people` 按决策下推范围，新增 `q`、`format=csv`、`POST /people/batch-read`；未命中与不存在统一 404 | `PeopleAccessApiTest`；`authz-scenarios.txt` |
| 文件接入 | 下载/完成前按 `file.read` 判定，资源归属交给范围解析，保留所有者兜底 | `backend-tests.txt`；`FileAccessApiTest` 用例并入人员越权套件 |
| 数据库与 RLS | `V9__authorization.sql`：8 张租户表 ENABLE+FORCE RLS，决策日志只插入，跨表引用 `(tenant_id, id)` | `db-verify.txt`（RLS 53/53） |
| API 与契约 | 授权权限目录、角色、授权、拒绝、字段/条件策略、判定/解释/模拟、缓存版本、审计；人员搜索/导出/批量；OpenAPI 1.5.0 新增 15 条路径、22 个 schema | `contract-check.txt`（7 份契约 PASS，基线已重冻结） |
| 运行手册与证据 | `docs/runbooks/authorization.md`、`docs/evidence/wp02-04/` | `backend-tests.txt`、`db-verify.txt`、`authz-scenarios.txt`、`coverage-gate.txt` |

**遗留与说明（WP02-04）：** ①职责分离规则与访问复核由 WP02-05 实现，本包只固定调用点并默认放行。②`project_space` 与 `record_shared` 尚无实体，一律 `SCOPE_UNSUPPORTED`，不静默降级为租户范围。③组织管理接口仍使用既有 `IdentityGuard` 管理员守卫；人员、文件、搜索/导出/批量与授权治理已统一到 `AuthorizationGuard`。④AI 工具尚未注册，本包提供同一守卫与测试替身，工具执行链接入时必须复用。⑤系统角色模板升级不回溯改写租户已复制的自定义角色。

### WP02-05 职责分离和访问复核

- [x] 定义权限申请/批准、薪酬制单/复核/批准、插件上传/安装、AI 工具注册/评级/启用冲突。
- [x] 高权限默认限时并记录依据；季度产生访问复核。
- [x] 禁止管理员修改自身审计、批准自己的高权限或兼任被禁止角色组合。

**验收证据：** 每条职责分离规则有正负用例；批量接口、后台作业和 AI 也受同一规则约束。

### WP02-06 多租户攻击测试

- [x] 使用两个相似租户测试 UUID 猜测、列表、批量、搜索、导出、附件、缓存、作业、Webhook 和 RLS。
- [x] 测试连接池复用、缺失 RLS 变量、异常事务和只读分析角色。
- [x] 测试对象存储路径、预签名 URL、搜索索引和日志关联隔离。
- [x] 自动化跨租户越权套件在双租户同业务编号、同姓名数据上通过率 100%，失败即阻断 G2。

**验收证据：** `tests/db/92-cross-tenant.sql`（58 张 FORCE RLS 表逐表 UUID 猜测矩阵、列表/批量/搜索/导出/附件/作业/授权/复核/审计、变量注入与连接复用）；`CrossTenantAttackDbTest`（17 个服务层用例）；`TenantHeaderMismatchTest`（5 个头部用例）；`tests/security/isolation-invariants.test.mjs`（4 条静态不变量）；证据见 `docs/evidence/wp02-06/`。

**遗留与说明（WP02-06）：** ①`platform_outbox` 按设计不启用 RLS（Worker 必须跨租户投递），隔离责任落在查询显式携带 `tenant_id`，用例断言该设计而非假装有 RLS。②Webhook 投递表尚未实现，攻击面暂由 Outbox/作业用例替代，脚本以 `RAISE NOTICE` 显式登记。③受 FORCE RLS 保护的租户表总数为 58（`40-integrity.sql` 的 57 张 + `tenancy_tenant` 自身），新表纳入时该计数与矩阵断言会同时失败，强制同步更新。

### WP02-07 平台运营端

PLAN §5.2 的六个界面此前没有工作包承接，本工作包补齐。

- [x] 建立 `/platform/api/v1` 独立认证域；平台运营账号不进入任何租户认证域。
- [x] 运营总览：租户数、活跃度、容量、错误率、AI 成本、集成健康；指标来自聚合，不含业务正文。
- [x] 租户管理：生命周期、套餐、配额、功能开关、域名和支持访问（工单、审批、时限、醒目标识）。
- [x] 平台目录：内置应用版本、连接器、插件签名、模型供应商。
- [x] 安全运营：异常登录、跨租户阻断、秘密轮换、漏洞与事件。
- [x] 作业与审计：迁移、备份、恢复演练、异步队列、平台级审计。
- [x] 平台设置：邮件、对象存储、模型网关和全局限制；不在此展示租户业务正文。
- [x] 导航项按权限隐藏，但直接访问路由仍由后端鉴权。

**验收证据：** 平台账号无法读取任一租户业务正文；六个界面均有对应的后端鉴权用例；支持访问全过程有工单、审批、时限与审计记录。证据见 `docs/evidence/wp02-07/`，运行手册见 `docs/runbooks/platform-operations.md`。

**遗留与说明（WP02-07）：** ①本包只做后端与鉴权用例，六个界面的前端实现不在本包。②`X-Platform-Operator-Id` 退役属破坏性变更：旧平台脚本必须改为 `POST /platform/api/v1/auth/login` 换 `plt_ops_session` Cookie，OpenAPI 升 1.7.0 并重冻结基线。③平台元数据表（目录、指标、安全事件、运营账号、会话、审计、AI 成本、作业运行）故意不启用 RLS，与 `platform_outbox` 同例；隔离责任落在「只写聚合与元数据、不写业务正文」，由 `93-platform-ops.sql` 与 `PlatformScreenAuthzTest` 断言。④运营账号的创建/停用/改角色尚无管理接口，当前只由启动引导与环境变量注入。

### WP02-08 删除、保留与数据主体请求

- [ ] 普通删除进入回收站，默认 30 天；法律留置、工资结果和审计不允许普通删除，被留置阻止时给出明确原因。
- [ ] 个人信息删除请求流程：识别法定义务、业务关系与备份传播，可匿名化的标识替换后保留统计事实，全流程留痕。
- [ ] 审计默认保留 7 年、业务日志默认 180 天的配置项与合规下限校验。
- [ ] 恢复与清空操作不得复活已删除内容或扩大权限。

**验收证据：** 回收站到期清理、留置阻止删除、匿名化替换、删除请求案件闭环各有正负用例与审计记录。

**Phase 2 退出标准：** 跨租户成功读写为 0；组织变更和撤权缓存满足目标；G2 由产品与安全共同签字。WP02-07 与 WP02-08 不阻断 G2，但必须在 G4 前完成，否则不得进入工作助手试点。

## 8 Phase 3 部门工作区 平台服务 手工工时与模块建议

**周期：** 10–12 周。  
**目标：** 交付老板和员工能直接使用的第一批完整业务。  
**退出门禁：** G3。

### WP03-01 部门空间和成员职位表

- [ ] 组织中每个有效部门自动拥有一个部门空间；调动保留历史归属。
- [ ] 第一屏固定表格展示成员、职位、汇报层级、今日工时状态、周报状态、授权文件和工作页入口。
- [ ] 姓名/职位公开字段与工时、周报、任务和文件使用不同权限决策。
- [ ] 无权单元格不返回数量、标题、摘要、文件名或可猜测链接。
- [ ] 员工从部门首页到本人工作页不超过两次点击。

**验收证据：** 员工、主管、老板三套端到端旅程；浏览器网络响应证明无权字段未传到前端。

### WP03-02 个人工作页和关系权限

- [ ] 工作页聚合工时、周报、通用任务、目标、工作动态、授权文件、会议行动项和历史提交。
- [ ] 员工仅本人；主管为本人加当前实线管理范围；限时管理委派按授权；老板为全租户已提交工作域。
- [ ] 项目成员只能访问项目范围投影，不能进入他人完整工作页。
- [ ] 未确认草稿、原始采集、私人频道、薪酬、银行和员工关系不受老板工作域权限放开。
- [ ] 查看他人页面、文件预览、下载、导出和分析下钻全部审计。
- [ ] 员工可查看“谁在何时因何权限访问过我的已提交工作数据”的访问记录；仅安全调查所需技术字段可受限隐藏。

**验收证据：** 下级看上级、同级互看、旧主管、虚线经理、项目成员和旧链接越权全部失败；员工能核对本人已提交工作数据的访问历史。

### WP03-03 部门协作最小原语

- [ ] 建立全员、限定成员和跨部门频道；支持主题帖、评论、@提及、置顶、确认回执和归档。
- [ ] 建立部门文件、版本、标签、引用、预览和下载；频道权限不自动扩大文件 ACL。
- [ ] 建立通用任务、看板、里程碑、会议纪要、决定记录、行动项和知识页最小版。
- [ ] 人员退出频道时扫描直接分享链接；权限在目标时限内失效。
- [ ] 企业搜索和通知只索引/发送当前主体有权看到的内容。

**验收证据：** 撤权后的搜索、通知、旧链接、缓存和文件下载均失败；敏感频道不会出现在无权人的统计中。

### WP03-04 管理洞察最小版

- [ ] 提供工时/周报提交率、逾期、任务状态和部门投入的权限感知统计。
- [ ] 聚合个人洞察的最小群组阈值为 5，租户只能提高；低于阈值抑制结果。
- [ ] 阈值不阻止主管/老板按关系权限查看单个员工已提交记录。
- [ ] 未确认草稿和原始采集不得进入排名、绩效或纪律分析。

**验收证据：** 小群体、筛选后小群体和差分查询不能反推个人；下钻再次鉴权。

### WP03-05 手工工时与周报闭环

- [ ] 员工可手工创建、修改、合并、拆分和删除当日工时草稿。
- [ ] 本阶段只支持手工文本补充线下会议；正式按键录音、转写、语音状态和音频删除在 Phase 4 交付。
- [ ] 下班时间生成汇总草稿；员工本人当前会话携带版本才能提交。
- [ ] 未确认草稿次日保留并提醒；主管只见未提交状态，不能见草稿正文。
- [ ] 提交形成不可变快照；主管可审核、评论或退回，退回创建新草稿版本。
- [ ] 周报只引用已确认工时，员工补充问题和下周计划后本人提交。

**验收证据：** 主管、管理员、AI、服务账号和批量作业代交成功为 0；重复提交和离线重试不产生重复记录。

### WP03-06 通用工作流和审批

- [ ] 完成定义版本、实例、人工任务、审批、条件、并行、定时器、服务任务、SLA、转交、退回、撤回和补偿。
- [ ] 审批人可按人员、岗位、部门负责人、汇报链和角色解析；为空进入异常队列。
- [ ] 高风险流程不得因超时默认批准；服务任务声明幂等、重试、补偿和人工入口。

**验收证据：** 老版本运行中实例不受新版本影响；重复回调不重复写业务；无审批人不会自动通过。

### WP03-07 模块目录和建议最小版

- [ ] 建立应用/模块目录、模板、负责人、版本、依赖、适用范围、健康和安装占位。
- [ ] 全员可发起模块建议，填写业务问题、目标、范围、数据、权限、成本、收益和负责人建议。
- [ ] 上级记录沟通意见；老板可受理、驳回或要求补充；受理后指定配置负责人。
- [ ] 完成草稿、沟通中、待受理、配置中、测试中、整改、测试失败、待最终批准、驳回、撤回、启用、特殊启用待复核、暂停、关闭和弃用状态定义。
- [ ] 本阶段不交付完整低代码配置和生产特殊启用，只完成状态机与页面骨架。

**验收证据：** 非法跳状态被后端拒绝；老板为空时正常受理/启用阻断；所有决定有通知和审计。

### WP03-08 G3 用户验收和重新估算

- [ ] 选择一个合成部门，包含老板、主管、三级下属、同级、兼岗、调动和项目成员。
- [ ] 连续运行至少两周手工工时与周报流程。
- [ ] 记录到达本人工作页点击数（目标 ≤ 2 次）、提交率、退回率、主管处理时效、权限失败和用户问题；点击路径以录制或截图留证。
- [ ] 依据实际页面数、缺陷、返工和周速率重估 Phase 4–11。

**Phase 3 退出标准：** 部门、个人工作页、手工工时、周报、协作和模块建议可演示；关系权限负例通过；用户批准进入工作助手阶段。

---

## 9 Phase 4 Windows 工作助手与 AI 工时草稿

**计划工期：** 12–16 个有效开发周。  
**前置门禁：** G3 已通过；适用的数据处理基础、劳动政策、员工隐私告知、必要的员工代表程序、自愿试点安排和强制排除清单已经由业务/法律/隐私责任人确认；不得仅以一份“同意文本”替代适用义务。  
**阶段目标：** 在设备侧只采集允许的最小工作信号，按员工设置的整点或定时间隔形成分段摘要，在下班时生成由员工本人审核和提交的工时草稿。

### WP04-01 采集政策与强制排除引擎

- [ ] 将应用分为强制排除、租户排除、员工排除、允许适配和未知默认拒绝五类；优先级不可被低级规则覆盖。
- [ ] 微信、QQ 及租户指定社交、个人通信、密码管理器、银行、医疗等进程和域名进入签名政策包。
- [ ] 身份识别至少使用签名发布者、可执行文件哈希/路径、包身份、进程树和窗口归属，不只依赖进程名或标题。
- [ ] 未知应用只记录不可逆的“未知工作时段”本地占位，默认不上传标题、正文、URL、截图或输入内容。
- [ ] 提供员工可见的采集状态、暂停、排除、删除当天本地记录和举报误采入口；管理员不能远程秘密开启。
- [ ] 策略包签名、版本化、灰度和回滚；策略过期或验签失败时停止采集并告知员工。

**验收证据：** 伪装进程、改名程序、嵌套浏览器、通知弹窗和剪贴板诱导不能让强制排除内容进入本地事件或网络请求；抓包结果归档。

### WP04-02 应用适配框架

- [ ] 首批适配 Edge/Chrome、Microsoft Office、VS Code、文件资源管理器和批准的业务软件。
- [ ] 优先使用稳定的应用 API 或可访问性语义；Windows UI Automation 仅作受限回退，不做屏幕持续录制。
- [ ] 每个适配器声明可采字段、数据分类、失败模式、最小应用版本和测试样本。
- [ ] 适配器进程隔离、超时、熔断、资源限额和崩溃恢复；单个适配器异常不得阻塞客户端。
- [ ] 版本不兼容时降级为应用类别和时间段，不猜测具体工作内容。

**交付物：** 适配器协议、兼容矩阵、首批适配器、失败降级说明和测试报告。

### WP04-03 本地最小化、敏感过滤与加密缓存

- [ ] 原始事件先在本地做字段白名单、敏感词/格式过滤、重复折叠，并只标记“可能空闲/低活动候选”，再生成最小事实卡片；候选不得自动扣减工时。
- [ ] 禁止键盘记录、密码字段、聊天正文、邮件正文全文、屏幕持续录制和后台麦克风录音。
- [ ] 本地缓存绑定当前 Windows 用户并使用系统密钥保护；设置容量、保留期和自动销毁。
- [ ] 上传前再次执行强制排除和敏感内容检测；无法判断时不上传并提示员工处理。
- [ ] 诊断日志只含事件类型、适配器版本和错误码，不写窗口标题、文档正文或语音转写。

**验收证据：** 客户端缓存被复制到另一账户或设备后不可解密；到期、退出试点和卸载后的删除结果可验证。

### WP04-04 时间分段、工作引用与草稿生成

- [ ] 员工可选择按每小时或自定义定时段生成摘要，租户只能设置合理上下限。
- [ ] 将前台活动、允许的业务上下文、日历、任务和员工补充合并为证据片段；不把“应用打开”直接等同有效工时。
- [ ] 支持跨应用合并、可能空闲/低活动标记、重叠会议、跨午夜、时区变化、睡眠唤醒和离线恢复；系统不得自动判断阅读、会议、无操作或离开电脑是否为有效工时，最终由员工调整确认。
- [ ] 每段草稿保存来源类别、可信度和可追溯 `work_reference`，但不向主管暴露设备原始事件；其首版可引用通用任务、外部任务或自由项目标签，不依赖 Phase 6 项目模块。
- [ ] 客户端提供“本日采集预览”，让员工在生成分段草稿前查看、排除或删除允许范围内的本日事实卡片。
- [ ] 员工可以修改、合并、拆分、删除、补充项目/任务/说明；所有 AI 建议明确标识。
- [ ] 下班时聚合为当日工时草稿；未确认则保留并在次日提醒，不自动提交、不因超时视为同意。

**验收证据：** 一组覆盖离线、跨日、重复、会议重叠和误分类的黄金日样本，人工核对总时长、说明和引用均一致。

### WP04-05 语音补充

- [ ] 仅在员工主动按下录音或语音输入时采集；界面持续显示录音状态和停止按钮。
- [ ] 先转写为员工可编辑文本，再由 AI 归纳为工时建议；低置信度和歧义必须提问或保留原意。
- [ ] 音频默认在转写确认后删除；若租户需保留，必须单独说明用途、期限、访问角色和删除方式。
- [ ] 语音提到微信、QQ 聊天内容时仅使用员工主动提供的业务概述，不触发对社交软件的自动读取。
- [ ] 转写和摘要供应商调用遵循租户数据驻留、无训练、保留期和敏感数据政策。

**验收证据：** 未主动录音时麦克风访问为零；员工删除语音草稿后服务端、本地和供应商侧按策略清除。

### WP04-06 本人确认、提交与服务端复核

- [ ] 草稿只对本人可见；服务端重新验证租户、归属人、版本、状态、日期和项目权限。
- [ ] 提交必须由本人当前交互会话触发，使用一次性意图和幂等键；主管、老板、管理员、AI 和后台作业均不能代交。
- [ ] 提交前展示日期、总时长、分段、项目和说明；员工明确勾选确认后手动提交。
- [ ] 客户端策略版本过旧、引用失效或总时长异常时阻止或警示，由员工修正，不静默重写。
- [ ] 提交生成不可变快照；后续更正使用新版本和原因，保留前后差异和审计。

**验收证据：** 篡改请求、重放、旧版本、越权代交、双击提交和离线重试的自动化负例全部通过。

### WP04-07 隐私试点与质量指标

- [ ] 先用合成数据，再由 5–10 名自愿员工运行不少于四周；试点外默认关闭。
- [ ] 建立误采率、漏采率、分段准确率、员工修改率、提交率、客户端崩溃率、CPU/内存/电量和支持工单指标。
- [ ] 每周与员工代表复核被排除应用、误分类和不适感；不收集为评估系统不必要的个人内容。
- [ ] 验证未开始、暂停、私人模式和退出登录四种状态下允许/禁止应用的自动采集与上传均为 0，并验证恢复后不追溯读取暂停期间内容。
- [ ] 若发生强制排除误采、后台录音、草稿越权或无法彻底停用，立即暂停试点并启动事件响应。
- [ ] 形成数据保护影响评估更新、试点结论和继续/整改/终止建议。

### WP04-08 G4 评审

- [ ] 产品负责人确认草稿流程符合实际填写习惯；员工代表确认可见、可控、可删。
- [ ] 安全负责人确认强制排除、客户端签名、更新链、缓存和网络证据。
- [ ] 老板只看到试点的已提交工时和匿名质量指标，不看到原始采集和未确认草稿。
- [ ] 根据实测适配成本、质量和隐私风险重估后续客户端维护预算。

**Phase 4 退出标准：** 允许应用能形成可编辑草稿；微信、QQ 等强制排除实现端到端零采集；未确认草稿次日提醒；提交权只属于员工本人；G4 证据批准。

---

## 10 Phase 5 低代码平台与模块全生命周期治理

**计划工期：** 10–12 个有效开发周。  
**前置门禁：** G4 已通过；统一权限、工作流和审计稳定。  
**阶段目标：** 企业可以像积木一样构建业务模块，但任何配置、发布、启用和特殊启用均受统一技术与业务门禁约束。

### WP05-01 元模型和运行时

- [ ] 定义应用、实体、字段、关系、视图、表单、规则、操作、权限、数据分类、事件和版本元模型。
- [ ] 元模型发布后生成受控运行描述，不生成绕过平台的任意后端代码。
- [ ] 每个记录包含租户、模块、所有者、组织范围、状态和审计上下文；运行时调用统一授权服务。
- [ ] 对循环关系、过深查询、超大字段、危险表达式和高成本规则设置静态限制。
- [ ] 元模型版本不可就地修改；草稿、测试、已发布和弃用版本明确分离。

### WP05-02 可视化搭建器

- [ ] 提供实体、字段、表单、表格、看板、详情页、校验、计算字段和权限配置。
- [ ] 提供模板、预览、差异、撤销、复制和依赖提示；配置错误在发布前阻断。
- [ ] 发起人被批准后可作为配置负责人在沙箱搭建；配置权不自动包含生产启用权。
- [ ] 数据分类和权限必须显式配置；默认仅创建者在沙箱可见。
- [ ] 可访问性、移动端响应和多语言键纳入组件验收。

### WP05-03 工作流、通知与报表绑定

- [ ] 低代码模块只能绑定平台工作流、通知、搜索和报表接口，不自建审批旁路。
- [ ] 表达式和自动化使用受限函数、执行配额、超时和幂等语义。
- [ ] 报表查询自动附加租户、组织、字段和记录权限；导出重新鉴权和审计。
- [ ] 高风险字段变化触发二次审批或人工复核，不能配置超时自动批准。

### WP05-04 沙箱、测试、发布、迁移与回滚

- [ ] 每个模块有独立草稿和沙箱数据；生产数据不得随意复制到沙箱。
- [ ] 发布前执行元模型校验、权限差异、迁移预演、自动测试、安全扫描、依赖和回滚检查。
- [ ] 应用管理员发布技术制品；发布不等于租户安装或业务启用。
- [ ] 数据迁移可重试、可审计并有兼容窗口；破坏性变更需备份、维护窗和业务批准。
- [ ] 回滚明确区分制品回滚、配置回滚和数据恢复，不能声称数据库无条件回滚。

### WP05-05 模块申请与常规启用

- [ ] 所有人可发起建议；上级沟通后老板受理，发起人或指定负责人完成沙箱配置。
- [ ] 完成业务验收、技术发布、风险分级、依赖、权限和迁移检查后进入待最终批准。
- [ ] 常规情况下老板选择租户、部门/群组范围、启用时间和数据政策后批准启用；若老板本人是发起人或配置负责人，必须由另一名已授权最高负责人批准，禁止自批。
- [ ] 启用动作再次验证制品哈希、审批版本和范围；任何差异使批准失效并退回。
- [ ] 启用后负责人、支持入口、健康状态、使用率和审计可见。

### WP05-06 M0–M3 风险分级

- [ ] M0 仅允许 C0/C1 数据的只读能力，不含外部写入、公开发布、插件或 AI 高风险动作。
- [ ] M1 仅允许单部门内、C0/C1 数据、可逆且有完整审计的内部写操作，不含任何外部写入。
- [ ] M2 包含跨部门、C2 数据、复杂迁移、插件能力或难以回滚的内部动作，但仍不得包含 M3 条件。
- [ ] M3 包含 C3 数据、薪酬/付款、纪律或关键人事决定、系统权限、敏感导出、任何外部系统写入、公开发布以及 R3/R4 AI 能力；M3 永不允许主管特殊启用。
- [ ] 风险由数据分类、写作用域、外部影响、可逆性、权限增量和自动化程度计算；人工只能提高，降低需安全/合规双人批准。
- [ ] 权限、数据、外部连接或 AI 能力升级时自动重新分级和复审。

### WP05-07 主管特殊启用

- [ ] 仅允许 M0/M1、技术制品已发布、测试/安全/依赖/迁移/回滚门禁全部通过的模块。
- [ ] 主管填写特殊情况、紧迫性、业务影响、启用原因、使用期限建议、数据分类、权限、外部影响、业务/技术负责人、退出方案和范围；系统强制限定为其当前生效部门。
- [ ] 主管可跳过老板事前业务批准并立即启用，但不能跳过应用管理员发布、职责分离或技术安全门禁。
- [ ] 状态变为“特殊启用待复核”，立即通知老板并进入独立队列；系统按租户策略周期提醒老板，但没有复核期限，不因等待自动转为批准或自动停用。
- [ ] 等待复核期间保持审计、健康和异常监控；安全事件可立即暂停，老板可确认、缩小范围、暂停或关闭。
- [ ] 主管离任、部门范围变化或模块升级时重新验证，不沿用旧特殊批准扩大权限。

**验收证据：** M2/M3、未发布、测试失败、无回滚点、跨部门范围和权限增量版本均无法特殊启用；老板长期不复核不会丢失队列或审计。

### WP05-08 所有权、停用与数据处置

- [ ] 模块必须有业务负责人和技术负责人；离职或调动前完成转移，孤儿模块自动告警。
- [ ] 支持暂停、只读、关闭、卸载和弃用；每种状态定义用户行为、API、自动化和数据访问。
- [ ] 停用前处理运行中流程、待审批、定时作业、外部订阅和导出链接。
- [ ] 数据按保留政策归档、导出或删除；卸载不默认删除业务数据。

### WP05-09 G5 评审与重新估算

- [ ] 受训管理员从标准模板开始，在 4 小时内完成一个标准验收应用的配置、测试、技术发布和待启用交付，且未获得越权帮助。
- [ ] 由普通员工发起一个模块建议，完成上级沟通、配置、测试、发布和老板正常启用全链路。
- [ ] 由主管对合格 M1 模块执行特殊启用，老板随后确认、缩围和暂停各测试一次。
- [ ] 验证升级新增权限会撤销旧批准并重新进入评审。
- [ ] 根据实际元模型复杂度、组件复用率和缺陷率重估参考模块与后续生态计划。

**Phase 5 退出标准：** 低代码模块能构建、测试、发布、正常启用和受控特殊启用；受训管理员 4 小时标准应用验收通过；权限与审批无法被配置绕过；迁移和回滚经过实测；G5 通过。

---

## 11 Phase 6 三个参考业务模块

**计划工期：** 6–8 个有效开发周。  
**前置门禁：** G5 已通过。  
**阶段目标：** 用真实模块证明平台不是只围绕 project/agents/workflow，而是可承载多类企业业务。

### WP06-01 项目与任务模块

- [ ] 支持项目组合、项目、里程碑、任务、依赖、负责人、参与人、风险、问题和变更。
- [ ] 工时只能引用员工有权访问的项目/任务；项目角色不得扩大部门工作页权限。
- [ ] 提供计划与实际、延期、负荷和投入报表；所有下钻遵循关系与项目双重权限。
- [ ] 通过平台元模型、工作流、通知、文件和报表实现，记录必要的平台缺口。

### WP06-02 知识与会议模块

- [ ] 支持知识空间、页面、版本、模板、标签、评审、有效期、会议、议题、决定和行动项。
- [ ] 页面可继承或独立权限；复制、引用、搜索和 AI 检索不扩大原始 ACL。
- [ ] 会议纪要可由参与者确认后转任务或知识，不自动视为工时事实。

### WP06-03 表单与事项申请模块

- [ ] 支持公开/定向表单、草稿、附件、重复校验、编号、审批、退回、撤回和归档。
- [ ] 提供物品领用、采购前置申请或通用行政事项模板，不直接实现财务付款。
- [ ] 表单字段、附件、导出和审批意见均执行字段级和记录级权限。

### WP06-04 安装、升级、停用和恢复验证

- [ ] 三个模块分别完成新装、灰度、升级、回滚、暂停、只读、卸载后重装和数据恢复。
- [ ] 验证模块之间依赖、版本兼容、权限差异和失败补偿。
- [ ] 统计定制需求中由配置解决、需要平台增强和必须定制开发的比例。

### WP06-05 G6 评审

- [ ] 业务代表用三个不同场景完成验收，不接受只看演示数据。
- [ ] 平台团队关闭阻断型缺口，并将非阻断缺口进入有优先级的产品待办。
- [ ] 文档包括安装、配置、权限、运营、升级、停用和恢复手册。

**Phase 6 退出标准：** 三类参考模块在同一平台独立运行并共享基础能力；生命周期测试通过；证明平台具备“积木式扩展”能力。

---

## 12 Phase 7 完整 HRM 第一波

**计划工期：** 10–12 个有效开发周。  
**前置门禁：** G6 已通过；高敏 HR 数据域、职责分离和保留政策获批。  
**阶段目标：** 交付人员、岗位编制、招聘、入转调离、考勤、排班和休假闭环，为薪酬与绩效提供可信主数据。

### WP07-01 人员和雇佣主数据

- [ ] 区分自然人、用户账号、员工编号、雇佣关系、合同和岗位任职，支持一人多雇佣关系。
- [ ] 建立法定姓名、常用名、联系方式、证件、地址、紧急联系人、合同和附件的数据分类与字段权限。
- [ ] 建立银行账户、税务身份、缴纳地和法定申报字段的独立高敏授权、双人变更复核和完整审计。
- [ ] HR 高敏域不因老板角色自动开放；按职责、目的和最小权限单独授权。
- [ ] 所有生效日期、历史修正、未来变更和撤销可追溯；禁止用覆盖当前行替代历史。
- [ ] 提供数据完整性、重复人员、即将到期和异常关系检查。
- [ ] 提供员工自助维护允许字段、经理自助发起允许流程；自助界面不能扩大本人/下属范围或修改受控法定字段。

### WP07-02 岗位、编制和组织规划

- [ ] 建立职务、岗位、职级、序列、成本中心、地点、编制和预算占位。
- [ ] 招聘需求、入职和调动必须引用有效岗位或记录例外批准。
- [ ] 组织和岗位变更经过预演，检查汇报链循环、主管空缺、超编和权限影响。
- [ ] 生效时触发工作区、个人页、文件、报表和审批范围失效/新增事件。

### WP07-03 招聘管理

- [ ] 支持职位需求、发布渠道、候选人、简历、阶段、面试、评价、Offer 和入职转换。
- [ ] 候选人同意、保留期、重复识别、删除请求和外部招聘方访问可管理。
- [ ] 面试评价在提交前相互隔离；敏感或歧视性字段被限制并审计。
- [ ] Offer 审批与薪酬权限分离；候选人转员工时保留来源并防止重复创建。

### WP07-04 入职、转正、调动与离职

- [ ] 按人员类型和岗位生成清单，涵盖账号、设备、培训、文件、权限和部门介绍。
- [ ] 转正、续签、调动、借调、返聘、晋升、降职和离职均为有生效日期的流程，不直接改历史。
- [ ] 离职在指定时点撤销会话、工作助手、部门、文件、模块和集成权限，转移工作所有权。
- [ ] 紧急离职支持立即停权和事后材料补录，但不删除审计。

### WP07-05 考勤与排班

- [ ] 支持班次、周期、节假日、时区、打卡、补卡、迟到早退、加班和异常处理。
- [ ] 明确考勤事实与 AI 工时草稿不同：两者可对账但不得互相自动覆盖。
- [ ] 排班变更通知员工并保留版本；跨日班和夏令时按地点规则处理。
- [ ] 规则参数化并带生效日期，法律/公司政策变化不改写历史结果。
- [ ] 考勤周期支持汇总、员工确认/申诉、主管处理、HR 复核、结算和锁定；锁定后更正使用新调整记录，不覆盖原结果。

### WP07-06 休假与假期

- [ ] 支持假期类型、资格、额度、累积、结转、过期、借支、申请、取消和余额调整。
- [ ] 余额计算可重放并生成明细账；人工调整需原因、审批和审计。
- [ ] 休假与排班、考勤、日历、工时提醒联动，但不自动代交工时。
- [ ] 医疗等敏感附件与审批结论分离，主管只见完成业务决策所需信息。
- [ ] 出差申请、行程、差旅时段与休假/排班冲突校验纳入时间管理；费用报销仍属于后续独立模块。

### WP07-07 基础 HR 分析与 Phase 7 内部检查点

- [ ] 提供人数、流动、编制、招聘漏斗、入职完成、考勤异常和休假余额报表。
- [ ] 每个 HR 指标保存名称、业务定义、分子分母、数据源、时间口径、版本、生效日期和负责人，历史报表固定其口径版本。
- [ ] 人群分析实施最小群组阈值、字段掩码、导出审批和目的审计。
- [ ] 用跨部门调动、兼岗、再入职、未来入职和紧急离职黄金场景回归。
- [ ] HR、主管、员工和安全负责人分别签署其范围的验收。

**Phase 7 退出标准：** 员工全生命周期与时间管理闭环运行；组织变更能驱动权限收敛；高敏 HR 数据不会被普通管理视图透出。

---

## 13 Phase 8 完整 HRM 第二波

**计划工期：** 11–13 个有效开发周。  
**前置门禁：** Phase 7 主数据连续两个周期稳定；薪酬规则和法律责任由具备资质的业务/法律人员确认。两个稳定周期及后续两个薪酬影子周期可能形成自然日等待，必须计入 P50/P80 日历，但不一定都计入有效开发周。  
**阶段目标：** 完成薪酬、福利、绩效、调薪、学习、人才和员工关系，并建立可复核的计算与隐私机制。

### WP08-01 薪酬计算核心

- [ ] 定义薪资周期、项目、规则、税前税后、补发、追溯、舍入、封顶、币种和地点版本。
- [ ] 输入来自已锁定的人员、合同、考勤、休假、奖金和调整快照，不查询随时变化的当前态。
- [ ] 计算为可重放批次，保存输入快照、规则版本、中间结果、输出和差异解释。
- [ ] 制单、复核、批准和发放文件导出职责分离；任何人不能独立完成全链路。
- [ ] 生成带版本、币种、项目明细、扣款、税费、实发和更正说明的工资单；员工仅见本人，雇主成本与个人工资分权展示。
- [ ] 先影子运行至少两个周期，与现有结果逐人逐项对账，达到约定差异阈值后才可切换。

**停止条件：** 未取得适用法律/税务确认、无法重放、职责分离失败或黄金样本存在未解释差异时，禁止用于实际发薪。

### WP08-02 福利管理

- [ ] 支持福利方案、资格、参保、家属、等待期、员工/公司缴费、变更窗口和停保。
- [ ] 福利与薪酬交换使用版本化接口和对账，不直接跨域修改结果。
- [ ] 健康等敏感资料单独加密和授权，主管不见诊断详情。
- [ ] 福利供应商文件在生成、加密、传输、回执、拒绝和重发全链路对账；不得把供应商返回成功等同业务已生效。

### WP08-03 绩效与 OKR

- [ ] 支持周期、目标、关键结果、持续反馈、自评、主管评价、校准、申诉和归档。
- [ ] 支持能力项、行为标准、多方评价、评价人保密规则和员工结果确认；匿名承诺必须与实际最小样本一致。
- [ ] AI 工时和设备活动不得自动形成绩效分数、纪律结论或隐性排名。
- [ ] 校准会话有参与范围、盲区控制和导出审计；最终结果需人工确认。
- [ ] 组织调动时明确评价责任和历史可见性，不将新主管自动开放全部旧材料。

### WP08-04 调薪与奖金评审

- [ ] 支持预算池、建议、区间、绩效参考、审批、模拟和生效前冻结。
- [ ] 汇总预算与个人薪酬使用不同权限；导出和批量调整需双人控制。
- [ ] 经批准的结果以生效日期进入薪酬输入，不允许直接改已结算批次。

### WP08-05 学习与人才

- [ ] 支持课程、计划、报名、学习记录、考试、证书、到期提醒和必修合规。
- [ ] 支持技能、人才盘点、继任岗位、候选人、发展计划和九宫格，但限制推断性敏感属性。
- [ ] AI 可建议学习内容或技能标签，员工/HR 可纠正，不能自动做晋升或淘汰决策。
- [ ] 保留风险或人才建议必须展示可解释的人工可核对事实、置信与限制，由有权人员复核；不得自动生成保留、晋升或淘汰决定。

### WP08-06 员工关系

- [ ] 支持案件、分类、当事人、调查任务、证据、访问墙、决定、申诉、保留和法律保全。
- [ ] 案件不进入一般老板视图、部门工作页、企业搜索或普通 AI 检索。
- [ ] 紧急授权、冲突回避、外部顾问访问和下载全部单独审批和审计。

### WP08-07 HR 分析、隐私与 G7

- [ ] 交付人员成本、薪酬、绩效、学习、人才和员工关系的权限化分析。
- [ ] 所有 HR 指标沿用版本化口径；口径变化不重写历史，跨期比较必须标注版本差异。
- [ ] 敏感统计实行最小群组、目的限制、列级脱敏、导出审批、水印和异常下载告警。
- [ ] 完成薪酬黄金样本、双周期影子运行、绩效校准、员工关系隔离和删除/保留测试。
- [ ] 由 HR 负责人、薪酬负责人、安全/隐私负责人和老板分别签署职责范围内的 G7。

**Phase 8 退出标准：** 完整 HRM 业务闭环通过；薪酬可重放且影子对账通过；绩效不使用未确认设备数据；员工关系保持独立高敏域。

---

## 14 Phase 9 集成与插件生态

**计划工期：** 6–7 个有效开发周。  
**前置门禁：** G7 已通过；平台 API、事件和权限契约稳定。  
**阶段目标：** 对外系统和第三方扩展只能通过受控连接器与隔离插件运行，失败可对账、可撤销、可审计。

### WP09-01 连接器框架

- [ ] 定义连接器清单、认证类型、读写范围、字段映射、频率、速率限制、数据分类和负责人。
- [ ] 凭据只以密钥库引用保存，界面、数据库和日志不出现明文；轮换和吊销可演练。
- [ ] 每个连接器支持禁用、暂停、试运行、断点续传、重试、死信和人工重放。
- [ ] 首批连接器只选择试点真正需要的目录、日历或业务系统，不追求数量。

### WP09-02 同步、幂等与对账

- [ ] 明确每个对象的权威源、方向、外部键、冲突策略、删除语义和延迟目标。
- [ ] 所有写入携带幂等键；重复、乱序、部分成功和超时可恢复。
- [ ] 建立日常对账、差异队列、修复动作和关闭证据；不以“调用成功”替代业务一致。
- [ ] 大批量导入先校验和预览，再分批执行；失败不污染已确认数据。

### WP09-03 Webhook 与事件交付

- [ ] 出站事件签名、重放保护、订阅范围、最小载荷、重试和死信可配置。
- [ ] 入站回调验证来源、时间窗、租户映射和权限，正文大小与频率受限。
- [ ] 事件架构版本化；消费者升级窗口和弃用通知明确。

### WP09-04 插件清单、签名与供应链

- [ ] 插件声明版本、入口、所需权限、网络域名、数据分类、资源配额、依赖和升级说明。
- [ ] 制品必须签名、生成 SBOM、依赖/恶意代码扫描并来自批准仓库。
- [ ] 权限差异、外联域名或数据分类变化触发重新批准；静默升级禁止。
- [ ] 插件市场展示负责人、来源、权限、风险、版本、支持和停用状态。

### WP09-05 隔离执行器

- [ ] 插件在独立执行边界运行，限制文件、进程、网络、CPU、内存、时间和并发。
- [ ] 插件只能通过短期、限域平台令牌访问业务 API；不能直连数据库或模型供应商。
- [ ] 网络默认拒绝，只开放清单域名；响应和日志执行敏感数据过滤。
- [ ] 插件异常、超额或攻击行为触发熔断、隔离和告警，不拖垮主应用。

### WP09-06 G8 评审

- [ ] 用一个读连接器、一个写连接器和一个插件覆盖成功、重复、超时、乱序、凭据轮换和撤销。
- [ ] 完成供应链篡改、越权 API、未声明外联、资源耗尽和升级新增权限负例。
- [ ] 运维人员能够依据手册停用、对账、重放和恢复。

**Phase 9 退出标准：** 集成可追踪到业务一致性；插件无法直连核心数据或无限外联；升级会重新评审权限；G8 通过。

---

## 15 Phase 10 执行型 AI

**计划工期：** 8–10 个有效开发周。  
**前置门禁：** G8 已通过；所有被调用业务动作已有稳定 API、授权、幂等、审计和补偿。  
**阶段目标：** AI 在用户授权和企业政策内计划并执行工作；高风险动作先获得人工审批，执行后返回可验证回执。

### WP10-01 模型网关与数据策略

- [ ] 所有模型调用经过统一网关，执行租户路由、供应商白名单、区域、保留期、无训练、脱敏、限额和成本记录。
- [ ] 提示、响应、工具参数和错误按数据分类决定是否以及如何留存；禁止秘密记录敏感正文。
- [ ] 模型不可直接获得数据库、密钥库、插件运行时或任意网络访问。
- [ ] 模型/提示版本固定到评估结果；升级先离线回归再灰度。

### WP10-02 权限感知知识检索

- [ ] 索引只接收已分类、已授权来源；删除和撤权事件在目标时限内传播。
- [ ] 检索前按租户、主体、关系、模块、记录、字段和用途过滤，生成答案后再次检查引用。
- [ ] 回答展示来源和权限边界；无依据时明确不确定，不拼接无权限片段。
- [ ] 防止通过摘要、缓存、嵌入、搜索建议和引用标题泄露已撤权内容。

### WP10-03 委托与工具注册表

- [ ] 用户委托声明目标、允许工具、对象范围、金额/数量、有效期、可否转委托和撤销方式。
- [ ] 工具注册表声明输入/输出架构、权限、风险级别、幂等、审批、补偿和数据分类。
- [ ] AI 令牌短期、限主体、限租户、限工具和限对象；最终业务 API 仍按原用户/批准人授权。
- [ ] 工具只能由平台受控执行器调用，模型文本不能变成未验证命令。

### WP10-04 计划、风险、审批、执行和回执

- [ ] AI 先形成可解释计划，政策引擎对每一步评估 R0–R4、权限增量和组合风险。
- [ ] R0/R1 可在委托内执行；R2 视租户策略审批；R3 必须明确人工事前审批，且批准绑定动作摘要和参数哈希；R4 永远拒绝，任何角色均不能批准执行。
- [ ] 参数、对象、权限或版本变化使批准失效；禁止用笼统“一键同意”覆盖未来未知动作。
- [ ] 执行使用幂等键、超时、重试和补偿；部分成功要停止并请求人处理，不伪装全部完成。
- [ ] 回执包含执行者、批准人、工具版本、输入摘要、对象、结果、失败、补偿和审计链接。
- [ ] 对执行记录做字段完整性断言：委托、主体、权限决策、工具版本、计划、风险等级、审批（如需）、回执八项 100% 非空，缺一即失败，不采用抽样豁免。

### WP10-05 高风险动作硬限制

- [ ] 发薪、调薪生效、终止雇佣、资金支付、法律提交、跨租户导出、权限管理员授予和大范围删除禁止全自动。
- [ ] 被政策判定为 R4 的动作在工具调用前硬拒绝，不提供“老板批准”“紧急例外”或拆分执行通道。
- [ ] AI 不得以拆分动作、调用低代码、插件、连接器或特殊启用规避总风险判断。
- [ ] 外部发送、批量修改和不可逆动作提供最终预览、明确批准、速率限制和紧急停止。
- [ ] 审批超时默认为拒绝或保持等待，不自动通过。

### WP10-06 注入、SSRF、目标漂移与滥用防护

- [ ] 将外部文档、网页、邮件和插件输出视为不可信数据，不当作系统指令。
- [ ] 工具参数做架构、域名、路径、对象和内容验证；网络请求经过代理白名单以阻断 SSRF。
- [ ] 每一步检查是否仍符合原目标、范围和预算；循环、异常扩张或冲突立即停止。
- [ ] 建立租户级/用户级速率、成本、动作数量和异常模式限制。

### WP10-07 评估、红队和成本

- [ ] 建立权限泄露、错误工具、幻觉参数、提示注入、审批绕过、部分失败、重复执行和敏感输出测试集。
- [ ] 离线评估准确性、完成率、拒绝质量、越权率、补偿率、延迟和单任务成本。
- [ ] 红队用例编号入册且不少于 100 个，覆盖提示注入、恶意文档、越权对象、敏感枚举、审批诱导、SSRF、重复工具调用和目标漂移八类；不得出现成功越权，跨租户、部门上下级、HR 高敏域、插件输出和恶意文件一并纳入。
- [ ] 逐条记录用例结果与修复状态，未闭环用例不得签署 G9。
- [ ] 达不到阈值的能力降级为建议或草稿，不以免责声明替代安全门禁。

### WP10-08 Kill switch、事件处置与 G9

- [ ] 支持按租户、模型、工具、风险级别和全局关闭执行，只保留只读或草稿能力。
- [ ] 关闭后撤销令牌、停止队列、标记在途动作并通知值班人；恢复需双人批准。
- [ ] 触发停机开关后 60 秒内阻止新执行，并记录在途动作的安全收敛终态（完成或停止，无未知状态）。
- [ ] 演练错误批量修改、模型供应商故障、注入攻击和审计缺失的隔离与恢复。
- [ ] G9 由产品、安全、业务所有者、运维和隐私负责人共同签署。

**Phase 10 退出标准：** AI 写操作全部经过工具、权限和风险门禁；R3 事前审批且 R4 执行为零；注入与跨租户测试通过；可一键停止并追溯每一步。

---

## 16 Phase 11 试点、性能、安全、灾备与正式上线

**计划工期：** 7–9 个有效开发周。  
**前置门禁：** G9 已通过；范围内功能、迁移、支持和运营负责人明确。  
**阶段目标：** 从受控试点扩展到可持续运营的正式 SaaS，并以证据完成 G10 上线决策。

### WP11-01 试点租户、部门与数据迁移

- [ ] 选择 1 个内部租户、2–3 个差异化部门和明确的自愿/授权员工群体，不直接全公司上线。
- [ ] 对人员、组织、项目、历史工时和必要 HR 数据做盘点、清洗、映射、预演和业务签字。
- [ ] 提供员工、组织、职位、合同、假期余额、薪资初始值和应用数据七套导入模板；每次导入经历扫描、映射、预检、错误修正、确认、执行和对账，初始数据设置明确的截至日期，原文件按保留策略加密保存或删除。
- [ ] 每次迁移生成源数量、目标数量、拒绝、转换、哈希/抽样对账和回滚点。
- [ ] 试点设退出条件、问题通道、每日值守和旧系统并行/只读策略。

### WP11-02 培训与变更管理

- [ ] 分别制作员工、主管、老板、HR、应用管理员、安全和支持人员的角色化手册。
- [ ] 员工培训突出采集边界、微信/QQ 零采集、暂停/排除、草稿修改、本人提交和申诉。
- [ ] 主管培训突出只能看下属已提交内容、不能看草稿和特殊启用责任。
- [ ] 通过真实任务演练而非只看视频；记录完成率、测验、问题和补训。
- [ ] 发布隐私说明、数据字典、支持渠道、事件通知和变更日历。

### WP11-03 性能、容量与长期稳定性

- [ ] 按 PLAN §2.6 容量基线建立容量模型：1,000 活跃租户、200,000 员工档案、2,000 并发在线、单租户默认 10,000 员工，并覆盖 P50/P80 的事件、文件、搜索、报表、工作流和 AI 调用。
- [ ] 测试峰值登录、部门首页、工时提交、周一报表、批量 HR、迁移、索引和连接器积压。
- [ ] 在容量基线与代表性数据分布下产出压测报告：常规读 API p95 ≤ 400 ms、写 API p95 ≤ 700 ms。
- [ ] 2,000 并发关键旅程错误率 < 1%，并给出连接池、锁等待与 Worker 队列的实测值及阈值依据。
- [ ] 长任务提交接口在 2 秒内返回作业 ID，附接口级计时证据。
- [ ] 对单实例、Worker 与模型供应商分别注入故障，证明核心 HR 事务不被破坏且有明确降级路径。
- [ ] 执行不少于 72 小时稳定性测试，观察内存、连接、队列、磁盘、缓存、索引和任务漂移，保留原始观测数据。
- [ ] 定义各关键路径 SLO、错误预算和扩容触发值，验证限流和优雅降级。

### WP11-04 安全测试与整改

- [ ] 完成威胁模型更新、依赖/镜像/密钥扫描、SAST/DAST、API 越权和多租户渗透测试。
- [ ] 专项测试 RLS、对象存储签名链接、搜索、导出、低代码、插件、工作助手更新链和 AI 工具。
- [ ] 严重和高危问题必须在 G10 前关闭并复测，不允许风险接受后带病 GA；风险接受仅适用于经安全确认的不阻断级问题，并需负责人、期限和补偿控制。
- [ ] 建立漏洞披露、补丁目标、组件清单和紧急版本流程。
- [ ] 按 OWASP ASVS L2 条目编号产出逐项符合性矩阵，每项给出证据或经批准的豁免理由。
- [ ] 核心员工自助与审批页通过 axe/Lighthouse 扫描，并完成仅用键盘的关键旅程走查，达到 WCAG 2.1 AA。
- [ ] 最新两个稳定版 Edge/Chrome 跑通关键旅程冒烟集并输出兼容性矩阵；移动端验证响应式布局。
- [ ] 阻断级与高危安全缺陷为 0；中危缺陷逐条登记风险接受人、修复日期与补偿控制。

### WP11-05 备份、恢复与灾难恢复

- [ ] 定义数据库、对象、配置、密钥引用、审计、搜索和队列的备份责任，目标 RPO ≤ 15 分钟、RTO ≤ 4 小时。
- [ ] 在隔离环境从备份恢复完整租户并实测计时，验证引用、权限、文件、密钥版本、租户隔离边界、审计链和迁移版本一致。
- [ ] 首次完整恢复演练记录随 G10 证据包提交；GA 后按季度执行并留存。
- [ ] 演练误删租户、数据库故障、对象存储丢失、区域不可用和密钥轮换事故。
- [ ] 搜索索引和缓存按源数据重建；恢复过程不能扩大权限或复活已删除内容。

### WP11-06 生产可观测性、值班和支持

- [ ] 日志、指标、追踪和审计带租户与关联 ID，但不写敏感正文和凭据。
- [ ] 监控登录、授权拒绝、工时提交、客户端策略、队列、迁移、连接器、插件和 AI 动作。
- [ ] 建立 P0–P3 告警、值班、升级路径、状态公告、客服分流和问题复盘。
- [ ] 支持工具默认只读、按工单临时提权、全程审计和到期回收；禁止共享管理员账号。
- [ ] 定义月可用性 SLO（≥ 99.9%，计划维护除外）与合成探测方案作为设计证据，GA 后按月出报告。
- [ ] 审计保留 7 年、业务日志保留 180 天的配置证据，以及保留期与合法留置阻止删除的测试记录。
- [ ] 抽样扫描生产级负载日志，证明不含明文秘密与高敏正文；请求、作业、流程、AI 执行与外部调用均带统一关联 ID。
- [ ] 建立模块交付周期、新租户激活时长、HR 数字化覆盖率、审批闭环率、工时与周报按时提交率五项运营指标的采集实现与试点基线，G10 前只要求证明可采集。

### WP11-07 发布、回退与 Go/No-Go

- [ ] 发布清单包含冻结范围、制品签名、迁移预演、兼容性、备份、灰度、监控、值班和回退触发值。
- [ ] 先内部、再小租户/小部门、再逐步扩大；每一波有观察窗和明确继续条件。
- [ ] No-Go 条件至少包括租户隔离缺陷、强制排除误采、薪酬未对账、无法恢复、审计缺口和高危漏洞。
- [ ] 回退区分应用、配置、客户端、模块和数据处置，执行后完成一致性对账。

### WP11-08 G10 正式上线证据包

- [ ] 汇总 G0–G9 签字、需求追踪、测试、渗透、隐私、迁移、性能、恢复、培训、支持和已知风险。
- [ ] 产品负责人确认范围与明确不在范围项；技术负责人确认可运营；安全/隐私确认控制；业务负责人确认试点价值。
- [ ] 老板基于剩余风险、成本、支持能力和回退准备作最终 Go/No-Go 决定。
- [ ] Go 后记录上线版本、租户/部门范围、观察期和下次复盘日期；No-Go 回到对应工作包整改。

**Phase 11 退出标准：** G10 正式批准；生产环境可监控、可支持、可恢复、可回退；首批租户和部门在受控范围稳定运行。

---

## 17 Phase 12 正式上线后的扩展（不属于首版 GA 门禁）

- [ ] 依据真实使用率和业务价值选择 P1 模块：资产、采购、合同、费用、客服、OKR 等；不得一次并行开发全部。
- [ ] 财务核算、供应链深水区、电子签、支付和地区化薪税属于单独立项，重新评估合规、集成和人员投入。
- [ ] 建立模块季度组合评审：新增、继续、整合、暂停、弃用和技术债预算。
- [ ] 用运营数据优化工作助手适配、低代码组件、AI 评估和连接器，但不得降低隐私与审批门槛。
- [ ] 多区域、移动原生、国际化和第三方市场在租户规模与收入证明后再启动。

**Phase 12 原则：** 上线后扩展不是绕过 Frozen 1.2 的后门；任何新高风险能力都要回到风险分级、隐私、安全和上线门禁。

---

## 18 关键路径、执行顺序与并行限制

### 18.1 不可颠倒的主路径

1. 用户确认 G0，才允许安装依赖、创建仓库或编写代码。
2. 完成 Phase 0 六类尖峰并通过 G1，才把技术选择固化到正式工程。
3. 完成租户、身份、组织、统一授权、RLS 和负例测试并通过 G2，才接入真实业务数据。
4. 先完成手工工时闭环，再做自动采集；否则无法区分业务流程问题与识别质量问题。
5. 工作助手先合成测试、再自愿小组试点；强制排除出现一次误采即暂停扩大。
6. 低代码先有元模型和后端强制授权，再有可视化搭建器；不能让前端配置成为安全边界。
7. 插件和 AI 只能调用已稳定、可幂等、可审计、可补偿的业务 API。
8. HRM 先主数据、考勤休假，再薪酬绩效；薪酬先影子运行再成为发薪依据。
9. 性能、渗透、恢复、培训和支持同时通过后，才能进入 G10。

### 18.2 可在同一阶段有限并行的工作

| 主任务 | 可并行任务 | 不可并行或不得提前 |
|---|---|---|
| 后端领域模型 | 对应页面原型、测试样本、文档 | 未冻结契约前大量前端实现 |
| 权限实现 | 权限矩阵、负例用例、审计字段 | 依赖权限的业务批量开发 |
| 工作助手适配器 | 本地合成事件生成器、隐私文案 | 真实员工全量采集 |
| 低代码元模型 | 组件设计、示例数据 | 插件自定义运行代码 |
| HR 主数据 | 招聘原型、培训材料 | 实际薪酬切换 |
| 性能测试准备 | 监控面板、容量模型 | 在数据模型频繁变化时做最终基准 |

### 18.3 个人开发者每周容量规则

- 每周按 5 个有效开发日规划，其中 3 天实现、1 天测试与修复、0.5 天文档/证据、0.5 天评审与缓冲。
- 同时进行的“进行中”工作包不超过 2 个；安全或数据阻断出现时，停止开启新功能。
- Phase 0–11 的阶段区间相加为 94–115 个有效周，另保留 0–1 周跨阶段收口缓冲，形成 94–116 周总基线。
- 每个阶段预留 15%–25% 缺陷和返工；这些缓冲已计入阶段区间，不能再把 94–116 周理解为连续自然周。
- 法律、薪酬、安全渗透、视觉/可用性和真实业务验收必须由相应人员参与，AI 不能代替签字责任。
- 若连续 3 个迭代实际吞吐低于计划 70%，立即缩减同阶段范围并重做 P50/P80，而不是压缩测试。

---

## 19 每周和每阶段的管理模板

### 19.1 周一计划

- 本周门禁目标：只写一个可验证结果。
- 选中的工作包：最多两个。
- 每个工作包的输入是否齐全：需求、权限、数据、接口、测试样本和负责人。
- 本周最高风险与停工条件。
- 计划产生的证据路径和评审人。

### 19.2 每日记录

- 昨日完成的可验证结果和证据链接。
- 今日准备关闭的最小任务。
- 新增阻断、决策和风险。
- 是否触碰租户隔离、个人隐私、高敏 HR、数据迁移或生产写入；若是，列出复核人。

### 19.3 周五复盘

- 计划与实际、完成/未完成原因、缺陷数、返工时长和自动测试变化。
- 权限负例、恢复、迁移、隐私和性能证据是否同步补齐。
- 新增技术债是否有负责人、影响、到期阶段和偿还计划。
- 下周是否仍满足工作包前置条件；不满足则不启动。

### 19.4 阶段评审材料顺序

1. 本阶段目标和明确不做的内容。
2. 从需求到验收的追踪矩阵。
3. 现场演示正常路径、越权路径和失败恢复路径。
4. 自动测试、性能、安全、迁移和恢复证据。
5. 已知缺陷、风险接受、运维成本和下一阶段估算。
6. 评审人签署“通过、带条件通过或不通过”；带条件通过不得包含安全/隐私硬门禁。

---

## 20 Definition of Ready 与 Definition of Done

### 20.1 工作包就绪标准（DoR）

只有全部满足才可改为 `[~]`：

- [ ] 业务目标、主体、数据、范围、异常和不做项明确。
- [ ] 对应 `PLAN.md` 条款和阶段门禁已链接。
- [ ] UI/API/事件/迁移影响已识别；上游契约可用。
- [ ] 权限矩阵至少包含允许、拒绝、调动后、撤权后和跨租户用例。
- [ ] 测试数据不含未经批准的真实个人数据。
- [ ] 验收人、证据位置、回滚/停止条件和预计工作量明确。
- [ ] 外部依赖和决策已完成，或有不会掩盖风险的替代方案。

### 20.2 工作包完成标准（DoD）

只有全部满足才可改为 `[x]`：

- [ ] 功能、异常、空状态、权限、审计、可访问性和数据保留符合验收。
- [ ] 单元、集成、契约、端到端和必要的安全/性能测试通过。
- [ ] 租户、角色、关系、字段、记录和对象存储负例覆盖；没有只靠前端隐藏。
- [ ] 迁移可在空库和上一共享版本执行；备份/恢复或补偿已实测。
- [ ] 日志、指标、告警、运行手册和支持说明同步更新。
- [ ] 文档、接口、决策记录和需求追踪更新；没有未登记的 TODO。
- [ ] 评审缺陷已关闭，或非阻断风险由有权人员明确接受并注明到期日。
- [ ] 证据归档到规定目录，另一人能够按说明复现关键结果。

---

## 21 阶段门禁检查表

### G0 计划确认

- [ ] Frozen 1.2 与本计划版本、范围和冲突处理规则确认。
- [ ] 老板、产品、业务、技术、安全/隐私、HR/薪酬和支持责任人确认。
- [ ] 预算、P50/P80、外部评审费用和停止条件理解一致。
- [ ] 当前明确决定“开始实施”；否则保持只读计划状态。

### G1–G2 技术与安全底座

- [ ] 六类尖峰结论有数据，不以示意图代替验证。
- [ ] 租户隔离、RLS、撤权、缓存、搜索、文件和任务负例通过。
- [ ] 备份可恢复、迁移可重放、审计不可被普通管理员修改。

### G3–G6 核心产品与平台

- [ ] 部门成员表和个人工作页符合上下级权限。
- [ ] 手工工时、周报和本人提交先于自动采集稳定运行。
- [ ] 强制排除零采集、草稿仅本人可见、语音主动触发。
- [ ] 模块正常/特殊启用状态、技术门禁、范围和复核队列正确。
- [ ] 三个参考模块完成全生命周期验证。

### G7–G9 HRM、扩展与 AI

- [ ] HR 高敏域隔离；薪酬双周期影子对账和职责分离通过。
- [ ] 连接器可对账；插件签名、权限差异和隔离执行通过。
- [ ] AI 授权、计划、风险、审批、工具、回执、补偿和 Kill switch 全链路通过；R3 未审批执行为零、R4 执行为零。

### G10 正式上线

- [ ] 没有开放的 P0/P1 缺陷或未处理高危漏洞。
- [ ] 容量、稳定性、恢复、迁移、监控、值班、培训和支持通过。
- [ ] 隐私说明、数据处理责任、外部供应商和已知风险正式确认。
- [ ] 灰度范围、回退触发值、负责人和观察期明确。

---

## 22 缺陷等级、响应目标与发布规则

| 等级 | 示例 | 首次响应目标 | 处置与发布规则 |
|---|---|---:|---|
| P0 | 跨租户泄露、微信/QQ 误采、后台录音、错误发薪、大范围不可逆 AI 动作 | 15 分钟 | 立即停用相关能力，启动事件响应；任何发布阻断 |
| P1 | 权限扩大、高敏数据泄露、无法恢复、关键流程数据损坏 | 1 小时 | 当日缓解并安排修复；阶段/GA 阻断 |
| P2 | 有替代路径的核心功能失败、明显错误计算、重要性能退化 | 1 个工作日 | 当前阶段关闭或获书面风险接受；不能无限延期 |
| P3 | 一般界面、文案、低影响兼容性或建议 | 2 个工作日 | 进入有负责人和版本的待办，不阻断门禁 |

实际生产 SLA 需根据付费方案、值班能力和合同单独批准；上表是内部工程目标，不直接构成客户承诺。

---

## 23 风险触发器与强制停工条件

出现以下任一情况，立即停止扩大范围，保护证据并进入对应事件/决策流程：

- 任意跨租户读写、搜索、缓存、文件、导出或 AI 泄露。
- 微信、QQ 或其他强制排除应用出现任何自动采集、上传或派生摘要。
- 非员工本人成功提交其工时，或主管/老板看到未确认草稿或设备原始事件。
- 组织调动后旧主管权限未在约定时限失效。
- M2/M3、未发布或技术门禁失败的模块被特殊启用。
- 薪酬结果无法重放、影子差异无法解释或职责分离被绕过。
- 插件绕过网络/资源限制、直接访问数据库或取得未声明权限。
- AI 未经必要批准执行 R3、任何 R4 被执行、批准参数与实际执行不一致，或 Kill switch 无法停止。
- 备份无法恢复、迁移不可对账、审计缺失或安全高危无补偿控制。
- 实际工作量连续三轮偏离且继续推进会迫使压缩安全、测试或恢复工作。

停工记录必须包含发现时间、影响范围、临时控制、证据、负责人、恢复条件和重新批准人。

---

## 24 数据迁移执行手册

### 24.1 迁移前

- [ ] 指定数据所有者、源系统联系人、迁移负责人、验收人和回退决定人。
- [ ] 盘点对象、数量、主键、质量、附件、历史、保留、合法依据和高敏字段。
- [ ] 定义源到目标映射、枚举、时区、金额精度、员工匹配、拒绝规则和默认值。
- [ ] 先清洗或记录例外，不把源系统错误静默变成新系统事实。
- [ ] 使用脱敏样本完成至少两次预演，测量时长、错误和目标容量。

### 24.2 迁移执行

- [ ] 冻结或记录增量起点；备份目标；记录制品、脚本、映射和负责人版本。
- [ ] 按组织、主数据、引用数据、业务记录、附件和索引的依赖顺序执行。
- [ ] 每批使用幂等键和检查点；错误进入拒绝队列，不手工绕过校验写库。
- [ ] 迁移账号最小权限且到期；日志不包含薪酬、证件或员工关系正文。

### 24.3 对账与切换

- [ ] 对比数量、金额/时长总和、引用完整性、哈希和风险抽样。
- [ ] 业务所有者签署关键记录；安全人员验证权限和高敏隔离。
- [ ] 未达阈值则回退或继续只读并行，不以发布日期为由接受未知差异。
- [ ] 切换后观察、处理增量、锁定源系统和保留迁移证据。

---

## 25 生产发布与回退手册

### 25.1 发布前 48 小时

- [ ] 冻结版本和变更范围，确认制品签名、SBOM、测试、漏洞和迁移预演。
- [ ] 确认数据库/对象备份、容量、告警、值班、状态页、支持话术和客户通知。
- [ ] 明确灰度租户、功能开关、成功指标、停止阈值、回退步骤和决定人。
- [ ] 禁止把未评审的临时修复、手工 SQL 或配置混入发布。

### 25.2 发布窗口

- [ ] 流水线验证环境、租户范围、版本和审批；执行者不能自批。
- [ ] 先应用向后兼容变更，再迁移和灰度；实时观察错误、延迟、队列和业务指标。
- [ ] 使用合成事务验证登录、部门页、工时提交、文件、审批和关键 HR 路径。
- [ ] 达到停止阈值立即停止扩大；需要时关闭功能或执行已验证回退。

### 25.3 发布后

- [ ] 完成租户和数据对账，确认客户端策略、连接器、插件和 AI 版本。
- [ ] 观察至少一个约定窗口，关闭发布事件并记录异常、人工动作和后续任务。
- [ ] 任何数据修复都通过受审计工具和双人复核，不直接隐式改生产库。

---

## 26 安全与隐私事件响应

1. **发现与分级：** 确认是否涉及租户隔离、强制排除、个人数据、薪酬、插件或 AI 写操作；按 P0–P3 分级。
2. **遏制：** 使用租户/功能/工具 Kill switch，撤销令牌，停止队列和外联，保留只读证据。
3. **取证：** 固化审计、日志、制品、配置、策略、批准和时间线；限制访问，避免在聊天中复制敏感正文。
4. **影响评估：** 确定租户、主体、数据、动作、时间、可逆性和法律/合同通知义务。
5. **修复与恢复：** 修复根因，回归同类旁路，从可信点恢复并完成业务对账。
6. **通知：** 由指定负责人按法律、合同和公司政策通知客户、员工、监管或供应商，不由开发者自行承诺。
7. **复盘：** 5 个工作日内形成无责复盘、控制改进、负责人和截止日；更新威胁模型与测试集。

---

## 27 培训、采用与运营移交

### 27.1 角色课程

| 角色 | 必须掌握 | 通过方式 |
|---|---|---|
| 员工 | 部门入口、本人页面、工时草稿、语音、排除/暂停、本人提交、申诉 | 完成一日草稿和一次周报模拟 |
| 主管 | 下属范围、退回、统计边界、模块特殊启用责任 | 权限负例和特殊启用场景测验 |
| 老板 | 全局已提交视图、模块最终批准、待复核、风险和审计 | 完成批准/缩围/暂停演练 |
| HR/薪酬 | 生效日期、高敏域、批次、对账和职责分离 | 黄金场景与影子批次签字 |
| 应用管理员 | 发布、迁移、模块、连接器、插件和回滚 | 沙箱发布与故障恢复演练 |
| 安全/支持 | 告警、临时访问、事件、隐私和 Kill switch | 桌面推演和操作证据 |

### 27.2 采用指标

- 工时草稿采用率、员工修改率、按时提交率、次日提醒关闭率和误采投诉。
- 部门页到本人工作页的成功率、主管退回时效和周报完成率。
- 模块申请到启用周期、特殊启用比例、老板待复核积压和停用率。
- HR 流程完成率、数据完整性、薪酬差异、权限事件和支持工单。
- 指标用于改进流程，不得把设备活动、草稿或未校正采用率直接变成绩效分数。

### 27.3 运营移交包

- 系统/数据/模块所有者清单，值班与供应商联系人。
- 架构、数据字典、权限、运行、恢复、发布、迁移、事件和支持手册。
- SLO、告警、仪表板、容量、证书/密钥轮换、保留和访问复核日历。
- 已知问题、技术债、合同/许可、成本基线和下一季度路线图。

---

## 28 资源、预算与采购模板

### 28.1 个人开发者基线之外必须预留的参与

- 老板/业务负责人：门禁和模块启用决策，每阶段评审。
- 产品/业务代表：每周需求澄清和 UAT，不能只在上线前出现。
- 安全/隐私：Phase 0、2、4、5、9、10、11 的设计与证据复核。
- HR/薪酬/法律：Phase 7–8 的规则、黄金样本、影子运行和合规确认。
- 外部专业服务：至少渗透测试；必要时劳动/隐私/薪税法律意见和无障碍测试。

### 28.2 每阶段预算项目

- 人力：开发、设计、测试、产品、业务验收、支持、培训和外部审计。
- 基础设施：开发/测试/预发布/生产计算、数据库、缓存、对象存储、日志、备份和流量。
- 第三方：模型、语音转写、邮件/短信、身份、监控、漏洞扫描、代码签名和应用分发。
- 客户端：Windows 签名证书、更新/CDN、设备测试矩阵和崩溃分析。
- 风险储备：按阶段直接成本至少预留 15%–25%，高敏 HR 和 AI 阶段取上限。

### 28.3 采购前检查

- 数据使用、训练、保留、区域、子处理者、删除、审计、可用性、退出和价格上限条款。
- API/导出能力、速率、版本政策、单点依赖、替代供应商和迁移成本。
- 安全证明、漏洞通知、事件协作、密钥管理和最小权限能力。

---

## 29 需求到实施的追踪矩阵

| Frozen 1.2 需求域 | 主要工作包 | 核心门禁 |
|---|---|---|
| 多租户、身份、组织、ABAC/RBAC/ReBAC、RLS | WP02-01–06 | G2 |
| 平台运营端（运营总览、租户管理、平台目录、安全运营、作业与审计、平台设置） | WP02-07 | G4 前完成 |
| 删除、保留与数据主体请求 | WP02-08 | G4 前完成 |
| 领域事件目录与三类契约治理 | WP01-01、`contracts/` | 各门禁 |
| 量化非功能验收（性能、容量、恢复、可访问性、ASVS、覆盖率） | WP11-03–06、WP01-03、WP02-04、WP03-06、WP05-01、WP08-01、WP10-04 | G10 |
| 部门成员表、个人工作页、上下级和老板范围 | WP03-01–04 | G3 |
| 手工工时、周报、本人提交 | WP03-05 | G3 |
| 工作助手、微信/QQ 零采集、分段、语音、次日提醒 | WP04-01–08 | G4 |
| 工作流和审批 | WP03-06、WP05-03 | G3/G5 |
| 应用中心、低代码、模块申请、正常/特殊启用 | WP03-07、WP05-01–09 | G5 |
| 三个 P0 参考模块 | WP06-01–05 | G6 |
| 完整 HRM | WP07-01–07、WP08-01–07 | G7 |
| 集成和插件 | WP09-01–06 | G8 |
| 执行型 AI、授权、人工审批、回执 | WP10-01–08 | G9 |
| 安全、隐私、性能、灾备、运营和 GA | WP11-01–08 | G10 |

任何 Frozen 1.2 条款若没有工作包、测试和门禁映射，不得宣称已经纳入交付。

---

## 30 获得 G0 后的实施第一天前十项动作

以下动作现在**不执行**，只有用户明确确认进入实施后按顺序开始：

1. 把 `PLAN.md` 和本文件设为基线版本，记录哈希、批准人和日期。
2. 创建决策、风险、需求追踪、测试证据和门禁记录模板。
3. 盘点 Windows/VS Code、Git、Java 21、Node LTS、Docker Desktop 和虚拟化状态，只记录，不急于安装。
4. 确认仓库边界、分支保护、提交签名、密钥扫描和制品保留政策。
5. 明确 Dev/Test/Staging/Production 的账号、网络、密钥和数据隔离责任。
6. 为六类 Phase 0 尖峰分别写假设、成功阈值、最大投入和失败后的替代方案。
7. 先做 PostgreSQL RLS 与连接上下文尖峰，验证连接池复用和跨租户负例。
8. 同时准备 Windows 强制排除识别样本，不读取任何真实微信/QQ 内容。
9. 建立首个周报：完成证据、风险、实际工时、剩余估算和 G1 状态。
10. G1 未通过前不搭建大规模业务模块，不进入真实员工试点。

---

## 31 明确不在当前首次落地范围

- 不做操作系统级隐蔽监控、键盘记录、持续截图、后台录音或微信/QQ 内容识别。
- 不允许 AI、主管、老板、管理员或定时任务代替员工提交工时。
- 不用设备活动或未确认草稿自动决定绩效、纪律、晋升、解雇或薪酬。
- 不在首版自研完整财务总账、银企支付、制造 MRP、仓储物流、全渠道 CRM 或全行业薪税引擎。
- 不在首版提供任意代码插件、直连数据库、无限网络访问或绕过平台 API 的低代码扩展。
- 不承诺一次适配所有 Windows 软件；未知应用默认拒绝并按优先级新增受控适配器。
- 不在完成单区域稳定运营前建设全球多区域主动-主动和完整国际化。
- 不把 94–116 有效周压缩成未经验证的固定发布日期；实际日期在 G1、G3、G5 重新估算。

---

## 32 当前停止点与确认事项

本文件是独立于老板汇报材料的实施执行手册，已经把 Frozen 1.2 转为从零开始的阶段、工作包、证据、门禁、停工条件和运营动作。

当前状态：

- [x] 产品范围和实施工作分解已形成。
- [x] 已明确个人开发者与 AI 辅助下的顺序、P50/P80 和强制复估点。
- [ ] 用户确认 G0，授权开始环境检查、工程创建和 Phase 0 技术尖峰。

在最后一项被用户明确确认前，不安装软件、不创建应用工程、不修改环境、不编写源代码，也不启动真实员工数据采集。

---

## 附录 A 量化验收登记与五域覆盖率门槛

完整的量化指标清单、证据形式与测量方法见 `docs/nfr-acceptance-register.md`。本节只固定三个容易被稀释的执行参数。

### A.1 已确认的三项验收口径

1. **月可用性 99.9% 按设计声明处理。** G10 只要求 SLO 定义、合成探测方案与架构证据，实测自 GA 起按月报告。
2. **季度恢复演练按设计声明处理。** G10 证据包只要求首次完整演练，季度节奏自 GA 起算。
3. **工时与周报按时提交率的 95% 不作为 G10 阻断项。** PLAN 标注为建议值，纳入运营观测，GA 后 90 天复盘评估。

### A.2 五域分支覆盖门槛

不设全局总覆盖率阈值，每条 JaCoCo rule 用 `includes` 限定到域包；阈值在各域工作包完成时生效，不等 G10。

| 域 | 代码范围 | 分支覆盖 | 强制结构化覆盖 | 生效门禁 |
|---|---|---|---|---|
| 权限决策 | `modules/authorization` | ≥ 90% | 决策表组合 100% 枚举，拒绝路径逐条用例 | G2 |
| 薪酬计算 | `modules/hr` 薪酬子包 | ≥ 95% | 黄金算例 100% 通过，舍入与边界枚举 | G7 |
| 工作流状态机 | `modules/workflow` | ≥ 90% | 状态迁移对 100%，含非法迁移与重入 | G3、G5 |
| 低代码表达式 | `modules/lowcode` | ≥ 90% | 空值、类型错误、循环引用、越权字段 | G5 |
| AI 策略与风险分级 | `modules/ai` | ≥ 90% | R0–R4 决策表 100%，拒绝与降级路径 | G9 |

### A.3 防作弊约束

1. 缩小 `includes` 或新增排除项必须附带评审记录。
2. 新增关键类必须同步登记进 `backend/coverage-scope.yaml`，CI 比对包内类与清单，缺项即失败。
3. `@Generated` 与框架生成代码单独登记，滥用视为违规。
4. 每个域必须提交风险用例清单并逐条对应 PLAN 验收条款编号；覆盖率达标但清单缺失，该域不算通过。
