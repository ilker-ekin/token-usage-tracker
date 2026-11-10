-- daily_rollup, weekly_rollup and monthly_rollup were the same table three
-- times, differing only in what their date column meant. They become one
-- table with a granularity column, so a new measure is added in one place
-- and an ingest updates all three periods with a single statement.
--
-- Contention is unchanged by this: an ingest still locks exactly three rows
-- (the day, the week, the month of its key), the same rows as before.
CREATE TABLE rollup (
    id                          UUID PRIMARY KEY,
    org_id                      UUID          NOT NULL REFERENCES organization (id),
    team_id                     UUID          REFERENCES team (id),
    member_id                   UUID          NOT NULL REFERENCES member (id),
    project_id                  UUID          NOT NULL REFERENCES project (id),
    granularity                 VARCHAR(10)   NOT NULL CHECK (granularity IN ('DAY', 'WEEK', 'MONTH')),
    -- The day itself, the Monday of the ISO week, or the first of the month (UTC)
    period_start                DATE          NOT NULL,
    total_input_tokens          BIGINT        NOT NULL DEFAULT 0,
    total_output_tokens         BIGINT        NOT NULL DEFAULT 0,
    total_cache_read_tokens     BIGINT        NOT NULL DEFAULT 0,
    total_cache_creation_tokens BIGINT        NOT NULL DEFAULT 0,
    total_cost_usd              NUMERIC(14,6) NOT NULL DEFAULT 0,
    total_lines_added           BIGINT        NOT NULL DEFAULT 0,
    total_lines_removed         BIGINT        NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- NULLS NOT DISTINCT for the same reason as in V3: team_id is nullable
    -- and ON CONFLICT must still fire for members without a team.
    CONSTRAINT uq_rollup_key
        UNIQUE NULLS NOT DISTINCT (org_id, team_id, member_id, project_id, granularity, period_start)
);

INSERT INTO rollup (id, org_id, team_id, member_id, project_id, granularity, period_start,
                    total_input_tokens, total_output_tokens, total_cache_read_tokens,
                    total_cache_creation_tokens, total_cost_usd, total_lines_added,
                    total_lines_removed, created_at)
SELECT id, org_id, team_id, member_id, project_id, 'DAY', rollup_date,
       total_input_tokens, total_output_tokens, total_cache_read_tokens,
       total_cache_creation_tokens, total_cost_usd, total_lines_added,
       total_lines_removed, created_at
FROM daily_rollup
UNION ALL
SELECT id, org_id, team_id, member_id, project_id, 'WEEK', week_start,
       total_input_tokens, total_output_tokens, total_cache_read_tokens,
       total_cache_creation_tokens, total_cost_usd, total_lines_added,
       total_lines_removed, created_at
FROM weekly_rollup
UNION ALL
SELECT id, org_id, team_id, member_id, project_id, 'MONTH', month_start,
       total_input_tokens, total_output_tokens, total_cache_read_tokens,
       total_cache_creation_tokens, total_cost_usd, total_lines_added,
       total_lines_removed, created_at
FROM monthly_rollup;

DROP TABLE daily_rollup, weekly_rollup, monthly_rollup;

-- Dashboard queries read one org, one granularity and a date range; budget
-- evaluation reads one org and one month. The unique key cannot serve those:
-- after org_id it continues with team, member and project.
-- Rollup totals are not part of any index, so the upsert's updates stay
-- heap-only and this index costs ingest nothing beyond the first insert of a key.
CREATE INDEX idx_rollup_org_granularity_period ON rollup (org_id, granularity, period_start);
