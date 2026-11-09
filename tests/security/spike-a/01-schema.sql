-- Spike A / step 1: schema and policy under test.
-- Runtime roles must NOT be superuser: superusers bypass RLS even with FORCE.
DROP SCHEMA IF EXISTS spike_a CASCADE;
CREATE SCHEMA spike_a;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'spike_app') THEN
    CREATE ROLE spike_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'spike_owner') THEN
    CREATE ROLE spike_owner NOLOGIN;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA spike_a TO spike_app, spike_owner;

CREATE TABLE spike_a.tenant (
  id   uuid PRIMARY KEY,
  name text NOT NULL
);

CREATE TABLE spike_a.employee (
  id        bigserial PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES spike_a.tenant(id),
  full_name text NOT NULL,
  salary    numeric(12,2) NOT NULL
);

INSERT INTO spike_a.tenant (id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'tenant-A'),
  ('22222222-2222-2222-2222-222222222222', 'tenant-B');

INSERT INTO spike_a.employee (tenant_id, full_name, salary) VALUES
  ('11111111-1111-1111-1111-111111111111', 'A-alice', 10000),
  ('11111111-1111-1111-1111-111111111111', 'A-bob',   12000),
  ('22222222-2222-2222-2222-222222222222', 'B-carol', 30000);

-- Owner is a NON-superuser role: this is what makes FORCE meaningful.
ALTER TABLE spike_a.employee OWNER TO spike_owner;

ALTER TABLE spike_a.employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE spike_a.employee FORCE ROW LEVEL SECURITY;

-- nullif(...,'') is required: RESET on a placeholder yields empty string,
-- and a bare ''::uuid cast raises instead of failing closed.
CREATE POLICY tenant_isolation ON spike_a.employee
  USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON spike_a.employee TO spike_app;
GRANT SELECT ON spike_a.tenant TO spike_app;
GRANT USAGE ON SEQUENCE spike_a.employee_id_seq TO spike_app;
