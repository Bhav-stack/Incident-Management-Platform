-- V1: initial schema — incidents + append-only timeline.
-- Schema evolves only via new Flyway migrations, never in place.

CREATE TABLE incidents (
    id          BIGSERIAL PRIMARY KEY,
    external_id UUID         NOT NULL UNIQUE,
    service     VARCHAR(128) NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    summary     TEXT         NOT NULL,
    opened_at   TIMESTAMPTZ  NOT NULL,
    resolved_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0  -- optimistic lock
);

CREATE INDEX idx_incidents_status    ON incidents (status);
CREATE INDEX idx_incidents_opened_at ON incidents (opened_at);

CREATE TABLE incident_events (
    id          BIGSERIAL PRIMARY KEY,
    incident_id BIGINT       NOT NULL REFERENCES incidents (id),
    event_type  VARCHAR(64)  NOT NULL,
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_events_incident ON incident_events (incident_id, created_at);