-- Spike B / step 2: five queries per model, three timed runs each.
\set tenant_a '''11111111-1111-1111-1111-111111111111'''
\timing on

\echo '########## Q1 list page (tenant A, newest first, 50 rows) ##########'
\echo '--- EAV ---'
\o /dev/null
SELECT id, created_at FROM spike_b.record_eav WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
SELECT id, created_at FROM spike_b.record_eav WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
SELECT id, created_at FROM spike_b.record_eav WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
\o
\echo '--- JSONB ---'
\o /dev/null
SELECT id, created_at FROM spike_b.record_json WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
SELECT id, created_at FROM spike_b.record_json WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
SELECT id, created_at FROM spike_b.record_json WHERE tenant_id = :tenant_a ORDER BY created_at DESC LIMIT 50;
\o

\echo '########## Q2 single-field filter (status=active, 50 rows) ##########'
\echo '--- EAV ---'
\o /dev/null
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v ON v.record_id = r.id AND v.tenant_id = r.tenant_id
 WHERE r.tenant_id = :tenant_a AND v.field_id = 1 AND v.val_text = 'active'
 ORDER BY r.created_at DESC LIMIT 50;
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v ON v.record_id = r.id AND v.tenant_id = r.tenant_id
 WHERE r.tenant_id = :tenant_a AND v.field_id = 1 AND v.val_text = 'active'
 ORDER BY r.created_at DESC LIMIT 50;
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v ON v.record_id = r.id AND v.tenant_id = r.tenant_id
 WHERE r.tenant_id = :tenant_a AND v.field_id = 1 AND v.val_text = 'active'
 ORDER BY r.created_at DESC LIMIT 50;
\o
\echo '--- JSONB ---'
\o /dev/null
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' ORDER BY created_at DESC LIMIT 50;
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' ORDER BY created_at DESC LIMIT 50;
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' ORDER BY created_at DESC LIMIT 50;
\o

\echo '########## Q3 two-field filter (status=active AND amount 1000..5000) ##########'
\echo '--- EAV ---'
\o /dev/null
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v1 ON v1.record_id = r.id AND v1.field_id = 1 AND v1.val_text = 'active'
  JOIN spike_b.field_value v2 ON v2.record_id = r.id AND v2.field_id = 2 AND v2.val_num BETWEEN 1000 AND 5000
 WHERE r.tenant_id = :tenant_a
 ORDER BY r.created_at DESC LIMIT 50;
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v1 ON v1.record_id = r.id AND v1.field_id = 1 AND v1.val_text = 'active'
  JOIN spike_b.field_value v2 ON v2.record_id = r.id AND v2.field_id = 2 AND v2.val_num BETWEEN 1000 AND 5000
 WHERE r.tenant_id = :tenant_a
 ORDER BY r.created_at DESC LIMIT 50;
SELECT r.id FROM spike_b.record_eav r
  JOIN spike_b.field_value v1 ON v1.record_id = r.id AND v1.field_id = 1 AND v1.val_text = 'active'
  JOIN spike_b.field_value v2 ON v2.record_id = r.id AND v2.field_id = 2 AND v2.val_num BETWEEN 1000 AND 5000
 WHERE r.tenant_id = :tenant_a
 ORDER BY r.created_at DESC LIMIT 50;
\o
\echo '--- JSONB ---'
\o /dev/null
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' AND (data ->> 'f02')::numeric BETWEEN 1000 AND 5000 ORDER BY created_at DESC LIMIT 50;
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' AND (data ->> 'f02')::numeric BETWEEN 1000 AND 5000 ORDER BY created_at DESC LIMIT 50;
SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' AND (data ->> 'f02')::numeric BETWEEN 1000 AND 5000 ORDER BY created_at DESC LIMIT 50;
\o

\echo '########## Q4 aggregate (count by status, whole tenant) ##########'
\echo '--- EAV ---'
\o /dev/null
SELECT v.val_text, count(*) FROM spike_b.field_value v WHERE v.tenant_id = :tenant_a AND v.field_id = 1 GROUP BY 1;
SELECT v.val_text, count(*) FROM spike_b.field_value v WHERE v.tenant_id = :tenant_a AND v.field_id = 1 GROUP BY 1;
\o
\echo '--- JSONB ---'
\o /dev/null
SELECT data ->> 'f01', count(*) FROM spike_b.record_json WHERE tenant_id = :tenant_a GROUP BY 1;
SELECT data ->> 'f01', count(*) FROM spike_b.record_json WHERE tenant_id = :tenant_a GROUP BY 1;
\o

\echo '########## Q5 detail (one record, all 20 fields) ##########'
\echo '--- EAV ---'
\o /dev/null
SELECT field_id, coalesce(val_text, val_num::text) FROM spike_b.field_value WHERE record_id = 50000 ORDER BY field_id;
SELECT field_id, coalesce(val_text, val_num::text) FROM spike_b.field_value WHERE record_id = 50000 ORDER BY field_id;
\o
\echo '--- JSONB ---'
\o /dev/null
SELECT data FROM spike_b.record_json WHERE id = 50000;
SELECT data FROM spike_b.record_json WHERE id = 50000;
\o

\echo '########## result sanity (rows returned per query) ##########'
SELECT 'Q2_eav' AS q, count(*) FROM (
  SELECT r.id FROM spike_b.record_eav r
    JOIN spike_b.field_value v ON v.record_id = r.id AND v.tenant_id = r.tenant_id
   WHERE r.tenant_id = :tenant_a AND v.field_id = 1 AND v.val_text = 'active'
   ORDER BY r.created_at DESC LIMIT 50) t
UNION ALL
SELECT 'Q2_json', count(*) FROM (
  SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}' ORDER BY created_at DESC LIMIT 50) t
UNION ALL
SELECT 'Q3_eav', count(*) FROM (
  SELECT r.id FROM spike_b.record_eav r
    JOIN spike_b.field_value v1 ON v1.record_id = r.id AND v1.field_id = 1 AND v1.val_text = 'active'
    JOIN spike_b.field_value v2 ON v2.record_id = r.id AND v2.field_id = 2 AND v2.val_num BETWEEN 1000 AND 5000
   WHERE r.tenant_id = :tenant_a ORDER BY r.created_at DESC LIMIT 50) t
UNION ALL
SELECT 'Q3_json', count(*) FROM (
  SELECT id FROM spike_b.record_json WHERE tenant_id = :tenant_a AND data @> '{"f01":"active"}'
     AND (data ->> 'f02')::numeric BETWEEN 1000 AND 5000 ORDER BY created_at DESC LIMIT 50) t;
