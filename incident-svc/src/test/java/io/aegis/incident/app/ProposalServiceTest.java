package io.aegis.incident.app;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.config.ProposalProperties;
import io.aegis.incident.controls.AutonomyPolicy;
import io.aegis.incident.controls.KillSwitchStore;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.ActionCommandPublisher;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.ProposalRepository;
import io.aegis.incident.rules.ProposalDraft;
import io.aegis.incident.rules.Proposer;
import io.aegis.incident.ws.IncidentBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalServiceTest {

    private ProposalRepository proposals;
    private IncidentEventRepository events;
    private IncidentStatePublisher publisher;
    private ActionCommandPublisher commands;
    private Proposer proposer;
    private IncidentBroadcaster broadcaster;
    private KillSwitchStore killSwitches;
    private AutonomyPolicy policy;
    private ShadowEvaluator shadow;
    private ProposalService service;

    private static final ProposalProperties PROPS =
            new ProposalProperties(Duration.ofMinutes(5), Duration.ofSeconds(30));
    private static final AnomalyEvent ANOMALY = new AnomalyEvent(
            UUID.randomUUID(), UUID.randomUUID(), "checkout-service",
            "error_rate", 8.5, 5.0, "SEV2", Instant.parse("2026-09-08T10:00:00Z"));
    private static final ProposalDraft DRAFT = new ProposalDraft(
            "restart_instance", "MEDIUM", 0.80, Map.of("target", "checkout-service"),
            Map.of("evidence", List.of("error_rate 8.5")));

    @BeforeEach
    void setUp() {
        proposals = mock(ProposalRepository.class);
        events = mock(IncidentEventRepository.class);
        publisher = mock(IncidentStatePublisher.class);
        commands = mock(ActionCommandPublisher.class);
        proposer = mock(Proposer.class);
        broadcaster = mock(IncidentBroadcaster.class);
        killSwitches = mock(KillSwitchStore.class);
        policy = mock(AutonomyPolicy.class);
        when(policy.shadowMode()).thenReturn(false);
        when(policy.evaluate(any())).thenReturn(new AutonomyPolicy.Decision(
                AutonomyPolicy.Verdict.HUMAN_APPROVAL, "human approval"));
        shadow = mock(ShadowEvaluator.class);
        service = new ProposalService(proposals, events, publisher, commands, PROPS, proposer,
                broadcaster, killSwitches, policy, shadow,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static Incident incidentAt(IncidentStatus status) {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        if (status == IncidentStatus.INVESTIGATING) {
            incident.transitionTo(IncidentStatus.INVESTIGATING);
        }
        if (status == IncidentStatus.AWAITING_APPROVAL) {
            incident.transitionTo(IncidentStatus.INVESTIGATING);
            incident.transitionTo(IncidentStatus.PROPOSAL);
            incident.transitionTo(IncidentStatus.AWAITING_APPROVAL);
        }
        return incident;
    }

    @Test
    void proposeForAnomalyPersistsProposalAndWaitsForApproval() {
        Incident incident = incidentAt(IncidentStatus.INVESTIGATING);
        when(proposer.propose(ANOMALY)).thenReturn(Optional.of(DRAFT));

        Optional<Proposal> result = service.proposeForAnomaly(incident, ANOMALY);

        assertTrue(result.isPresent());
        assertEquals(ProposalStatus.PENDING, result.get().getStatus());
        assertEquals(IncidentStatus.AWAITING_APPROVAL, incident.getStatus());
        verify(proposals).save(result.get());
        verify(publisher).publish(incident);
        verify(broadcaster).proposal(result.get());
    }

    @Test
    void noDraftMeansNoProposalAndIncidentStaysInvestigating() {
        Incident incident = incidentAt(IncidentStatus.INVESTIGATING);
        when(proposer.propose(ANOMALY)).thenReturn(Optional.empty());

        assertTrue(service.proposeForAnomaly(incident, ANOMALY).isEmpty());
        assertEquals(IncidentStatus.INVESTIGATING, incident.getStatus());
        verify(proposals, never()).save(any());
    }

    @Test
    void killSwitchBlocksProposalWithTimelineNote() {
        Incident incident = incidentAt(IncidentStatus.INVESTIGATING);
        when(killSwitches.isKilled("checkout-service")).thenReturn(true);

        assertTrue(service.proposeForAnomaly(incident, ANOMALY).isEmpty());
        assertEquals(IncidentStatus.INVESTIGATING, incident.getStatus());
        verify(proposals, never()).save(any());
        verify(proposer, never()).propose(any());
        verify(events).save(argThat(e -> e.getEventType().equals("KILL_SWITCH")));
    }

    @Test
    void shadowModeEvaluatesButPersistsNothing() {
        Incident incident = incidentAt(IncidentStatus.INVESTIGATING);
        when(proposer.propose(ANOMALY)).thenReturn(Optional.of(DRAFT));
        when(policy.shadowMode()).thenReturn(true);

        assertTrue(service.proposeForAnomaly(incident, ANOMALY).isEmpty());
        assertEquals(IncidentStatus.INVESTIGATING, incident.getStatus());
        verify(proposals, never()).save(any());
        verify(shadow).recordLive("checkout-service", "error_rate", DRAFT);
    }

    @Test
    void lowRiskAutoApproveShortCircuitsTheGate() {
        Incident incident = incidentAt(IncidentStatus.INVESTIGATING);
        ProposalDraft low = new ProposalDraft("clear_cache", "LOW", 0.90,
                Map.of("target", "checkout-service"),
                Map.of("evidence", List.of("a", "b", "c")));
        when(proposer.propose(ANOMALY)).thenReturn(Optional.of(low));
        when(policy.evaluate(any())).thenReturn(new AutonomyPolicy.Decision(
                AutonomyPolicy.Verdict.AUTO_APPROVE, "LOW risk auto-approved"));

        Optional<Proposal> result = service.proposeForAnomaly(incident, ANOMALY);

        assertTrue(result.isPresent());
        assertEquals(ProposalStatus.APPROVED, result.get().getStatus());
        assertEquals("policy:auto", result.get().getDecidedBy());
        verify(commands).publish(any(io.aegis.contracts.events.ActionCommandEvent.class));
    }

    @Test
    void approveRecordsApproverAndAudits() {
        Proposal proposal = new Proposal(incidentAt(IncidentStatus.AWAITING_APPROVAL), DRAFT);
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));

        Proposal approved = service.approve(proposal.getExternalId(), "alice");

        assertEquals(ProposalStatus.APPROVED, approved.getStatus());
        assertEquals("alice", approved.getDecidedBy());
        verify(events).save(any(IncidentEvent.class));
        verify(broadcaster).proposal(approved);
        // Approval must issue an execution command (direct path: no tx in tests).
        verify(commands).publish(any(io.aegis.contracts.events.ActionCommandEvent.class));
    }

    @Test
    void rejectClosesTheIncidentAsRejected() {
        Proposal proposal = new Proposal(incidentAt(IncidentStatus.AWAITING_APPROVAL), DRAFT);
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));

        Proposal rejected = service.reject(proposal.getExternalId(), "alice");

        assertEquals(ProposalStatus.REJECTED, rejected.getStatus());
        assertEquals(IncidentStatus.REJECTED, rejected.getIncident().getStatus());
        verify(publisher).publish(rejected.getIncident());
        verify(broadcaster).proposal(rejected);
    }

    @Test
    void decidingTwiceIsRejectedWithConflict() {
        Proposal proposal = new Proposal(incidentAt(IncidentStatus.AWAITING_APPROVAL), DRAFT);
        proposal.approve("alice");
        when(proposals.findByExternalId(proposal.getExternalId())).thenReturn(Optional.of(proposal));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.approve(proposal.getExternalId(), "bob"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void sweepExpiresDueProposalsAndEscalates() {
        Proposal stale = new Proposal(incidentAt(IncidentStatus.AWAITING_APPROVAL), DRAFT);
        when(proposals.findByStatusAndCreatedAtBefore(eq(ProposalStatus.PENDING), any()))
                .thenReturn(List.of(stale));

        service.expireDueProposals();

        assertEquals(ProposalStatus.EXPIRED, stale.getStatus());
        assertEquals(IncidentStatus.EXPIRED, stale.getIncident().getStatus());
        verify(publisher).publish(stale.getIncident());
    }

    @Test
    void expiryNeverAutoApproves() {
        Proposal stale = new Proposal(incidentAt(IncidentStatus.AWAITING_APPROVAL), DRAFT);
        when(proposals.findByStatusAndCreatedAtBefore(eq(ProposalStatus.PENDING), any()))
                .thenReturn(List.of(stale));

        service.expireDueProposals();

        assertEquals(ProposalStatus.EXPIRED, stale.getStatus());
    }
}