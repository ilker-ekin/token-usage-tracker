-- Rollups gain a model dimension, so the dashboard can break spend down by
-- model without aggregating raw usage_event rows. Every existing aggregate
-- groups by something else and sums across models, so its results are
-- unchanged; only the row count grows (one row per model used).
--
-- Existing rows carry no model, so the table is rebuilt from usage_event,
-- the source of truth. Periods are UTC, as in RollupService: the day, the
-- Monday of the ISO week (date_trunc('week') is ISO), the first of the month.
ALTER TABLE rollup ADD COLUMN model VARCHAR(100);

-- The old key has no model, so per-model rows would collide with it.
ALTER TABLE rollup DROP CONSTRAINT uq_rollup_key;

TRUNCATE rollup;

INSERT INTO rollup (id, org_id, team_id, member_id, project_id, model, granularity, period_start,
                    total_input_tokens, total_output_tokens, total_cache_read_tokens,
                    total_cache_creation_tokens, total_cost_usd, total_lines_added, total_lines_removed)
SELECT gen_random_uuid(), e.org_id, e.team_id, e.member_id, e.project_id, e.model, g.granularity,
       CASE g.granularity
           WHEN 'DAY'   THEN (e.event_time AT TIME ZONE 'UTC')::date
           WHEN 'WEEK'  THEN date_trunc('week',  e.event_time AT TIME ZONE 'UTC')::date
           WHEN 'MONTH' THEN date_trunc('month', e.event_time AT TIME ZONE 'UTC')::date
       END AS period_start,
       sum(e.input_tokens), sum(e.output_tokens), sum(e.cache_read_tokens),
       sum(e.cache_creation_tokens), sum(e.cost_usd),
       sum(coalesce(e.lines_added, 0)), sum(coalesce(e.lines_removed, 0))
FROM usage_event e
CROSS JOIN (VALUES ('DAY'), ('WEEK'), ('MONTH')) AS g(granularity)
GROUP BY e.org_id, e.team_id, e.member_id, e.project_id, e.model, g.granularity, period_start;

ALTER TABLE rollup ALTER COLUMN model SET NOT NULL;

ALTER TABLE rollup
    ADD CONSTRAINT uq_rollup_key
        UNIQUE NULLS NOT DISTINCT (org_id, team_id, member_id, project_id, model, granularity, period_start);
