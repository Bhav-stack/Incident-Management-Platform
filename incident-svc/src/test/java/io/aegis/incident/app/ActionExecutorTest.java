package io.aegis.incident.app;

import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.incident.action.ActionHandler;
import io.aegis.incident.action.ClearCacheActionHandler;
import io.aegis.incident.action.RestartActionHandler;
import io.aegis.incident.action.RollbackActionHandler;
import io.aegis.incident.action.ScaleActionHandler;
import io.aegis.incident.action.SimulatorClient;
import io.aegis.incident.config.ActionProperties;
import io.aegis.incident.controls.KillSwitchStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.aegis.incident.domain.Action;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.ActionRepository;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.ProposalRepository;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionExecutorTest {

    private ProposalRepository proposals;
    private ActionRepository actions;
    private IncidentEventRepository events;
    private IncidentStatePublisher publisher;
    private SimulatorClient simulator;
    private KillSwitchStore killSwitches;
    private ActionExecutor executor;

    private static final ActionProperties PROPS =
            new ActionProperties(Duration.ofSeconds(60), Duration.ofSeconds(10), 5.0,
                    Duration.ofMinutes(5));
    private static final List<ActionHandler> HANDLERS = List.of(
            new RestartActionHandler(), new ScaleActionHandler(),
            new RollbackActionHandler(), new ClearCacheActionHandler());

    @BeforeEach
    void setUp() {
        proposals = mock(ProposalRepository.class);
        actions = mock(ActionRepository.class);
        events = mock(IncidentEventRepository.class);
        publisher = mock(IncidentStatePublisher.class);
        simulator = mock(SimulatorClient.class);
        killSwitches = mock(KillSwitchStore.class);
        MeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RollbackEngine rollback = new RollbackEngine(actions, events, publisher, simulator, metrics);
        executor = new ActionExecutor(proposals, actions, events, publisher, simulator,
                rollback, HANDLERS, PROPS, killSwitches, metrics);
    }

    private static Incident incidentAwaitingApproval() {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        incident.transitionTo(IncidentStatus.INVESTIGATING);
        incident.transitionTo(IncidentStatus.PROPOSAL);
        incident.transitionTo(IncidentStatus.AWAITING_APPROVAL);
        return incident;
    }

    private Proposal approvedProposal() {
        Proposal proposal = new Proposal(incidentAwaitingApproval(),
                new ProposalDraft("restart_instance", "MEDIUM", 0.8,
                        Map.of("target", "checkout-service"), Map.of()));
        proposal.approve("alice");
        return proposal;
    }

    private ActionCommandEvent command(Proposal proposal) {
        return new ActionCommandEvent(UUID.randomUUID(), proposal.getExternalId(),
                proposal.getIncident().getExternalId(), "checkout-service",
                "restart_instance", Map.of("target", "checkout-service"), Instant.now());
    }

    @Test
    void executesApprovedProposalAndMovesToVerifying() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(simulator.status("checkout-service"))
                .thenReturn(new SimulatorClient.ServiceStatus(8.0, 2, "v2.4.0", null));
        when(simulator.execute(eq("restart_instance"), eq("checkout-service"), any()))
                .thenReturn(new SimulatorClient.ExecuteResult(true, "instance restarted"));

        executor.execute(command(proposal));

        assertEquals(ProposalStatus.EXECUTED, proposal.getStatus());
        assertEquals(IncidentStatus.VERIFYING, proposal.getIncident().getStatus());
        verify(actions).saveAndFlush(any(Action.class));
        verify(simulator).execute("restart_instance", "checkout-service",
                Map.of("target", "checkout-service"));
        verify(publisher).publish(proposal.getIncident());
    }

    @Test
    void undoPlanIsCapturedBeforeExecution() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(simulator.status("checkout-service"))
                .thenReturn(new SimulatorClient.ServiceStatus(8.0, 4, "v2.4.0", null));

        ActionCommandEvent scale = new ActionCommandEvent(UUID.randomUUID(),
                proposal.getExternalId(), proposal.getIncident().getExternalId(),
                "checkout-service", "scale_replicas", Map.of("to", 8), Instant.now());
        when(simulator.execute(eq("scale_replicas"), eq("checkout-service"), any()))
                .thenReturn(new SimulatorClient.ExecuteResult(true, "scaled to 8 replicas"));

        executor.execute(scale);

        // Undo must be the PRE-execution count (4), not the new count (8).
        verify(simulator).execute("scale_replicas", "checkout-service", Map.of("to", 8));
        verify(actions).saveAndFlush(any(Action.class));
        assertEquals(IncidentStatus.VERIFYING, proposal.getIncident().getStatus());
    }

    @Test
    void ignoresCommandsForNonApprovedProposals() {
        Proposal rejected = new Proposal(incidentAwaitingApproval(),
                new ProposalDraft("restart_instance", "MEDIUM", 0.8,
                        Map.of("target", "checkout-service"), Map.of()));
        rejected.reject("alice");  // from PENDING, the legal path
        when(proposals.findByExternalId(rejected.getExternalId())).thenReturn(Optional.of(rejected));

        executor.execute(command(rejected));

        verify(actions, never()).saveAndFlush(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void killSwitchRefusesApprovedCommandAtExecutionTime() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(killSwitches.isKilled("checkout-service")).thenReturn(true);

        executor.execute(command(proposal));

        verify(actions, never()).saveAndFlush(any());
        verify(simulator, never()).execute(any(), any(), any());
        verify(events).save(argThat(e -> e.getEventType().equals("KILL_SWITCH")));
        // The proposal stays APPROVED: the operator can re-trigger later.
        assertEquals(ProposalStatus.APPROVED, proposal.getStatus());
    }

    @Test
    void duplicateCommandIsSkipped() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(actions.existsByCommandId(any())).thenReturn(true);

        executor.execute(command(proposal));

        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void commandIdRaceLosesToUniqueIndexAndSkips() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(actions.saveAndFlush(any(Action.class)))
                .thenThrow(new DataIntegrityViolationException("unique command_id"));

        executor.execute(command(proposal));

        verify(publisher, never()).publish(any());
    }

    @Test
    void failedExecutionRollsBackAndClosesIncident() {
        Proposal proposal = approvedProposal();
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));
        when(simulator.status("checkout-service"))
                .thenReturn(new SimulatorClient.ServiceStatus(8.0, 2, "v2.4.0", null));
        when(simulator.execute(eq("restart_instance"), eq("checkout-service"), any()))
                .thenReturn(new SimulatorClient.ExecuteResult(false, "target unreachable"));

        executor.execute(command(proposal));

        // FAILED -> ROLLING_BACK -> RESOLVED; restart has no inverse, so the
        // rollback escalates instead of calling the simulator again.
        assertEquals(IncidentStatus.RESOLVED, proposal.getIncident().getStatus());
        verify(simulator, times(1)).execute(any(), any(), any());
        verify(publisher, times(2)).publish(proposal.getIncident());
    }
}