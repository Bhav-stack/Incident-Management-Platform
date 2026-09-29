-- Two-person rule for high-risk recovery actions.
--
-- `approvals` holds one entry per verified operator who approved, in order:
--   [{"operator": "bhav", "at": "2026-09-13T15:27:45.010Z"}, ...]
-- The identity is derived from the caller's credential, never from the
-- request body, so this column is the audit record of who signed off.
--
-- `approvals_required` is captured when the proposal is created, from the
-- policy in force at that moment. Storing it makes the decision auditable
-- even if the policy is later relaxed: an action that required two people
-- still shows that it did.
ALTER TABLE proposals
    ADD COLUMN approvals JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN approvals_required INT NOT NULL DEFAULT 1;

-- Existing rows predate the rule; every one of them was a single approval.
UPDATE proposals SET approvals = jsonb_build_array(jsonb_build_object('operator', decided_by))
    WHERE decided_by IS NOT NULL;
