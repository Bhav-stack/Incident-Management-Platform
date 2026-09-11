package io.aegis.incident.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.IncidentStateEvent;
import io.aegis.contracts.events.Topics;
import io.aegis.incident.domain.Incident;
import io.aegis.incident.ws.IncidentBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes every incident state change to {@code incidents}, keyed by
 * service so the dashboard feed sees per-service order. The dashboard's live
 * view is this topic over WebSocket (Phase 3); for now it is the durable feed.
 */
@Component
public class IncidentStatePublisher {

    private static final Logger log = LoggerFactory.getLogger(IncidentStatePublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final IncidentBroadcaster broadcaster;

    public IncidentStatePublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                  IncidentBroadcaster broadcaster) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    public void publish(Incident incident) {
        IncidentStateEvent event = new IncidentStateEvent(
                UUID.randomUUID(),
                incident.getExternalId(),
                incident.getService(),
                incident.getSeverity().name(),
                incident.getStatus().name(),
                incident.getSummary(),
                incident.getOpenedAt(),
                Instant.now());
        try {
            kafka.send(Topics.INCIDENTS, incident.getService(),
                    mapper.writeValueAsString(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Publish failed for incident state {}: {}", event.eventId(),
                                    error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Could not serialize incident state {}: {}", event.eventId(), e.getMessage());
        }
        broadcaster.incident(incident, event);
    }
}