-- Optional client-supplied key (Idempotency-Key header on POST /usage) so a
-- retried request books its spend once.
--
-- Partial unique index: events sent without a key are not constrained, and
-- the key is scoped to the org, so two tenants can use the same value.
ALTER TABLE usage_event
    ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uq_usage_event_org_idempotency_key
    ON usage_event (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
