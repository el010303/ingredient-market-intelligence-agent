-- Migration: record every ingestion run (schedule or manual) for the pipeline status page.
-- Run AS THE APPLICATION'S DATABASE USER (see 2026-10-23_hts_rates.sql for why).
CREATE TABLE IF NOT EXISTS ingestion_runs (
    id          BIGSERIAL PRIMARY KEY,
    trigger     VARCHAR(20) NOT NULL,          -- 'schedule' | 'manual'
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    result      TEXT,
    error       TEXT
);
