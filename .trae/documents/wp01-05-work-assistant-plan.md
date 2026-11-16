# WP01-05 Windows 工作助手技术骨架 实施计划

## Context

WP01-01~04 已完成：CI 与分支保护、本地/测试拓扑、迁移与恢复基线、通用平台骨架（请求上下文、幂等、作业、文件、观测、前端壳）。当前 `desktop-agent/` 只有隐私引擎（`CaptureSession` + `ForbiddenApplicationPolicy` + 控制台自检），没有托盘壳、设备身份、本地缓存与升级能力。

WP01-05 四个验收项（`IMPLEMENTATION_PLAN.md` 第 356–366 行）：

1. 托盘状态、显式开始/暂停/结束、私人模式、不可隐藏的采集提示。
2. 设备注册、设备密钥、策略签名验证、版本报告、撤销和更新回滚。
3. Windows 安全存储保护本地缓存；离线队列、过期删除和崩溃恢复。
4. 不提供管理员远程秘密开启接口；任何策略只能收窄或增加排除。

验收证据：撤销设备后无法上传；员工未开始或已暂停时采集量为 0；状态始终可见。

已有约束（必须沿用）：
- ADR-0006：排除判定先于内容读取；硬编码下限不可被租户覆盖；设备私钥 ECDSA P-256、DPAPI 保护、设备注册/吊销/换机流程。
- PLAN §11.5：采集状态持续可见，管理员不得隐藏托盘状态或远程秘密启用；租户只能增加排除项。
- `desktop-agent` 现有风格：控制台 `Checks` 项目输出 `PASS/FAIL`，CI 在 `windows-latest` 上 `dotnet build`（警告即错误）+ 跑自检。

## 需要确认的决策

**托盘壳技术：推荐 WinForms（.NET 8 内置 `NotifyIcon`），逻辑与 UI 解耦。**

理由：WinUI 3 需要 Windows App SDK 与第三方托盘组件（`H.NotifyIcon` 等），为一个骨架引入供应链与构建风险；WinForms 的托盘、置顶提示窗是 BCL 能力，`windows-latest` 可直接构建。PLAN 技术表写的「Windows App SDK/WinUI 3」视为 Phase 4 的完整外壳目标，本工作包按「受控例外」新建 ADR-0008 记录：UI 只做展示与本地状态迁移，状态机、策略、队列、上传门禁全部在可测库中，后续换壳不影响验收。

若坚持本工作包就用 WinUI 3，则计划改为：`Microsoft.WindowsAppSDK` + `H.NotifyIcon.WinUI`，CI 与本地构建时间显著增加，且托盘行为仍要单独自测。

## 一、桌面端分层（新增 5 个项目）

| 项目 | 目标框架 | 职责 |
|---|---|---|
| `core/WorkAssistant.Core` | net8.0 | `TrayStatePresenter`（状态→托盘文案/图标/提示可见性）、`UploadGate`（未采集即拒绝）、策略模型与严格反序列化、签名规范化字符串 |
| `storage/WorkAssistant.Storage` | net8.0 | DPAPI 加密仓库、原子写队列、过期删除、启动恢复（临时文件/损坏条目） |
| `device/WorkAssistant.Device` | net8.0 | 设备身份（ECDSA P-256）、注册/心跳/上传/策略拉取、策略签名验证与版本单调、更新清单验签与回滚 |
| `device/WorkAssistant.Device.Checks` | net8.0-windows | 离线自检 + `--live` 真实链路（注册→上传→撤销→离线补传） |
| `windows-app/WorkAssistant.WindowsApp` | net8.0-windows | WinForms 托盘壳：`NotifyIcon`、菜单、置顶采集提示窗、`--self-check` 无界面自检 |

`desktop-agent.sln` 增加 `core/storage/device/windows-app` 解决方案目录；`Directory.Build.props` 保持全局 net8.0，个别项目覆盖 `TargetFramework`。
新增唯一 NuGet 依赖：`System.Security.Cryptography.ProtectedData` 8.0.0（DPAPI，官方包）。不引 HTTP/JSON 之外的第三方库（`HttpClient` + `System.Text.Json`）。

## 二、托盘状态与不可隐藏提示

`TrayStatePresenter` 是唯一状态展示来源（可无 UI 断言）：

| 采集状态 | 托盘图标/文案 | 提示窗 | 菜单项 |
|---|---|---|---|
| `Stopped` | 「未记录」 | 隐藏 | 开始记录 |
| `Capturing` | 「记录中」高亮 | **始终显示**（工作助手：记录中） | 暂停 / 结束 / 私人模式 |
| `Paused` | 「已暂停」 | **始终显示**（已暂停） | 继续 / 结束 / 私人模式 |
| `PrivateMode` | 「私人模式」 | **始终显示**（私人模式，不采集） | 退出私人模式 / 结束 |

- 菜单**不提供**「隐藏提示」「隐藏托盘」「静默运行」；`Exit` 只在 `Stopped` 时可用（未结束记录必须先结束）。
- 提示窗为无边框置顶小窗（`TopMost`，右下角），无关闭按钮；`OnClosing` 在非 `Stopped` 时取消。管理员/远程策略没有任何入口能改变它。
- 私人模式与暂停均不采集；`CaptureSession` 已有非法迁移抛错语义，继续复用。
- 壳层只做 `session.Start/Pause/Resume/Stop/EnterPrivateMode/ExitPrivateMode`，不直接读写状态。

自检证据：`TrayStatePresenter` 对四种状态的 `IndicatorVisible`、菜单项集合、`Exit` 可用性断言；`--self-check` 输出状态迁移与提示可见性；本机运行截图归档 `indicator.png`（失败则用 presenter 日志代替，并注明原因）。

## 三、设备协议（后端 + 客户端）

### 3.1 数据表（V5）

`backend/db/src/main/resources/db/migration/V5__work_assistant_device.sql`，全部 RLS + FORCE，沿用 `tenant-table.template.sql`：

| 表 | 关键列 |
|---|---|
| `work_assistant_device` | `id uuid pk, tenant_id, subject_id, device_name, public_key_spki text, app_version, policy_version, status(active/revoked), registered_at, last_seen_at, revoked_at, revoked_by`；唯一 `(tenant_id, subject_id, public_key_spki)` |
| `work_assistant_policy` | `tenant_id pk, version int, additional_exclusions jsonb, updated_at, updated_by` |
| `work_assistant_segment` | `id uuid pk, tenant_id, device_id, client_segment_id uuid, captured_from, captured_to, source_type, summary text null, policy_version int, received_at`；唯一 `(tenant_id, device_id, client_segment_id)` |

同步更新：`scripts/db-verify.ps1` 版本数断言、`tests/db/40-integrity.sql`（3 张新表 RLS/FORCE/单策略断言）。

### 3.2 端点（`worklog` 模块 + `boot-api` 控制器）

| 方法/路径 | 说明 |
|---|---|
| `POST /api/v1/work-assistant/devices/register` | 设备公钥（SPKI base64）+ 名称 + 版本；幂等（同主体同公钥返回原设备）；返回 `device_id`、策略信封、策略/更新验签公钥与 key id |
| `POST /api/v1/work-assistant/devices/{id}/heartbeat` | 版本报告（app/policy 版本 + 本地状态），更新 `last_seen_at`；返回 `status`、最低支持版本、可选更新清单 |
| `GET /api/v1/work-assistant/devices/{id}/policy` | 返回最新签名策略；设备被撤销返回 403 |
| `POST /api/v1/work-assistant/devices/{id}/revoke` | 撤销设备，记录 `revoked_by`；之后该设备上传/心跳/策略一律 403 |
| `POST /api/v1/work-assistant/segments` | 上传最小化片段元数据；唯一键去重（重复返回 200 `duplicate=true`） |
| `GET /api/v1/work-assistant/update-manifest` | 服务端签名的更新清单 |
| `PUT /api/v1/work-assistant/policies/current` | 仅接受 `additional_exclusions`（追加项）；未知字段 422；返回新版本签名策略 |

**没有**任何 `start`/`capture`/`enable` 端点；策略 schema 不含 `capture_enabled`/`auto_start` 等控制字段。

### 3.3 签名

- 设备密钥：ECDSA P-256。`device_id` 由 `MachineGuid + tenantId` 的 SHA-256 派生为 UUID；`MachineGuid` 不可读时退化为本地持久化随机 ID（写入加密状态）。
- 请求签名：头 `X-Device-Id`、`X-Device-Timestamp`、`X-Device-Signature`；规范串 `METHOD\nPATH\nTIMESTAMP\nSHA256(body)`；时间窗 ±300s；编码为 IEEE P1363（.NET `SignData` 默认，Java 用 `SHA256withECDSAinP1363Format`）。
- 服务端公钥用 `X.509 SPKI` base64 存储与导入，不存私钥。
- 重放边界：时间窗 + `client_segment_id` 唯一键；多实例 nonce 缓存留待后续（写入运行手册）。

### 3.4 版本报告与撤销

- 心跳每 5 分钟一次（壳层定时器），上报 `app_version`、`policy_version`、本地状态；失败只降级为「离线」，不影响可见状态。
- 任何设备请求收到 403 `DEVICE_REVOKED`：客户端立即停采集（状态置 `Stopped`）、清空离线队列（记录条数到本地审计日志）、托盘显示「设备已撤销」。

## 四、策略签名、版本单调与只收窄

服务端：
- 基线排除清单来自 `contracts/work-assistant/baseline-policy.v1.json`（进程名 + 标题关键字，含 ADR-0006 下限：WeChat/Weixin/QQ/TIM/QQNT/Telegram/DingTalk/Feishu/Slack/Discord）。
- 租户策略只存「追加项」，有效策略 = 基线 ∪ 追加项；追加项校验 `^[A-Za-z0-9._-]{1,64}$`，拒绝空串、通配符与重复。
- 签名信封：`{policy_version, issued_at, key_id, processes[], title_keywords[], upload_enabled, signature}`；签名规范串 `version|issued_at|sorted(processes)|sorted(title_keywords)|upload_enabled`。私钥从 `PLATFORM_WA_POLICY_KEY`（PKCS8 base64）读取，缺失则端点 503，不静默降级。

客户端：
- `JsonSerializer` 设置 `UnmappedMemberHandling.Disallow`：策略里出现任何未知控制字段即拒收（证明没有第二控制通道）。
- 用注册时下发的公钥验证签名；`key_id` 必须匹配；`policy_version` 必须大于本地已存版本（防回滚）。
- 有效排除 = **本地硬编码下限 ∪ 服务端下发的 processes**（客户端永不删减本地下限）。
- `upload_enabled=false` 视为收窄：停止上传、保留本地队列。
- 服务端 `PUT` 只接受追加项；请求体里出现 `forbidden_processes`/`remove` 等字段直接 422。

## 五、Windows 安全存储、离线队列、过期与崩溃恢复

根目录：`%LOCALAPPDATA%\AcmeWorkAssistant\<tenantId>\`。

| 文件 | 内容 | 保护 |
|---|---|---|
| `device.key` | 设备私钥（PKCS8）+ `device_id` | DPAPI `CurrentUser`，附加熵 `SHA256("work-assistant|"+device_id)` |
| `state.json` | 会话状态、已应用策略版本、设备状态、上次心跳 | 同上 |
| `policy.pinned.json` | 策略/更新验签公钥与 key id | 同上 |
| `queue/<segmentId>.seg` | 片段元数据 + 到期时间 | 同上 |
| `queue/<segmentId>.tmp` | 写入中间态 | 启动时一律删除 |

- 原子写：写 `.tmp` → `Flush(true)` → `File.Move(..., overwrite: true)`；崩溃只会留下 `.tmp`。
- 离线队列：API 不可达时入队（默认保留 72 小时，`expiresAt` 写入条目）；启动、每次上传成功后执行 `PurgeExpired`。
- 崩溃恢复：启动时删除 `.tmp`、扫描 `queue/`、解密/解析失败条目移入 `quarantine/` 并计数；会话状态**一律重置为 `Stopped`**（崩溃后绝不自动恢复采集）；恢复后继续补传未确认条目。
- 上传语义：先上传后删本地；服务端按 `client_segment_id` 去重，因此「上传成功但本地未删就崩溃」重启重传不会产生重复行。
- 不含原始内容：本工作包只存片段元数据（时间范围、来源类别、摘要），原始内容与语音属 Phase 4。

## 六、更新验证与回滚

- 更新清单 `{version, package_sha256, package_url, min_supported_version, released_at, key_id, signature}`；签名规范串 `version|sha256|url|min|released_at`，公钥来自注册响应并 DPAPI 固定。
- `UpdateService`：
  1. 验签 → 拒绝篡改/未知 key/低于 `min_supported_version` 的降级包；
  2. 校验包 SHA-256 → 写入 `updates/<version>/` 暂存，置 `pending.json`；
  3. `ApplyWithRollback(IUpdateInstaller, Func<bool> healthCheck)`：安装前把 `current` 复制为 `previous`；安装后健康检查失败则恢复 `previous`、删除破损包、记录 `rolled_back`。
- 骨架用可注入的 `IUpdateInstaller`（检查项目用临时目录模拟），真实安装器与签名分发留到发布工作包；运行手册写明触发条件、手工回滚与证据留存。
- 交付 `docs/runbooks/work-assistant-upgrade.md`：设备注册/换机/撤销、离线补传、策略更新、升级与回滚、密钥轮换边界。

## 七、契约、测试与 CI

- `contracts/openapi/platform-api.v1.json` 增加上述 7 个端点与 `WorkAssistant*` schema；沿用现有 OpenAPI 破坏性变更门禁，不新增第四类契约。
- `contracts/work-assistant/baseline-policy.v1.json`：基线排除清单（后端资源文件逐字一致）。
- 新增 Node 测试（`tests` 作业）：
  - `baseline-policy.test.mjs`：契约基线 ⊆ 客户端硬编码下限（解析 C# 常量），后端资源与契约一致；
  - `device-protocol.test.mjs`：OpenAPI 必须包含注册/心跳/策略/撤销/上传/更新清单；断言不存在 `start|capture|enable` 类端点，策略 schema 无控制字段。
- Java 单测（`worklog`）：P1363 验签（通过/篡改/超时窗口）、策略只收窄（追加接受、删除/未知字段 422）、版本单调。
- `desktop-agent` 作业新增：`dotnet run --project device/WorkAssistant.Device.Checks -c Release --no-build`（离线自检：签名、策略、DPAPI、队列、崩溃恢复、更新回滚）。
- 新增 `scripts/work-assistant-verify.ps1`（UTF-8 BOM，PowerShell 5.1）：生成本地策略/更新密钥（只进 `var/`，不提交）→ 启动 API → 跑 `Device.Checks --live` → 用 psql 断言 `work_assistant_segment` 行数 → 归档证据。

## 八、证据目录 `docs/evidence/wp01-05/`

`desktop-build.txt`、`capture-gating.txt`、`indicator.txt`（+ `indicator.png`）、`device-registration.txt`、`device-revocation.txt`、`policy-signature.txt`、`secure-storage.txt`、`offline-queue.txt`、`crash-recovery.txt`、`update-rollback.txt`、`ci-run.txt`、`README.md`（含失败与修复记录、口径说明）。

## 九、执行顺序与提交

1. 新增 ADR-0008（托盘壳与本地存储边界）+ 契约文件 + Node 测试骨架。
2. V5 迁移 + db 断言更新 → `scripts/db-verify.ps1`。
3. `worklog` 设备/策略/更新清单服务 + boot-api 控制器 + OpenAPI + Java 单测。
4. 桌面 `Core` → `Storage` → `Device` → `Checks`。
5. WinForms 托盘壳 + `--self-check`；solution 更新。
6. 离线自检全绿 → `scripts/work-assistant-verify.ps1 --live` 取证 → 本机运行截图。
7. 回填 `IMPLEMENTATION_PLAN.md`（4 条勾选 + 进展记录表 + 遗留说明）。
8. 分支 `wp01-05-work-assistant` → PR → 10 项检查全绿 → squash 合并；同步 `docs/evidence/wp01-05/ci-run.txt`。

## 风险与边界

- 占位认证（`X-Tenant-Id`/`X-Subject-Id`）继续沿用；撤销接口没有真实角色校验，WP02-02 接入真实会话与管理员权限前禁止用于共享环境。
- `MachineGuid` 派生设备 ID 在系统重装后变化，换机会生成新设备；撤销只影响旧设备。
- 签名重放只靠时间窗 + 片段唯一键；多实例 nonce 去重与密钥轮换属后续安全加固。
- 本工作包不实现真实采集适配器、语音与真实安装器；壳层只驱动状态机与元数据上传。
- WinForms 与 PLAN 技术表的差异由 ADR-0008 记录并限定范围。
