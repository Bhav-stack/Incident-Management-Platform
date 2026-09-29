package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A stateless-per-sample rule: given ordered metric samples, decide whether
 * they confirm an anomaly. Cross-sample state (streak counters, fired flags)
 * lives in {@link RuleStateStore} so rules stay unit-testable and horizontally
 * scalable.
 *
 * <p>{@link #evaluateBatch} is the entry point the pipeline uses, because the
 * flush path processes a whole ordered batch at once and a rule can then
 * pipeline its state reads/writes for every sample in one round trip. The
 * default implementation is the naive per-sample loop, which is correct;
 * {@code ErrorRateRule} overrides it with the pipelined version.
 *
 * <p>New signals (p99, 5xx rate, instance down) implement this SPI and are
 * picked up by Spring automatically.
 */
public interface AnomalyRule {

    String signal();

    Optional<AnomalyEvent> evaluate(MetricSample sample);

    /** Evaluates an ordered batch; the default keeps the per-sample semantics. */
    default List<AnomalyEvent> evaluateBatch(List<MetricSample> samples) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        for (MetricSample sample : samples) {
            evaluate(sample).ifPresent(anomalies::add);
        }
        return anomalies;
    }
}
