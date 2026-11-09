#!/usr/bin/env python3
"""隔离插件执行器的监督进程（容器 PID 1）。

为什么需要监督进程：尖峰 D（docs/spikes/spike-D-plugin-isolation.md 第 7 节）证明，
若插件直接作为 PID 1 运行，内核对 PID 1 的特殊信号语义会吞掉 SIGSEGV，
容器以退出码 0 结束，崩溃被伪装成成功。本进程作为 PID 1、插件作为子进程，
把信号语义与退出码如实透传给编排层。

用法：
    python3 runner.py [--memory-mb N] [--cpu-seconds N] [--open-files N] [--processes N] -- <command...>
    python3 runner.py --self-check

退出码：正常退出透传子进程退出码；被信号终止返回 128 + signum（SIGSEGV → 139）；
参数错误返回 64。网络隔离由容器 `--network none` 提供，本进程不提供任何绕过入口。
"""

from __future__ import annotations

import json
import os
import resource
import signal
import subprocess
import sys
import time
from typing import Sequence

EXIT_USAGE = 64

OPTION_KEYS = {
    "--memory-mb": "memory_mb",
    "--cpu-seconds": "cpu_seconds",
    "--open-files": "open_files",
    "--processes": "processes",
}


def log(event: str, **fields: object) -> None:
    record = {"ts": round(time.time(), 3), "component": "plugin-runner", "event": event, **fields}
    print(json.dumps(record, ensure_ascii=False, sort_keys=True), file=sys.stderr, flush=True)


def exit_code_from_status(status: int) -> int:
    """把 subprocess 返回码转换成 shell 语义：被信号杀死 → 128 + signum。"""
    if status < 0:
        return 128 + (-status)
    return status


def parse_args(argv: Sequence[str]) -> tuple[dict[str, int], list[str]]:
    options = {
        "memory_mb": int(os.environ.get("PLUGIN_MEMORY_MB", "256")),
        "cpu_seconds": int(os.environ.get("PLUGIN_CPU_SECONDS", "60")),
        "open_files": int(os.environ.get("PLUGIN_OPEN_FILES", "256")),
        "processes": int(os.environ.get("PLUGIN_PROCESSES", "32")),
    }
    command: list[str] = []
    index = 0
    while index < len(argv):
        argument = argv[index]
        if argument == "--":
            command = list(argv[index + 1:])
            break
        if argument in OPTION_KEYS:
            if index + 1 >= len(argv):
                raise ValueError(f"{argument} 缺少取值")
            options[OPTION_KEYS[argument]] = int(argv[index + 1])
            index += 2
            continue
        raise ValueError(f"未知参数: {argument}")

    for key in ("memory_mb", "cpu_seconds", "open_files", "processes"):
        if options[key] <= 0:
            raise ValueError(f"限额 {key} 必须为正数")
    return options, command


def limit_setter(options: dict[str, int]):
    """在 exec 之前限制插件子进程可用的内核资源。"""

    def apply() -> None:
        memory_bytes = options["memory_mb"] * 1024 * 1024
        resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))
        cpu = options["cpu_seconds"]
        resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu))
        files = options["open_files"]
        resource.setrlimit(resource.RLIMIT_NOFILE, (files, files))
        processes = options["processes"]
        resource.setrlimit(resource.RLIMIT_NPROC, (processes, processes))

    return apply


def run(options: dict[str, int], command: list[str]) -> int:
    log(
        "supervisor_start",
        pid=os.getpid(),
        is_pid1=os.getpid() == 1,
        command=command,
        limits=options,
    )

    child: subprocess.Popen[bytes] | None = None
    forwarded: list[int] = []

    def forward(signum: int, _frame: object) -> None:
        forwarded.append(signum)
        if child is not None and child.poll() is None:
            log("forward_signal", signal=signum)
            try:
                child.send_signal(signum)
            except ProcessLookupError:
                pass

    for handled in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(handled, forward)

    child = subprocess.Popen(command, preexec_fn=limit_setter(options), close_fds=True)
    log("plugin_started", pid=child.pid, is_pid1=child.pid == 1)

    raw_status = child.wait()
    code = exit_code_from_status(raw_status)
    log("plugin_exited", raw_status=raw_status, exit_code=code, forwarded_signals=forwarded)
    return code


def self_check() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(f"{'PASS' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    check("被信号终止返回 128+signum", exit_code_from_status(-11) == 139)
    check("正常退出透传退出码", exit_code_from_status(7) == 7)

    options, command = parse_args(["--memory-mb", "64", "--open-files", "32", "--", "/bin/true"])
    check("解析限额参数", options["memory_mb"] == 64 and options["open_files"] == 32)
    check("解析插件命令", command == ["/bin/true"])

    empty_options, empty_command = parse_args([])
    check("缺省限额来自环境变量", empty_options["memory_mb"] > 0 and empty_command == [])

    try:
        parse_args(["--memory-mb", "0", "--", "/bin/true"])
        check("拒绝非正数限额", False)
    except ValueError:
        check("拒绝非正数限额", True)

    try:
        parse_args(["--unknown", "--", "/bin/true"])
        check("拒绝未知参数", False)
    except ValueError:
        check("拒绝未知参数", True)

    print(f"自检通过 {len(failures) == 0}：失败 {len(failures)} 项")
    return 0 if not failures else 1


def main(argv: Sequence[str]) -> int:
    if "--self-check" in argv:
        return self_check()

    try:
        options, command = parse_args(argv)
    except ValueError as error:
        log("usage_error", detail=str(error))
        print(__doc__, file=sys.stderr)
        return EXIT_USAGE

    if not command:
        log("usage_error", detail="缺少 `-- <command>`")
        print(__doc__, file=sys.stderr)
        return EXIT_USAGE

    return run(options, command)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
