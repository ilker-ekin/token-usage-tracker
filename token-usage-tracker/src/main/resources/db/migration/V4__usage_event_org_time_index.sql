-- GET /usage always filters on org_id and sorts by event_time DESC (with an
-- optional time range), but only team/member/project + time indexes existed.
--
-- Plain CREATE INDEX: it briefly blocks writes to usage_event while it builds,
-- which is fine at this project's size. On a large live table this would be
-- CREATE INDEX CONCURRENTLY, which additionally requires
-- spring.flyway.postgresql.transactional-lock=false; with Flyway's default
-- transactional advisory lock the concurrent build waits on Flyway's own
-- open transaction and the migration hangs.
CREATE INDEX idx_usage_event_org_time ON usage_event (org_id, event_time DESC);
