package io.aegis.ingest.rules;

import io.aegis.contracts.events.AnomalyEvent;

import java.util.Optional;

/**
 * A stateless-per-sample rule: given one ordered metric sample, decide
 * whether it confirms an anomaly. Cross-sample state (streak counters, fired
 * flags) lives in {@link RuleStateStore} so rules stay unit-testable and
 * horizontally scalable.
 *
 * <p>New signals (p99, 5xx rate, instance down) implement this SPI and are
 * picked up by Spring automatically.
 */
public interface AnomalyRule {

    String signal();

    Optional<AnomalyEvent> evaluate(MetricSample sample);
}