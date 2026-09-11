package io.aegis.incident.app;

import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackEngineTest {

    private ActionRepository actions;
    private IncidentEventRepository events;
    private IncidentStatePublisher publisher;
    private SimulatorClient simulator;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() {
        actions = mock(ActionRepository.class);
        events = mock(IncidentEventRepository.class);
        publisher = mock(IncidentStatePublisher.class);
        simulator = mock(SimulatorClient.class);
        engine = new RollbackEngine(actions, events, publisher, simulator,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** A VERIFYING action with its undo plan captured, on a VERIFYING incident. */
    private static Action verifyingActionWithUndo(String undoType, Map<String, Object> undoParams) {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        incident.transitionTo(IncidentStatus.INVESTIGATING);
        incident.transitionTo(IncidentStatus.PROPOSAL);
        incident.transitionTo(IncidentStatus.AWAITING_APPROVAL);
        incident.transitionTo(IncidentStatus.EXECUTING);
        incident.transitionTo(IncidentStatus.VERIFYING);
        Proposal proposal = new Proposal(incident,
                new ProposalDraft("scale_replicas", "MEDIUM", 0.8, Map.of(), Map.of()));
        Action action = new Action(new ActionCommandEvent(UUID.randomUUID(),
                proposal.getExternalId(), incident.getExternalId(), "checkout-service",
                "scale_replicas", Map.of(), Instant.now()), incident, proposal);
        action.setUndoPlan(new io.aegis.incident.action.UndoPlan(undoType, undoParams, "undo"));
        action.markExecuted(Instant.now().plus(Duration.ofSeconds(60)));
        return action;
    }

    @Test
    void restartHasNoInverseSoRollbackEscalatesWithoutCallingSimulator() {
        Action action = verifyingActionWithUndo("none", Map.of());
        Incident incident = action.getIncident();

        engine.failAndRollback(action, incident, "metrics did not recover");

        assertEquals(IncidentStatus.RESOLVED, incident.getStatus());
        verify(simulator, never()).execute(any(), any(), any());
        verify(actions, never()).save(any());
        verify(publisher, times(2)).publish(incident);
    }

    @Test
    void scaleUndoExecutesOriginalReplicaCountAndLinksRollbackAction() {
        Action action = verifyingActionWithUndo("scale_replicas", Map.of("to", 2));
        Incident incident = action.getIncident();
        when(simulator.execute(eq("scale_replicas"), eq("checkout-service"),
                eq(Map.of("to", 2))))
                .thenReturn(new SimulatorClient.ExecuteResult(true, "scaled to 2 replicas"));

        engine.failAndRollback(action, incident, "metrics did not recover");

        assertEquals(IncidentStatus.RESOLVED, incident.getStatus());
        verify(simulator).execute("scale_replicas", "checkout-service", Map.of("to", 2));
        verify(actions).save(any(Action.class));
        assertNotNull(action.getRollbackAction());
        verify(publisher, times(2)).publish(incident);
    }

    @Test
    void failAndRollbackWalksFailedThroughRollingBackToResolved() {
        Action action = verifyingActionWithUndo("none", Map.of());
        Incident incident = action.getIncident();

        engine.failAndRollback(action, incident, "execution failed: boom");

        assertEquals(IncidentStatus.RESOLVED, incident.getStatus());
        verify(publisher, times(2)).publish(incident);
        // ACTION_FAILED + ROLLBACK_SKIPPED + ESCALATED
        verify(events, times(3)).save(any(io.aegis.incident.domain.IncidentEvent.class));
    }
}