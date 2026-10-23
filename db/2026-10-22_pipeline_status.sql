-- Migration: async pipeline status tracking (RabbitMQ module)
-- Run once per environment, before deploying the version that uses the status column.
-- Safe to inspect first:  SELECT source_url, country, count(*) FROM market_events
--                         GROUP BY 1, 2 HAVING count(*) > 1;   -- must return 0 rows

BEGIN;

ALTER TABLE market_events
  ADD COLUMN status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN status_updated_at TIMESTAMPTZ DEFAULT now();

-- Backfill status from the data that already exists
UPDATE market_events SET status = 'EMBEDDED'
  WHERE llm_summary IS NOT NULL AND embedding IS NOT NULL;
UPDATE market_events SET status = 'SUMMARIZED'
  WHERE llm_summary IS NOT NULL AND embedding IS NULL;

-- One row per (filing, affected country); last line of defense for idempotent writes
CREATE UNIQUE INDEX uq_event_source_country ON market_events (source_url, country);

COMMIT;

-- Expected: every existing row reports its real pipeline stage
SELECT status, count(*) FROM market_events GROUP BY status;
