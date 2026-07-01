CREATE TABLE organization (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE team (
    id         UUID PRIMARY KEY,
    org_id     UUID         NOT NULL REFERENCES organization (id),
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id           UUID PRIMARY KEY,
    org_id       UUID         NOT NULL REFERENCES organization (id),
    team_id      UUID         REFERENCES team (id),
    email        VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id         UUID PRIMARY KEY,
    org_id     UUID         NOT NULL REFERENCES organization (id),
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE model_pricing (
    id                  UUID PRIMARY KEY,
    model               VARCHAR(100)  NOT NULL,
    input_price_per_1k  NUMERIC(12,6) NOT NULL,
    output_price_per_1k NUMERIC(12,6) NOT NULL,
    valid_from          TIMESTAMPTZ   NOT NULL,
    valid_to            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE usage_event (
    id            UUID PRIMARY KEY,
    org_id        UUID          NOT NULL REFERENCES organization (id),
    team_id       UUID          REFERENCES team (id),
    member_id     UUID          NOT NULL REFERENCES member (id),
    project_id    UUID          NOT NULL REFERENCES project (id),
    model         VARCHAR(100)  NOT NULL,
    input_tokens  INT           NOT NULL,
    output_tokens INT           NOT NULL,
    lines_added   INT,
    lines_removed INT,
    cost_usd      NUMERIC(12,6) NOT NULL,
    event_time    TIMESTAMPTZ   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE daily_rollup (
    id                  UUID PRIMARY KEY,
    org_id              UUID          NOT NULL REFERENCES organization (id),
    team_id             UUID          REFERENCES team (id),
    member_id           UUID          NOT NULL REFERENCES member (id),
    project_id          UUID          NOT NULL REFERENCES project (id),
    rollup_date         DATE          NOT NULL,
    total_input_tokens  BIGINT        NOT NULL DEFAULT 0,
    total_output_tokens BIGINT        NOT NULL DEFAULT 0,
    total_cost_usd      NUMERIC(14,6) NOT NULL DEFAULT 0,
    total_lines_added   BIGINT        NOT NULL DEFAULT 0,
    total_lines_removed BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (org_id, team_id, member_id, project_id, rollup_date)
);

CREATE TABLE weekly_rollup (
    id                  UUID PRIMARY KEY,
    org_id              UUID          NOT NULL REFERENCES organization (id),
    team_id             UUID          REFERENCES team (id),
    member_id           UUID          NOT NULL REFERENCES member (id),
    project_id          UUID          NOT NULL REFERENCES project (id),
    week_start          DATE          NOT NULL, -- Monday of the ISO week
    total_input_tokens  BIGINT        NOT NULL DEFAULT 0,
    total_output_tokens BIGINT        NOT NULL DEFAULT 0,
    total_cost_usd      NUMERIC(14,6) NOT NULL DEFAULT 0,
    total_lines_added   BIGINT        NOT NULL DEFAULT 0,
    total_lines_removed BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (org_id, team_id, member_id, project_id, week_start)
);

CREATE TABLE monthly_rollup (
    id                  UUID PRIMARY KEY,
    org_id              UUID          NOT NULL REFERENCES organization (id),
    team_id             UUID          REFERENCES team (id),
    member_id           UUID          NOT NULL REFERENCES member (id),
    project_id          UUID          NOT NULL REFERENCES project (id),
    month_start         DATE          NOT NULL, -- first day of the month
    total_input_tokens  BIGINT        NOT NULL DEFAULT 0,
    total_output_tokens BIGINT        NOT NULL DEFAULT 0,
    total_cost_usd      NUMERIC(14,6) NOT NULL DEFAULT 0,
    total_lines_added   BIGINT        NOT NULL DEFAULT 0,
    total_lines_removed BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (org_id, team_id, member_id, project_id, month_start)
);

CREATE INDEX idx_usage_event_team_time    ON usage_event (team_id, event_time);
CREATE INDEX idx_usage_event_member_time  ON usage_event (member_id, event_time);
CREATE INDEX idx_usage_event_project_time ON usage_event (project_id, event_time);
CREATE INDEX idx_model_pricing_validity   ON model_pricing (model, valid_from, valid_to);
