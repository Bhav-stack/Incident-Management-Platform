package io.aegis.incident.ws;

import io.aegis.contracts.events.IncidentStateEvent;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.domain.ProposalStatus;
import io.aegis.incident.domain.Severity;
import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IncidentBroadcasterTest {

    @Test
    void incidentStateGoesToTopicIncidentsWithTheKafkaPayloadShape() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        IncidentBroadcaster broadcaster = new IncidentBroadcaster(template);
        Incident incident = new Incident("checkout-service", Severity.SEV2, "error_rate 8.5");
        IncidentStateEvent event = new IncidentStateEvent(
                UUID.randomUUID(), UUID.randomUUID(), "checkout-service", "SEV2",
                "AWAITING_APPROVAL", "error_rate 8.5", Instant.now(), Instant.now());

        broadcaster.incident(incident, event);

        // Same object the Kafka topic carries: one payload shape for the UI.
        verify(template).convertAndSend(eq("/topic/incidents"), eq(event));
    }

    @Test
    void proposalChangesGoToTopicProposals() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        IncidentBroadcaster broadcaster = new IncidentBroadcaster(template);
        Incident incident = new Incident("checkout-service", Severity.SEV2, "error_rate 8.5");
        Proposal proposal = new Proposal(incident, new ProposalDraft(
                "restart_instance", "MEDIUM", 0.80, Map.of("target", "checkout-service"),
                Map.of("evidence", List.of("error_rate 8.5"))));
        proposal.approve("alice");

        broadcaster.proposal(proposal);

        verify(template).convertAndSend(eq("/topic/proposals"), any(IncidentBroadcaster.ProposalMessage.class));
    }
}