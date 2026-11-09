#!/bin/sh
# Spike D (degraded): plugin isolation WITHOUT containers.
# Isolates with: separate unprivileged user, empty network namespace,
# cleared environment, no-new-privs, and rlimits (address space, CPU, procs, file size).
set -u
BASE=/srv/plugins
HOSTD=$BASE/host
SB=$BASE/sandbox

echo "=== SETUP ==="
# The unix-socket cases need a live PostgreSQL; start the lab cluster if the helper exists.
if [ -x /usr/local/bin/pgup ]; then sh /usr/local/bin/pgup >/dev/null 2>&1; sleep 2; fi
echo "postgres socket: $(ls -l /tmp/.s.PGSQL.5433 2>&1)"
id plugin >/dev/null 2>&1 || useradd -M -s /usr/sbin/nologin plugin
mkdir -p "$HOSTD" "$SB"
chown root:root "$HOSTD"; chmod 755 "$HOSTD"
printf 'HOST-SECRET-DO-NOT-READ\n' > "$HOSTD/secret.txt"
chown root:root "$HOSTD/secret.txt"; chmod 600 "$HOSTD/secret.txt"

mkplugin() {
  d=$SB/$1
  rm -rf "$d"; mkdir -p "$d"
  printf '%s\n' "$2" > "$d/plugin.sh"
  chown -R plugin:plugin "$d"; chmod 700 "$d"
}

mkplugin read_host_secret 'cat /srv/plugins/host/secret.txt'
mkplugin read_shadow      'head -n 1 /etc/shadow'
mkplugin write_outside    'echo evil > /srv/plugins/host/evil.txt && echo wrote'
mkplugin network          'python3 -c "import socket; socket.create_connection((\"1.1.1.1\", 80), 3); print(\"connected\")"'
mkplugin memory           'python3 -c "a = bytearray(200*1024*1024); print(\"allocated 200MB\")"'
mkplugin cpu_loop         'while :; do :; done'
mkplugin fork_bomb        'i=0; while [ $i -lt 64 ]; do sleep 5 & i=$((i+1)); done; echo "spawned $i"'
mkplugin env_dump         'env | sort'
mkplugin write_huge       'dd if=/dev/zero of=./big.bin bs=1M count=4 2>&1 | tail -n 1; ls -l big.bin 2>/dev/null | awk "{print \"size:\", \$5}"'
mkplugin read_windows_mount 'head -n 2 /mnt/c/Windows/win.ini && echo "WINDOWS-FS-READABLE" || echo "windows fs blocked"'
mkplugin db_direct         'python3 -c "import socket; socket.create_connection((\"127.0.0.1\", 5433), 3); print(\"db-connected\")"'
mkplugin db_unix_socket    'python3 -c "import socket; s=socket.socket(socket.AF_UNIX); s.connect(\"/tmp/.s.PGSQL.5433\"); print(\"UNIX-SOCKET-CONNECTED\")"'
mkplugin db_unix_login     '/usr/lib/postgresql/16/bin/psql -h /tmp -p 5433 -U platform_owner -d postgres -Atc "select current_user"'
mkplugin crash            'kill -SEGV $$'
echo "plugin user: $(id plugin)"
echo "sandbox: $(ls -ld $SB | awk "{print \$1, \$3, \$4}")"
echo "host secret perms: $(ls -l $HOSTD/secret.txt | awk "{print \$1, \$3, \$4}")"

run() {
  name=$1
  echo "--- $name ---"
  unshare -n -- sh -c "cd $SB/$name && exec setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs \
    prlimit --as=134217728 --cpu=5 --nproc=64 --fsize=1048576 --core=0 -- \
    env -i PATH=/usr/bin:/bin HOME=$SB/$name sh ./plugin.sh" 2>&1
  echo "exit=$?"
  # Degraded mode has no cgroup, so leftover children can only be reaped by uid after the fact.
  pkill -9 -u plugin 2>/dev/null
  echo "leftover after reap: $(pgrep -u plugin | wc -l)"
}

pkill -9 -u plugin 2>/dev/null
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
echo "=== POST-CHECKS ==="
echo "host secret still readable by host: $(cat $HOSTD/secret.txt)"
if [ -f "$HOSTD/evil.txt" ]; then echo "LEAK: plugin wrote into host dir"; else echo "host dir clean (no evil.txt)"; fi
echo "runner still alive after plugin SIGSEGV: yes (pid $$)"
echo "leftover plugin processes: $(pgrep -u plugin | wc -l)"
