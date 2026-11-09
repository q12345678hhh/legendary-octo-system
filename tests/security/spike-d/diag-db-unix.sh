#!/bin/sh
# Can a plugin process reach PostgreSQL over the shared unix socket, bypassing the empty netns?
set -u
PSQL=/usr/lib/postgresql/16/bin/psql
exec_as_plugin() {
  unshare -n -- sh -c "cd /tmp && exec setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs \
    prlimit --nproc=64 -- env -i PATH=/usr/bin:/bin HOME=/tmp $PSQL -h /tmp -p 5433 -U platform_owner -d postgres -Atc \"$1\"" 2>&1
}

echo "--- A: TCP 127.0.0.1:5433 ---"
unshare -n -- sh -c 'cd /tmp && exec setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs prlimit --nproc=64 -- env -i PATH=/usr/bin:/bin HOME=/tmp python3 -c "import socket; socket.create_connection((\"127.0.0.1\", 5433), 3); print(\"TCP-CONNECTED\")"' 2>&1
echo "exitA=$?"

echo "--- B: unix socket connect ---"
unshare -n -- sh -c 'cd /tmp && exec setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs prlimit --nproc=64 -- env -i PATH=/usr/bin:/bin HOME=/tmp python3 -c "import socket; s=socket.socket(socket.AF_UNIX); s.connect(\"/tmp/.s.PGSQL.5433\"); print(\"UNIX-SOCKET-CONNECTED\")"' 2>&1
echo "exitB=$?"

echo "--- C: login as platform_owner (trust) over unix socket ---"
exec_as_plugin "select current_user, current_setting('server_version')"
echo "exitC=$?"

echo "--- D: enumerate tenant tables as platform_owner ---"
exec_as_plugin "select table_schema||'.'||table_name from information_schema.tables where table_schema like 'spike%' order by 1 limit 10"
echo "exitD=$?"
