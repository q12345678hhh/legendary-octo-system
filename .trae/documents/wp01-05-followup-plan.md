# WP01-05 遗留项收口 实施方案

## 背景

WP01-05 已合并（`ba2f953`，PR #3），三处遗留写在 `IMPLEMENTATION_PLAN.md`「遗留与说明（WP01-05）」：

1. 真实窗口/进程采集器未接入 app 运行时（只存在于 `PrivacyEngine.Checks` 的 `LiveWindowProbe`）。
2. 更新只有 `IUpdateInstaller` 编排，没有真实安装包执行。
3. 托盘使用系统默认图标。

本工作包只收口这三项，不新增产品能力，不改服务端协议。

## 一、新增 `desktop-agent/adapters/WorkAssistant.Adapters`（net8.0）

| 文件 | 职责 |
| --- | --- |
| `WindowsWindowProbe.cs` | 实现 `IWindowProbe`：`GetForegroundWindow` + 进程名/标题；`EnumWindows` 枚举可见窗口。只读元数据，不读窗口正文/控件树/像素。 |
| `ForegroundActivityCapture.cs` | 运行时采集适配：读前台快照 → `CaptureSession.EvaluateCapture`（排除判定先于任何内容读取）→ `AgentRuntime.RecordActivity` 入离线队列。摘要只写进程名，不上传窗口标题。 |
| `ZipPackageInstaller.cs` | 生产版 `IUpdateInstaller`：备份当前安装目录 → 解压 ZIP 载荷到暂存区（拒绝目录穿越）→ 覆盖安装；`RestorePrevious` 恢复备份并删除本次新增文件。 |

`IWindowProbe` 放在 `WorkAssistant.PrivacyEngine`，避免 UI 依赖。

`PrivacyEngine.Checks/LiveWindowProbe` 改为复用 `WindowsWindowProbe`，消除两份 P/Invoke 副本；进程级扫描保留。

## 二、运行时接入（`TrayApplicationContext`）

- 注册成功后创建 `ForegroundActivityCapture`（窗口 60 秒，与现有定时器同周期）。
- 每次 `Tick` 先尝试采集，再心跳与补传；采集异常只丢弃本周期，不影响心跳。
- 私人模式/暂停/停止仍由 `CaptureSession` + `CaptureGate` 双重拦截，采集量为 0。
- `Refresh()` 按 `session.State` 设置托盘图标（`TrayIconFactory`）：未记录灰、记录中绿、暂停琥珀、私人模式紫。
- `TrayIconFactory` 用 `System.Drawing` 生成 16×16 图标并按状态缓存；句柄用 `DestroyIcon` 释放。

## 三、自检与证据

离线（`Device.Checks`）：

- 假探针：未开始/暂停不产生条目；微信被排除且队列不变；允许时入队且摘要为进程名。
- `ZipPackageInstaller`：备份→安装→回滚恢复旧文件并删除新增文件；`../` 条目抛 `InvalidDataException` 且安装目录不变。

壳层（`--self-check`）：四个状态的图标非空且中心像素颜色两两不同。

取证脚本 `scripts/work-assistant-followup-verify.ps1`（纯离线，不需数据库/后端）：

```
dotnet build desktop-agent.sln -c Release
Device.Checks
WindowsApp --self-check --report
PrivacyEngine.Checks（离线 + --live）
```

产物写入 `docs/evidence/wp01-05-followup/`，任一 FAIL 或非零退出码中断。

## 四、文档

- `IMPLEMENTATION_PLAN.md` 的 WP01-05 遗留说明改为已收口项，并新增本工作包的小节。
- `docs/runbooks/work-assistant-upgrade.md` 补充 ZIP 载荷格式、备份目录与回滚语义。
- `docs/evidence/wp01-05/README.md` 的「边界与遗留」更新指向新证据目录。

## 五、交付流程

分支 `wp01-05-followup` → commit → push → REST 建 PR → 10 项 CI 全绿 → squash 合并 → 删分支 → 本地 main 快进。
