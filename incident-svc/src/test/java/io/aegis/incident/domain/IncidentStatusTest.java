package io.aegis.incident.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentStatusTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertTrue(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.INVESTIGATING));
        assertTrue(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.PROPOSAL));
        assertTrue(IncidentStatus.PROPOSAL.canTransitionTo(IncidentStatus.AWAITING_APPROVAL));
        assertTrue(IncidentStatus.AWAITING_APPROVAL.canTransitionTo(IncidentStatus.EXECUTING));
        assertTrue(IncidentStatus.EXECUTING.canTransitionTo(IncidentStatus.VERIFYING));
        assertTrue(IncidentStatus.VERIFYING.canTransitionTo(IncidentStatus.RESOLVED));
    }

    @Test
    void rollbackPathIsAllowed() {
        assertTrue(IncidentStatus.FAILED.canTransitionTo(IncidentStatus.ROLLING_BACK));
        assertTrue(IncidentStatus.ROLLING_BACK.canTransitionTo(IncidentStatus.RESOLVED));
    }

    @Test
    void terminalStatesRejectFurtherTransitions() {
        assertFalse(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.EXECUTING));
        assertFalse(IncidentStatus.REJECTED.canTransitionTo(IncidentStatus.AWAITING_APPROVAL));
        assertFalse(IncidentStatus.EXPIRED.canTransitionTo(IncidentStatus.EXECUTING));
    }

    @Test
    void skippingStatesIsRejected() {
        assertFalse(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.RESOLVED));
        assertFalse(IncidentStatus.AWAITING_APPROVAL.canTransitionTo(IncidentStatus.RESOLVED));
    }

    @Test
    void aggregateEnforcesTheTable() {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        incident.transitionTo(IncidentStatus.INVESTIGATING);
        assertThrows(IllegalStateException.class,
                () -> incident.transitionTo(IncidentStatus.EXECUTING));
    }
}