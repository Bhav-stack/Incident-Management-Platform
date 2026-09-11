package io.aegis.incident.domain;

import java.util.Map;
import java.util.Set;

/**
 * Incident lifecycle (see ARCHITECTURE.md §6.1). Transitions are validated —
 * an {@code EXECUTING} event for a resolved incident is ignored by the state
 * machine, never applied — which is the first line of defense against
 * duplicate or out-of-order events.
 */
public enum IncidentStatus {

    OPEN(true),
    INVESTIGATING(true),
    PROPOSAL(true),
    AWAITING_APPROVAL(true),
    EXECUTING(true),
    VERIFYING(true),
    RESOLVED(false),
    REJECTED(false),
    EXPIRED(false),
    FAILED(true),
    ROLLING_BACK(true);

    private final boolean active;

    IncidentStatus(boolean active) {
        this.active = active;
    }

    /** True while the incident still demands attention (correlation target). */
    public boolean isActive() {
        return active;
    }

    private static final Map<IncidentStatus, Set<IncidentStatus>> ALLOWED = Map.of(
            OPEN, Set.of(INVESTIGATING),
            INVESTIGATING, Set.of(PROPOSAL, RESOLVED, REJECTED),
            PROPOSAL, Set.of(AWAITING_APPROVAL, REJECTED),
            AWAITING_APPROVAL, Set.of(EXECUTING, REJECTED, EXPIRED),
            EXECUTING, Set.of(VERIFYING, FAILED),
            VERIFYING, Set.of(RESOLVED, FAILED),
            FAILED, Set.of(ROLLING_BACK),
            ROLLING_BACK, Set.of(RESOLVED)
    );

    public boolean canTransitionTo(IncidentStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }
}