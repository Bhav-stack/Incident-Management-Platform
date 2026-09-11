package io.aegis.simulator.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.RawEvent;
import io.aegis.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link RawEvent}s to {@code raw.events}, keyed by {@code source}
 * so Kafka preserves per-instance ordering. The producer is configured with
 * {@code enable.idempotence=true} (see application.yml) so retries never
 * duplicate events on the broker.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                          MeterRegistry metrics) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public void publish(RawEvent event) {
        metrics.counter("aegis_events_emitted", "type", event.eventType().name())
                .increment();
        try {
            String json = mapper.writeValueAsString(event);
            kafka.send(Topics.RAW_EVENTS, event.source(), json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Publish failed for {} ({}): {}",
                                    event.eventId(), event.eventType(), error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Could not serialize event {}: {}", event.eventId(), e.getMessage());
        }
    }
}