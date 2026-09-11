-- V1: confirmed anomalies emitted by ingest-svc.
-- Unique indexes are the durable idempotency backstop for at-least-once
-- delivery: a redelivered rule evaluation cannot insert twice.

CREATE TABLE anomalies (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID           NOT NULL UNIQUE,
    source_event_id UUID           NOT NULL UNIQUE,
    service         VARCHAR(128)   NOT NULL,
    signal          VARCHAR(64)    NOT NULL,
    value           DOUBLE PRECISION NOT NULL,
    threshold       DOUBLE PRECISION NOT NULL,
    severity        VARCHAR(16)    NOT NULL,
    window_start    TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_anomalies_service_signal ON anomalies (service, signal);
CREATE INDEX idx_anomalies_window_start  ON anomalies (window_start);