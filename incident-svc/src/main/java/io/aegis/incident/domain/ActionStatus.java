package io.aegis.incident.domain;

/**
 * Lifecycle of an executed recovery action. VERIFYING is entered only after a
 * successful execution; FAILED actions go through the rollback engine.
 */
public enum ActionStatus {
    EXECUTING,
    VERIFYING,
    VERIFIED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK
}