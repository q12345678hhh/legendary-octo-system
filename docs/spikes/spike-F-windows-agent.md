# 尖峰 F 报告：Windows 工作助手

| 项目 | 内容 |
|---|---|
| 任务 | T00-03-6 |
| 日期 | 2026-11-09 |
| 结论 | **通过（标题级）**：排除判定先于内容读取、设备身份可验签、本地脱敏与上报载荷签名均成立；内容适配器（UIA/DOM）、真实语音转写未验证 |
| 证据 | `docs/evidence/spike-f/verify-output.txt`、`telemetry.json`、`sample-2s.wav` |
| 代码 | `tests/windows/spike-f/`（.NET 8 控制台，14 项自检全部 PASS） |
| 决策 | `docs/adr/ADR-0006-desktop-collection-boundary.md` |
| 环境 | Windows 11 26200，.NET SDK 8.0.427（本次新装） |

## 1. 环境：.NET 8 SDK 补齐

原阻塞是「只有 .NET 8 运行时（8.0.21），没有 SDK」。处置过程：

| 步骤 | 结果 |
|---|---|
| 官方源探测 | `builds.dotnet.microsoft.com` 200 可达（与 Docker Hub 不同，微软 CDN 在本机可通） |
| 首轮下载 | HEAD 成功、GET 报「基础连接已关闭」，判定为 PowerShell 5.1 默认 TLS 1.0 |
| 修正 | `[Net.ServicePointManager]::SecurityProtocol = Tls12` 后正常下载 `dotnet-install.ps1` |
| 安装 | `-Channel 8.0 -InstallDir "C:\Program Files\dotnet" -NoPath`，与既有运行时同根 |
| 验证 | `dotnet --list-sdks` → 8.0.427；`dotnet new/build/run` 冒烟通过 |

该安装属于 G0「环境安装逐项授权」范围，已由用户单独授权，仅影响本机开发环境，不进入产品交付物。

## 2. 做了什么

在 `tests/windows/spike-f/` 实现一个最小但可跑的控制台探针，只依赖 BCL 与 Win32 P/Invoke：

| 模块 | 能力 |
|---|---|
| `CollectionPolicy` | 硬编码下限（微信/QQ/TIM/Telegram/DingTalk/Feishu/Slack/Discord 等）+ 租户强制排除，硬编码优先 |
| `WindowScanner` | `EnumWindows` 枚举可见窗口；**先按进程名判定排除，再决定是否调用 `GetWindowTextW`** |
| `TextMinimizer` | 空白归一、邮箱/身份证/银行卡/手机号脱敏、200 字符上限 |
| `DeviceIdentity` | ECDSA P-256 生成、`MachineGuid` 指纹、载荷签名与验签 |
| `Audio` | `waveIn` 枚举采集端点、16 kHz/16 bit/单声道录制、手写 WAV 头 |

## 3. 结果（14 项自检，全部 PASS）

```
PASS  policy.hard-excluded          Weixin/QQ 命中硬排除
PASS  policy.allowed                msedge 允许采集
PASS  policy.tenant-excluded        租户强制排除生效
PASS  scan.exclusion-before-read    找到 4 个排除窗口，标题读取数=0
PASS  scan.audit-trail              content_read=false 4 条，content_read=true 29 条
PASS  scan.tenant-enforced-runtime  mspaint 窗口 1 个，全部排除且未读标题
PASS  minimizer.redaction           手机/邮箱/身份证/卡号全部替换为占位符
PASS  minimizer.length-cap          len=201
PASS  device.sign-verify            device_id=8bea2b43755e1152
PASS  device.tamper-rejected        篡改载荷后验签失败
PASS  audio.devices                 capture devices=2: #0(2ch) / #1(2ch)
PASS  audio.record-2s               wav bytes=64302
PASS  telemetry.no-excluded-content segments=29，其中排除应用=0
PASS  telemetry.signature           上报载荷可签名并验签
```

关键点不是「跑通了」，而是**排除应用连标题都没被读**：真实环境里微信（`Weixin`）与 QQ 正在运行，扫描器只取了进程名就返回，`GetWindowTextW` 调用次数为 0，审计日志逐条留痕。这是 PLAN D-012 与「评审重点 6」的可执行版本。

## 4. 明确的边界（不得过度声称）

| 未验证项 | 说明 |
|---|---|
| 内容适配器 | 只做到**窗口标题级**；Edge 的 DOM、Office 文档正文、VS Code 编辑器内容、UIA 可访问性树均未接入。WBS 要求的「Edge/Office/VS Code 适配可用」**只达成一半** |
| 语音转写 | 录到了 2 秒有效 PCM（64 KB，2.0 秒 @ 32 KB/s），但**没有 ASR 端点，转写未验证**；`sample-2s.wav` 为静音环境底噪 |
| 采集端点名称 | `waveInGetDevCapsW` 两个端点 `szPname` 均返回空串（已排除结构体编码问题，改用非托管缓冲直读仍为空），生产需改用 MMDevice（`IMMDeviceEnumerator`）取友好名 |
| 分段与频率 | 未实现「固定间隔/员工自定义间隔」的分段器，只在自检里一次性扫描 |
| 会话与多屏 | 锁屏、切换用户、远程桌面、多显示器、UWP 隔离进程均未测 |
| 静默/后台窗口 | 只处理可见窗口；后台窗口由 `IsWindowVisible` 直接跳过，未验证是否遗漏 |
| 采集开关 | 未实现「员工主动开启/暂停」的状态机与本地开关持久化 |

## 5. 对后续阶段的影响

| 结论 | 要求 | 归属 |
|---|---|---|
| 「排除先于读取」必须写成不变量 | `GetWindowText`/UIA/截图/录音一律在策略判定之后；任何新增适配器不得绕过 | WP06-*、WP08-* |
| 适配器需要独立评审 | UIA/DOM 读取面远大于标题读取，必须单独定义最小化与失败降级 | 尖峰 F2（新增） |
| 设备身份可用 | 设备私钥 + 签名上报可作为「来源可信」基线，服务端仍需绑定设备注册与吊销 | WP08-* |
| 音频必须独立开关 | 语音补充是显式行为，不得与窗口采集共用总开关 | WP08-* |
| MMDevice 取代 winmm 取名 | 显示设备名需要 Core Audio API | WP08-* |
