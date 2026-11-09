-- Spike A / step 2: verification. Expectation is stated in each label.
-- Session user: platform_owner (superuser, used only for DDL).

\echo '=== CONTROL 1: superuser + FORCE, no context (expect 3: superuser bypasses RLS) ==='
SELECT count(*) AS visible_rows FROM spike_a.employee;

\echo '=== CONTROL 2: non-superuser table owner + FORCE, no context (expect 0) ==='
SET ROLE spike_owner;
SELECT count(*) AS visible_rows FROM spike_a.employee;
RESET ROLE;

\echo '=== CONTROL 3: non-superuser owner + NO FORCE (expect 3: FORCE is mandatory) ==='
ALTER TABLE spike_a.employee NO FORCE ROW LEVEL SECURITY;
SET ROLE spike_owner;
SELECT count(*) AS visible_rows FROM spike_a.employee;
RESET ROLE;
ALTER TABLE spike_a.employee FORCE ROW LEVEL SECURITY;

\echo '=== CASE 1: app role, no context SELECT (expect 0) ==='
SET ROLE spike_app;
SELECT count(*) AS visible_rows FROM spike_a.employee;

\echo '=== CASE 1b: app role, no context INSERT (expect ERROR) ==='
INSERT INTO spike_a.employee (tenant_id, full_name, salary)
VALUES ('11111111-1111-1111-1111-111111111111', 'no-context', 1);

\echo '=== CASE 2: context=A, forged insert tenant_id=B (expect ERROR) ==='
SET app.tenant_id = '11111111-1111-1111-1111-111111111111';
INSERT INTO spike_a.employee (tenant_id, full_name, salary)
VALUES ('22222222-2222-2222-2222-222222222222', 'forged', 1);

\echo '=== CASE 3: context=A, total visible (expect 2) ==='
SELECT count(*) AS visible_rows FROM spike_a.employee;

\echo '=== CASE 3b: context=A, filter on tenant B (expect 0) ==='
SELECT count(*) AS b_rows FROM spike_a.employee
WHERE tenant_id = '22222222-2222-2222-2222-222222222222';

\echo '=== CASE 4: session GUC persists across requests (expect 2, 1, 2) ==='
SELECT count(*) AS before_txn FROM spike_a.employee;
BEGIN;
SET LOCAL app.tenant_id = '22222222-2222-2222-2222-222222222222';
SELECT count(*) AS inside_txn FROM spike_a.employee;
COMMIT;
SELECT count(*) AS after_commit_reverts_to_session_value FROM spike_a.employee;

\echo '=== CASE 5: RESET then query with hardened policy (expect 0, no error) ==='
RESET app.tenant_id;
BEGIN;
SELECT count(*) AS visible_rows_after_reset FROM spike_a.employee;
COMMIT;

\echo '=== CASE 6: plain SET inside txn survives COMMIT (expect 2: pooled connection leaks) ==='
BEGIN;
SET app.tenant_id = '11111111-1111-1111-1111-111111111111';
COMMIT;
SELECT count(*) AS leaked_after_commit FROM spike_a.employee;

RESET app.tenant_id;
RESET ROLE;
