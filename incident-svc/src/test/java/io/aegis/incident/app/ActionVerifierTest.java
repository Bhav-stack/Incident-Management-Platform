package io.aegis.incident.app;

import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.config.ActionProperties;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.ActionStatus;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionVerifierTest {

    private ActionRepository actions;
    private IncidentEventRepository events;
    private IncidentStatePublisher publisher;
    private SimulatorClient simulator;
    private RollbackEngine rollback;
    private ActionVerifier verifier;

    private static final ActionProperties PROPS =
            new ActionProperties(Duration.ofSeconds(60), Duration.ofSeconds(10), 5.0,
                    Duration.ofMinutes(5));

    @BeforeEach
    void setUp() {
        actions = mock(ActionRepository.class);
        events = mock(IncidentEventRepository.class);
        publisher = mock(IncidentStatePublisher.class);
        simulator = mock(SimulatorClient.class);
        rollback = mock(RollbackEngine.class);
        verifier = new ActionVerifier(actions, events, publisher, simulator, rollback, PROPS,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static Action verifyingAction(Instant verifyDeadline) {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        incident.transitionTo(IncidentStatus.INVESTIGATING);
        incident.transitionTo(IncidentStatus.PROPOSAL);
        incident.transitionTo(IncidentStatus.AWAITING_APPROVAL);
        incident.transitionTo(IncidentStatus.EXECUTING);
        incident.transitionTo(IncidentStatus.VERIFYING);
        Proposal proposal = new Proposal(incident,
                new ProposalDraft("restart_instance", "MEDIUM", 0.8, Map.of(), Map.of()));
        Action action = new Action(
                new io.aegis.contracts.events.ActionCommandEvent(java.util.UUID.randomUUID(),
                        proposal.getExternalId(), incident.getExternalId(), "checkout-service",
                        "restart_instance", Map.of(), Instant.now()),
                incident, proposal);
        action.markExecuted(verifyDeadline);
        return action;
    }

    @Test
    void recoveredMetricsResolveTheIncident() {
        Action action = verifyingAction(Instant.now().plus(Duration.ofSeconds(60)));
        when(actions.findByStatus(ActionStatus.VERIFYING)).thenReturn(List.of(action));
        when(simulator.status("checkout-service"))
                .thenReturn(new SimulatorClient.ServiceStatus(1.2, 2, "v2.4.0", null));

        verifier.verifyPending();

        assertEquals(ActionStatus.VERIFIED, action.getStatus());
        assertEquals(IncidentStatus.RESOLVED, action.getIncident().getStatus());
        verify(rollback, never()).failAndRollback(any(), any(), anyString());
        verify(publisher).publish(action.getIncident());
    }

    @Test
    void oneUnreachableTargetDoesNotBlockTheOtherIncidents() {
        Action broken = verifyingAction(Instant.now().plus(Duration.ofSeconds(60)));
        Action healthy = verifyingAction(Instant.now().plus(Duration.ofSeconds(60)));
        when(actions.findByStatus(ActionStatus.VERIFYING)).thenReturn(List.of(broken, healthy));
        when(simulator.status("checkout-service"))
                .thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(new SimulatorClient.ServiceStatus(1.0, 2, "v2.4.0", null));

        verifier.verifyPending();

        assertEquals(ActionStatus.VERIFYING, broken.getStatus(),
                "the unreachable action is retried on the next poll");
        assertEquals(ActionStatus.VERIFIED, healthy.getStatus(),
                "the other in-flight action is still verified");
    }

    @Test
    void deadlineExceededTriggersAutomaticRollback() {
        Action action = verifyingAction(Instant.now().minusSeconds(10));
        when(actions.findByStatus(ActionStatus.VERIFYING)).thenReturn(List.of(action));
        when(simulator.status("checkout-service"))
                .thenReturn(new SimulatorClient.ServiceStatus(8.5, 2, "v2.4.0", null));
        // Deadline in the past: the window ran out without recovery.

        verifier.verifyPending();

        verify(rollback).failAndRollback(any(), any(), anyString());
        verify(publisher, never()).publish(any());
    }
}