-- Rollups are now written with INSERT ... ON CONFLICT DO UPDATE, which needs
-- the rollup key to be unique. team_id is nullable and NULLs are distinct in a
-- plain UNIQUE constraint, so members without a team could get several rows
-- for the same key (and ON CONFLICT would never fire for them).
--
-- Chosen fix: UNIQUE NULLS NOT DISTINCT (PostgreSQL 15+). The alternative, a
-- unique index on COALESCE(team_id, <sentinel uuid>), works on older versions
-- but forces every ON CONFLICT clause to repeat the exact expression and
-- invents a fake team id. We run PostgreSQL 16, so the declarative form wins.
--
-- Step 1 merges any duplicate team-less rows that already exist (summing their
-- totals into one row), otherwise the new constraint could not be created.

WITH dupes AS (
    SELECT org_id, member_id, project_id, rollup_date,
           min(id::text)::uuid      AS keep_id,
           sum(total_input_tokens)  AS input_tokens,
           sum(total_output_tokens) AS output_tokens,
           sum(total_cost_usd)      AS cost_usd,
           sum(total_lines_added)   AS lines_added,
           sum(total_lines_removed) AS lines_removed
    FROM daily_rollup
    WHERE team_id IS NULL
    GROUP BY org_id, member_id, project_id, rollup_date
    HAVING count(*) > 1
), kept AS (
    UPDATE daily_rollup r
    SET total_input_tokens  = d.input_tokens,
        total_output_tokens = d.output_tokens,
        total_cost_usd      = d.cost_usd,
        total_lines_added   = d.lines_added,
        total_lines_removed = d.lines_removed
    FROM dupes d
    WHERE r.id = d.keep_id
)
DELETE FROM daily_rollup r
USING dupes d
WHERE r.team_id IS NULL
  AND r.org_id = d.org_id AND r.member_id = d.member_id
  AND r.project_id = d.project_id AND r.rollup_date = d.rollup_date
  AND r.id <> d.keep_id;

WITH dupes AS (
    SELECT org_id, member_id, project_id, week_start,
           min(id::text)::uuid      AS keep_id,
           sum(total_input_tokens)  AS input_tokens,
           sum(total_output_tokens) AS output_tokens,
           sum(total_cost_usd)      AS cost_usd,
           sum(total_lines_added)   AS lines_added,
           sum(total_lines_removed) AS lines_removed
    FROM weekly_rollup
    WHERE team_id IS NULL
    GROUP BY org_id, member_id, project_id, week_start
    HAVING count(*) > 1
), kept AS (
    UPDATE weekly_rollup r
    SET total_input_tokens  = d.input_tokens,
        total_output_tokens = d.output_tokens,
        total_cost_usd      = d.cost_usd,
        total_lines_added   = d.lines_added,
        total_lines_removed = d.lines_removed
    FROM dupes d
    WHERE r.id = d.keep_id
)
DELETE FROM weekly_rollup r
USING dupes d
WHERE r.team_id IS NULL
  AND r.org_id = d.org_id AND r.member_id = d.member_id
  AND r.project_id = d.project_id AND r.week_start = d.week_start
  AND r.id <> d.keep_id;

WITH dupes AS (
    SELECT org_id, member_id, project_id, month_start,
           min(id::text)::uuid      AS keep_id,
           sum(total_input_tokens)  AS input_tokens,
           sum(total_output_tokens) AS output_tokens,
           sum(total_cost_usd)      AS cost_usd,
           sum(total_lines_added)   AS lines_added,
           sum(total_lines_removed) AS lines_removed
    FROM monthly_rollup
    WHERE team_id IS NULL
    GROUP BY org_id, member_id, project_id, month_start
    HAVING count(*) > 1
), kept AS (
    UPDATE monthly_rollup r
    SET total_input_tokens  = d.input_tokens,
        total_output_tokens = d.output_tokens,
        total_cost_usd      = d.cost_usd,
        total_lines_added   = d.lines_added,
        total_lines_removed = d.lines_removed
    FROM dupes d
    WHERE r.id = d.keep_id
)
DELETE FROM monthly_rollup r
USING dupes d
WHERE r.team_id IS NULL
  AND r.org_id = d.org_id AND r.member_id = d.member_id
  AND r.project_id = d.project_id AND r.month_start = d.month_start
  AND r.id <> d.keep_id;

-- Step 2: replace the V1 constraints (auto-named, truncated to 63 chars).
ALTER TABLE daily_rollup
    DROP CONSTRAINT daily_rollup_org_id_team_id_member_id_project_id_rollup_dat_key,
    ADD CONSTRAINT uq_daily_rollup_key
        UNIQUE NULLS NOT DISTINCT (org_id, team_id, member_id, project_id, rollup_date);

ALTER TABLE weekly_rollup
    DROP CONSTRAINT weekly_rollup_org_id_team_id_member_id_project_id_week_star_key,
    ADD CONSTRAINT uq_weekly_rollup_key
        UNIQUE NULLS NOT DISTINCT (org_id, team_id, member_id, project_id, week_start);

ALTER TABLE monthly_rollup
    DROP CONSTRAINT monthly_rollup_org_id_team_id_member_id_project_id_month_st_key,
    ADD CONSTRAINT uq_monthly_rollup_key
        UNIQUE NULLS NOT DISTINCT (org_id, team_id, member_id, project_id, month_start);
