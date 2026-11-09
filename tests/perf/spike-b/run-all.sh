#!/bin/sh
# Spike B runner. Executed inside WSL distro 'pglab'.
set -u
PGBIN=/usr/lib/postgresql/16/bin
BASE='/mnt/c/新建文件夹/temp_20261109_01/tests/perf/spike-b'
PSQL="$PGBIN/psql -h /tmp -p 5433 -U platform_owner -d platform"

su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D /var/lib/postgresql/spike -o '-p 5433 -k /tmp -c listen_addresses=' -l /tmp/pgspike.log -w start" >/dev/null 2>&1 || true

echo '=== BUILD DATA (100k records / 2M EAV rows) ==='
$PSQL -v ON_ERROR_STOP=1 -q -f "$BASE/01-schema.sql" 2>&1 | tail -5
echo "schema_exit=$?"

echo '=== BENCH ==='
$PSQL -f "$BASE/02-bench.sql" 2>&1 | grep -Ev '^\s*$' | grep -E 'Q[0-9]|---|Time:|count|status|record|^\s+[0-9]|f0|txt|active|paused|closed'

echo '=== EXPLAIN ==='
$PSQL -f "$BASE/03-explain.sql" 2>&1 | grep -Ev '^\s*$'

echo '=== TABLE SIZES ==='
$PSQL -tA -c "select 'eav_records='||pg_size_pretty(pg_total_relation_size('spike_b.record_eav')), 'eav_values='||pg_size_pretty(pg_total_relation_size('spike_b.field_value')), 'json_records='||pg_size_pretty(pg_total_relation_size('spike_b.record_json'))"
