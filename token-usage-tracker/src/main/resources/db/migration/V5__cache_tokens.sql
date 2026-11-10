-- Prompt-cache tokens are billed at their own rates (reads far below the
-- input price, writes above it), so they are stored and priced separately
-- from input_tokens, which now means uncached input only.
--
-- Token columns are NOT NULL DEFAULT 0: existing rows never reported cache
-- tokens separately, so 0 is the truth for them and sums need no COALESCE.
-- PostgreSQL adds a constant default without rewriting the table.
ALTER TABLE usage_event
    ADD COLUMN cache_read_tokens     INT NOT NULL DEFAULT 0,
    ADD COLUMN cache_creation_tokens INT NOT NULL DEFAULT 0;

ALTER TABLE daily_rollup
    ADD COLUMN total_cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_cache_creation_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE weekly_rollup
    ADD COLUMN total_cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_cache_creation_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE monthly_rollup
    ADD COLUMN total_cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_cache_creation_tokens BIGINT NOT NULL DEFAULT 0;

-- Price columns are nullable: NULL means "no cache price known" and the
-- calculator falls back to the input price, which is what every existing row
-- has charged so far. NUMERIC(18,10) rather than the (12,6) of the other
-- price columns, because cheap cache-read prices need more than six decimals
-- per 1k tokens (e.g. 0.0000375).
ALTER TABLE model_pricing
    ADD COLUMN cache_read_price_per_1k     NUMERIC(18,10),
    ADD COLUMN cache_creation_price_per_1k NUMERIC(18,10);
