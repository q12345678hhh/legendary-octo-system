#!/bin/sh
# Spike C / step 3: two concurrent transitions from the same version.
PGBIN=${PGBIN:-/usr/lib/postgresql/16/bin}
PSQL="$PGBIN/psql -h /tmp -p 5433 -U platform_owner -d platform -tA"
INST='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

$PSQL -c "UPDATE spike_c.wf_instance SET state='draft', version=0 WHERE id='$INST'" >/dev/null

echo '=== CASE 5: concurrent advance from version 0 (expect advance=1 for one, 0 for the other) ==='
(
  $PSQL <<SQL
BEGIN;
SELECT 'A advance=' || spike_c.advance('$INST', 0, 'running-A');
SELECT pg_sleep(2);
COMMIT;
SQL
) > /tmp/wf_a.out 2>&1 &

sleep 0.4

(
  $PSQL <<SQL
BEGIN;
SELECT 'B advance=' || spike_c.advance('$INST', 0, 'running-B');
SELECT pg_sleep(2);
COMMIT;
SQL
) > /tmp/wf_b.out 2>&1 &

wait
echo "session A: $(grep advance /tmp/wf_a.out)"
echo "session B: $(grep advance /tmp/wf_b.out)"

echo '=== CASE 5b: final state must be a single transition (expect version=1) ==='
$PSQL -c "SELECT state || ' version=' || version AS final FROM spike_c.wf_instance WHERE id='$INST'"
