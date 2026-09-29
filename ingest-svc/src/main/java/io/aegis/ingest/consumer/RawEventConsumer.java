package io.aegis.ingest.consumer;

import io.aegis.contracts.events.Topics;
import io.aegis.ingest.pipeline.EventPipeline;
import io.aegis.ingest.stats.EventStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Consumes {@code raw.events} with <b>at-least-once</b> semantics:
 * {@code enable.auto.commit=false} + manual acks (see application.yml), so an
 * offset is only committed after the full batch was accepted by the pipeline.
 * Every downstream side effect is idempotent (Redis SETNX dedup, unique index
 * on anomalies), so redelivery is harmless by design.
 *
 * <p>The listener takes a whole poll batch ({@code spring.kafka.listener.type:
 * batch}). That is what lets dedup pipeline one Redis round trip per batch
 * instead of one per event — the measured bottleneck before it. Concurrency is
 * config-driven and is meant to be matched to the partition count of
 * {@code raw.events} (the topic is created with that many partitions; the
 * producer keys by {@code source}, so per-source order is preserved).
 *
 * <p>Poison records are handled inside the pipeline (skipped and logged), so
 * this listener sees only transient failures and leaves those unacked for
 * redelivery.
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

    @KafkaListener(topics = Topics.RAW_EVENTS,
            concurrency = "${ingest.consumer.concurrency:3}")
    public void consume(List<String> events, Acknowledgment ack) {
        try {
            pipeline.processBatch(events);
            stats.recordCount(events.size());
            ack.acknowledge();
        } catch (Exception e) {
            // Processing failure: do NOT ack — redelivery is the point of
            // at-least-once. Let the container handle retry/backoff.
            log.error("Processing failed, leaving batch unacked for redelivery", e);
            throw e;
        }
    }
}
