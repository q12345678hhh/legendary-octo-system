-- Spike C / step 1: minimal workflow model with idempotency and compensation ledgers.
DROP SCHEMA IF EXISTS spike_c CASCADE;
CREATE SCHEMA spike_c;

-- Instance carries an optimistic version; transitions must match the expected version.
CREATE TABLE spike_c.wf_instance (
  id         uuid PRIMARY KEY,
  state      text NOT NULL,
  version    integer NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Inbox dedupes inbound callbacks: one row per message key, ever.
CREATE TABLE spike_c.inbox (
  msg_key      text PRIMARY KEY,
  received_at  timestamptz NOT NULL DEFAULT now()
);

-- Side effects are recorded once per idempotency key.
CREATE TABLE spike_c.side_effect (
  id        bigserial PRIMARY KEY,
  idem_key  text NOT NULL UNIQUE,
  amount    numeric(12,2) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Compensations are keyed so replay cannot double-reverse.
CREATE TABLE spike_c.compensation (
  idem_key   text PRIMARY KEY,
  target_key text NOT NULL,
  amount     numeric(12,2) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Handler contract: inbox guard first, side effect second, both in one transaction.
CREATE OR REPLACE FUNCTION spike_c.handle_callback(p_key text, p_amount numeric)
RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  v_rows integer;
BEGIN
  INSERT INTO spike_c.inbox (msg_key) VALUES (p_key) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows = 0 THEN
    RETURN 'duplicate';
  END IF;
  INSERT INTO spike_c.side_effect (idem_key, amount) VALUES (p_key, p_amount);
  RETURN 'applied';
END
$$;

CREATE OR REPLACE FUNCTION spike_c.compensate(p_key text, p_target text, p_amount numeric)
RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  v_rows integer;
BEGIN
  INSERT INTO spike_c.compensation (idem_key, target_key, amount)
  VALUES (p_key, p_target, p_amount) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows = 0 THEN
    RETURN 'replay-skipped';
  END IF;
  INSERT INTO spike_c.side_effect (idem_key, amount) VALUES ('comp:' || p_key, -p_amount);
  RETURN 'compensated';
END
$$;

-- Transitions are only valid from the expected version; losers must see 0 rows.
CREATE OR REPLACE FUNCTION spike_c.advance(p_id uuid, p_from_version integer, p_to_state text)
RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
  v_rows integer;
BEGIN
  UPDATE spike_c.wf_instance
     SET state = p_to_state,
         version = version + 1,
         updated_at = now()
   WHERE id = p_id AND version = p_from_version;
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  RETURN v_rows;
END
$$;

INSERT INTO spike_c.wf_instance (id, state, version)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'draft', 0);
