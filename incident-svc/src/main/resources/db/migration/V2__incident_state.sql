-- V2: correlation guard + timeline idempotency.
--
-- 1. One active incident per service. Two anomalies racing for the same
--    service would both see "no open incident" and both insert. The partial
--    unique index lets the second insert fail; the consumer catches the
--    conflict and merges into the winner instead. This is the same
--    insert-idempotently pattern as ingest's anomalies table.
CREATE UNIQUE INDEX idx_incidents_one_active_per_service
    ON incidents (service)
    WHERE status IN ('OPEN', 'INVESTIGATING', 'PROPOSAL', 'AWAITING_APPROVAL',
                     'EXECUTING', 'VERIFYING', 'FAILED', 'ROLLING_BACK');

-- 2. Timeline entries can carry an idempotency key (the source anomaly
--    eventId), so a redelivered anomaly appends its evidence exactly once.
ALTER TABLE incident_events ADD COLUMN dedup_key VARCHAR(64);

CREATE UNIQUE INDEX idx_incident_events_dedup
    ON incident_events (dedup_key)
    WHERE dedup_key IS NOT NULL;