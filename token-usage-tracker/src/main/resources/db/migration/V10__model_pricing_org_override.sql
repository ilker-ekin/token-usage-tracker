-- POST /model-pricing used to write global rows, and the ADMIN role of any
-- org was enough, so one tenant's admin could change what every tenant's
-- usage cost. Prices written through the API are now overrides for the
-- caller's org. NULL org_id means a global catalog row; those are written
-- only by migrations, the dev seeder and the LiteLLM auto-pricing.
--
-- Rows that already exist stay global: there is no record of which org
-- wrote a row through the API, so an operator should review them.
ALTER TABLE model_pricing
    ADD COLUMN org_id UUID REFERENCES organization (id) ON DELETE CASCADE;

-- Override lookups are by org and model; global lookups keep using
-- idx_model_pricing_validity.
CREATE INDEX idx_model_pricing_org_override
    ON model_pricing (org_id, model, valid_from)
    WHERE org_id IS NOT NULL;
