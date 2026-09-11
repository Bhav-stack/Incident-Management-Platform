package io.aegis.incident.ws;

import io.aegis.contracts.events.IncidentStateEvent;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.Proposal;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Pushes every user-visible state change to the dashboard over STOMP. Both
 * destinations are on {@code /topic}: incident state transitions (the same
 * payload as the {@code incidents} Kafka topic, so the dashboard has one
 * shape whether it reads live or replays) and proposal changes (approval gate
 * events the incident feed alone does not carry).
 */
@Component
public class IncidentBroadcaster {

    private final SimpMessagingTemplate template;

    public IncidentBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void incident(Incident incident, IncidentStateEvent event) {
        template.convertAndSend("/topic/incidents", event);
    }

    public void proposal(Proposal proposal) {
        template.convertAndSend("/topic/proposals", new ProposalMessage(
                proposal.getExternalId(),
                proposal.getIncident().getExternalId(),
                proposal.getIncident().getService(),
                proposal.getActionType(),
                proposal.getRiskLevel(),
                proposal.getConfidence(),
                proposal.getStatus().name(),
                proposal.getCreatedAt(),
                proposal.getDecidedAt(),
                proposal.getDecidedBy()));
    }

    /**
     * Proposal feed payload. Deliberately flat and self-contained: the
     * dashboard can render the approval gate without joining the incident.
     */
    public record ProposalMessage(UUID externalId, UUID incidentExternalId, String service,
                                  String actionType, String riskLevel, double confidence,
                                  String status, Instant createdAt, Instant decidedAt,
                                  String decidedBy) {
    }
}