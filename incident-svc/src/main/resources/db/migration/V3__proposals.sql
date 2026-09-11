-- V3: recovery proposals and the approval gate.

CREATE TABLE proposals (
    id          BIGSERIAL PRIMARY KEY,
    external_id UUID         NOT NULL UNIQUE,
    incident_id BIGINT       NOT NULL REFERENCES incidents (id),
    action_type VARCHAR(64)  NOT NULL,
    params      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    risk_level  VARCHAR(16)  NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL,
    reasoning   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ,
    decided_by  VARCHAR(128)
);

CREATE INDEX idx_proposals_incident ON proposals (incident_id, created_at);
CREATE INDEX idx_proposals_status    ON proposals (status, created_at);