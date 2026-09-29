package io.aegis.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Pipeline tuning, all config-driven (see IMPLEMENTATION_PLAN: policy is
 * config, never hard-coded). Bound from {@code ingest.*} in application.yml.
 */
@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        Duration dedupTtl,
        Buffer buffer,
        Rule rule,
        Consumer consumer
) {

    /**
     * @param window        hold events this long to let out-of-order siblings arrive
     * @param grace         drop events arriving later than window + grace (stragglers)
     * @param flushInterval how often the buffer drains
     * @param maxQueueSize  per-source cap; oldest entries dropped first
     */
    public record Buffer(Duration window, Duration grace, Duration flushInterval, int maxQueueSize) {
    }

    /**
     * Consumer parallelism, matched to the {@code raw.events} partition count.
     * The topic is created with {@code partitions} partitions and the listener
     * runs {@code concurrency} threads; the producer keys by {@code source}, so
     * each source still lands on one partition and keeps its order.
     *
     * @param partitions  partitions to create {@code raw.events} with
     * @param concurrency listener threads (clamped to at least one per partition)
     */
    public record Consumer(int partitions, int concurrency) {
    }

    /**
     * @param errorRateThreshold breach threshold for the error_rate signal (percent)
     * @param requiredStreaks    consecutive breach samples before the anomaly is confirmed
     * @param signal             signal name attached to emitted anomalies
     * @param severity           suggested severity for confirmed anomalies
     */
    public record Rule(double errorRateThreshold, int requiredStreaks, String signal, String severity) {
    }

    public IngestProperties {
        dedupTtl = dedupTtl == null ? Duration.ofHours(1) : dedupTtl;
        buffer = buffer == null
                ? new Buffer(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(1), 1000)
                : buffer;
        rule = rule == null
                ? new Rule(5.0, 2, "error_rate", "SEV2")
                : rule;
        consumer = consumer == null
                ? new Consumer(3, 3)
                : consumer;
    }
}