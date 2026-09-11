package io.aegis.incident.app;

import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.IncidentEvent;
import io.aegis.incident.domain.IncidentStatus;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.events.IncidentStatePublisher;
import io.aegis.incident.repo.IncidentEventRepository;
import io.aegis.incident.repo.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Application service owning incident state changes. Anomalies become
 * incidents (correlated per service with merge-if-open); every change is
 * published to the {@code incidents} topic for the live feed. Proposal and
 * approval flows build on the same domain model in later steps.
 */
@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentEventRepository events;
    private final IncidentStatePublisher publisher;
    private final ProposalService proposals;
    private final MeterRegistry metrics;

    public IncidentService(IncidentRepository incidents, IncidentEventRepository events,
                           IncidentStatePublisher publisher, ProposalService proposals,
                           MeterRegistry metrics) {
        this.incidents = incidents;
        this.events = events;
        this.publisher = publisher;
        this.proposals = proposals;
        this.metrics = metrics;
    }

    /**
     * Correlation entry point: a confirmed anomaly becomes an incident, unless
     * one is already active for the service, in which case the anomaly is
     * merged as new evidence. The race (two anomalies opening simultaneously)
     * is resolved by the partial unique index: the loser's insert fails and
     * it merges into the winner.
     */
    @Transactional
    public Incident openFromAnomaly(AnomalyEvent anomaly) {
        Optional<Incident> active = findActiveByService(anomaly.service());
        if (active.isPresent()) {
            Incident existing = mergeEvidence(active.get(), anomaly);
            publisher.publish(existing);  // new evidence is a visible state change
            return existing;
        }

        Incident incident = new Incident(anomaly.service(), severityOf(anomaly),
                summaryOf(anomaly));
        incident.transitionTo(IncidentStatus.INVESTIGATING);
        try {
            incidents.saveAndFlush(incident);
        } catch (DataIntegrityViolationException e) {
            // Lost the open race: another anomaly for the same service won.
            Incident winner = findActiveByService(anomaly.service()).orElseThrow();
            return mergeEvidence(winner, anomaly);
        }

        events.save(new IncidentEvent(incident, "OPENED", Map.of("summary", incident.getSummary())));
        metrics.counter("aegis_incidents_opened", "service", incident.getService())
                .increment();
        // The rule-based proposer fills this slot today; the agent replaces
        // it in Phase 4. No proposal (unknown signal) still opens the incident.
        proposals.proposeForAnomaly(incident, anomaly);
        publisher.publish(incident);
        return incident;
    }

    /** Appends anomaly evidence exactly once (dedup key = anomaly eventId). */
    private Incident mergeEvidence(Incident incident, AnomalyEvent anomaly) {
        try {
            events.saveAndFlush(new IncidentEvent(incident, "EVIDENCE", Map.of(
                    "signal", anomaly.signal(),
                    "value", anomaly.value(),
                    "threshold", anomaly.threshold(),
                    "sourceEventId", anomaly.sourceEventId().toString()
            ), anomaly.eventId().toString()));
        } catch (DataIntegrityViolationException e) {
            // Already appended this evidence on a previous delivery.
        }
        return incident;
    }

    private Optional<Incident> findActiveByService(String service) {
        return incidents.findFirstByServiceAndStatusIn(service, activeStatuses());
    }

    private static List<IncidentStatus> activeStatuses() {
        return Arrays.stream(IncidentStatus.values())
                .filter(IncidentStatus::isActive)
                .collect(Collectors.toList());
    }

    private static Severity severityOf(AnomalyEvent anomaly) {
        try {
            return Severity.valueOf(anomaly.severity());
        } catch (IllegalArgumentException e) {
            return Severity.SEV3;
        }
    }

    private static String summaryOf(AnomalyEvent anomaly) {
        return anomaly.signal() + " " + anomaly.value() + " (threshold "
                + anomaly.threshold() + ") on " + anomaly.service();
    }

    @Transactional
    public Incident openIncident(String service, Severity severity, String summary) {
        Incident incident = new Incident(service, severity, summary);
        incidents.save(incident);
        events.save(new IncidentEvent(incident, "OPENED", Map.of("summary", summary)));
        return incident;
    }

    @Transactional
    public Incident transition(UUID externalId, IncidentStatus target) {
        Incident incident = get(externalId);
        incident.transitionTo(target);
        events.save(new IncidentEvent(incident, target.name(), Map.of()));
        if (target == IncidentStatus.RESOLVED) {
            metrics.counter("aegis_incidents_resolved").increment();
        }
        publisher.publish(incident);
        return incident;
    }

    @Transactional(readOnly = true)
    public Incident get(UUID externalId) {
        return incidents.findByExternalId(externalId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Incident not found"));
    }

    @Transactional(readOnly = true)
    public List<Incident> list() {
        return incidents.findAllByOrderByOpenedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<IncidentEvent> timeline(UUID externalId) {
        return events.findByIncidentIdOrderByCreatedAtAsc(get(externalId).getId());
    }
}