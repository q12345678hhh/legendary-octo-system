#!/bin/sh
# Spike C runner. Executed inside WSL distro 'pglab'.
set -u
PGBIN=/usr/lib/postgresql/16/bin
BASE='/mnt/c/新建文件夹/temp_20261109_01/tests/security/spike-c'
PSQL="$PGBIN/psql -h /tmp -p 5433 -U platform_owner -d platform"

su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D /var/lib/postgresql/spike -o '-p 5433 -k /tmp -c listen_addresses=' -l /tmp/pgspike.log -w start" >/dev/null 2>&1 || true

echo '=== APPLY SCHEMA ==='
$PSQL -v ON_ERROR_STOP=1 -q -f "$BASE/01-schema.sql"
echo "schema_exit=$?"

echo '=== VERIFY: idempotency and compensation ==='
$PSQL -f "$BASE/02-verify.sql"

echo '=== VERIFY: concurrency ==='
PGBIN=$PGBIN sh "$BASE/03-concurrency.sh"
echo "concurrency_exit=$?"
