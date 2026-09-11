package io.aegis.ingest.consumer;

import io.aegis.contracts.events.Topics;
import io.aegis.ingest.pipeline.EventPipeline;
import io.aegis.ingest.stats.EventStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code raw.events} with <b>at-least-once</b> semantics:
 * {@code enable.auto.commit=false} + manual acks (see application.yml), so an
 * offset is only committed after the full pipeline accepted the record.
 * Every downstream side effect is idempotent (Redis SETNX dedup, unique index
 * on anomalies), so redelivery is harmless by design.
 */
@Component
public class RawEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RawEventConsumer.class);

    private final EventPipeline pipeline;
    private final EventStatsService stats;

    public RawEventConsumer(EventPipeline pipeline, EventStatsService stats) {
        this.pipeline = pipeline;
        this.stats = stats;
    }

    @KafkaListener(topics = Topics.RAW_EVENTS)
    public void consume(String json, Acknowledgment ack) {
        try {
            pipeline.process(json);
            stats.recordCount();
            ack.acknowledge();
        } catch (IllegalArgumentException e) {
            // Poison message: acknowledge so the partition keeps moving; a
            // dead-letter topic is Phase 1 hardening.
            log.warn("Skipping invalid event: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            // Processing failure: do NOT ack — redelivery is the point of
            // at-least-once. Let the container handle retry/backoff.
            log.error("Processing failed, leaving unacked for redelivery", e);
            throw e;
        }
    }
}