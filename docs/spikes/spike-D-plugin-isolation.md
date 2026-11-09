# 尖峰 D 报告：插件隔离（降级模式 + 容器版复跑）

| 项目 | 内容 |
|---|---|
| 任务 | T00-03-4 |
| 日期 | 2026-11-09 |
| 结论 | **降级模式部分通过**：14 项越界中 11 项被拦住；`/mnt/c` 宿主文件系统可读，PostgreSQL unix socket 可连通并以超级用户 `platform_owner` 登录。**容器版 16 项全部拦住**（内存项为「限额生效但未终止」，见 §7）；进程级隔离**不可替代容器** |
| 证据 | `docs/evidence/spike-d/isolation-output.txt`（降级）、`docs/evidence/spike-d/container-isolation-output.txt`（容器） |
| 脚本 | `tests/security/spike-d/run-all.sh`、`run-containers.sh`、`diag-nproc.sh`（进程数标定）、`diag-db-unix.sh`（数据库 socket 探测）、`diag-memory.sh`（cgroup 内存记账） |
| 决策 | `docs/adr/ADR-0005-plugin-isolation-baseline.md` |
| 环境 | 降级模式：WSL2 `pglab`（Ubuntu 24.04 base rootfs）；容器版：同一 WSL2 内 Docker Engine 29.1.3（cgroup v2、overlayfs）+ `python:3.12-slim` |

## 1. 为什么要做降级验证

本机 Docker 完全不可用（`auth.docker.io` EOF，五个镜像源均不可达，`wsl --install` 因微软 CDN `WININET_E_CANNOT_CONNECT` 失败）。尖峰 D 的原始目标是「插件在容器内运行，无法读宿主、无网络、限额、崩溃不外溢」。在没有容器的前提下，用内核原语尽量逼近同一组不变量，用来判断：

1. 隔离不变量本身是否可测、可写成回归用例；
2. 缺少容器时，缺口到底有多大——大到能否接受上线。

## 2. 降级方案构成

| 控制 | 实现 | 对应容器能力 |
|---|---|---|
| 身份隔离 | 独立系统用户 `plugin`（uid 1000，`nologin`） | 容器内非 root 用户 |
| 提权阻断 | `setpriv --reuid/--regid --clear-groups --no-new-privs` | `no-new-privileges` |
| 网络 | `unshare -n`（空网络命名空间，仅 lo 未启用） | `--network none` |
| 数据库 | 依赖上一条：TCP 不通，但 unix socket 仍可达（无独立 mount） | 容器内不共享宿主 `/tmp` |
| 环境 | `env -i PATH=... HOME=...` | 镜像环境变量白名单 |
| 内存 | `prlimit --as=134217728`（128 MiB 地址空间） | `--memory` |
| CPU | `prlimit --cpu=5`（5 秒 CPU 时间） | `--cpus` / `cpu.max` |
| 进程数 | `prlimit --nproc=64` | `pids.max` |
| 文件大小 | `prlimit --fsize=1048576`（1 MiB） | 存储配额 / `--storage-opt` |
| 崩溃隔离 | 独立进程，父进程不随子进程信号退出 | `--restart`、独立 PID 命名空间 |
| 残留回收 | 每个用例后 `pkill -9 -u plugin` | 容器销毁即回收 cgroup |

## 3. 结果

| # | 用例 | 绕过意图 | 实测 | 是否拦住 |
|---|---|---|---|---|
| 1 | `read_host_secret` | 读宿主 600 权限文件 | `Permission denied`，exit=1 | 是 |
| 2 | `read_shadow` | 读 `/etc/shadow` | `Permission denied`，exit=1 | 是 |
| 3 | `write_outside` | 写宿主目录 | `Permission denied`，宿主无 `evil.txt` | 是 |
| 4 | `network` | 出网连 1.1.1.1:80 | `OSError: [Errno 101] Network is unreachable` | 是 |
| 5 | `memory` | 申请 200 MiB | `MemoryError`（128 MiB 上限） | 是 |
| 6 | `cpu_loop` | 死循环耗 CPU | `Killed`，exit=137 | 是 |
| 7 | `fork_bomb` | 派生 64 个 `sleep` | `Cannot fork`，exit=2，残留 0 | 是 |
| 8 | `env_dump` | 读宿主环境变量 | 仅 `HOME/PATH/PWD`，无宿主变量 | 是 |
| 9 | `write_huge` | 写 4 MiB 文件 | `File size limit exceeded`，落地 1048576 字节 | 是 |
| 10 | `read_windows_mount` | 读 Windows 盘 `/mnt/c/Windows/win.ini` | **读出内容**，`WINDOWS-FS-READABLE` | **否** |
| 11 | `db_direct` | 直连 `127.0.0.1:5433` | `OSError: [Errno 101] Network is unreachable` | 是 |
| 12 | `db_unix_socket` | 连 `/tmp/.s.PGSQL.5433` | **`UNIX-SOCKET-CONNECTED`** | **否** |
| 13 | `db_unix_login` | 以超级用户登录数据库 | **`select current_user` 返回 `platform_owner`** | **否** |
| 14 | `crash` | 插件段错误 | `Segmentation fault`，exit=139，runner 存活 | 是 |

## 4. 六条固化结论

1. **网络、身份、文件大小、内存、CPU、进程数六类硬限额在 `prlimit` + `unshare -n` 下都可强制**，且失败形态是显式错误（`Permission denied` / `Network is unreachable` / `MemoryError` / `Killed` / `Cannot fork` / `SIGXFSZ`），可直接作为回归断言。
2. **`RLIMIT_NPROC` 在本环境存在约 64 的固定抬升**：`--nproc=32` 时连 `grep` 都无法 fork，`--nproc=64` 才可正常起 4 个进程并拒绝第 64 个。定义限额前必须先用 `diag-nproc.sh` 标定，不能照抄数值。
3. **`ulimit` 不可用于此场景**：Ubuntu 的 `sh` 是 dash，`ulimit -u` 报 `Illegal option` 且被静默忽略，导致第一轮 fork 炸弹「通过」并残留 64 进程。限额必须用 `prlimit`（或容器运行时），且必须有「请求超过限额」的反向用例。
4. **进程回收必须显式做**：没有 cgroup，插件退出不会带走子进程；每个用例后按 uid `pkill` 才把残留降到 0。这是「事后清理」而非「隔离」，存在竞态窗口。
5. **没有 mount 命名空间就没有真正的文件系统边界**：`/mnt/c` 是 WSL 的 drvfs 挂载，权限位对它不生效，插件以非 root 身份照样读到 Windows 侧文件。这是降级模式的**结构性缺口**，加任何 `prlimit` 都补不上。
6. **「网络隔离」不等于「连不上数据库」**：`unshare -n` 只清空网络设备，不改变文件系统视图；PostgreSQL 的 unix socket 位于共享的 `/tmp`（`srwxrwxrwx`），插件以非 root 身份成功连上，并在 `trust` 认证下以超级用户 `platform_owner` 登录。也就是说，本环境里「数据库直连」这条验收项**在网络层被拦住、在 socket 层被绕过**。

## 5. 明确的边界（不得过度声称）

- **未证明**：seccomp/系统调用白名单、只读根文件系统、独立 PID 命名空间、cgroup 级限额、镜像不可变性、插件运行时依赖的可重现构建。这六项只有容器能给。
- **`db_unix_login` 依赖实验集群的 `trust` 配置**：生产不会用 `trust`，凭证也不会落在插件可见的文件系统里。但「socket 可达」是结构性问题，与认证方式无关——容器必须不共享宿主 `/tmp`，数据库也必须只监听容器网络。本用例证明的是可达性，不是生产可直接复现的入侵路径。
- **未证明**：插件对宿主已挂载目录的写入（本次只测 `/mnt/c` 读）。WSL 下 drvfs 默认允许写，风险高于读。
- **未证明**：多插件并发时的额度公平性（本尖峰串行执行）。
- **环境偏差**：`pglab` 是 root 登录的 base rootfs，`/sys/fs/cgroup` 只读，无法用 `pids.max`/`memory.max` 做对照实验；真实容器环境的行为需在 Docker 可用后复测。
- **证据措辞**：`write_huge` 与 `crash` 输出中的 `(core dumped)` 是 dash 的固定文案，实际已用 `prlimit --core=0` 关闭核心转储，磁盘上不产生 core 文件。

## 6. 对后续阶段的影响

| 影响 | 要求 | 归属 |
|---|---|---|
| 插件执行器正式形态 | 必须是容器（或等价的 mount/PID/cgroup/seccomp 隔离），不得以系统用户 + `prlimit` 上线 | WP09-*、G3 前完成 |
| 隔离回归用例 | 本报告 14 个用例转为自动化回归，容器版需新增 seccomp 与只读根两项断言，并保留 unix socket 用例作为「无 mount 隔离即失败」的反例 | WP01-02 |
| 数据库暴露面 | 数据库只监听容器网络；禁止把宿主 `/tmp`、socket 目录挂进插件容器 | WP09-*、WP11-* |
| 本机开发 | 降级模式仅可用于本地联调和回归演练，且必须在报告与发布说明中标注「非隔离」 | WP09-* |
| 额度标定 | 容器 `--memory`/`--pids-limit` 数值需按 `diag-nproc.sh` 同样的方式实测标定，不照抄 | WP09-* |
| 宿主机边界 | 插件不得挂载宿主目录；确需文件交换走对象存储 + 显式白名单 | WP09-*、WP11-* |

## 7. 容器版复跑（2026-11-09，Docker 打通后）

同一组 14 项用例放进容器执行，另加 2 项容器专属用例（seccomp、只读根文件系统），共 16 项；末尾附 1 个「故意放开网络」的探针。容器参数：`--network none`、`--read-only` + `--tmpfs /tmp`、`--user 65534:65534`、`--cap-drop ALL`、`--security-opt no-new-privileges`、`--memory 128m`、`--pids-limit 64`、`--cpus 0.5`、`--ulimit cpu=5 --ulimit fsize=1048576 --ulimit core=0`。

### 7.1 结果

| # | 用例 | 容器内实测 | 判定 |
|---|---|---|---|
| 1 | `read_host_secret` | `No such file or directory`（宿主路径不在容器视图内） | 拦住 |
| 2 | `read_shadow` | `Permission denied`（非 root 读镜像内 `/etc/shadow`） | 拦住 |
| 3 | `write_outside` | `Directory nonexistent`（根文件系统只读且无宿主目录） | 拦住 |
| 4 | `network` | `Network is unreachable` | 拦住 |
| 5 | `memory` | `memory.max=134217728`；申请 300 MiB 后 `VmHWM=134476kB`、`VmSwap=184316kB`、`memory.peak=134217728` | 限额生效，**未终止**（见 7.2-2） |
| 6 | `cpu_loop` | `Killed`，exit=137 | 拦住 |
| 7 | `fork_bomb` | `Cannot fork`，exit=2 | 拦住 |
| 8 | `env_dump` | 仅镜像变量（`HOME/PATH/LANG/PYTHON_*`），无宿主变量 | 拦住 |
| 9 | `write_huge` | `File size limit exceeded`，落地 1048576 字节 | 拦住 |
| 10 | `read_windows_mount` | `No such file or directory`（无 drvfs 挂载） | 拦住（降级模式的硬缺口已闭合） |
| 11 | `db_direct` | `Connection refused`（容器内无 PG，宿主也不监听 TCP 5433） | 拦住 |
| 12 | `db_unix_socket` | `FileNotFoundError`（`/tmp` 是独立 tmpfs） | 拦住（降级模式的缺口已闭合） |
| 13 | `db_unix_login` | `LOGIN-BLOCKED`（socket 不存在，构造的 PG 启动包未发出） | 拦住（降级模式的缺口已闭合） |
| 14 | `crash` | `Segmentation fault`，exit=139，宿主 runner 与容器均正常回收 | 拦住 |
| 15 | `seccomp_caps`（容器专属） | `Seccomp=2`、`Seccomp_filters=1`、`NoNewPrivs=1`、`CapEff=CapBnd=0`；`mount()`/`unshare(CLONE_NEWNS)` 均 `EPERM` | 拦住 |
| 16 | `readonly_rootfs`（容器专属） | `touch /evil.txt` → `Read-only file system`；`/tmp` 可写；根挂载 `ro` | 拦住 |
| A1 | `db_host_gateway`（附加，放开网络） | 连宿主网关 `172.25.112.1:5433` 超时 | 不可达（PG 只监听 unix socket） |

收尾核对：宿主 `secret.txt` 仍为 `HOST-SECRET-DO-NOT-READ`、宿主目录无 `evil.txt`、残留容器 0、插件用户残留进程 0。

### 7.2 四条新增结论

1. **降级模式的三处结构性缺口在容器内全部闭合**：`/mnt/c` 不在挂载表、宿主 `/tmp` 不共享、`platform_owner` 无法登录。这验证了 ADR-0005「正式形态只有容器」的判断——缺口来自「视图」，不来自「权限」。
2. **`--memory` 在 WSL 内核下只约束驻留集，不终止进程**：宿主有 4 GiB swap 且该内核不暴露 `memory.swap.max`，超限内存被换页（`VmSwap=180 MiB`），进程存活。cgroup 记账确实生效（`memory.peak` 被钳在 128 MiB）。因此：生产必须同时设 `--memory-swap` = `--memory`（或关闭 swap）；回归断言应以「`memory.peak` 不超过上限」为准，不能以「进程是否被杀」为准。
3. **插件不得作为容器 PID 1 运行**：第一次复跑时 `crash` 用例退出码为 0——Linux 的 init 语义会忽略没有处理器安装的致命信号，`sh` 作为 PID 1 收到 `SIGSEGV` 不终止。改为由外层 `sh -c 'sh /plugin/plugin.sh'` 让插件作为子进程执行后，退出码恢复 139。若不做这一层，插件崩溃会被误判为「执行成功」，属于**静默失效**，WP09 必须以 tini/自定义 runner 作为 PID 1 并透传退出码。
4. **隔离参数已可按用例断言**：16 项全部产生显式失败形态（`No such file` / `Permission denied` / `Read-only file system` / `Network is unreachable` / `EPERM` / `SIGXFSZ` / `Cannot fork`），仅内存一项需要改判据；这批命令可直接转成 WP01-02 的自动化回归。
