# WP01-05 遗留项收口证据

生成方式：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\work-assistant-followup-verify.ps1
```

脚本纯离线：只构建桌面端并运行自检，不依赖数据库或后端服务，任一步非零退出码即中断。

## 收口项与证据

| 遗留项 | 落点 | 证据文件 | 关键结论 |
| --- | --- | --- | --- |
| 真实窗口/进程采集器未接入运行时 | `adapters/WindowsWindowProbe`（`IWindowProbe` 实现）、`adapters/ForegroundActivityCapture`、`TrayApplicationContext` 每个周期先采集后心跳 | [offline-checks.txt](offline-checks.txt) 第 54–58 项、[live-window-probe.txt](live-window-probe.txt) | 假探针覆盖未开始/暂停零采集、聊天应用入队前排除、摘要只写进程名；真实桌面采样与运行时共用同一探针实现 |
| 更新安装器只有编排，无真实执行 | `adapters/ZipPackageInstaller`（ZIP 载荷覆盖安装、安装前整目录备份、回滚删除新增文件、拒绝目录穿越） | [offline-checks.txt](offline-checks.txt) 第 59–63 项 | 安装覆盖旧文件并落地新增文件；回滚恢复旧文件且删除本次新增；`../` 条目抛 `InvalidDataException` 且安装目录不变 |
| 托盘使用系统默认图标 | `TrayIconFactory`（按采集状态生成 16×16 图标，句柄即时释放、按状态缓存） | [indicator-icons.txt](indicator-icons.txt) | 未记录灰、记录中绿、暂停琥珀、私人模式紫，四种状态图标中心像素与状态颜色一致 |

## 其他证据

- [build.txt](build.txt)：`dotnet build desktop-agent.sln -c Release` 零警告零错误（警告即错误）。
- [privacy-checks.txt](privacy-checks.txt)：排除判定离线自检 14 项全部通过。
- 设备离线自检共 63 项（WP01-05 原有 53 项 + 本次 10 项）全部通过。

## 设计与隐私边界

- 排除判定仍然先于任何内容读取：`IWindowProbe` 只读进程名与窗口标题，不读窗口正文、控件树、剪贴板或屏幕像素。
- 运行时会话只在 `Capturing` 时产生片段；暂停、私人模式、停止、未注册策略、策略关闭上传时采集量为 0（`CaptureSession` + `CaptureGate` 双层）。
- 离线片段的摘要只写进程名，窗口标题可能含文档名或聊天对象，不上传。
- 安装器只接受 ZIP 载荷；哈希与签名在 `UpdateService.Stage` 已校验，安装器不重复信任包内容，并对每个条目做安装目录越界检查。

## 仍不在本工作包范围

- 更新包的下载、员工确认与安装入口：服务端心跳目前只返回 `updateRequired`，壳层尚未接入下载与确认流程；安装器本身已可执行并通过离线验证。
- 服务端策略固定 `uploadEnabled=true`，关闭上传仍是客户端本地止损能力。
