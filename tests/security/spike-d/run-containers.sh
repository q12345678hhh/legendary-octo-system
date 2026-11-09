#!/bin/bash
# 尖峰 D 的容器版复跑：把 14 项降级模式用例放进容器执行，另加 2 项容器专属用例。
# 与降级模式的关键差异：容器提供 mount/PID 命名空间、只读根文件系统、seccomp 与 cgroup，
# 因此「宿主视图不可见」由命名空间保证，而不是靠权限位。
set -u
IMAGE=${IMAGE:-python:3.12-slim}
CASES=/tmp/spike-d-containers/cases
HOSTD=/srv/plugins/host
GW=$(ip route | awk '/^default/ {print $3; exit}')

echo "=== SETUP ==="
if ! docker info >/dev/null 2>&1; then
    echo "dockerd not running; start it with /usr/local/bin/dockup" >&2
    exit 1
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "image missing: $IMAGE (use dockpull)" >&2; exit 1; }
echo "image=$IMAGE id=$(docker image inspect -f '{{.Id}}' "$IMAGE" | cut -c1-19)"
echo "engine=$(docker info --format '{{.ServerVersion}}') cgroup=$(docker info --format '{{.CgroupVersion}}')"
echo "default seccomp=$(docker info --format '{{.SecurityOptions}}' | tr ',' '\n' | grep -i seccomp)"
echo "postgres socket on host: $(ls -l /tmp/.s.PGSQL.5433 2>&1)"
echo "postgres tcp on host: $(ss -lnt 2>/dev/null | grep -c ':5433 ' || true) listener(s)"
echo "host gateway=$GW"

rm -rf "$CASES"; mkdir -p "$CASES"
mkdir -p "$HOSTD"; printf 'HOST-SECRET-DO-NOT-READ\n' >"$HOSTD/secret.txt"
chmod 600 "$HOSTD/secret.txt"
rm -f "$HOSTD/evil.txt"

mk() {
    d=$CASES/$1
    mkdir -p "$d"
    cat >"$d/plugin.sh"
}

mk read_host_secret <<'EOF'
cat /srv/plugins/host/secret.txt
EOF

mk read_shadow <<'EOF'
head -n 1 /etc/shadow
EOF

mk write_outside <<'EOF'
echo evil > /srv/plugins/host/evil.txt && echo wrote
EOF

mk network <<'EOF'
python3 -c "import socket; socket.create_connection((\"1.1.1.1\", 80), 3); print(\"connected\")"
EOF

mk memory <<'EOF'
echo "memory.max=$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"
python3 - <<'PY'
# bytearray 的零页不进 cgroup 账，必须逐页写入才能触发 memcg 限制。
a = bytearray(300 * 1024 * 1024)
for i in range(0, len(a), 4096):
    a[i] = 1
vm = {}
for line in open("/proc/self/status"):
    if line.startswith(("VmHWM", "VmRSS", "VmSwap")):
        k, v = line.split(":")
        vm[k] = v.split()[0]
def rd(p):
    try:
        return open(p).read().strip()
    except Exception:
        return "n/a"
print("requested=300MB VmHWM=%skB VmRSS=%skB VmSwap=%skB" % (vm.get("VmHWM"), vm.get("VmRSS"), vm.get("VmSwap")))
print("memory.current=%s memory.peak=%s" % (rd("/sys/fs/cgroup/memory.current"), rd("/sys/fs/cgroup/memory.peak")))
PY
EOF

mk cpu_loop <<'EOF'
while :; do :; done
EOF

mk fork_bomb <<'EOF'
i=0; while [ $i -lt 64 ]; do sleep 5 & i=$((i+1)); done; echo "spawned $i"
EOF

mk env_dump <<'EOF'
env | sort
EOF

mk write_huge <<'EOF'
dd if=/dev/zero of=/tmp/big.bin bs=1M count=4 2>&1 | tail -n 1
ls -l /tmp/big.bin 2>/dev/null | awk '{print "size:", $5}'
EOF

mk read_windows_mount <<'EOF'
head -n 2 /mnt/c/Windows/win.ini && echo "WINDOWS-FS-READABLE" || echo "windows fs blocked"
EOF

mk db_direct <<'EOF'
python3 -c "import socket; socket.create_connection((\"127.0.0.1\", 5433), 3); print(\"db-connected\")"
EOF

mk db_unix_socket <<'EOF'
python3 -c "import socket; s=socket.socket(socket.AF_UNIX); s.connect(\"/tmp/.s.PGSQL.5433\"); print(\"UNIX-SOCKET-CONNECTED\")"
EOF

mk db_unix_login <<'EOF'
python3 - <<'PY'
import socket, struct, sys
try:
    s = socket.socket(socket.AF_UNIX)
    s.settimeout(3)
    s.connect("/tmp/.s.PGSQL.5433")
except OSError as e:
    print("LOGIN-BLOCKED:", e)
    sys.exit(0)
params = b"user\x00platform_owner\x00database\x00postgres\x00\x00"
s.sendall(struct.pack("!II", len(params) + 8, 196608) + params)
print("SERVER-REPLY:", s.recv(32)[:8].hex())
PY
EOF

mk crash <<'EOF'
kill -SEGV $$
EOF

# --- 容器专属用例 ---
mk seccomp_caps <<'EOF'
grep -E '^(Seccomp|NoNewPrivs|CapEff|CapBnd)' /proc/self/status
python3 - <<'PY'
import ctypes, os
libc = ctypes.CDLL("libc.so.6", use_errno=True)
r = libc.mount(b"none", b"/tmp", b"tmpfs", 0, None)
print("mount ret=%s errno=%s (%s)" % (r, ctypes.get_errno(), os.strerror(ctypes.get_errno())))
ctypes.set_errno(0)
r = libc.unshare(0x00020000)
print("unshare(CLONE_NEWNS) ret=%s errno=%s (%s)" % (r, ctypes.get_errno(), os.strerror(ctypes.get_errno())))
PY
EOF

mk readonly_rootfs <<'EOF'
touch /evil.txt 2>&1; echo "write_root_rc=$?"
touch /tmp/ok && echo "tmpfs_write_ok"
grep ' / ' /proc/mounts | awk '{print "root_opts:", $4}'
EOF

# --- 附加探针（不计入 16 项）：若放开网络，数据库是否可达 ---
mk db_host_gateway <<EOF
python3 -c "import socket; socket.create_connection((\"$GW\", 5433), 3); print(\"db-reachable-from-container\")"
EOF

run() {
    name=$1
    shift
    echo "--- $name ---"
    timeout -k 5 60 docker run --rm --name "spike-d-$name" \
        --network none \
        --read-only --tmpfs /tmp:rw,nosuid,nodev,size=8m \
        --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges \
        --memory 128m --pids-limit 64 --cpus 0.5 \
        --ulimit cpu=5 --ulimit fsize=1048576 --ulimit core=0 \
        -e HOME=/tmp -w /tmp \
        --mount "type=bind,src=$CASES/$name,dst=/plugin,readonly" \
        "$@" "$IMAGE" sh -c 'sh /plugin/plugin.sh' 2>&1
    echo "exit=$?"
}

# 崩溃与内存用例必须以「容器内非 PID 1 的子进程」执行：
# Linux 的 init 会忽略没有处理器的致命信号，PID 1 收到 SIGSEGV 不会终止。
run_bridge() {
    name=$1
    echo "--- $name ---"
    timeout -k 5 60 docker run --rm --name "spike-d-$name" \
        --network bridge \
        --read-only --tmpfs /tmp:rw,nosuid,nodev,size=8m \
        --user 65534:65534 --cap-drop ALL --security-opt no-new-privileges \
        --memory 128m --pids-limit 64 --cpus 0.5 \
        --ulimit cpu=5 --ulimit fsize=1048576 --ulimit core=0 \
        -e HOME=/tmp -w /tmp \
        --mount "type=bind,src=$CASES/$name,dst=/plugin,readonly" \
        "$IMAGE" sh -c 'sh /plugin/plugin.sh' 2>&1
    echo "exit=$?"
}

echo
echo "=== CASES (each is a bypass attempt) ==="
run read_host_secret
run read_shadow
run write_outside
run network
run memory
run cpu_loop
run fork_bomb
run env_dump
run write_huge
run read_windows_mount
run db_direct
run db_unix_socket
run db_unix_login
run crash

echo
echo "=== CONTAINER-SPECIFIC CASES ==="
run seccomp_caps
run readonly_rootfs

echo
echo "=== APPENDIX PROBE (network enabled on purpose) ==="
run_bridge db_host_gateway

echo
echo "=== POST-CHECKS ==="
echo "host secret still readable by host: $(cat $HOSTD/secret.txt)"
if [ -f "$HOSTD/evil.txt" ]; then echo "LEAK: plugin wrote into host dir"; else echo "host dir clean (no evil.txt)"; fi
echo "runner still alive after plugin SIGSEGV: yes (pid $$)"
echo "leftover containers: $(docker ps -aq --filter name=spike-d- | wc -l)"
echo "host processes of plugin user: $(pgrep -u plugin 2>/dev/null | wc -l)"
