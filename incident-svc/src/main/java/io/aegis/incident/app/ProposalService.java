package io.aegis.incident.app;

import io.aegis.contracts.events.ActionCommandEvent;
import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.config.ProposalProperties;
import io.aegis.incident.controls.AutonomyPolicy;
import io.aegis.incident.controls.KillSwitchStore;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import io.aegis.incident.events.ActionCommandPublisher;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.ProposalRepository;
import io.aegis.incident.rules.ProposalDraft;
import io.aegis.incident.rules.Proposer;
import io.aegis.incident.ws.IncidentBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The approval gate. A proposal enters PENDING with a TTL; humans decide via
 * approve/reject, and the sweep expires proposals whose window ran out.
 * <b>Expiry escalates, it never auto-approves.</b> The agent (Phase 4) cannot
 * influence this service: approval decisions are recorded with the approver's
 * identity and are outside the model's reach.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final ProposalRepository proposals;
    private final IncidentEventRepository events;
    private final IncidentStatePublisher publisher;
    private final ActionCommandPublisher commands;
    private final ProposalProperties properties;
    private final Proposer proposer;
    private final IncidentBroadcaster broadcaster;
    private final KillSwitchStore killSwitches;
    private final AutonomyPolicy policy;
    private final ShadowEvaluator shadow;
    private final MeterRegistry metrics;

    public ProposalService(ProposalRepository proposals, IncidentEventRepository events,
                           IncidentStatePublisher publisher, ActionCommandPublisher commands,
                           ProposalProperties properties, Proposer proposer,
                           IncidentBroadcaster broadcaster, KillSwitchStore killSwitches,
                           AutonomyPolicy policy, ShadowEvaluator shadow,
                           MeterRegistry metrics) {
        this.proposals = proposals;
        this.events = events;
        this.publisher = publisher;
        this.commands = commands;
        this.properties = properties;
        this.proposer = proposer;
        this.broadcaster = broadcaster;
        this.killSwitches = killSwitches;
        this.policy = policy;
        this.shadow = shadow;
        this.metrics = metrics;
    }

    /**
     * Runs the proposer for a freshly opened incident and, if a draft comes
     * back, routes it through the policy layer: a kill switch blocks the
     * proposal entirely, shadow mode scores it without persisting anything,
     * LOW-risk auto-approve may shortcut the gate, and everything else waits
     * for a human at AWAITING_APPROVAL.
     */
    @Transactional
    public Optional<Proposal> proposeForAnomaly(Incident incident, AnomalyEvent anomaly) {
        if (killSwitches.isKilled(incident.getService())) {
            events.save(new IncidentEvent(incident, "KILL_SWITCH", Map.of(
                    "reason", "recovery disabled for " + incident.getService())));
            log.info("Kill switch blocks proposal for {} (incident {})",
                    incident.getService(), incident.getExternalId());
            return Optional.empty();
        }

        Optional<ProposalDraft> draft = proposer.propose(anomaly);
        if (draft.isEmpty()) {
            return Optional.empty();
        }

        if (policy.shadowMode()) {
            // Measure the proposer on a live incident without executing.
            shadow.recordLive(incident.getService(), anomaly.signal(), draft.get());
            events.save(new IncidentEvent(incident, "SHADOW_EVALUATED", Map.of(
                    "actionType", draft.get().actionType(),
                    "confidence", draft.get().confidence())));
            return Optional.empty();
        }

        AutonomyPolicy.Decision decision = policy.evaluate(draft.get());
        if (decision.verdict() == AutonomyPolicy.Verdict.BLOCKED) {
            events.save(new IncidentEvent(incident, "BLOCKED_BY_POLICY", Map.of(
                    "reason", decision.reason())));
            log.info("Policy blocked proposal for {}: {}", incident.getService(),
                    decision.reason());
            return Optional.empty();
        }

        Proposal proposal = new Proposal(incident, draft.get());
        proposals.save(proposal);
        metrics.counter("aegis_proposals_created").increment();
        incident.transitionTo(IncidentStatus.PROPOSAL);
        incident.transitionTo(IncidentStatus.AWAITING_APPROVAL);
        events.save(new IncidentEvent(incident, "PROPOSAL", Map.of(
                "proposalId", proposal.getExternalId().toString(),
                "actionType", draft.get().actionType(),
                "riskLevel", draft.get().riskLevel(),
                "confidence", draft.get().confidence())));
        if (decision.verdict() == AutonomyPolicy.Verdict.AUTO_APPROVE) {
            proposal.approve("policy:auto");
            metrics.counter("aegis_proposals_auto_approved").increment();
            events.save(new IncidentEvent(incident, "PROPOSAL_AUTO_APPROVED", Map.of(
                    "proposalId", proposal.getExternalId().toString(),
                    "reason", decision.reason())));
            publishCommandAfterCommit(proposal);
        }
        publisher.publish(incident);
        broadcaster.proposal(proposal);
        return Optional.of(proposal);
    }

    @Transactional
    public Proposal approve(UUID externalId, String approver) {
        Proposal proposal = get(externalId);
        try {
            proposal.approve(approver);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        metrics.counter("aegis_proposals_approved").increment();
        events.save(new IncidentEvent(proposal.getIncident(), "PROPOSAL_APPROVED", Map.of(
                "proposalId", proposal.getExternalId().toString(),
                "approver", approver)));
        publishCommandAfterCommit(proposal);
        broadcaster.proposal(proposal);
        return proposal;
    }

    /**
     * Mini-outbox: the command is published only after the approval commits,
     * so the executor can never see an APPROVED state that then rolls back.
     * The executor's guards make a lost command safe too (proposal stays
     * APPROVED and would be picked up again by a retry of the approval).
     */
    private void publishCommandAfterCommit(Proposal proposal) {
        ActionCommandEvent command = new ActionCommandEvent(
                UUID.randomUUID(),
                proposal.getExternalId(),
                proposal.getIncident().getExternalId(),
                proposal.getIncident().getService(),
                proposal.getActionType(),
                proposal.getParams(),
                Instant.now());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            commands.publish(command);
                        }
                    });
        } else {
            commands.publish(command);  // direct path for tests and non-tx callers
        }
    }

    @Transactional
    public Proposal reject(UUID externalId, String approver) {
        Proposal proposal = get(externalId);
        try {
            proposal.reject(approver);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        metrics.counter("aegis_proposals_rejected").increment();
        Incident incident = proposal.getIncident();
        incident.transitionTo(IncidentStatus.REJECTED);
        events.save(new IncidentEvent(incident, "PROPOSAL_REJECTED", Map.of(
                "proposalId", proposal.getExternalId().toString(),
                "approver", approver)));
        publisher.publish(incident);
        broadcaster.proposal(proposal);
        return proposal;
    }

    /**
     * Expires proposals past their TTL. The incident is closed as EXPIRED with
     * an escalation note for a human; nothing is ever auto-approved.
     */
    @Scheduled(fixedDelayString = "${proposal.sweep-interval:30s}")
    @Transactional
    public void expireDueProposals() {
        Instant cutoff = Instant.now().minus(properties.ttl());
        List<Proposal> due = proposals.findByStatusAndCreatedAtBefore(ProposalStatus.PENDING, cutoff);
        for (Proposal proposal : due) {
            proposal.expire();
            metrics.counter("aegis_proposals_expired").increment();
            Incident incident = proposal.getIncident();
            incident.transitionTo(IncidentStatus.EXPIRED);
            events.save(new IncidentEvent(incident, "PROPOSAL_EXPIRED", Map.of(
                    "proposalId", proposal.getExternalId().toString(),
                    "ttlSeconds", properties.ttl().toSeconds())));
            events.save(new IncidentEvent(incident, "ESCALATED", Map.of(
                    "reason", "approval window expired")));
            publisher.publish(incident);
            broadcaster.proposal(proposal);
            log.info("Expired proposal {} for incident {}", proposal.getExternalId(),
                    incident.getExternalId());
        }
    }

    @Transactional(readOnly = true)
    public Proposal get(UUID externalId) {
        return proposals.findByExternalId(externalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Proposal not found"));
    }

    @Transactional(readOnly = true)
    public List<Proposal> listForIncident(UUID incidentExternalId) {
        return proposals.findByIncidentExternalIdOrderByCreatedAtDesc(incidentExternalId);
    }
}