-- Spike C / step 2: duplicate callback, timeout retry, compensation replay.
\echo '=== CASE 1: same callback delivered twice (expect applied, duplicate) ==='
SELECT spike_c.handle_callback('cb-1', 100) AS first_call;
SELECT spike_c.handle_callback('cb-1', 100) AS second_call;

\echo '=== CASE 1b: side effects must be exactly 1 (expect 1) ==='
SELECT count(*) AS side_effects FROM spike_c.side_effect WHERE idem_key = 'cb-1';

\echo '=== CASE 2: timeout retry reuses the key after a timeout (expect duplicate, still 1) ==='
SELECT spike_c.handle_callback('cb-2', 50) AS original;
SELECT spike_c.handle_callback('cb-2', 50) AS retry_after_timeout;
SELECT count(*) AS side_effects FROM spike_c.side_effect WHERE idem_key = 'cb-2';

\echo '=== CASE 2b: retry with a different key double-charges (expect 2: keys must be stable) ==='
SELECT spike_c.handle_callback('cb-2-retry', 50) AS different_key;
SELECT count(*) AS side_effects FROM spike_c.side_effect WHERE idem_key LIKE 'cb-2%';

\echo '=== CASE 3: compensation applied twice (expect compensated, replay-skipped) ==='
SELECT spike_c.compensate('cmp-1', 'cb-1', 100) AS first_compensation;
SELECT spike_c.compensate('cmp-1', 'cb-1', 100) AS replayed_compensation;

\echo '=== CASE 3b: net effect must be zero for cb-1 plus its compensation (expect 0) ==='
SELECT sum(amount) AS net FROM spike_c.side_effect WHERE idem_key IN ('cb-1', 'comp:cmp-1');

\echo '=== CASE 4: sequential stale transition (expect 0 rows, state unchanged) ==='
SELECT spike_c.advance('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 0, 'running') AS first_advance;
SELECT spike_c.advance('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 0, 'running') AS stale_advance;
SELECT state, version FROM spike_c.wf_instance;
