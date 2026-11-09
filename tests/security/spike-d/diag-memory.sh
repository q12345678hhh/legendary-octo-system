#!/bin/bash
# 诊断：WSL 内核下 cgroup v2 memory 限制是否真正生效。
set -u
echo "=== host kernel: $(uname -r)"
echo "=== host memory controller: $(grep -c memory /sys/fs/cgroup/cgroup.controllers) / $(cat /sys/fs/cgroup/cgroup.controllers)"
echo "=== host swap limit: $(cat /sys/fs/cgroup/memory.swap.max 2>&1)"

echo
echo "=== container: memory.max vs actual usage ==="
docker run -i --rm --memory 128m python:3.12-slim python3 - <<'PY'
import os
def rd(p):
    try:
        return open(p).read().strip()
    except Exception as e:
        return "ERR:%s" % e
print("memory.max   =", rd("/sys/fs/cgroup/memory.max"))
print("memory.current(before) =", rd("/sys/fs/cgroup/memory.current"))
a = bytearray(300 * 1024 * 1024)
for i in range(0, len(a), 4096):
    a[i] = 1
print("memory.current(after 300MB touched) =", rd("/sys/fs/cgroup/memory.current"))
print("memory.peak  =", rd("/sys/fs/cgroup/memory.peak"))
print("allocation survived: yes")
PY
echo "exit=$?"

echo
echo "=== kernel oom evidence ==="
dmesg 2>/dev/null | grep -iE 'oom|killed process' | tail -n 5 || echo "(dmesg unavailable)"
