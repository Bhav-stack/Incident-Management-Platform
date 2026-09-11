package io.aegis.incident.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.contracts.events.Topics;
import io.aegis.incident.app.IncidentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code anomalies} with at-least-once semantics (manual acks, see
 * application.yml). Duplicate delivery is made harmless downstream: opening
 * is guarded by the one-active-incident-per-service unique index, and
 * evidence entries carry the anomaly eventId as their dedup key.
 */
@Component
public class AnomalyConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyConsumer.class);

    private final ObjectMapper mapper;
    private final IncidentService incidents;

    public AnomalyConsumer(ObjectMapper mapper, IncidentService incidents) {
        this.mapper = mapper;
        this.incidents = incidents;
    }

    @KafkaListener(topics = Topics.ANOMALIES)
    public void consume(String json, Acknowledgment ack) {
        try {
            AnomalyEvent anomaly = mapper.readValue(json, AnomalyEvent.class);
            incidents.openFromAnomaly(anomaly);
            ack.acknowledge();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Poison message: acknowledge so the partition keeps moving.
            log.warn("Skipping invalid anomaly: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Processing failed, leaving unacked for redelivery", e);
            throw new RuntimeException(e);
        }
    }
}