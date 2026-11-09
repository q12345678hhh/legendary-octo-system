# 交换契约治理规范

| 项目 | 内容 |
|---|---|
| 状态 | 生效（2026-11-09），Phase 0 制定，WP01-01 落地 |
| 依据 | `IMPLEMENTATION_PLAN.md` WP01-01、§3.1 目录结构；`PLAN.md` D-010 |
| 适用 | `contracts/` 下全部契约：HTTP API、领域事件、插件协议、Webhook、AI 工具 |
| 强制程度 | CI 阻断项。缺任一类契约视为未覆盖，不得通过 G1 |

## 1. 契约优先

契约是**跨边界交换的唯一真相**：前端、桌面助手、插件、外部集成只认契约，不认实现。任何一处手写「与后端字段对齐」的副本都视为缺陷。

```
contracts/
  openapi/     # HTTP API（OpenAPI 3.1）
  events/      # 领域事件 JSON Schema + 信封
  plugins/     # 插件协议（manifest、调用、限额、权限声明）
  webhooks/    # 对外 Webhook 载荷与重试语义
  ai-tools/    # AI 工具模式（名称、风险等级、数据域、参数结构、审批要求）
```

## 2. 版本规则

| 契约类 | 版本载体 | 规则 |
|---|---|---|
| HTTP API | URL 前缀 `/api/v1` | 主版本变更才改 URL；`/api/v1` 内只允许向后兼容演进 |
| 领域事件 | 事件名 + `.vN`（如 `employee.hired.v1`） | 事件名用过去时；信封字段固定：`event_id`、`event_name`、`occurred_at`、`tenant_id`、`correlation_id`、`producer`、`schema_version` |
| 插件协议 | `manifest.protocol_version` | 主版本不兼容即拒载；插件声明所需权限与资源限额 |
| Webhook | `X-Contract-Version` 头 | 订阅方按版本解析；弃用需提前 90 天公告 |
| AI 工具 | `tool_schema_version` | 与 ADR-0007 六层判定的字段一一对应，缺字段即不可注册 |

**同一契约不得并存两个主版本实现**；迁移期内旧版本只做缺陷修复，不再新增能力。

## 3. 兼容性判定

| 允许（向后兼容） | 禁止（破坏性变更） |
|---|---|
| 新增可选字段 | 删除或重命名字段 |
| 新增端点 | 修改字段类型、单位、编码 |
| 放宽枚举（仅消费方容忍未知值） | 收窄枚举取值 |
| 新增可选请求参数 | 新增必填参数 |
| 新增事件类型 | 修改事件信封字段 |
| 文档补充 | 修改错误码语义、分页或幂等语义 |

破坏性变更只有两条出路：**升主版本**，或走变更请求并按 `PLAN.md` §14.3 重新评估范围与工期。

## 4. CI 门禁

1. 三类契约（OpenAPI、领域事件、插件协议）**全部**纳入破坏性变更检测；缺任一类直接失败，不允许「先空着」。
2. 检测器对主分支基线做差异比对；命中第 3 节「禁止」清单即阻断，并输出具体路径与字段。
3. 例外必须附已批准的变更请求编号与到期日；无编号的豁免一律视为失败。
4. 事件目录与 `contracts/events/` 必须同步：代码里发出的事件若不在目录中，构建失败。
5. 契约文件本身走代码所有者评审，与 ADR 同等级别。

## 5. 生成与所有权

- 服务端为契约所有者；客户端从契约生成类型，不手写。
- 生成物带「自动生成，勿手改」头，且不进评审重点。
- 契约变更必须同时更新：契约文件、生成物、受影响测试、变更记录（`CHANGELOG` 段）。
- 插件协议与 AI 工具模式由平台团队维护；租户只能声明，不能扩展字段。

## 6. 当前状态

Phase 0 未产生真实业务 API，首批契约在 WP01-01 冻结，先立规则与门禁，避免实现先行、契约追认。首批清单：

| 顺序 | 契约 | 触发工作包 | 状态 |
|---|---|---|---|
| 1 | 统一错误、分页、幂等头、并发控制 | WP01-03 | 待落地（`Problem` schema 已在 `contracts/openapi/platform-api.v1.json` 中占位） |
| 2 | 认证与会话（登录壳） | WP01-04 | 待落地 |
| 3 | 文件预签名上传与下载再授权 | WP01-04 | 待落地 |
| 4 | 首个领域事件目录（组织与员工） | WP01-01 | **已冻结**：`contracts/events/envelope.v1.json`、`employee.hired.v1.json`、`event-catalog.json` |
| 5 | 插件 manifest 与调用协议 | WP01-01（manifest v1）/ WP09（调用协议） | **manifest 已冻结**：`contracts/plugins/plugin-manifest.v1.json` |
| 6 | AI 工具模式（对齐 ADR-0007） | WP10 | 待落地 |

**已落地机制（WP01-01）**：

| 能力 | 位置 |
|---|---|
| 三类契约覆盖与破坏性变更判定 | `contracts/tools/contract-check.mjs` |
| 基线冻结 | `contracts/baseline/contracts-baseline.json`（`--update` 生成） |
| 变更请求豁免（需编号 + 未到期） | `contracts/baseline/approvals/` |
| 门禁自测（9 项，含过期审批必须失败） | `contracts/tools/contract-check.test.mjs` |
| 跨组件黑盒验收（三类各一处破坏均阻断） | `tests/contracts/breaking-change-blocks.test.mjs` |
| CI 接线 | `.github/workflows/ci.yml` 的 `contracts`、`tests` 任务 |

首批 HTTP 契约仅覆盖 `GET /api/v1/system/health`，用于把「契约 → 门禁 → 流水线」链路跑通；WP01-03 起按清单逐个扩充，每次扩充都需重跑 `--update` 冻结基线并提交评审。

## 7. 与验收的关系

WP01-01 的验收证据是「故意引入三类契约的未批准破坏性变更都会阻断流水线」。本规范提供判定清单（第 3 节）与门禁规则（第 4 节），因此该验收可以转成三条具体的 CI 测试用例，而不是靠人工判断。
