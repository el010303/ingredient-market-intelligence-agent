-- Migration: USITC HTS base tariff rates (data source 2)
-- Run once per environment, before deploying the version that uses tariff_rates.

BEGIN;

-- Curated mapping ingredient -> HTS 10-digit statistical line.
-- Verified against the USITC HTS REST API on 2026-10-23. Ingredients without a
-- dedicated or order-scope line stay NULL rather than being mapped to a guess.
ALTER TABLE ingredients ADD COLUMN IF NOT EXISTS hts_code VARCHAR(20);

UPDATE ingredients SET hts_code = '2922.49.43.00' WHERE name = 'Glycine';
UPDATE ingredients SET hts_code = '2918.14.00.00' WHERE name = 'Citric Acid';
UPDATE ingredients SET hts_code = '2912.41.00.00' WHERE name = 'Vanillin';
UPDATE ingredients SET hts_code = '2922.42.10.00' WHERE name = 'Monosodium Glutamate';
UPDATE ingredients SET hts_code = '2932.14.00.00' WHERE name = 'Sucralose';
UPDATE ingredients SET hts_code = '1108.20.00.00' WHERE name = 'Inulin';
-- Basket lines ("Other" / "Protein isolates"): same lines cited in the AD/CVD order scopes
UPDATE ingredients SET hts_code = '2905.49.40.00' WHERE name = 'Erythritol';
UPDATE ingredients SET hts_code = '3504.00.10.00' WHERE name = 'Pea Protein';
UPDATE ingredients SET hts_code = '2938.90.00.00' WHERE name = 'Stevia';
-- Statistical suffix: the duty rate is defined on the 8-digit parent 3913.90.20 (inherited in code)
UPDATE ingredients SET hts_code = '3913.90.20.15' WHERE name = 'Xanthan Gum';

-- Latest observed rates per HTS line (one row per line; history lives in market_events)
CREATE TABLE IF NOT EXISTS tariff_rates (
    hts_code      VARCHAR(20) PRIMARY KEY,
    description   TEXT,
    general_rate  VARCHAR(200),
    special_rate  TEXT,
    other_rate    VARCHAR(200),
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMIT;

SELECT name, hts_code FROM ingredients WHERE hts_code IS NOT NULL ORDER BY name;
