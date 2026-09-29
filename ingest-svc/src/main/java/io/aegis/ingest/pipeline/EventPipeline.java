package io.aegis.ingest.pipeline;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.ingest.anomalies.AnomalyDetector;
import io.aegis.ingest.metrics.IngestMetrics;
import io.aegis.ingest.rules.MetricSample;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pipeline order: <b>normalize → dedup → order → detect</b>.
 *
 * <p>Dedup before buffering keeps duplicates from consuming buffer capacity;
 * the buffer restores event-time order per source; only then do rules run, so
 * confirmation windows see a correct, ordered signal.
 *
 * <p>The consumer hands over a whole Kafka poll batch, so normalization and
 * dedup are batched: the normalize pass builds the batch, then one pipelined
 * Redis round trip claims every event id. The per-event dedup timer is still
 * recorded once per received event (the batch cost is amortized across it) so
 * the stage latency stays comparable to the earlier per-event measurement.
 */
@Component
public class EventPipeline {

    private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);

    private final EventNormalizer normalizer;
    private final RedisDedupStore dedup;
    private final OutOfOrderBuffer buffer;
    private final AnomalyDetector detector;
    private final MeterRegistry metrics;
    private final IngestMetrics timings;

    public EventPipeline(EventNormalizer normalizer, RedisDedupStore dedup,
                         OutOfOrderBuffer buffer, AnomalyDetector detector,
                         MeterRegistry metrics, IngestMetrics timings) {
        this.normalizer = normalizer;
        this.dedup = dedup;
        this.buffer = buffer;
        this.detector = detector;
        this.metrics = metrics;
        this.timings = timings;
    }

    /**
     * Processes one Kafka poll batch. A poison record is skipped and logged so
     * it cannot fail the whole batch; any other failure propagates to the
     * consumer, which leaves the batch unacknowledged for redelivery (safe,
     * because every side effect is idempotent).
     */
    public void processBatch(List<String> jsons) {
        if (jsons == null || jsons.isEmpty()) {
            return;
        }

        List<RawEvent> events = new ArrayList<>(jsons.size());
        for (String json : jsons) {
            RawEvent event;
            try {
                event = normalizer.normalize(json);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping invalid event: {}", e.getMessage());
                continue;
            }
            metrics.counter("aegis_events_received").increment();
            events.add(event);
        }
        if (events.isEmpty()) {
            return;
        }

        List<UUID> ids = new ArrayList<>(events.size());
        for (RawEvent event : events) {
            ids.add(event.eventId());
        }

        long start = System.nanoTime();
        Set<UUID> fresh = dedup.filterNew(ids);
        long nanos = System.nanoTime() - start;
        long perEvent = nanos / Math.max(1, ids.size());
        for (int i = 0; i < ids.size(); i++) {
            timings.dedup().record(Duration.ofNanos(perEvent));
        }

        for (RawEvent event : events) {
            if (!fresh.contains(event.eventId())) {
                metrics.counter("aegis_events_deduplicated").increment();
                continue;
            }
            Timer.Sample offerSample = timings.sample();
            try {
                buffer.offer(event, this::handleOrderedBatch);
            } finally {
                offerSample.stop(timings.bufferOffer());
            }
        }
    }

    /**
     * Ordered delivery: this runs from the buffer's flush thread once the
     * watermark passes the events, so the time between the two points is the
     * ordering window plus everything the buffer was waiting on. It receives
     * the whole flush batch so the rules can pipeline their Redis state in one
     * round trip rather than one per sample.
     */
    private void handleOrderedBatch(List<RawEvent> events) {
        List<MetricSample> samples = new ArrayList<>(events.size());
        for (RawEvent event : events) {
            timings.ordered();
            timings.eventToRule().record(clampToZero(Duration.between(event.eventTime(), Instant.now())));
            if (event.eventType() == EventType.METRIC) {
                Number value = (Number) event.payload().getOrDefault("value", 0);
                String metric = String.valueOf(event.payload().getOrDefault("metric", "unknown"));
                samples.add(new MetricSample(
                        event.service(),
                        event.instance(),
                        metric,
                        value.doubleValue(),
                        event.eventTime(),
                        event.eventId()));
            }
            // Phase 1.5: LOG and HEALTH signals (5xx rate, instance down rules)
        }
        if (samples.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        detector.onMetrics(samples);
        long perSample = (System.nanoTime() - start) / Math.max(1, samples.size());
        for (int i = 0; i < samples.size(); i++) {
            timings.rule().record(Duration.ofNanos(perSample));
        }
    }

    /** A future-dated event would otherwise record a negative duration. */
    private static Duration clampToZero(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
