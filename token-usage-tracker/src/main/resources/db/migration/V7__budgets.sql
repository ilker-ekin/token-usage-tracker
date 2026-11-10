-- Monthly spend caps per project or team, and the alerts raised against them.
--
-- scope_id points at project.id or team.id depending on scope_type, so it
-- cannot carry a foreign key; the service checks it belongs to the org.
CREATE TABLE budget (
    id                UUID PRIMARY KEY,
    org_id            UUID          NOT NULL REFERENCES organization (id),
    scope_type        VARCHAR(20)   NOT NULL CHECK (scope_type IN ('PROJECT', 'TEAM')),
    scope_id          UUID          NOT NULL,
    monthly_limit_usd NUMERIC(14,2) NOT NULL CHECK (monthly_limit_usd > 0),
    webhook_url       VARCHAR(2000) NOT NULL,
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_budget_scope UNIQUE (org_id, scope_type, scope_id)
);

-- One row per budget, month and threshold. The unique key is what prevents
-- duplicate alerts: the evaluation inserts with ON CONFLICT DO NOTHING, so
-- repeated runs, restarts and several app instances all converge on one row.
--
-- The table doubles as the delivery outbox (status, attempts, claimed_at).
-- spend_usd and limit_usd are snapshots from the moment the threshold was
-- first seen crossed.
CREATE TABLE budget_alert (
    id            UUID PRIMARY KEY,
    budget_id     UUID          NOT NULL REFERENCES budget (id) ON DELETE CASCADE,
    period_start  DATE          NOT NULL, -- first day of the month, UTC
    threshold_pct INT           NOT NULL,
    spend_usd     NUMERIC(14,6) NOT NULL,
    limit_usd     NUMERIC(14,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts      INT           NOT NULL DEFAULT 0,
    claimed_at    TIMESTAMPTZ,
    sent_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_budget_alert UNIQUE (budget_id, period_start, threshold_pct)
);

-- The sender only ever looks for undelivered rows.
CREATE INDEX idx_budget_alert_undelivered ON budget_alert (created_at)
    WHERE status IN ('PENDING', 'SENDING');
