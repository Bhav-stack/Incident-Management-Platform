package io.aegis.ingest.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Per-stage latency for the ingest path, so throughput and latency claims are
 * measured rather than asserted.
 *
 * <p>Four stages are timed independently, because they fail and scale
 * differently:
 *
 * <ul>
 *   <li>{@code aegis_ingest_dedup_seconds} — the Redis dedup lookup, one per
 *       received event. This is the only stage that touches the network on the
 *       hot path.</li>
 *   <li>{@code aegis_ingest_buffer_offer_seconds} — inserting into the
 *       out-of-order buffer. Should be flat in the number of sources; a rise
 *       means queue contention.</li>
 *   <li>{@code aegis_ingest_rule_seconds} — evaluating every anomaly rule for
 *       one ordered sample.</li>
 *   <li>{@code aegis_ingest_event_to_rule_seconds} — event time to rule
 *       evaluation, which includes the deliberate 5s ordering window. This is
 *       the number that matters to an operator: how stale a signal is by the
 *       time the rules see it.</li>
 * </ul>
 *
 * <p>Percentiles are published by the client (0.5/0.95/0.99) rather than
 * approximated from histogram buckets, so the numbers read straight off the
 * Prometheus endpoint are exact for this process.
 *
 * <p>It also counts every way an event can fail to reach the rules, because
 * the pipeline is deliberately allowed to drop events and those drops must be
 * visible rather than inferred from a log grep:
 * {@code aegis_events_ordered} (completed the pipeline),
 * {@code aegis_events_dropped_straggler} (arrived beyond window + grace),
 * {@code aegis_events_dropped_overflow} (per-source buffer cap exceeded) and
 * {@code aegis_events_dropped_sink} (a flush batch was already off the queues
 * when the downstream stage threw).
 * A dashboard alert belongs on the drop ratio, not only on throughput.
 */
@Component
public class IngestMetrics {

    private final MeterRegistry registry;
    private final Timer dedup;
    private final Timer bufferOffer;
    private final Timer rule;
    private final Timer eventToRule;
    private final Counter ordered;
    private final Counter stragglers;
    private final Counter overflow;
    private final Counter sinkFailures;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.dedup = timer("aegis_ingest_dedup", registry);
        this.bufferOffer = timer("aegis_ingest_buffer_offer", registry);
        this.rule = timer("aegis_ingest_rule", registry);
        this.eventToRule = timer("aegis_ingest_event_to_rule", registry);
        this.ordered = registry.counter("aegis_events_ordered");
        this.stragglers = registry.counter("aegis_events_dropped_straggler");
        this.overflow = registry.counter("aegis_events_dropped_overflow");
        this.sinkFailures = registry.counter("aegis_events_dropped_sink");
    }

    private static Timer timer(String name, MeterRegistry registry) {
        return Timer.builder(name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /** Starts a sample to be stopped against {@link #dedup()}. */
    public Timer.Sample sample() {
        return Timer.start(registry);
    }

    public Timer dedup() {
        return dedup;
    }

    public Timer bufferOffer() {
        return bufferOffer;
    }

    public Timer rule() {
        return rule;
    }

    public Timer eventToRule() {
        return eventToRule;
    }

    /** An event finished the pipeline and reached the rules. */
    public void ordered() {
        ordered.increment();
    }

    /** An event was dropped for arriving beyond window + grace. */
    public void straggler() {
        stragglers.increment();
    }

    /** An event was dropped because its source queue was full. */
    public void overflow() {
        overflow.increment();
    }

    /**
     * A whole ordered batch was dropped because the downstream stage failed.
     * Counted per event, not per batch, so it stays comparable with the other
     * drop counters.
     */
    public void droppedBySink(int events) {
        sinkFailures.increment(events);
    }
}
