-- V4: executed recovery actions.
-- command_id is the executor's durable idempotency key (unique): a redelivered
-- action.commands message can never execute twice. undo_type/undo_params are
-- captured before execution so the rollback engine can restore pre-action state.

CREATE TABLE actions (
    id                BIGSERIAL PRIMARY KEY,
    external_id       UUID           NOT NULL UNIQUE,
    command_id        UUID           NOT NULL UNIQUE,
    proposal_id       BIGINT         NOT NULL REFERENCES proposals (id),
    incident_id       BIGINT         NOT NULL REFERENCES incidents (id),
    action_type       VARCHAR(64)    NOT NULL,
    params            JSONB          NOT NULL DEFAULT '{}'::jsonb,
    undo_type         VARCHAR(64),
    undo_params       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(32)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    executed_at       TIMESTAMPTZ,
    verified_at       TIMESTAMPTZ,
    verify_deadline   TIMESTAMPTZ,
    rollback_action_id BIGINT        REFERENCES actions (id)
);

CREATE INDEX idx_actions_status   ON actions (status);
CREATE INDEX idx_actions_incident ON actions (incident_id, created_at);