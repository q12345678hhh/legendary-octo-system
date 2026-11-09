-- Spike B / step 1: 100k records with 20 dynamic fields, two storage models.
-- Model 1 (EAV): record + field_value rows (2,000,000 rows), tenant denormalized for fair filtering.
-- Model 2 (JSONB): record + jsonb payload (100,000 rows) + GIN.
DROP SCHEMA IF EXISTS spike_b CASCADE;
CREATE SCHEMA spike_b;

CREATE TABLE spike_b.module_field (
  id   integer PRIMARY KEY,
  code text NOT NULL
);
INSERT INTO spike_b.module_field (id, code)
SELECT g, 'f' || lpad(g::text, 2, '0') FROM generate_series(1, 20) g;

-- ---------- Model 1: EAV ----------
CREATE TABLE spike_b.record_eav (
  id         bigint PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  created_at timestamptz NOT NULL
);

CREATE TABLE spike_b.field_value (
  tenant_id uuid    NOT NULL,
  record_id bigint  NOT NULL,
  field_id  integer NOT NULL,
  val_text  text,
  val_num   numeric,
  PRIMARY KEY (record_id, field_id)
);

INSERT INTO spike_b.record_eav (id, tenant_id, created_at)
SELECT g,
       CASE WHEN g % 2 = 0 THEN '11111111-1111-1111-1111-111111111111'::uuid
            ELSE '22222222-2222-2222-2222-222222222222'::uuid END,
       timestamptz '2026-01-01' + (g || ' seconds')::interval
FROM generate_series(1, 100000) g;

INSERT INTO spike_b.field_value (tenant_id, record_id, field_id, val_text, val_num)
SELECT r.tenant_id, r.id, f.id,
       CASE WHEN f.id = 1 THEN (ARRAY['active','paused','closed'])[1 + (r.id % 3)]
            WHEN f.id BETWEEN 3 AND 17 THEN 'txt-' || (r.id % 1000)
            ELSE NULL END,
       CASE WHEN f.id = 2 THEN (r.id * 37 % 100000)::numeric
            WHEN f.id BETWEEN 18 AND 20 THEN (r.id * 11 % 1000)::numeric
            ELSE NULL END
FROM spike_b.record_eav r
CROSS JOIN spike_b.module_field f;

CREATE INDEX ix_eav_tenant_time ON spike_b.record_eav (tenant_id, created_at DESC);
CREATE INDEX ix_eav_status ON spike_b.field_value (tenant_id, field_id, val_text, record_id);
CREATE INDEX ix_eav_amount ON spike_b.field_value (tenant_id, field_id, val_num, record_id);

-- ---------- Model 2: JSONB ----------
CREATE TABLE spike_b.record_json (
  id         bigint PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  created_at timestamptz NOT NULL,
  data       jsonb NOT NULL
);

INSERT INTO spike_b.record_json (id, tenant_id, created_at, data)
SELECT r.id, r.tenant_id, r.created_at,
       jsonb_build_object(
         'f01', (ARRAY['active','paused','closed'])[1 + (r.id % 3)],
         'f02', (r.id * 37 % 100000),
         'f03', 'txt-' || (r.id % 1000), 'f04', 'txt-' || (r.id % 1000),
         'f05', 'txt-' || (r.id % 1000), 'f06', 'txt-' || (r.id % 1000),
         'f07', 'txt-' || (r.id % 1000), 'f08', 'txt-' || (r.id % 1000),
         'f09', 'txt-' || (r.id % 1000), 'f10', 'txt-' || (r.id % 1000),
         'f11', 'txt-' || (r.id % 1000), 'f12', 'txt-' || (r.id % 1000),
         'f13', 'txt-' || (r.id % 1000), 'f14', 'txt-' || (r.id % 1000),
         'f15', 'txt-' || (r.id % 1000), 'f16', 'txt-' || (r.id % 1000),
         'f17', 'txt-' || (r.id % 1000),
         'f18', (r.id * 11 % 1000), 'f19', (r.id * 11 % 1000), 'f20', (r.id * 11 % 1000))
FROM spike_b.record_eav r;

CREATE INDEX ix_json_tenant_time ON spike_b.record_json (tenant_id, created_at DESC);
CREATE INDEX ix_json_gin ON spike_b.record_json USING gin (data jsonb_path_ops);
CREATE INDEX ix_json_amount ON spike_b.record_json (((data ->> 'f02')::numeric));

ANALYZE spike_b.record_eav;
ANALYZE spike_b.field_value;
ANALYZE spike_b.record_json;

SELECT (SELECT count(*) FROM spike_b.record_eav)  AS eav_records,
       (SELECT count(*) FROM spike_b.field_value) AS eav_values,
       (SELECT count(*) FROM spike_b.record_json) AS json_records;
