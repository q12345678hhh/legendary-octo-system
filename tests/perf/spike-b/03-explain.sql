-- Spike B / step 3: plans for the hardest query (Q3) under both models.
\timing on
\echo '--- EAV plan ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v1 ON v1.record_id = r.id AND v1.field_id = 1 AND v1.val_text = 'active'
  JOIN spike_b.field_value v2 ON v2.record_id = r.id AND v2.field_id = 2 AND v2.val_num BETWEEN 1000 AND 5000
 WHERE r.tenant_id = '11111111-1111-1111-1111-111111111111'
 ORDER BY r.created_at DESC LIMIT 50;

\echo '--- JSONB plan ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM spike_b.record_json
 WHERE tenant_id = '11111111-1111-1111-1111-111111111111' AND data @> '{"f01":"active"}'
   AND (data ->> 'f02')::numeric BETWEEN 1000 AND 5000
 ORDER BY created_at DESC LIMIT 50;
