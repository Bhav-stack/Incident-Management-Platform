package io.aegis.ingest.anomalies;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.contracts.events.AnomalyEvent;
import io.aegis.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes confirmed anomalies to {@code anomalies}, keyed by service so
 * incident-svc sees per-service order. Idempotent producer on (see
 * application.yml) — retries cannot duplicate on the broker.
 */
@Component
public class AnomalyPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnomalyPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public AnomalyPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                            MeterRegistry metrics) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public void publish(AnomalyEvent anomaly) {
        metrics.counter("aegis_anomalies_emitted", "signal", anomaly.signal())
                .increment();
        try {
            String json = mapper.writeValueAsString(anomaly);
            kafka.send(Topics.ANOMALIES, anomaly.service(), json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Publish failed for anomaly {}: {}", anomaly.eventId(),
                                    error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Could not serialize anomaly {}: {}", anomaly.eventId(), e.getMessage());
        }
    }
}