package io.aegis.ingest.pipeline;

import io.aegis.contracts.events.EventType;
import io.aegis.contracts.events.RawEvent;
import io.aegis.ingest.anomalies.AnomalyDetector;
import io.aegis.ingest.rules.MetricSample;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Pipeline order: <b>normalize → dedup → order → detect</b>.
 *
 * <p>Dedup before buffering keeps duplicates from consuming buffer capacity;
 * the buffer restores event-time order per source; only then do rules run, so
 * confirmation windows see a correct, ordered signal.
 */
@Component
public class EventPipeline {

    private final EventNormalizer normalizer;
    private final RedisDedupStore dedup;
    private final OutOfOrderBuffer buffer;
    private final AnomalyDetector detector;
    private final MeterRegistry metrics;

    public EventPipeline(EventNormalizer normalizer, RedisDedupStore dedup,
                         OutOfOrderBuffer buffer, AnomalyDetector detector,
                         MeterRegistry metrics) {
        this.normalizer = normalizer;
        this.dedup = dedup;
        this.buffer = buffer;
        this.detector = detector;
        this.metrics = metrics;
    }

    public void process(String json) {
        RawEvent event = normalizer.normalize(json);
        metrics.counter("aegis_events_received").increment();
        if (dedup.isDuplicate(event.eventId())) {
            metrics.counter("aegis_events_deduplicated").increment();
            return;
        }
        buffer.offer(event, this::handleOrdered);
    }

    private void handleOrdered(RawEvent event) {
        if (event.eventType() == EventType.METRIC) {
            Number value = (Number) event.payload().getOrDefault("value", 0);
            String metric = String.valueOf(event.payload().getOrDefault("metric", "unknown"));
            detector.onMetric(new MetricSample(
                    event.service(),
                    event.instance(),
                    metric,
                    value.doubleValue(),
                    event.eventTime(),
                    event.eventId()));
        }
        // Phase 1.5: LOG and HEALTH signals (5xx rate, instance down rules)
    }
}